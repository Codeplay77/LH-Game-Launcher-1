// Copyright 2026 r0mn-creator
// SPDX-License-Identifier: Apache-2.0

package org.lighthouse.emu

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * libretro cores for the built-in player, downloaded from the official buildbot - on first
 * launch of a game, and ahead of time for every console that has games (see [prefetch]).
 */
class EmulatorCores(private val context: Context) {

    /**
     * @param bios files (relative to the system dir) the core cannot boot without - any one of them
     *   is enough; an entry ending in "/" means any file inside that folder. When none is present the
     *   next choice for the console is used instead.
     * @param assets buildbot system pack the core needs beside it (Dolphin's Sys, PPSSPP's fonts...).
     */
    data class Core(val name: String, val bios: List<String> = emptyList(), val assets: String? = null)

    /** Cores must live in app-private internal storage: Android refuses to dlopen from shared storage. */
    private val coreDir = File(context.filesDir, "cores")

    /** Same folder GameActivity hands to the core; users drop BIOS files here. */
    val systemDir: File get() = File(context.getExternalFilesDir(null), "emulation/system").apply { mkdirs() }

    fun coreFile(core: String) = File(coreDir, "${core}_libretro_android.so")

    /** BIOS expected by a given libretro core. */
    fun biosForCore(coreName: String): List<String> =
        CORES.values.flatten().firstOrNull { it.name == coreName }?.bios.orEmpty()

    /** BIOS this libretro core still needs: empty when any accepted file is present. */
    fun missingBios(coreName: String): List<String> {
        val bios = biosForCore(coreName)
        return if (bios.any { hasBios(it) }) emptyList() else bios
    }

    private fun hasBios(path: String): Boolean =
        if (path.endsWith("/")) File(systemDir, path).listFiles()?.any { it.isFile && !it.name.startsWith(".") } == true
        else File(systemDir, path).isFile

    /** Best core for a console given the BIOS files present right now, or null for external-app consoles. */
    fun coreFor(platformId: String): Core? {
        val choices = CORES[platformId.lowercase()] ?: return null
        return choices.firstOrNull { c -> c.bios.isEmpty() || c.bios.any { hasBios(it) } }
            ?: choices.last()
    }

    fun isReady(core: Core) = coreFile(core.name).isFile && (core.assets == null || assetMarker(core.assets).isFile)

    suspend fun ensure(core: Core, progress: (Int) -> Unit = {}): File = lock.withLock {
        withContext(Dispatchers.IO) {
            core.assets?.let { pack ->
                if (!assetMarker(pack).isFile) {
                    unzip("$ASSETS/${pack.replace(" ", "%20")}.zip", systemDir, progress)
                    assetMarker(pack).writeText("ok")
                }
            }
            val target = coreFile(core.name)
            if (!target.isFile) {
                coreDir.mkdirs()
                val abi = Build.SUPPORTED_ABIS.firstOrNull { it in SUPPORTED_ABIS }
                    ?: error("ABI não suportada: ${Build.SUPPORTED_ABIS.joinToString()}")
                val tmpDir = File(coreDir, "${core.name}.tmp").apply { deleteRecursively(); mkdirs() }
                unzip("$BUILDBOT/$abi/${core.name}_libretro_android.so.zip", tmpDir, progress)
                val so = tmpDir.walk().firstOrNull { it.name.endsWith(".so") }
                    ?: error("Pacote do emulador ${core.name} sem biblioteca")
                check(so.renameTo(target)) { "Não foi possível instalar o emulador ${core.name}" }
                tmpDir.deleteRecursively()
            }
            target
        }
    }

    /** Whether any BIOS this console's cores use is still to be downloaded. */
    fun biosToFetch(platformId: String): Boolean = CORES[platformId.lowercase()].orEmpty()
        .any { c -> BIOS_DOWNLOADS[c.name].orEmpty().keys.any { !File(systemDir, it).isFile } }

    /**
     * Fetches the BIOS files the console's cores use (see [BIOS_DOWNLOADS]) that are not in the
     * system dir yet. Call before [coreFor] so the preferred core gets picked. Failures are skipped.
     */
    suspend fun fetchBios(platformId: String) = withContext(Dispatchers.IO) {
        val cores = CORES[platformId.lowercase()] ?: return@withContext
        for ((local, remote) in cores.flatMap { BIOS_DOWNLOADS[it.name].orEmpty().entries }) {
            val target = File(systemDir, local)
            if (target.isFile) continue
            runCatching {
                val conn = URL("$RETROBIOS/" + remote.replace(" ", "%20")).openConnection() as HttpURLConnection
                conn.connectTimeout = 20_000; conn.readTimeout = 60_000
                try {
                    check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
                    target.parentFile?.mkdirs()
                    val tmp = File(target.path + ".part")
                    conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    check(tmp.renameTo(target))
                } finally { conn.disconnect() }
            }
        }
    }

