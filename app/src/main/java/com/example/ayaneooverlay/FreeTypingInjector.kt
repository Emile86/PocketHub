package com.example.ayaneooverlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors

/**
 * Talks to [RootInputBridge], the root app_process helper that injects key events
 * into a chosen display. Started lazily the first time the free keyboard is used.
 */
internal class RootInputBridgeClient(context: Context) : Closeable {
    private val process: Process
    private val reader: BufferedReader
    private val writer: BufferedWriter
    private val lock = Any()
    @Volatile private var closed = false
    val isAlive: Boolean get() = !closed && process.isAlive

    init {
        val apkPath = context.applicationInfo.sourceDir
        val command = "CLASSPATH=${shellQuote(apkPath)} app_process /system/bin com.example.ayaneooverlay.RootInputBridge"
        process = ProcessBuilder("su", "-c", command).redirectErrorStream(false).start()
        reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
        Thread({
            try {
                BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).useLines { lines ->
                    lines.forEach { Log.w(TAG, it) }
                }
            } catch (_: Exception) { }
        }, "PocketHubRootInput-stderr").apply { isDaemon = true; start() }
        val hello = request(JSONObject().put("cmd", "ping"))
        check(hello.optBoolean("ok", false)) { hello.optString("error", "The root input helper did not answer") }
    }

    fun key(code: Int, meta: Int = 0, display: Int = TARGET_DISPLAY, wait: Boolean = false): Boolean =
        request(JSONObject().put("cmd", "key").put("code", code).put("meta", meta).put("display", display).put("wait", wait))
            .optBoolean("ok", false)

    fun focus(display: Int = TARGET_DISPLAY): Boolean =
        request(JSONObject().put("cmd", "focus").put("display", display)).optBoolean("ok", false)

    fun touch(action: String, x: Float, y: Float, display: Int = TARGET_DISPLAY): Boolean =
        request(JSONObject().put("cmd", "touch").put("action", action).put("x", x.toDouble()).put("y", y.toDouble()).put("display", display))
            .optBoolean("ok", false)

    fun rightClick(x: Float, y: Float, display: Int = TARGET_DISPLAY): Boolean =
        request(JSONObject().put("cmd", "rightclick").put("x", x.toDouble()).put("y", y.toDouble()).put("display", display))
            .optBoolean("ok", false)

    fun scroll(x: Float, y: Float, vertical: Float, horizontal: Float, display: Int = TARGET_DISPLAY): Boolean =
        request(JSONObject().put("cmd", "scroll").put("x", x.toDouble()).put("y", y.toDouble())
            .put("v", vertical.toDouble()).put("h", horizontal.toDouble()).put("display", display)).optBoolean("ok", false)

    fun text(value: String, display: Int = TARGET_DISPLAY): Boolean =
        request(JSONObject().put("cmd", "text").put("text", value).put("display", display)).optBoolean("ok", false)

    private fun request(request: JSONObject): JSONObject = synchronized(lock) {
        check(!closed) { "Root input helper is closed" }
        if (!process.isAlive) throw IllegalStateException("Root input helper exited (code ${process.exitValue()})")
        writer.write(request.toString())
        writer.newLine()
        writer.flush()
        val line = reader.readLine() ?: throw IllegalStateException("Root input helper closed its output")
        JSONObject(line)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            try {
                if (process.isAlive) {
                    writer.write(JSONObject().put("cmd", "stop").toString())
                    writer.newLine()
                    writer.flush()
                }
            } catch (_: Exception) { }
            closed = true
            try { writer.close() } catch (_: Exception) { }
            try { reader.close() } catch (_: Exception) { }
            try { process.destroy() } catch (_: Exception) { }
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    companion object {
        private const val TAG = "PocketHubRootInput"
        /** Pocket DS: display 0 is the upper screen, the keyboard lives on the lower one. */
        const val TARGET_DISPLAY = 0
    }
}

/**
 * InputConnection used while the keyboard was opened from PocketHub's Keyboard button.
 * There is no text field attached to the IME then, so every edit is turned into key
 * events injected into the upper screen. Characters the virtual key map cannot type
 * (Cyrillic, Chinese, emoji) are pasted through the clipboard with Ctrl+V, and the
 * previous clipboard content is restored afterwards.
 */
