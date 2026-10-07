// Copyright 2026 r0mn-creator
// SPDX-License-Identifier: Apache-2.0

package org.lighthouse.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.Normalizer
import java.util.zip.GZIPInputStream

/** Romgi-compatible catalog client. It consumes the documented v4 schema only. */
class RomgiCatalog(private val context: Context) {
    private val root = File(context.getExternalFilesDir(null), "romgi-catalog")
    private val dbFile = File(root, "romdb.db")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable data class Version(
        val version: String = "", val generated_at: String? = null,
        val schema_version: Int = 0, val entries: Int = 0, val links: Int = 0
    )
    data class Platform(val id: String, val brand: String, val name: String, val count: Int = 0)
    data class Entry(val slug: String, val title: String, val platform: String,
                     val boxartUrl: String?, val regions: String, val installed: Boolean = false)
    data class Link(val name: String, val type: String, val format: String,
                    val url: String, val filename: String?, val size: Long?, val sha256: String?,
                    val torrentInfohash: String? = null, val torrentFileIndex: Int? = null,
                    val torrentFilePath: String? = null) {
        val isTorrent: Boolean get() = torrentInfohash != null
    }

    suspend fun update(baseUrl: String): Version = withContext(Dispatchers.IO) {
        require(baseUrl.startsWith("https://")) { "A fonte deve usar HTTPS." }
        root.mkdirs()
        val v = json.decodeFromString<Version>(getText(baseUrl.trimEnd('/') + "/version.json"))
        require(v.schema_version in 1..4) { "Schema Romgi não suportado: ${v.schema_version}" }
        val target = File(root, "romdb.db.gz")
        download(baseUrl.trimEnd('/') + "/romdb.db.gz", target)
        // Decompress beside the live DB and swap, so a screen reading it never sees a half-written file.
        val tmp = File(root, "romdb.db.tmp")
        GZIPInputStream(FileInputStream(target)).use { input ->
            FileOutputStream(tmp).use { output -> input.copyTo(output) }
        }
        target.delete()
        if (!tmp.renameTo(dbFile)) { dbFile.delete(); check(tmp.renameTo(dbFile)) { "Não foi possível gravar o catálogo." } }
        File(root, "version.json").writeText(json.encodeToString(Version.serializer(), v))
        v
    }

    fun isInstalled(): Boolean = dbFile.isFile

    fun isStale(maxAgeMs: Long): Boolean =
        !dbFile.isFile || System.currentTimeMillis() - dbFile.lastModified() > maxAgeMs

    fun cachedVersion(): Version? = File(root, "version.json").takeIf { it.isFile }?.let {
        runCatching { json.decodeFromString<Version>(it.readText()) }.getOrNull()
    }

    fun platforms(): List<Platform> = query { db ->
        db.rawQuery(
            "SELECT p.id, p.brand, p.name, COUNT(e.slug) FROM platforms p JOIN entries e ON e.platform = p.id " +
                "GROUP BY p.id ORDER BY p.brand, p.name", null
        ).use { c ->
            buildList { while (c.moveToNext()) add(Platform(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: c.getString(0), c.getInt(3))) }
        }
    }

    /**
     * Installed games matched to catalog entries by normalized title, so they get catalog covers.
     * Games with no match are kept as local-only entries carrying their own cover.
     */
    fun matchInstalled(games: List<Triple<String, String, String?>>): List<Entry> {
        val local = { t: String, p: String, cover: String? -> Entry("local:$p:$t", t, p, cover, "", installed = true) }
        if (games.isEmpty()) return emptyList()
        if (!isInstalled()) return games.map { (t, p, c) -> local(t, p, c) }.sortedBy { it.title.lowercase() }
        return query { db ->
            games.groupBy { it.second }.flatMap { (platform, list) ->
                val byKey = HashMap<String, Entry>()
                val ids = catalogIdsFor(platform)
                db.rawQuery(
                    "SELECT slug,title,platform,boxart_url FROM entries WHERE platform IN (${ids.joinToString(",") { "?" }})",
                    ids.toTypedArray(),
                ).use { c ->
                    while (c.moveToNext()) {
                        val e = Entry(c.getString(0), c.getString(1), c.getString(2), c.getString(3), "", installed = true)
                        byKey.putIfAbsent(titleKey(e.title), e)
                    }
                }
                list.map { (t, p, cover) ->
                    byKey[titleKey(t)]?.let { if (cover != null) it.copy(boxartUrl = cover) else it } ?: local(t, p, cover)
                }
            }.distinctBy { it.slug }.sortedBy { it.title.lowercase() }
        }
    }