    /** Downloads, one by one and quietly, the cores of every console listed. Failures are skipped. */
    suspend fun prefetch(platformIds: Collection<String>) {
        platformIds.forEach { fetchBios(it) }
        platformIds.mapNotNull { coreFor(it) }.distinct().filterNot { isReady(it) }
            .forEach { runCatching { ensure(it) } }
    }

    private fun assetMarker(pack: String) = File(systemDir, ".lh-assets-${pack.replace(" ", "_")}")

    private fun unzip(url: String, into: File, progress: (Int) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000; conn.readTimeout = 60_000
        try {
            check(conn.responseCode == 200) { "Download indisponível (HTTP ${conn.responseCode}): $url" }
            val total = conn.contentLengthLong
            var read = 0L
            val counted = object : java.io.FilterInputStream(conn.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { if (it > 0) { read += it; if (total > 0) progress((read * 100 / total).toInt()) } }
            }
            val root = into.canonicalPath + File.separator
            ZipInputStream(counted).use { zip ->
                generateSequence { zip.nextEntry }.forEach { e ->
                    val out = File(into, e.name)
                    // Zip-slip guard: an entry must not escape the target folder.
                    check(out.canonicalPath.startsWith(root)) { "Entrada inválida no pacote: ${e.name}" }
                    if (e.isDirectory) out.mkdirs()
                    else { out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
                }
            }
        } finally { conn.disconnect() }
    }

    companion object {
        private const val BUILDBOT = "https://buildbot.libretro.com/nightly/android/latest"
        private const val ASSETS = "https://buildbot.libretro.com/assets/system"
        private const val RETROBIOS = "https://raw.githubusercontent.com/Abdess/retrobios/main/bios"
        private val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        private val lock = Mutex()

        private val PS1_BIOS = listOf("scph5501.bin", "scph1001.bin", "scph7001.bin", "scph5500.bin", "scph5502.bin")
        private val SATURN_BIOS = listOf("sega_101.bin", "mpr-17933.bin")

        /** core -> BIOS to fetch: path in the system dir -> path in the retrobios repo. */
        private val BIOS_DOWNLOADS: Map<String, Map<String, String>> = mapOf(
            "swanstation" to mapOf(
                "scph5501.bin" to "Sony/PlayStation/scph5501.bin", // US
                "scph5500.bin" to "Sony/PlayStation/scph5500.bin", // JP
                "scph5502.bin" to "Sony/PlayStation/scph5502.bin", // EU
                "sbi.zip" to "Sony/PlayStation/sbi.zip", // LibCrypt subchannel data (GameActivity.fetchSbi)
            ),
            "pcsx_rearmed" to mapOf("sbi.zip" to "Sony/PlayStation/sbi.zip"),
            "mednafen_saturn" to mapOf(
                "sega_101.bin" to "Sega/Saturn/sega_101.bin", // JP
                "mpr-17933.bin" to "Sega/Saturn/mpr-17933.bin", // US/EU
            ),
            "flycast" to mapOf(
                "dc/dc_boot.bin" to "Sega/Dreamcast/dc_boot.bin",
                "dc/dc_flash.bin" to "Sega/Dreamcast/dc_flash.bin",
            ),
        )

        /**
         * Console id (catalog and profile ids alike) -> cores, preferred first. Consoles absent here
         * (3DS/Azahar, PS2/NetherSX2 - the PCSX2 core needs desktop GL or Vulkan, LibretroDroid only offers GLES -, Wii U, Switch, PS3, Vita, Xbox, Xbox 360) keep using the profile's external app.
         */
        private val CORES: Map<String, List<Core>> = listOf(
            listOf("nes", "famicom") to listOf(Core("mesen")),
            listOf("snes", "sfc") to listOf(Core("snes9x")),
            listOf("gb", "gbc") to listOf(Core("sameboy")),
            listOf("gba") to listOf(Core("mgba")),
            listOf("n64") to listOf(Core("mupen64plus_next_gles3")),
            listOf("nds", "ds") to listOf(Core("melonds")),
            listOf("gc", "wii") to listOf(Core("dolphin", assets = "Dolphin")),
            listOf("sms", "mastersystem", "smd", "genesis", "megadrive", "md", "scd", "segacd", "megacd") to
                listOf(Core("genesis_plus_gx")),
            // Genesis Plus GX has no 32X support; PicoDrive is the libretro core that runs it.
            listOf("32x", "sega32x") to listOf(Core("picodrive")),
            listOf("sat", "saturn") to listOf(Core("mednafen_saturn", bios = SATURN_BIOS), Core("yabasanshiro")),
            listOf("dc", "dreamcast") to listOf(Core("flycast")),
            listOf("ps1", "psx", "ps", "playstation") to listOf(Core("swanstation", bios = PS1_BIOS), Core("pcsx_rearmed")),
            listOf("psp") to listOf(Core("ppsspp", assets = "PPSSPP")),
        ).flatMap { (ids, cores) -> ids.map { it to cores } }.toMap()

        fun hasCore(platformId: String) = CORES.containsKey(platformId.lowercase())
    }
}
