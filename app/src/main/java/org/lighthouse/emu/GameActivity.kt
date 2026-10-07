// Copyright 2026 r0mn-creator
// SPDX-License-Identifier: Apache-2.0

package org.lighthouse.emu

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.VirtualFile
import org.lighthouse.theme.LocalTheme
import org.lighthouse.ui.GameContextMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs a game in-process through a libretro core, so it feels native: no third-party app,
 * no RetroArch UI. Select+Start (or Home/Back) opens the pause menu.
 */
class GameActivity : ComponentActivity() {

    private var retroView by mutableStateOf<GLRetroView?>(null)
    private var menuOpen by mutableStateOf(false)
    private var menuCursor by mutableIntStateOf(0)
    private var message by mutableStateOf<String?>("Carregando...")
    private val held = HashSet<Int>()
    /** Each physical controller gets its own player port in the order it is first used. */
    private val ports = LinkedHashMap<Int, Int>()
    private var romFd: ParcelFileDescriptor? = null

    private lateinit var core: String
    private lateinit var saveKey: String

    private val menuItems = listOf("Continuar", "Salvar estado", "Carregar estado", "Reiniciar", "Sair")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        core = intent.getStringExtra(EXTRA_CORE)!!
        val corePath = intent.getStringExtra(EXTRA_CORE_PATH)!!
        val rom = Uri.parse(intent.getStringExtra(EXTRA_ROM)!!)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        saveKey = title.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { rom.lastPathSegment ?: "game" }

        setContent { GameUi() }