    fun search(term: String, platform: String? = null, limit: Int = 100, offset: Int = 0): List<Entry> = query { db ->
        val normalizedTerm = normalizeSearch(term)
        val args = mutableListOf<String>()
        val clauses = mutableListOf<String>()
        if (term.isNotBlank()) {
            clauses += "(LOWER(e.search_key) LIKE ? OR LOWER(e.title) LIKE ?)"
            args += "%${term.lowercase()}%"
            args += "%${term.lowercase()}%"
        }
        if (!platform.isNullOrBlank()) {
            clauses += "e.platform = ?"
            args += platform
        }
        val where = if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")
        val sql = "SELECT e.slug,e.title,e.platform,e.boxart_url, " +
            "(SELECT group_concat(r.name, ', ') FROM regions r JOIN regions_entries re ON re.region=r.id WHERE re.entry=e.slug) " +
            "FROM entries e$where ORDER BY e.title LIMIT $limit OFFSET $offset"
        val rows = db.rawQuery(sql, args.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(Entry(c.getString(0), c.getString(1), c.getString(2),
                c.getString(3), c.getString(4) ?: "")) }
        }

        if (normalizedTerm.isEmpty()) return@query rows
        rows.filter { entry ->
            val haystack = normalizeSearch("${entry.title} ${entry.slug} ${entry.platform} ${entry.regions}")
            normalizedTerm.split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .all { token -> haystack.contains(token) }
        }
    }

    /**
     * Every downloadable link, direct HTTPS first, then torrents (a whole-collection .torrent
     * from which only this game's file is fetched). Login-only links are skipped.
     */
    fun links(slug: String): List<Link> = query { db ->
        db.rawQuery(
            "SELECT l.name,l.type,l.format,l.url,l.filename,l.size,l.torrent_infohash,l.torrent_file_index,l.torrent_file_path " +
                "FROM links l LEFT JOIN sources s ON s.id = l.source_id " +
                "WHERE l.entry=? AND l.requires_auth = 0 AND l.url LIKE 'https://%' " +
                "ORDER BY (l.torrent_infohash IS NOT NULL), COALESCE(s.priority, 1000), l.size", arrayOf(slug)
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Link(
                    c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3),
                    c.getString(4), c.getLongOrNull(5), null,
                    torrentInfohash = c.getString(6),
                    torrentFileIndex = if (c.isNull(7)) null else c.getInt(7),
                    torrentFilePath = c.getString(8),
                ))
            }
        }
    }

    /**
     * Downloads one catalog link into the console's SAF folder. Zips are unpacked so the game
     * shows up under the profile's own extensions and every core can open it; arcade sets stay zipped.
     */
    suspend fun install(link: Link, platformId: String, treeUri: String, progress: (String, Int?) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        require(link.url.startsWith("https://")) { "Download bloqueado: URL não HTTPS." }
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: error("Pasta inválida")
        require(tree.canWrite()) { "A pasta do console não permite gravação." }
        val name = sanitize(link.filename ?: Uri.decode(link.url.substringAfterLast('/').substringBefore('?')))
        val unzip = name.endsWith(".zip", ignoreCase = true) && platformId.lowercase() !in KEEP_ZIPPED

        when {
            link.isTorrent -> {
                val file = TorrentFetcher.fetch(context, link.url, link.torrentFileIndex, link.torrentFilePath, progress)
                try { place(file, name, unzip, tree, progress) } finally { file.delete() }
            }
            unzip -> {
                val tmp = File(context.cacheDir, "downloads/$name").apply { parentFile?.mkdirs() }
                try {
                    httpGet(link.url) { input, total -> tmp.outputStream().use { copy(input, it, total, "Baixando", progress) } }
                    place(tmp, name, unzip = true, tree, progress)
                } finally { tmp.delete() }
            }
            else -> httpGet(link.url) { input, total ->
                writeToTree(tree, name) { out -> copy(input, out, total, "Baixando", progress) }
            }
        }
    }

    private fun httpGet(url: String, body: (java.io.InputStream, Long) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000; conn.readTimeout = 60_000; conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "LightHouse/1.0")
        try {
            // Checked before any file exists, so a 403/404 leaves nothing half-made behind.
            check(conn.responseCode in 200..299) { "servidor respondeu HTTP ${conn.responseCode}" }
            conn.inputStream.use { body(it, conn.contentLengthLong) }
        } finally { conn.disconnect() }
    }

    private fun place(file: File, name: String, unzip: Boolean, tree: DocumentFile, progress: (String, Int?) -> Unit) {
        if (!unzip) {
            progress("Copiando para a pasta do console...", null)
            file.inputStream().use { input -> writeToTree(tree, name) { input.copyTo(it, 256 * 1024) } }
            return
        }
        progress("Extraindo $name...", null)
        var count = 0
        java.util.zip.ZipInputStream(file.inputStream().buffered()).use { zip ->
            generateSequence { zip.nextEntry }.filter { !it.isDirectory }.forEach { e ->
                writeToTree(tree, sanitize(e.name.substringAfterLast('/'))) { zip.copyTo(it, 256 * 1024) }
                count++
            }
        }
        check(count > 0) { "o zip baixado está vazio" }
    }

    private fun writeToTree(tree: DocumentFile, name: String, write: (java.io.OutputStream) -> Unit) {
        tree.findFile(name)?.delete()
        val target = tree.createFile("application/octet-stream", name) ?: error("Não foi possível criar $name")
        try {
            context.contentResolver.openOutputStream(target.uri).use { write(requireNotNull(it)) }
        } catch (t: Throwable) { target.delete(); throw t }
    }

    private fun copy(input: java.io.InputStream, out: java.io.OutputStream, total: Long, label: String, progress: (String, Int?) -> Unit) {
        val buf = ByteArray(256 * 1024)
        var done = 0L
        var lastPct = -1L
        while (true) {
            val n = input.read(buf); if (n < 0) break
            out.write(buf, 0, n); done += n
            val pct = if (total > 0) done * 100 / total else done / (1024 * 1024)
            if (pct != lastPct) {
                lastPct = pct
                progress(if (total > 0) "$label $pct%" else "$label ${done / 1024 / 1024} MB", if (total > 0) pct.toInt() else null)
            }
        }
    }

    fun delete(uri: String): Boolean = DocumentFile.fromSingleUri(context, Uri.parse(uri))?.delete() == true

    fun deleteByEntry(entry: Entry, treeUri: String): Boolean {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return false
        val terms = listOfNotNull(
            entry.slug,
            entry.title,
            entry.slug.replace('-', ' '),
            entry.title.replace("-", " ")
        ).map { normalizeSearch(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { return false }

        fun matches(name: String): Boolean {
            val normalizedName = normalizeSearch(name)
            return terms.any { token -> normalizedName.contains(token) }
        }

        var deleted = false
        fun walk(file: DocumentFile) {
            if (file.isFile && matches(file.name.orEmpty())) {
                deleted = file.delete() || deleted
                return
            }
            if (file.isDirectory) {
                for (child in file.listFiles()) walk(child)
            }
        }
        walk(tree)
        return deleted
    }

    private fun <T> query(block: (android.database.sqlite.SQLiteDatabase) -> T): T {
        require(dbFile.isFile) { "Catálogo não instalado. Atualize a fonte primeiro." }
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        return try { block(db) } finally { db.close() }
    }
    private fun getText(url: String): String = URL(url).openStream().bufferedReader().use { it.readText() }
    private fun download(url: String, target: File) { URL(url).openStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } } }
    private fun sanitize(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(180).ifBlank { "download.rom" }
    private fun normalizeSearch(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("[\\p{Mn}]"), "")
        .lowercase()
        .trim()
    /** "Pokémon - Red Version (USA, Europe) [b]" -> "pokemonredversion" */
    private fun titleKey(value: String): String = normalizeSearch(value)
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), "")
        .replace(Regex("[^a-z0-9]"), "")
    private fun android.database.Cursor.getLongOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)

    companion object {
        /** Arcade cores load the zip itself as the ROM set. */
        private val KEEP_ZIPPED = setOf("mame", "fbneo", "arcade", "neogeo")

        /** Romgi platform id -> LightHouse profile ids that mean the same console. */
        private val PROFILE_ALIASES = mapOf(
            "nds" to listOf("ds"),
            "ps1" to listOf("psx", "ps", "playstation"),
            "psv" to listOf("vita"),
            "x360" to listOf("xbox360"),
            "smd" to listOf("genesis", "megadrive", "md"),
            "scd" to listOf("segacd", "megacd"),
            "sms" to listOf("mastersystem"),
            "gg" to listOf("gamegear"),
            "dc" to listOf("dreamcast"),
            "a26" to listOf("atari2600"),
            "tg16" to listOf("pce", "pcengine", "turbografx16"),
            "mame" to listOf("arcade"),
            "fbneo" to listOf("arcade"),
        )

        fun matchesProfile(catalogId: String, profileId: String): Boolean =
            catalogId.equals(profileId, ignoreCase = true) ||
                PROFILE_ALIASES[catalogId.lowercase()]?.contains(profileId.lowercase()) == true

        fun catalogIdsFor(profileId: String): List<String> {
            val p = profileId.lowercase()
            return listOf(p) + PROFILE_ALIASES.filterValues { p in it }.keys
        }
    }
}
