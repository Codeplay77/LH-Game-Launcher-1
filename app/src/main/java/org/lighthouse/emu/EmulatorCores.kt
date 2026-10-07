// Copyright 2026 r0mn-creator
// SPDX-License-Identifier: Apache-2.0

package org.lighthouse.emu

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** libretro cores for the built-in player, downloaded on first use from the official buildbot. */
class EmulatorCores(private val context: Context) {

    /** Cores must live in app-private internal storage: Android refuses to dlopen from shared storage. */
    private val coreDir = File(context.filesDir, "cores")

    fun coreFile(core: String) = File(coreDir, "${core}_libretro_android.so")

    fun isDownloaded(core: String) = coreFile(core).isFile

    suspend fun ensure(core: String, progress: (Int) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val target = coreFile(core)
        if (target.isFile) return@withContext target
        coreDir.mkdirs()
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in SUPPORTED_ABIS } ?: error("ABI não suportada: ${Build.SUPPORTED_ABIS.joinToString()}")
        val conn = URL("$BUILDBOT/$abi/${core}_libretro_android.so.zip").openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000; conn.readTimeout = 60_000
        try {
            check(conn.responseCode == 200) { "Emulador $core indisponível (HTTP ${conn.responseCode})" }
            val total = conn.contentLengthLong
            val tmp = File(coreDir, "$core.tmp")
            var read = 0L
            ZipInputStream(object : java.io.FilterInputStream(conn.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { if (it > 0) { read += it; if (total > 0) progress((read * 100 / total).toInt()) } }
            }).use { zip ->
                generateSequence { zip.nextEntry }.firstOrNull { it.name.endsWith(".so") }
                    ?: error("Pacote do emulador $core sem biblioteca")
                tmp.outputStream().use { zip.copyTo(it) }
            }
            check(tmp.renameTo(target)) { "Não foi possível instalar o emulador $core" }
            target
        } finally { conn.disconnect() }
    }

    companion object {
        private const val BUILDBOT = "https://buildbot.libretro.com/nightly/android/latest"
        private val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

        /** Profile/platform id -> libretro core. Ids not listed keep using their external launch intent. */
        private val CORES = mapOf(
            "mgba" to listOf("gba"),
            "gambatte" to listOf("gb", "gbc"),
            "fceumm" to listOf("nes", "famicom", "fds"),
            "snes9x" to listOf("snes", "sfc", "superfamicom"),
            "genesis_plus_gx" to listOf("smd", "scd", "genesis", "megadrive", "md", "sms", "mastersystem", "gg", "gamegear", "segacd", "megacd"),
            "picodrive" to listOf("32x", "sega32x"),
            "pcsx_rearmed" to listOf("psx", "ps1", "ps", "playstation"),
            "mupen64plus_next_gles3" to listOf("n64", "nintendo64"),
            "melonds" to listOf("ds", "nds"),
            "flycast" to listOf("dreamcast", "dc"),
            "mednafen_pce_fast" to listOf("pce", "pcengine", "tg16", "turbografx16"),
            "mednafen_ngp" to listOf("ngp", "ngpc"),
            "mednafen_wswan" to listOf("ws", "wsc", "wonderswan"),
            "handy" to listOf("lynx"),
            "stella2014" to listOf("a26", "atari2600", "a2600"),
            "fbneo" to listOf("arcade", "fbneo", "neogeo", "mame"),
        ).flatMap { (core, ids) -> ids.map { it to core } }.toMap()

        fun coreFor(platformId: String): String? = CORES[platformId.lowercase()]
    }
}