internal class FreeTypingInputConnection(
    view: View,
    private val context: Context,
    private val onError: (String) -> Unit,
    private val onInternalClip: (String) -> Unit
) : BaseInputConnection(view, false) {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PocketHubFreeTyping").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val keyMap: KeyCharacterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    @Volatile private var bridge: RootInputBridgeClient? = null
    @Volatile private var errorReported = false
    @Volatile private var lastInjectionMs = 0L

    /**
     * Returns the root helper, first giving input focus back to the upper screen when typing
     * resumes after a pause (tapping PocketHub on the lower screen takes focus away).
     */
    private fun bridge(): RootInputBridgeClient? {
        val client = connectBridge() ?: return null
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastInjectionMs > REFOCUS_AFTER_IDLE_MS) {
            try { client.focus() } catch (error: Exception) { Log.w(TAG, "Could not focus the upper screen", error) }
        }
        lastInjectionMs = now
        return client
    }

    private fun connectBridge(): RootInputBridgeClient? {
        bridge?.takeIf { it.isAlive }?.let { return it }
        return try {
            RootInputBridgeClient(context).also { bridge = it; errorReported = false }
        } catch (error: Exception) {
            Log.e(TAG, "Root input helper unavailable", error)
            if (!errorReported) {
                errorReported = true
                mainHandler.post { onError(error.message ?: error.javaClass.simpleName) }
            }
            null
        }
    }

    /** Warm up the root helper so the first key press is not delayed by `su`. */
    fun prepare() { worker.execute { bridge() } }

    fun close() {
        worker.execute {
            try { bridge?.close() } catch (_: Exception) { }
            bridge = null
        }
    }

    private fun typeKey(code: Int, meta: Int = 0) {
        worker.execute {
            try { bridge()?.key(code, meta) } catch (error: Exception) { Log.w(TAG, "Key injection failed", error) }
        }
    }

    private fun typeText(text: String) {
        if (text.isEmpty()) return
        // Split into runs the virtual keyboard can type directly and runs that need a paste.
        val runs = ArrayList<Pair<Boolean, String>>()
        val current = StringBuilder()
        var currentTypable: Boolean? = null
        for (char in text) {
            val typable = char == '\n' || keyMap.getEvents(charArrayOf(char)) != null
            if (currentTypable != null && typable != currentTypable) {
                runs.add(currentTypable to current.toString()); current.setLength(0)
            }
            currentTypable = typable
            current.append(char)
        }
        if (current.isNotEmpty() && currentTypable != null) runs.add(currentTypable to current.toString())
        runs.forEach { (typable, run) -> if (typable) typeDirect(run) else pasteRun(run) }
    }

    private fun typeDirect(run: String) {
        worker.execute {
            try {
                val client = bridge() ?: return@execute
                run.split('\n').forEachIndexed { index, part ->
                    if (index > 0) client.key(KeyEvent.KEYCODE_ENTER)
                    if (part.isNotEmpty()) client.text(part)
                }
            } catch (error: Exception) { Log.w(TAG, "Text injection failed", error) }
        }
    }

    private fun pasteRun(run: String) {
        worker.execute {
            try {
                val client = bridge() ?: return@execute
                val previous = try { clipboard.primaryClip } catch (_: Exception) { null }
                onInternalClip(run)
                clipboard.setPrimaryClip(ClipData.newPlainText("PocketHub keyboard", run))
                client.key(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON, wait = true)
                Thread.sleep(150)
                if (previous != null) {
                    previous.getItemAt(0)?.coerceToText(context)?.toString()?.let(onInternalClip)
                    clipboard.setPrimaryClip(previous)
                }
            } catch (error: Exception) { Log.w(TAG, "Paste injection failed", error) }
        }
    }

    /** Ctrl+C / Ctrl+X / Ctrl+V on whatever is focused on the upper screen. */
    fun ctrlShortcut(keyCode: Int) = typeKey(keyCode, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        typeText(text?.toString() ?: return true)
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true
    override fun finishComposingText(): Boolean = true

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        repeat(beforeLength.coerceIn(0, 200)) { typeKey(KeyEvent.KEYCODE_DEL) }
        repeat(afterLength.coerceIn(0, 200)) { typeKey(KeyEvent.KEYCODE_FORWARD_DEL) }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        // The bridge sends a full down/up pair, so only react to the down half.
        if (event != null && event.action == KeyEvent.ACTION_DOWN) typeKey(event.keyCode, event.metaState)
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        typeKey(KeyEvent.KEYCODE_ENTER)
        return true
    }

    // Nothing is known about the remote field, so report it as empty.
    override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = ""
    override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = ""
    override fun getSelectedText(flags: Int): CharSequence? = null
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null

    companion object {
        private const val TAG = "PocketHubFreeTyping"
        private const val REFOCUS_AFTER_IDLE_MS = 1500L
    }
}
