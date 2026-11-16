package com.example.ayaneooverlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Full PC keyboard (US English layout) on the lower screen. Every key is injected into
 * the upper screen as a real key code with modifier meta state, so Ctrl/Alt/Win shortcuts,
 * function keys and navigation keys behave like a hardware keyboard.
 * Modifiers are sticky: tap once for the next key, tap twice to lock.
 */
class PcKeyboardOverlayService : Service() {
    private data class Key(
        val label: String,
        val code: Int,
        val weight: Float = 1f,
        val shiftLabel: String? = null,
        val modifier: Int = 0,
        val repeat: Boolean = false
    )

    private enum class ModState { OFF, ONCE, LOCKED }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PocketHubPcKeyboard").apply { isDaemon = true }
    }
    @Volatile private var bridge: RootInputBridgeClient? = null
    @Volatile private var errorShown = false
    @Volatile private var lastInjectionMs = 0L

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var windowContext: Context? = null
    private var keyHeightPx = 0

    private val modifiers = linkedMapOf(
        MOD_SHIFT to ModState.OFF, MOD_CTRL to ModState.OFF, MOD_ALT to ModState.OFF, MOD_META to ModState.OFF
    )
    private var capsLock = false
    private val lastModifierTap = HashMap<Int, Long>()

    private var panelColor = Color.rgb(12, 16, 28)
    private var keyColor = Color.rgb(36, 44, 66)
    private var specialColor = Color.rgb(26, 33, 52)
    private var activeColor = Color.rgb(77, 163, 255)
    private var lockedColor = Color.rgb(255, 176, 64)
    private var textColor = Color.rgb(232, 238, 250)
    private var mutedColor = Color.rgb(150, 162, 188)
    /** Follows PocketHub's "Aurora Live" theme: glass keys over the moving aurora. */
    private var aurora = false

    private fun applyTheme() {
        val theme = getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getString("layout_theme", MainActivity.DEFAULT_LAYOUT_THEME)
        aurora = theme == "aurora"
        if (aurora) {
            panelColor = Color.rgb(9, 8, 24)
            keyColor = Color.argb(150, 46, 38, 98)
            specialColor = Color.argb(115, 26, 22, 64)
            activeColor = Color.rgb(64, 240, 196)
            lockedColor = Color.rgb(255, 112, 200)
            textColor = Color.rgb(242, 240, 255)
            mutedColor = Color.rgb(166, 160, 210)
        } else {
            panelColor = Color.rgb(12, 16, 28)
            keyColor = Color.rgb(36, 44, 66)
            specialColor = Color.rgb(26, 33, 52)
            activeColor = Color.rgb(77, 163, 255)
            lockedColor = Color.rgb(255, 176, 64)
            textColor = Color.rgb(232, 238, 250)
            mutedColor = Color.rgb(150, 162, 188)
        }
    }

    private val rows: List<List<Key>> by lazy {
        val f = (1..12).map { Key("F$it", KeyEvent.KEYCODE_F1 + it - 1) }
        listOf(
            listOf(Key("Esc", KeyEvent.KEYCODE_ESCAPE, 1.2f)) + f + listOf(Key("Del", KeyEvent.KEYCODE_FORWARD_DEL, 1.2f, repeat = true)),
            listOf(
                Key("`", KeyEvent.KEYCODE_GRAVE, shiftLabel = "~"), Key("1", KeyEvent.KEYCODE_1, shiftLabel = "!"),
                Key("2", KeyEvent.KEYCODE_2, shiftLabel = "@"), Key("3", KeyEvent.KEYCODE_3, shiftLabel = "#"),
                Key("4", KeyEvent.KEYCODE_4, shiftLabel = "$"), Key("5", KeyEvent.KEYCODE_5, shiftLabel = "%"),
                Key("6", KeyEvent.KEYCODE_6, shiftLabel = "^"), Key("7", KeyEvent.KEYCODE_7, shiftLabel = "&"),
                Key("8", KeyEvent.KEYCODE_8, shiftLabel = "*"), Key("9", KeyEvent.KEYCODE_9, shiftLabel = "("),
                Key("0", KeyEvent.KEYCODE_0, shiftLabel = ")"), Key("-", KeyEvent.KEYCODE_MINUS, shiftLabel = "_"),
                Key("=", KeyEvent.KEYCODE_EQUALS, shiftLabel = "+"), Key("⌫", KeyEvent.KEYCODE_DEL, 2f, repeat = true)
            ),
            listOf(Key("Tab", KeyEvent.KEYCODE_TAB, 1.5f)) + letters("qwertyuiop") + listOf(
                Key("[", KeyEvent.KEYCODE_LEFT_BRACKET, shiftLabel = "{"), Key("]", KeyEvent.KEYCODE_RIGHT_BRACKET, shiftLabel = "}"),
                Key("\\", KeyEvent.KEYCODE_BACKSLASH, 1.5f, shiftLabel = "|")
            ),
            listOf(Key("Caps", KeyEvent.KEYCODE_CAPS_LOCK, 1.8f)) + letters("asdfghjkl") + listOf(
                Key(";", KeyEvent.KEYCODE_SEMICOLON, shiftLabel = ":"), Key("'", KeyEvent.KEYCODE_APOSTROPHE, shiftLabel = "\""),
                Key("Enter", KeyEvent.KEYCODE_ENTER, 2.2f)
            ),
            listOf(Key("Shift", KeyEvent.KEYCODE_SHIFT_LEFT, 2.3f, modifier = MOD_SHIFT)) + letters("zxcvbnm") + listOf(
                Key(",", KeyEvent.KEYCODE_COMMA, shiftLabel = "<"), Key(".", KeyEvent.KEYCODE_PERIOD, shiftLabel = ">"),
                Key("/", KeyEvent.KEYCODE_SLASH, shiftLabel = "?"), Key("↑", KeyEvent.KEYCODE_DPAD_UP, repeat = true),
                Key("Shift", KeyEvent.KEYCODE_SHIFT_RIGHT, 1.7f, modifier = MOD_SHIFT)
            ),
            listOf(
                Key("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, 1.4f, modifier = MOD_CTRL),
                Key("Win", KeyEvent.KEYCODE_META_LEFT, 1.2f, modifier = MOD_META),
                Key("Alt", KeyEvent.KEYCODE_ALT_LEFT, 1.2f, modifier = MOD_ALT),
                Key("Space", KeyEvent.KEYCODE_SPACE, 5.6f),
                Key("Alt", KeyEvent.KEYCODE_ALT_RIGHT, 1.2f, modifier = MOD_ALT),
                Key("Ctrl", KeyEvent.KEYCODE_CTRL_RIGHT, 1.2f, modifier = MOD_CTRL),
                Key("←", KeyEvent.KEYCODE_DPAD_LEFT, repeat = true),
                Key("↓", KeyEvent.KEYCODE_DPAD_DOWN, repeat = true),
                Key("→", KeyEvent.KEYCODE_DPAD_RIGHT, repeat = true)
            )
        )
    }

    private fun letters(chars: String): List<Key> = chars.map { c ->
        Key(c.toString(), KeyEvent.KEYCODE_A + (c - 'a'), shiftLabel = c.uppercase())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> hideAndStop()
            else -> if (rootView != null && intent?.action == ACTION_TOGGLE) hideAndStop() else show()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeWindow()
        running = false
        worker.execute {
            try { bridge?.close() } catch (_: Exception) { }
            bridge = null
        }
        super.onDestroy()
    }

    private fun show() {
        if (rootView != null) return
        applyTheme()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "PocketHub: allow display over other apps", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val bottom = displayManager.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        if (bottom == null) {
            Toast.makeText(this, "PocketHub: the second screen is not available", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        try {
            val context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                createWindowContext(bottom, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else createDisplayContext(bottom)
            val manager = context.getSystemService(WindowManager::class.java)
            val screenHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) manager.maximumWindowMetrics.bounds.height()
                else context.resources.displayMetrics.heightPixels
            // Six key rows plus a toolbar; keep keys between 34 and 58 dp tall.
            keyHeightPx = ((screenHeight * 0.80f - dp(context, 48)) / 6f).toInt()
                .coerceIn(dp(context, 34), dp(context, 58))
            windowContext = context
            windowManager = manager
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 6))
                if (aurora) background = AuroraBackdrop(panelColor, intArrayOf(
                    Color.rgb(40, 220, 180), Color.rgb(130, 80, 255), Color.rgb(255, 80, 180), Color.rgb(60, 140, 255)))
                else setBackgroundColor(panelColor)
            }
            rootView = root
            buildKeys()
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
                title = "PocketHub PC keyboard"
            }
            manager.addView(root, params)
            running = true
            worker.execute { connectBridge() }
        } catch (error: Exception) {
            Log.e(TAG, "Could not show the PC keyboard", error)
            removeWindow()
            stopSelf()
        }
    }

    private fun hideAndStop() {
        removeWindow()
        running = false
        stopSelf()
    }

    private fun removeWindow() {
        rootView?.let { view -> try { windowManager?.removeView(view) } catch (_: Exception) { } }
        rootView = null
        windowManager = null
        windowContext = null
    }

    // ---- Layout ---------------------------------------------------------------------

    private fun buildKeys() {
        val context = windowContext ?: return
        val root = rootView ?: return
        root.removeAllViews()
        val toolbar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf(
            Key("Home", KeyEvent.KEYCODE_MOVE_HOME), Key("End", KeyEvent.KEYCODE_MOVE_END),
            Key("PgUp", KeyEvent.KEYCODE_PAGE_UP, repeat = true), Key("PgDn", KeyEvent.KEYCODE_PAGE_DOWN, repeat = true),
            Key("Ins", KeyEvent.KEYCODE_INSERT), Key("PrtSc", KeyEvent.KEYCODE_SYSRQ)
        ).forEach { key -> toolbar.addView(keyView(context, key, special = true), LinearLayout.LayoutParams(dp(context, 60), -1).apply { marginEnd = dp(context, 4) }) }
        toolbar.addView(TextView(context).apply {
            text = if (aurora) "PC · EN  ✦" else "PC · EN"
            setTextColor(mutedColor); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, -1, 1f))
        toolbar.addView(TextView(context).apply {
            text = "✕"
            gravity = Gravity.CENTER
            setTextColor(textColor); textSize = 17f
            background = rounded(specialColor, 10)
            setOnClickListener { hideAndStop() }
        }, LinearLayout.LayoutParams(dp(context, 52), -1))
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(context, 40)).apply { bottomMargin = dp(context, 4) })

        rows.forEach { keys ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            keys.forEach { key ->
                row.addView(keyView(context, key), LinearLayout.LayoutParams(0, -1, key.weight).apply {
                    setMargins(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 2))
                })
            }
            root.addView(row, LinearLayout.LayoutParams(-1, keyHeightPx))
        }
    }

    private fun shiftActive(): Boolean = modifiers[MOD_SHIFT] != ModState.OFF

    private fun keyView(context: Context, key: Key, special: Boolean = false): View {
        val isLetter = key.code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z
        val shown = when {
            isLetter && (shiftActive() xor capsLock) -> key.shiftLabel ?: key.label
            !isLetter && shiftActive() && key.shiftLabel != null -> key.shiftLabel
            else -> key.label
        }
        val modState = if (key.modifier != 0) modifiers[key.modifier] else null
        val bgColor = when {
            modState == ModState.ONCE -> activeColor
            modState == ModState.LOCKED -> lockedColor
            key.code == KeyEvent.KEYCODE_CAPS_LOCK && capsLock -> lockedColor
            special || key.modifier != 0 || key.label.length > 1 -> specialColor
            else -> keyColor
        }
        return TextView(context).apply {
            text = shown
            gravity = Gravity.CENTER
            maxLines = 1
            val highlighted = modState == ModState.LOCKED || (key.code == KeyEvent.KEYCODE_CAPS_LOCK && capsLock) ||
                (aurora && modState == ModState.ONCE)
            setTextColor(if (highlighted) Color.rgb(9, 8, 24) else textColor)
            textSize = if (shown.length > 2) 11f else 15f
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(bgColor, 7)
            setOnTouchListener(KeyTouchListener(key))
        }
    }

    private inner class KeyTouchListener(private val key: Key) : View.OnTouchListener {
        private var repeating = false
        private val repeatRunnable = object : Runnable {
            override fun run() {
                if (!repeating) return
                sendKey(key, clearOneShot = false)
                mainHandler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    view.alpha = 0.6f
                    if (key.repeat) {
                        sendKey(key, clearOneShot = false)
                        repeating = true
                        mainHandler.postDelayed(repeatRunnable, REPEAT_DELAY_MS)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    view.alpha = 1f
                    if (key.repeat) {
                        repeating = false
                        mainHandler.removeCallbacks(repeatRunnable)
                        clearOneShotModifiers()
                    } else onKey(key)
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.alpha = 1f
                    repeating = false
                    mainHandler.removeCallbacks(repeatRunnable)
                }
            }
            return true
        }
    }

    private fun onKey(key: Key) {
        when {
            key.modifier != 0 -> {
                val now = SystemClock.uptimeMillis()
                val doubleTap = now - (lastModifierTap[key.modifier] ?: 0L) < DOUBLE_TAP_MS
                lastModifierTap[key.modifier] = now
                modifiers[key.modifier] = when (modifiers[key.modifier]) {
                    ModState.OFF -> ModState.ONCE
                    ModState.ONCE -> if (doubleTap) ModState.LOCKED else ModState.OFF
                    else -> ModState.OFF
                }
                buildKeys()
            }
            key.code == KeyEvent.KEYCODE_CAPS_LOCK -> {
                capsLock = !capsLock
                buildKeys()
            }
            else -> sendKey(key, clearOneShot = true)
        }
    }

    private fun currentMeta(key: Key): Int {
        var meta = 0
        if (modifiers[MOD_SHIFT] != ModState.OFF) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (modifiers[MOD_CTRL] != ModState.OFF) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (modifiers[MOD_ALT] != ModState.OFF) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (modifiers[MOD_META] != ModState.OFF) meta = meta or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        // Caps Lock inverts Shift for letters only, like a real keyboard.
        if (capsLock && key.code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            meta = meta xor (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        }
        return meta
    }

    private fun sendKey(key: Key, clearOneShot: Boolean) {
        val code = key.code
        val meta = currentMeta(key)
        worker.execute {
            try { connectBridge()?.key(code, meta) } catch (error: Exception) { Log.w(TAG, "Key injection failed", error) }
        }
        if (clearOneShot) clearOneShotModifiers()
    }

    private fun clearOneShotModifiers() {
        var changed = false
        modifiers.keys.toList().forEach { mod ->
            if (modifiers[mod] == ModState.ONCE) { modifiers[mod] = ModState.OFF; changed = true }
        }
        if (changed) buildKeys()
    }

    // ---- Root bridge ----------------------------------------------------------------

    /** Returns the root helper; gives focus back to the upper screen when typing resumes after a pause. */
    private fun connectBridge(): RootInputBridgeClient? {
        val client = bridge?.takeIf { it.isAlive } ?: try {
            RootInputBridgeClient(this).also { bridge = it; errorShown = false }
        } catch (error: Exception) {
            Log.e(TAG, "Root input helper unavailable", error)
            if (!errorShown) {
                errorShown = true
                mainHandler.post {
                    Toast.makeText(this, "PocketHub PC keyboard: root access is needed (${error.message})", Toast.LENGTH_LONG).show()
                }
            }
            return null
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastInjectionMs > REFOCUS_AFTER_IDLE_MS) {
            try { client.focus() } catch (error: Exception) { Log.w(TAG, "Could not focus the upper screen", error) }
        }
        lastInjectionMs = now
        return client
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = ((if (aurora) radiusDp + 5 else radiusDp) * resources.displayMetrics.density)
        if (aurora) setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), Color.argb(70, 255, 255, 255))
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val TAG = "PocketHubPcKeyboard"
        const val ACTION_TOGGLE = "com.example.ayaneooverlay.PC_KEYBOARD_TOGGLE"
        const val ACTION_HIDE = "com.example.ayaneooverlay.PC_KEYBOARD_HIDE"
        private const val MOD_SHIFT = 1
        private const val MOD_CTRL = 2
        private const val MOD_ALT = 3
        private const val MOD_META = 4
        private const val DOUBLE_TAP_MS = 400L
        private const val REPEAT_DELAY_MS = 420L
        private const val REPEAT_INTERVAL_MS = 55L
        private const val REFOCUS_AFTER_IDLE_MS = 1500L

        @Volatile var running = false
            private set

        fun toggle(context: Context) {
            context.startService(Intent(context, PcKeyboardOverlayService::class.java).setAction(ACTION_TOGGLE))
        }
    }
}