        lifecycleScope.launch {
            val view = runCatching { withContext(Dispatchers.IO) { buildView(corePath, rom) } }
                .getOrElse { fail("Não foi possível abrir o jogo: ${it.message}"); return@launch }
            lifecycle.addObserver(view)
            launch { view.getGLRetroErrors().collect { fail(errorText(it)) } }
            retroView = view
            message = null
        }
    }

    private fun buildView(corePath: String, rom: Uri): GLRetroView {
        val doc = DocumentFile.fromSingleUri(this, rom)
        val name = doc?.name ?: rom.lastPathSegment?.substringAfterLast('/') ?: "game.bin"
        val size = doc?.length() ?: 0L
        val data = GLRetroViewData(this).apply {
            coreFilePath = corePath
            systemDirectory = dir("system").path
            savesDirectory = dir("saves").path
            saveRAMState = sramFile().takeIf { it.isFile }?.readBytes()
            shader = ShaderConfig.Default
            rumbleEventsEnabled = true
            preferLowLatencyAudio = true
        }
        when {
            rom.scheme == "file" -> data.gameFilePath = rom.path
            // Small ROMs are copied once: every core can read a real file. Big discs stream via VFS instead.
            size in 1..COPY_LIMIT -> data.gameFilePath = cachedCopy(rom, name, size).path
            else -> {
                val fd = contentResolver.openFileDescriptor(rom, "r") ?: error("sem acesso ao arquivo")
                romFd = fd
                data.gameVirtualFiles = listOf(VirtualFile(name, fd))
            }
        }
        return GLRetroView(this, data).apply { isFocusable = false }
    }

    private fun cachedCopy(rom: Uri, name: String, size: Long): File {
        val dir = File(cacheDir, "roms/${rom.toString().hashCode().toUInt()}").apply { mkdirs() }
        val f = File(dir, name)
        if (f.length() == size) return f
        contentResolver.openInputStream(rom)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    private fun dir(kind: String) = File(getExternalFilesDir(null), "emulation/$kind").apply { mkdirs() }
    private fun sramFile() = File(dir("saves/$core"), "$saveKey.srm")
    private fun stateFile() = File(dir("states/$core"), "$saveKey.state")

    private fun saveSram() {
        if (glPaused) return
        val bytes = runCatching { retroView?.serializeSRAM() }.getOrNull() ?: return
        if (bytes.isNotEmpty()) runCatching { sramFile().writeBytes(bytes) }
    }

    /**
     * serializeSRAM/serializeState/unserializeState block the caller until the GL thread runs them.
     * Calling one while the GL thread is paused deadlocks the main thread - and with it the whole
     * app, launcher included. So: save before pausing, and wake the thread before any of them.
     */
    private var glPaused = false

    private fun setPaused(paused: Boolean) {
        val v = retroView ?: return
        if (paused == glPaused) return
        v.audioEnabled = !paused
        if (paused) v.onPause() else v.onResume()
        glPaused = paused
    }

    private fun openMenu() {
        if (retroView == null || menuOpen) return
        saveSram()
        menuOpen = true; menuCursor = 0
        held.clear()
        setPaused(true)
    }

    private fun closeMenu() { menuOpen = false; setPaused(false) }

    private fun activate(i: Int) {
        val v = retroView ?: return
        setPaused(false)
        when (menuItems[i]) {
            "Continuar" -> closeMenu()
            "Salvar estado" -> {
                val ok = runCatching { stateFile().writeBytes(v.serializeState()) }.isSuccess
                toast(if (ok) "Estado salvo" else "Falha ao salvar estado"); closeMenu()
            }
            "Carregar estado" -> {
                val f = stateFile()
                val ok = f.isFile && runCatching { v.unserializeState(f.readBytes()) }.getOrDefault(false)
                toast(if (ok) "Estado carregado" else "Nenhum estado salvo"); closeMenu()
            }
            "Reiniciar" -> { v.reset(); closeMenu() }
            "Sair" -> finish()
        }
    }

    private fun portFor(event: android.view.InputEvent): Int =
        ports.getOrPut(event.deviceId) { ports.size.coerceAtMost(3) }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        if (menuOpen) {
            if (down && event.repeatCount == 0) when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> menuCursor = (menuCursor - 1 + menuItems.size) % menuItems.size
                KeyEvent.KEYCODE_DPAD_DOWN -> menuCursor = (menuCursor + 1) % menuItems.size
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> activate(menuCursor)
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_MODE -> closeMenu()
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (down) openMenu()
            return true
        }
        if (down) held += code else held -= code
        if (down && KeyEvent.KEYCODE_BUTTON_SELECT in held && KeyEvent.KEYCODE_BUTTON_START in held) {
            retroView?.sendKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_SELECT, portFor(event))
            retroView?.sendKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_START, portFor(event))
            openMenu()
            return true
        }
        if (event.repeatCount > 0) return true
        retroView?.sendKeyEvent(event.action, code, portFor(event)) ?: return super.dispatchKeyEvent(event)
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val v = retroView
        if (v == null || menuOpen || event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) {
            return super.dispatchGenericMotionEvent(event)
        }
        val port = portFor(event)
        v.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y), port)
        v.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y), port)
        v.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_RZ), port)
        return true
    }

    override fun onPause() {
        if (!menuOpen) openMenu()
        super.onPause()
    }

    /** The core is torn down on the GL thread, so it must be running when the activity goes away. */
    override fun finish() {
        saveSram()
        setPaused(false)
        super.finish()
    }

    override fun onDestroy() {
        setPaused(false)
        runCatching { romFd?.close() }
        super.onDestroy()
    }

    private fun fail(text: String) { toast(text); finish() }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun errorText(code: Int) = when (code) {
        GLRetroView.ERROR_LOAD_LIBRARY -> "Falha ao carregar o emulador $core"
        GLRetroView.ERROR_LOAD_GAME -> "O emulador $core não abriu este arquivo (formato ou BIOS ausente)"
        GLRetroView.ERROR_GL_NOT_COMPATIBLE -> "GPU incompatível com o emulador $core"
        else -> "Erro no emulador ($code)"
    }

    @Composable
    private fun GameUi() {
        val theme = remember { org.lighthouse.LightHouseApp.instance.activeColors() }
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            retroView?.let { v -> AndroidView(factory = { v }, modifier = Modifier.fillMaxSize()) }
            message?.let { Text(it, color = Color.White, fontSize = 18.sp) }
            if (menuOpen) {
                CompositionLocalProvider(LocalTheme provides theme) {
                    GameContextMenu(
                        title = "Pausado",
                        items = menuItems.map { it to (it == "Sair") },
                        confirming = false,
                        cursor = menuCursor,
                        onSelect = { menuCursor = it },
                        onActivate = { activate(menuCursor) },
                        onDismiss = { closeMenu() },
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_CORE = "core"
        private const val EXTRA_CORE_PATH = "core_path"
        private const val EXTRA_ROM = "rom"
        private const val EXTRA_TITLE = "title"
        private const val COPY_LIMIT = 256L * 1024 * 1024

        fun intent(context: Context, core: String, corePath: String, rom: Uri, title: String) =
            Intent(context, GameActivity::class.java)
                .putExtra(EXTRA_CORE, core)
                .putExtra(EXTRA_CORE_PATH, corePath)
                .putExtra(EXTRA_ROM, rom.toString())
                .putExtra(EXTRA_TITLE, title)
    }
}
