// Copyright 2026 r0mn-creator
// SPDX-License-Identifier: Apache-2.0

package org.lighthouse.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pulls ONE file out of a whole-collection torrent: every other file is set to IGNORE,
 * so a 300 KB ROM never means downloading a 50 GB set.
 */
object TorrentFetcher {

    private var session: SessionManager? = null

    @Synchronized
    private fun session(): SessionManager = session ?: SessionManager(false).also {
        it.start()
        session = it
    }

    /**
     * @param fileIndex index hint from the catalog; [filePath] wins when both are present,
     *   since indexes shift if the torrent is regenerated.
     */
    suspend fun fetch(
        context: Context,
        torrentUrl: String,
        fileIndex: Int?,
        filePath: String?,
        progress: (String, Int?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val root = File(context.getExternalFilesDir(null), "torrents").apply { mkdirs() }
        val torrentFile = File(root, "meta/" + torrentUrl.hashCode().toUInt() + ".torrent")
        if (!torrentFile.isFile) {
            progress("Baixando índice do torrent...", null)
            torrentFile.parentFile?.mkdirs()
            val conn = URL(torrentUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000; conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "LightHouse/1.0")
            try {
                check(conn.responseCode in 200..299) { "índice do torrent indisponível (HTTP ${conn.responseCode})" }
                val tmp = File(torrentFile.path + ".tmp")
                conn.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                check(tmp.renameTo(torrentFile)) { "falha ao salvar o índice do torrent" }
            } finally { conn.disconnect() }
        }

        val info = TorrentInfo(torrentFile)
        val fs = info.files()
        val wantedTail = filePath?.substringAfterLast('/')
        val index = (0 until fs.numFiles()).firstOrNull { i ->
            filePath != null && (fs.filePath(i).replace('\\', '/').endsWith(filePath) || fs.fileName(i) == wantedTail)
        } ?: fileIndex?.takeIf { it in 0 until fs.numFiles() }
            ?: error("arquivo não encontrado dentro do torrent")

        val size = fs.fileSize(index)
        val out = File(root, fs.filePath(index))
        if (out.isFile && out.length() == size) return@withContext out

        val priorities = Array(fs.numFiles()) { if (it == index) Priority.TOP_PRIORITY else Priority.IGNORE }
        val sm = session()
        val existing = sm.find(info.infoHash())
        if (existing != null && existing.isValid) existing.prioritizeFiles(priorities)
        else sm.download(info, root, null, priorities, null, torrent_flags_t())

        var lastDone = -1L
        var lastProgressAt = System.currentTimeMillis()
        try {
            while (true) {
                val handle = sm.find(info.infoHash())
                if (handle == null || !handle.isValid) { delay(500); continue }
                val done = handle.fileProgress()[index]
                val st = handle.status()
                if (done >= size) break
                if (done != lastDone) { lastDone = done; lastProgressAt = System.currentTimeMillis() }
                check(System.currentTimeMillis() - lastProgressAt < STALL_MS) {
                    "torrent sem progresso há ${STALL_MS / 60_000} min (${st.numPeers()} peers)"
                }
                val pct = if (size > 0) done * 100 / size else 0
                progress(
                    if (st.numPeers() == 0) "Procurando peers do torrent..."
                    else "Torrent $pct% · ${st.downloadPayloadRate() / 1024} KB/s · ${st.numPeers()} peers",
                    pct.toInt(),
                )
                delay(1_000)
            }
        } finally {
            // Drop the handle so it stops seeding/connecting; the finished file stays on disk.
            sm.find(info.infoHash())?.takeIf { it.isValid }?.let { sm.remove(it) }
        }
        out
    }

    private const val STALL_MS = 5 * 60_000L
}
