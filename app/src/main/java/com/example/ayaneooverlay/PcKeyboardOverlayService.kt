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
    /** Dusk theme: like Aurora but without the moving background. */
    private var dusk = false
    /** Starship Console theme: solid console-coloured capsule keys with dark labels. */
    private var consoleTheme = false
    /** Obsidian theme: graphite keys with a lime accent over flowing lines. */
    private var obsidian = false
    /** Blueprint theme: square outlined keys on a still drawing grid. */
    private var blueprint = false
    /** Cartridge theme: rubber console keys on charcoal. */
    private var cart = false
    /** Deep Sea theme: dark rounded keys with a soft turquoise edge. */
    private var abyss = false
    /** Hi-Fi theme: hard metal keys with a bevel; lit keys glow amber / teal. */
    private var hifi = false
    /** Hologram theme: see-through keys with a cyan outline; lit keys solid. */
    private var hud = false
    /** Bauhaus theme: flat square keys, yellow and red for lit keys. */
    private var bauhaus = false
    /** Jade theme: soft green keys with leaf corners. */
    private var jade = false

    private fun applyTheme() {
        val theme = MainActivity.normalizeLayoutTheme(getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getString("layout_theme", MainActivity.DEFAULT_LAYOUT_THEME))
        aurora = theme == "aurora" || theme == "dusk" || theme == "obsidian" || theme == "blueprint" || theme == "cartridge" || theme == "abyss" || theme == "hifi" || theme == "hud" || theme == "bauhaus" || theme == "jade"
        dusk = theme == "dusk"
        obsidian = theme == "obsidian"
        blueprint = theme == "blueprint"
        cart = theme == "cartridge"
        abyss = theme == "abyss"
        hifi = theme == "hifi"
        hud = theme == "hud"
        bauhaus = theme == "bauhaus"
        jade = theme == "jade"
        consoleTheme = theme == "bridge"
        if (jade) {
            panelColor = Color.rgb(5, 14, 10)
            keyColor = Color.rgb(14, 33, 25)
            specialColor = Color.rgb(20, 46, 34)
            activeColor = Color.rgb(70, 214, 142)
            lockedColor = Color.rgb(176, 230, 120)
            textColor = Color.rgb(224, 244, 231)
            mutedColor = Color.rgb(116, 154, 132)
        } else if (bauhaus) {
            panelColor = Color.rgb(14, 14, 14)
            keyColor = Color.rgb(34, 34, 33)
            specialColor = Color.rgb(50, 50, 48)
            activeColor = Color.rgb(255, 200, 0)
            lockedColor = Color.rgb(230, 57, 45)
            textColor = Color.rgb(242, 238, 228)
            mutedColor = Color.rgb(150, 146, 138)
        } else if (hud) {
            panelColor = Color.rgb(2, 7, 11)
            keyColor = Color.rgb(6, 26, 35)
            specialColor = Color.rgb(9, 38, 50)
            activeColor = Color.rgb(0, 226, 255)
            lockedColor = Color.rgb(255, 196, 0)
            textColor = Color.rgb(212, 248, 255)
            mutedColor = Color.rgb(92, 150, 166)
        } else if (hifi) {
            panelColor = Color.rgb(13, 12, 11)
            keyColor = Color.rgb(44, 42, 39)
            specialColor = Color.rgb(60, 57, 53)
            activeColor = Color.rgb(255, 178, 48)
            lockedColor = Color.rgb(92, 236, 214)
            textColor = Color.rgb(238, 232, 220)
            mutedColor = Color.rgb(156, 148, 134)
        } else if (abyss) {
            panelColor = Color.rgb(4, 10, 18)
            keyColor = Color.rgb(12, 25, 39)
            specialColor = Color.rgb(18, 36, 54)
            activeColor = Color.rgb(64, 222, 206)
            lockedColor = Color.rgb(156, 128, 255)
            textColor = Color.rgb(226, 240, 248)
            mutedColor = Color.rgb(116, 146, 166)
        } else if (cart) {
            panelColor = Color.rgb(17, 17, 21)
            keyColor = Color.rgb(36, 36, 43)
            specialColor = Color.rgb(48, 48, 57)
            activeColor = Color.rgb(232, 86, 92)
            lockedColor = Color.rgb(236, 198, 124)
            textColor = Color.rgb(240, 237, 232)
            mutedColor = Color.rgb(146, 143, 154)
        } else if (blueprint) {
            panelColor = Color.rgb(7, 13, 24)
            keyColor = Color.rgb(11, 21, 37)
            specialColor = Color.rgb(17, 31, 52)
            activeColor = Color.rgb(206, 222, 242)
            lockedColor = Color.rgb(118, 160, 214)
            textColor = Color.rgb(226, 234, 245)
            mutedColor = Color.rgb(112, 132, 162)
        } else if (obsidian) {
            panelColor = Color.rgb(6, 7, 9)
            keyColor = Color.argb(236, 22, 25, 31)
            specialColor = Color.argb(236, 14, 16, 20)
            activeColor = Color.rgb(190, 242, 74)
            lockedColor = Color.rgb(92, 158, 255)
            textColor = Color.rgb(236, 238, 242)
            mutedColor = Color.rgb(130, 136, 150)
        } else if (consoleTheme) {
            // Starship Console: console blocks on black, dark labels.
            aurora = false
            panelColor = Color.BLACK
            keyColor = Color.rgb(255, 204, 153)
            specialColor = Color.rgb(255, 153, 0)
            activeColor = Color.rgb(204, 153, 255)
            lockedColor = Color.rgb(221, 68, 68)
            textColor = Color.rgb(10, 10, 14)
            mutedColor = Color.rgb(153, 153, 204)
        } else if (dusk) {
            // Dusk: same glass keys, quiet slate colours, still background.
            panelColor = Color.rgb(16, 18, 26)
            keyColor = Color.argb(200, 36, 40, 54)
            specialColor = Color.argb(170, 26, 29, 40)
            activeColor = Color.rgb(126, 200, 180)
            lockedColor = Color.rgb(214, 150, 160)
            textColor = Color.rgb(232, 234, 240)
            mutedColor = Color.rgb(150, 156, 176)
        } else if (aurora) {
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
                if (jade) background = JadeBackdrop(panelColor, activeColor, resources.displayMetrics.density)
                else if (bauhaus) background = BauhausBackdrop(panelColor, resources.displayMetrics.density)
                else if (hud) background = HudBackdrop(panelColor, activeColor, resources.displayMetrics.density)
                else if (hifi) background = HiFiBackdrop(panelColor, resources.displayMetrics.density)
                else if (abyss) background = AbyssBackdrop(panelColor, activeColor, lockedColor, resources.displayMetrics.density)
                else if (cart) background = CartridgeBackdrop(panelColor, Color.WHITE, resources.displayMetrics.density)
                else if (blueprint) background = BlueprintGridBackdrop(panelColor, activeColor, resources.displayMetrics.density)
                else if (obsidian) background = ObsidianBackdrop(panelColor, activeColor, lockedColor)
                else if (dusk) setBackgroundColor(panelColor)
                else if (aurora) background = AuroraBackdrop(panelColor, intArrayOf(
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
            text = if (consoleTheme) "PC ▸ 47-11" else if (jade) "❧ PC · EN" else if (bauhaus) "● ■ ▲  PC / EN" else if (hud) "◇ PC // EN" else if (hifi) "PC · EN · STEREO" else if (abyss) "PC  ◦  EN" else if (cart) "PC ▶ EN" else if (blueprint) "PC / EN" else if (obsidian) "PC  ·  EN" else if (aurora) "PC · EN  ✦" else "PC · EN"
            if (obsidian || blueprint) letterSpacing = 0.18f
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
            typeface = if (consoleTheme) Typeface.create("sans-serif-condensed", Typeface.BOLD)
                else if (obsidian) Typeface.create("sans-serif-medium", Typeface.NORMAL)
                else if (blueprint) Typeface.create("sans-serif-condensed", Typeface.BOLD)
                else if (cart) Typeface.create("monospace", Typeface.BOLD)
                else if (abyss) Typeface.create("sans-serif-medium", Typeface.NORMAL)
                else if (hifi) Typeface.create("sans-serif-condensed", Typeface.BOLD)
                else if (hud) Typeface.create("monospace", Typeface.BOLD)
                else if (bauhaus) Typeface.create("sans-serif-black", Typeface.NORMAL)
                else if (jade) Typeface.create("sans-serif-medium", Typeface.NORMAL)
                else Typeface.DEFAULT_BOLD
            // Obsidian: modifier and function keys carry lime labels.
            if (obsidian && !highlighted && bgColor == specialColor) setTextColor(activeColor)
            if (consoleTheme) { setAllCaps(true); gravity = Gravity.END or Gravity.BOTTOM; setPadding(0, 0, dp(context, 8), dp(context, 4)) }
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

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        if (jade) {
            // Jade keys: leaf corners, a soft green shade; lit keys are solid green / lime.
            val lit = color == activeColor || color == lockedColor
            val d = resources.displayMetrics.density
            val big = minOf(radiusDp, 10) * d; val small = 3f * d
            return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(LivingIconView.mixColors(color, Color.WHITE, if (lit) 0.12f else 0.05f), LivingIconView.mixColors(color, Color.BLACK, 0.18f))).apply {
                cornerRadii = floatArrayOf(big, big, small, small, big, big, small, small)
                setStroke(d.toInt().coerceAtLeast(1), if (lit) Color.argb(60, 255, 255, 255) else LivingIconView.withAlpha(activeColor, if (color == specialColor) 80 else 45))
            }
        }
        if (bauhaus) {
            // Bauhaus keys: flat squares; function keys get a heavy edge, lit keys are solid colour.
            val lit = color == activeColor || color == lockedColor
            return GradientDrawable().apply {
                setColor(color)
                cornerRadius = 0f
                if (!lit && color == specialColor) setStroke((2 * resources.displayMetrics.density).toInt(), Color.argb(120, 242, 238, 228))
            }
        }
        if (hud) {
            // Hologram keys: square-cut, see-through with a cyan outline; lit keys are solid.
            val lit = color == activeColor || color == lockedColor
            return GradientDrawable().apply {
                setColor(if (lit) color else LivingIconView.withAlpha(color, 200))
                cornerRadius = 1.5f * resources.displayMetrics.density
                setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1),
                    if (lit) LivingIconView.withAlpha(Color.WHITE, 110) else LivingIconView.withAlpha(activeColor, if (color == specialColor) 150 else 90))
            }
        }
        if (hifi) {
            // Hi-Fi keys: hard metal keys, lighter on top, darker at the bottom; lit keys glow.
            val lit = color == activeColor || color == lockedColor
            return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                if (lit) intArrayOf(LivingIconView.mixColors(color, Color.WHITE, 0.25f), color, LivingIconView.mixColors(color, Color.BLACK, 0.2f))
                else intArrayOf(LivingIconView.mixColors(color, Color.WHITE, 0.12f), color, LivingIconView.mixColors(color, Color.BLACK, 0.35f))).apply {
                cornerRadius = minOf(radiusDp, 4) * resources.displayMetrics.density
                setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1),
                    if (lit) LivingIconView.mixColors(color, Color.BLACK, 0.35f) else Color.argb(150, 0, 0, 0))
            }
        }
        if (abyss) {
            // Deep Sea keys: rounded, a soft top-to-bottom shade and a faint turquoise edge.
            val lit = color == activeColor || color == lockedColor
            return (if (lit) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(color, LivingIconView.mixColors(color, if (color == activeColor) lockedColor else activeColor, 0.45f)))
            else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                LivingIconView.mixColors(color, Color.WHITE, 0.05f), LivingIconView.mixColors(color, Color.BLACK, 0.15f)))).apply {
                cornerRadius = minOf(radiusDp, 12) * resources.displayMetrics.density
                setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1),
                    if (lit) Color.argb(50, 255, 255, 255) else LivingIconView.withAlpha(activeColor, if (color == specialColor) 70 else 40))
            }
        }
        if (cart) {
            // Cartridge keys: plastic with a darker bottom edge; lit keys are solid red / gold.
            val lit = color == activeColor || color == lockedColor
            return (if (lit) GradientDrawable().apply { setColor(color) }
            else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                LivingIconView.mixColors(color, Color.WHITE, 0.05f), color, LivingIconView.mixColors(color, Color.BLACK, 0.45f)))).apply {
                cornerRadius = minOf(radiusDp, 8) * resources.displayMetrics.density
                setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), if (lit) color else Color.argb(20, 255, 255, 255))
            }
        }
        if (blueprint) {
            // Blueprint keys: square and flat with a fine ink outline; lit keys are solid ink.
            val lit = color == activeColor || color == lockedColor
            return GradientDrawable().apply {
                setColor(if (lit) color else LivingIconView.withAlpha(color, 245))
                cornerRadius = 2f * resources.displayMetrics.density
                setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1),
                    if (lit) color else LivingIconView.withAlpha(activeColor, if (color == specialColor) 110 else 60))
            }
        }
        if (obsidian) {
            // Obsidian keys: graphite slabs with a hairline edge; lit keys are solid accent.
            val lit = color == activeColor || color == lockedColor
            return (if (lit) GradientDrawable().apply { setColor(color) }
            else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                LivingIconView.withAlpha(LivingIconView.mixColors(color, Color.WHITE, 0.04f), 240),
                LivingIconView.withAlpha(LivingIconView.mixColors(color, Color.BLACK, 0.35f), 240)))).apply {
                cornerRadius = (minOf(radiusDp + 1, 10) * resources.displayMetrics.density)
                setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), if (lit) color else Color.argb(38, 255, 255, 255))
            }
        }
        return roundedDefault(color, radiusDp)
    }

    private fun roundedDefault(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = ((if (consoleTheme) 18 else if (aurora) radiusDp + 5 else radiusDp) * resources.displayMetrics.density)
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
