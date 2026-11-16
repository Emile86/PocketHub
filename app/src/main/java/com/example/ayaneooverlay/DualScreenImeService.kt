package com.example.ayaneooverlay

import android.app.Presentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.hardware.display.DisplayManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import java.lang.ref.WeakReference
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray

/** Native IME with a touch keyboard that can be drawn on Pocket DS display 2. */
class DualScreenImeService : InputMethodService() {
    private var bottomPresentation: KeyboardPresentation? = null
    private var bottomOverlayContext: Context? = null
    private var bottomOverlayWindowManager: WindowManager? = null
    private var bottomOverlayView: View? = null
    private var bottomOverlayDisplayId: Int? = null
    private var bottomScreenKeyboardRequested = false
    /** True while the keyboard was opened from PocketHub's Keyboard button (no text field attached). */
    private var freeTyping = false
    private var freeTypingConnection: FreeTypingInputConnection? = null
    /** Clipboard texts PocketHub sets itself while pasting, kept out of the clipboard history. */
    private val internalClipTexts = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private var shift = false
    private var capsLock = false
    private var symbols = false
    private var emojiMode = false
    private var clipboardMode = false
    private var utilityBarVisible = true
    private var sensitiveInput = false
    private var emojiCategory = 0
    private var numericInput = false
    private var phoneInput = false
    private var dateTimeInput = false
    private var emailInput = false
    private var uriInput = false
    private var pinyinBuffer = ""
    private var lastShiftTapMs = 0L
    private var backspaceRepeating = false
    private val repeatHandler = Handler(Looper.getMainLooper())
    private val backspaceRepeat = object : Runnable {
        override fun run() {
            if (!backspaceRepeating) return
            if (language == LANGUAGE_ZH && pinyinBuffer.isNotEmpty()) {
                pinyinBuffer = pinyinBuffer.dropLast(1)
                refreshKeyboard()
            } else currentInputConnection?.deleteSurroundingText(1, 0)
            repeatHandler.postDelayed(this, 65L)
        }
    }
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val displayManager by lazy { getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }
    private val clipboardManager by lazy { getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    private var clipboardListenerRegistered = false
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        val text = readPrimaryClipboardText()
        if (text != null && internalClipTexts.remove(text)) return@OnPrimaryClipChangedListener
        if (text != null) {
            rememberClipboardText(text)
            if (clipboardMode && !sensitiveInput) refreshKeyboard()
        }
    }
    private val emojiCategories = listOf(
        listOf("😀", "😂", "😊", "😍", "🥰", "😎", "😭", "😡", "🤔", "🥳", "😴", "🤗", "👍", "👎", "🙏", "👏", "❤️", "💔", "🔥", "✨", "🎉", "💯", "✅", "❌"),
        listOf("❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "💔", "💕", "💞", "💖", "💘", "💝", "💟", "❣️", "💌", "💋", "💤", "💢", "💬", "👋", "🤝", "💪"),
        listOf("🍎", "🍌", "🍇", "🍓", "🍉", "🍕", "🍔", "🍟", "🌭", "🍿", "🍩", "🍪", "🎂", "🍰", "☕", "🧃", "🥤", "🍺", "🍣", "🌮", "🥗", "🍜", "🍦", "🍫"),
        listOf("⚽", "🏀", "🏈", "⚾", "🎾", "🏐", "🎮", "🎧", "🎸", "🎹", "🚗", "🚕", "🚌", "🚲", "✈️", "🚀", "🏠", "🌍", "🌙", "☀️", "⭐", "🌈", "🎁", "🏆")
    )
    private val pinyinDictionary = linkedMapOf(
        "ni" to listOf("你", "尼", "呢", "泥"), "hao" to listOf("好", "号", "浩"),
        "nihao" to listOf("你好", "你号"), "nihaoma" to listOf("你好吗"),
        "wo" to listOf("我", "窝", "握"), "shi" to listOf("是", "时", "事", "市"),
        "de" to listOf("的", "得", "德"), "zhong" to listOf("中", "种", "重"),
        "guo" to listOf("国", "过", "果"), "zhongguo" to listOf("中国"),
        "ren" to listOf("人", "认", "任"), "men" to listOf("们", "门"),
        "xie" to listOf("谢", "写", "些"), "xiexie" to listOf("谢谢"),
        "zai" to listOf("在", "再"), "jian" to listOf("见", "间", "件"),
        "zaijian" to listOf("再见"), "ma" to listOf("吗", "妈", "马"),
        "woaini" to listOf("我爱你"), "pengyou" to listOf("朋友"),
        "zhongwen" to listOf("中文"), "xue" to listOf("学", "雪"),
        "xuexi" to listOf("学习"), "ai" to listOf("爱", "矮"),
        "qing" to listOf("请", "情"), "wen" to listOf("问", "文"),
        "zao" to listOf("早"), "shang" to listOf("上"), "wan" to listOf("晚"),
        "an" to listOf("安"), "zaoshanghao" to listOf("早上好"), "wanans" to listOf("晚安")
    )
    private val allLanguages = listOf(LANGUAGE_EN, LANGUAGE_RU, LANGUAGE_DE, LANGUAGE_FR, LANGUAGE_ES, LANGUAGE_ZH)
    private val supportedLanguages: List<String>
        get() {
            val configured = prefs.getStringSet(KEY_ENABLED_LANGUAGES, null)?.toSet()
                ?: DEFAULT_ENABLED_LANGUAGES
            return allLanguages.filter { it in configured }.ifEmpty { listOf(LANGUAGE_EN) }
        }
    private var language: String
        get() = prefs.getString(KEY_LANGUAGE, LANGUAGE_EN)?.takeIf { it in supportedLanguages }
            ?: supportedLanguages.first()
        set(value) { prefs.edit().putString(KEY_LANGUAGE, value).apply() }

    override fun onCreate() {
        super.onCreate()
        instanceRef = WeakReference(this)
        Log.i(TAG, "Input method service created")
    }

    /**
     * While free typing, edits go to [FreeTypingInputConnection], which injects key events
     * into the upper screen through root, instead of to the (absent) attached text field.
     */
    override fun getCurrentInputConnection(): InputConnection? =
        if (freeTyping) freeTypingConnection ?: super.getCurrentInputConnection() else super.getCurrentInputConnection()

    /** Opens the keyboard on the lower screen without a text field. Returns false if it could not be shown. */
    fun startFreeTyping(): Boolean {
        val connection = freeTypingConnection ?: FreeTypingInputConnection(
            View(this), this,
            onError = { message ->
                Toast.makeText(this, "PocketHub Keyboard: root access is needed to type ($message)", Toast.LENGTH_LONG).show()
            },
            onInternalClip = { text -> internalClipTexts.add(text) }
        ).also { freeTypingConnection = it }
        connection.prepare()
        freeTyping = true
        utilityBarVisible = prefs.getBoolean(KEY_TOOLBAR_DEFAULT_VISIBLE, true)
        numericInput = false; dateTimeInput = false; phoneInput = false; emailInput = false; uriInput = false
        sensitiveInput = false
        emojiMode = false; clipboardMode = false; symbols = false; pinyinBuffer = ""
        shift = false; capsLock = false
        startClipboardMonitor()
        bottomScreenKeyboardRequested = true
        if (!showOnBottomDisplay()) {
            stopFreeTyping()
            return false
        }
        return true
    }

    fun stopFreeTyping() {
        if (!freeTyping) return
        freeTyping = false
        bottomScreenKeyboardRequested = false
        stopClipboardMonitor()
        dismissBottomPresentation()
    }

    fun isFreeTyping(): Boolean = freeTyping

    private fun hideKeyboard() {
        if (freeTyping) stopFreeTyping() else requestHideSelf(0)
    }

    override fun onCreateInputView(): View = activeDisplayStub()

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        // The real keyboard is hosted by a Presentation on the lower display.
        // Keep the otherwise empty IME window on the main display from consuming taps.
        if (bottomScreenKeyboardRequested) {
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.setEmpty()
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // A real text field took over: leave free typing and use its connection.
        freeTyping = false
        Log.i(TAG, "Starting input view; restarting=$restarting, placement=${prefs.getString(KEY_PLACEMENT, PLACEMENT_BOTTOM)}")
        utilityBarVisible = prefs.getBoolean(KEY_TOOLBAR_DEFAULT_VISIBLE, true)
        val inputType = info?.inputType ?: InputType.TYPE_CLASS_TEXT
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        numericInput = inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_DATETIME
        dateTimeInput = inputClass == InputType.TYPE_CLASS_DATETIME
        phoneInput = inputClass == InputType.TYPE_CLASS_PHONE
        emailInput = inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        )
        uriInput = inputClass == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI
        sensitiveInput = (inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )) || (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) ||
            ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        stopClipboardMonitor()
        if (!sensitiveInput) startClipboardMonitor()
        // Always return to the text layout for a new editor; emoji/symbol panels are temporary modes.
        emojiMode = false
        clipboardMode = false
        symbols = false
        pinyinBuffer = ""
        shift = inputClass == InputType.TYPE_CLASS_TEXT && inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0
        capsLock = false
        if (prefs.getString(KEY_PLACEMENT, PLACEMENT_BOTTOM) == PLACEMENT_BOTTOM) {
            bottomScreenKeyboardRequested = true
            setInputView(activeDisplayStub())
            if (!showOnBottomDisplay()) {
                bottomScreenKeyboardRequested = false
                setInputView(keyboardSurface(this))
            }
        } else {
            bottomScreenKeyboardRequested = false
            dismissBottomPresentation()
            setInputView(keyboardSurface(this))
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Log.d(TAG, "Finishing input view; finishingInput=$finishingInput")
        if (freeTyping) {
            // The free keyboard is not tied to a text field; keep it open.
            super.onFinishInputView(finishingInput)
            return
        }
        stopClipboardMonitor()
        bottomScreenKeyboardRequested = false
        dismissBottomPresentation()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        Log.w(TAG, "Input method service destroyed")
        if (instanceRef?.get() === this) instanceRef = null
        freeTyping = false
        freeTypingConnection?.close()
        freeTypingConnection = null
        stopClipboardMonitor()
        bottomScreenKeyboardRequested = false
        dismissBottomPresentation()
        super.onDestroy()
    }

    private fun showOnBottomDisplay(): Boolean {
        val display = displayManager.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        if (display == null) {
            Log.e(TAG, "No secondary display available; falling back to the main display keyboard")
            dismissBottomPresentation()
            return false
        }
        if (Settings.canDrawOverlays(this)) {
            try {
                if (bottomOverlayDisplayId == display.displayId && bottomOverlayView != null) {
                    refreshBottomOverlay()
                    return true
                }
                dismissBottomPresentation()
                val overlayContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                } else createDisplayContext(display)
                val manager = overlayContext.getSystemService(WindowManager::class.java)
                    ?: throw IllegalStateException("WindowManager unavailable for lower display")
                val view = keyboardSurface(overlayContext)
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.BOTTOM
                    title = "AYANEO Keyboard"
                }
                manager.addView(view, params)
                bottomOverlayContext = overlayContext
                bottomOverlayWindowManager = manager
                bottomOverlayView = view
                bottomOverlayDisplayId = display.displayId
                return true
            } catch (error: Exception) {
                Log.e(TAG, "Failed to show overlay keyboard on lower display; trying Presentation", error)
                removeBottomOverlay()
            }
        }
        bottomPresentation?.let {
            if (it.isShowing && it.display?.displayId == display.displayId) {
                it.refreshKeys()
                return true
            }
            it.dismiss()
        }
        return try {
            bottomPresentation = KeyboardPresentation(createDisplayContext(display), display).also { it.show() }
            true
        } catch (error: Exception) {
            Log.e(TAG, "Failed to show keyboard on the secondary display", error)
            dismissBottomPresentation()
            false
        }
    }

    private fun dismissBottomPresentation() {
        removeBottomOverlay()
        bottomPresentation?.dismiss()
        bottomPresentation = null
    }

    private fun startClipboardMonitor() {
        if (sensitiveInput || clipboardListenerRegistered) return
        try {
            clipboardManager.addPrimaryClipChangedListener(clipboardListener)
            clipboardListenerRegistered = true
            readPrimaryClipboardText()?.let(::rememberClipboardText)
        } catch (error: Exception) {
            Log.w(TAG, "Unable to monitor clipboard", error)
        }
    }

    private fun stopClipboardMonitor() {
        if (!clipboardListenerRegistered) return
        try { clipboardManager.removePrimaryClipChangedListener(clipboardListener) } catch (_: Exception) { }
        clipboardListenerRegistered = false
    }

    private fun readPrimaryClipboardText(): String? = try {
        if (sensitiveInput || !clipboardManager.hasPrimaryClip()) null
        else clipboardManager.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
    } catch (error: Exception) {
        Log.w(TAG, "Unable to read clipboard", error)
        null
    }

    private fun clipboardHistory(): List<String> = try {
        val stored = prefs.getString(KEY_CLIPBOARD_HISTORY, "[]") ?: "[]"
        val json = JSONArray(stored)
        (0 until json.length()).mapNotNull { index -> json.optString(index).takeIf { it.isNotEmpty() } }
    } catch (_: Exception) {
        emptyList()
    }

    private fun rememberClipboardText(text: String) {
        if (sensitiveInput || text.isBlank()) return
        val history = (listOf(text) + clipboardHistory().filterNot { it == text }).take(MAX_CLIPBOARD_ITEMS)
        val json = JSONArray().apply { history.forEach(::put) }
        prefs.edit().putString(KEY_CLIPBOARD_HISTORY, json.toString()).apply()
    }

    private fun removeBottomOverlay() {
        val view = bottomOverlayView
        val manager = bottomOverlayWindowManager
        if (view != null && manager != null) {
            try { manager.removeView(view) } catch (_: Exception) { }
        }
        bottomOverlayView = null
        bottomOverlayWindowManager = null
        bottomOverlayContext = null
        bottomOverlayDisplayId = null
    }

    private fun refreshBottomOverlay() {
        val context = bottomOverlayContext ?: return
        val manager = bottomOverlayWindowManager ?: return
        val oldView = bottomOverlayView ?: return
        try { manager.removeView(oldView) } catch (_: Exception) { }
        val newView = keyboardSurface(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            title = "AYANEO Keyboard"
        }
        manager.addView(newView, params)
        bottomOverlayView = newView
    }

    private fun activeDisplayStub(): View = View(this).apply {
        setBackgroundColor(Color.TRANSPARENT)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this@DualScreenImeService, 1))
    }

    private data class KeySpec(val label: String, val action: String = label, val widthDp: Int = 0)

    private data class KeyboardPalette(
        val outerStart: Int, val outerEnd: Int, val outerStroke: Int,
        val accent: Int, val text: Int, val specialText: Int, val muted: Int,
        val keyTop: Int, val keyBottom: Int, val specialTop: Int, val specialBottom: Int,
        val pressedTop: Int, val pressedBottom: Int, val keyStroke: Int, val pressedStroke: Int,
        val candidateBg: Int, val candidateStroke: Int, val keyRadiusDp: Int, val outerRadiusDp: Int
    )

    private fun keyboardPalette(): KeyboardPalette = when (prefs.getString(KEY_THEME, THEME_OCEAN)) {
        THEME_GRAPHITE -> KeyboardPalette(
            Color.rgb(20, 22, 26), Color.rgb(31, 34, 39), Color.rgb(75, 80, 89),
            Color.rgb(230, 232, 235), Color.rgb(243, 244, 246), Color.rgb(248, 249, 250), Color.rgb(158, 164, 173),
            Color.rgb(66, 69, 75), Color.rgb(48, 51, 57), Color.rgb(91, 95, 103), Color.rgb(68, 72, 79),
            Color.rgb(115, 120, 128), Color.rgb(88, 92, 99), Color.rgb(85, 89, 97), Color.rgb(205, 209, 215),
            Color.rgb(57, 61, 68), Color.rgb(103, 108, 117), 5, 12
        )
        THEME_NEON -> KeyboardPalette(
            Color.rgb(12, 7, 24), Color.rgb(25, 9, 38), Color.rgb(151, 67, 255),
            Color.rgb(69, 255, 221), Color.rgb(250, 244, 255), Color.rgb(255, 246, 255), Color.rgb(184, 154, 205),
            Color.rgb(46, 22, 67), Color.rgb(26, 13, 43), Color.rgb(74, 27, 91), Color.rgb(48, 18, 66),
            Color.rgb(166, 49, 220), Color.rgb(104, 28, 164), Color.rgb(93, 48, 126), Color.rgb(255, 90, 226),
            Color.rgb(47, 18, 71), Color.rgb(147, 63, 204), 16, 18
        )
        THEME_LIGHT -> KeyboardPalette(
            Color.rgb(225, 234, 242), Color.rgb(204, 218, 230), Color.rgb(154, 174, 192),
            Color.rgb(20, 111, 171), Color.rgb(27, 39, 50), Color.rgb(255, 255, 255), Color.rgb(93, 110, 126),
            Color.rgb(255, 255, 255), Color.rgb(239, 244, 248), Color.rgb(61, 137, 183), Color.rgb(37, 108, 157),
            Color.rgb(117, 197, 239), Color.rgb(76, 158, 207), Color.rgb(177, 193, 207), Color.rgb(29, 120, 177),
            Color.rgb(218, 233, 244), Color.rgb(154, 184, 205), 12, 15
        )
        THEME_DARK -> KeyboardPalette(
            Color.rgb(20, 25, 32), Color.rgb(29, 37, 46), Color.rgb(60, 73, 87),
            Color.rgb(20, 111, 171), Color.rgb(239, 244, 249), Color.rgb(255, 255, 255), Color.rgb(165, 178, 191),
            Color.rgb(53, 63, 74), Color.rgb(40, 49, 59), Color.rgb(61, 137, 183), Color.rgb(37, 108, 157),
            Color.rgb(117, 197, 239), Color.rgb(76, 158, 207), Color.rgb(75, 91, 107), Color.rgb(29, 120, 177),
            Color.rgb(38, 50, 62), Color.rgb(78, 101, 121), 12, 15
        )
        THEME_TERMINAL -> KeyboardPalette(
            Color.rgb(6, 17, 10), Color.rgb(14, 31, 18), Color.rgb(58, 91, 49),
            Color.rgb(255, 190, 72), Color.rgb(213, 255, 170), Color.rgb(13, 28, 13), Color.rgb(130, 171, 116),
            Color.rgb(29, 48, 31), Color.rgb(17, 34, 22), Color.rgb(154, 215, 83), Color.rgb(101, 168, 52),
            Color.rgb(255, 202, 91), Color.rgb(215, 142, 44), Color.rgb(58, 86, 53), Color.rgb(255, 206, 91),
            Color.rgb(22, 47, 27), Color.rgb(94, 142, 72), 3, 5
        )
        THEME_KEYCAP -> KeyboardPalette(
            // Retro mechanical keyboard: charcoal case, cream PBT keycaps, grey modifiers, orange Enter.
            Color.rgb(44, 42, 40), Color.rgb(30, 29, 28), Color.rgb(70, 66, 62),
            Color.rgb(255, 122, 52), Color.rgb(48, 42, 34), Color.rgb(250, 248, 244), Color.rgb(170, 160, 146),
            Color.rgb(242, 235, 219), Color.rgb(196, 184, 160), Color.rgb(146, 142, 136), Color.rgb(104, 100, 95),
            Color.rgb(255, 160, 96), Color.rgb(214, 96, 36), Color.rgb(160, 150, 132), Color.rgb(255, 122, 52),
            Color.rgb(58, 55, 52), Color.rgb(90, 85, 80), 7, 14
        )
        THEME_ORBIT -> KeyboardPalette(
            Color.rgb(17, 16, 42), Color.rgb(33, 26, 66), Color.rgb(107, 95, 163),
            Color.rgb(255, 169, 112), Color.rgb(250, 245, 255), Color.rgb(255, 248, 237), Color.rgb(177, 166, 207),
            Color.rgb(83, 78, 139), Color.rgb(47, 46, 101), Color.rgb(132, 97, 177), Color.rgb(82, 63, 137),
            Color.rgb(255, 190, 124), Color.rgb(236, 111, 137), Color.rgb(141, 132, 195), Color.rgb(255, 220, 166),
            Color.rgb(38, 34, 81), Color.rgb(143, 124, 205), 99, 24
        )
        else -> KeyboardPalette(
            Color.rgb(8, 15, 26), Color.rgb(13, 28, 44), Color.rgb(41, 82, 112),
            Color.rgb(95, 203, 255), Color.WHITE, Color.rgb(222, 244, 255), Color.rgb(155, 181, 194),
            Color.rgb(36, 52, 70), Color.rgb(22, 35, 50), Color.rgb(36, 81, 112), Color.rgb(25, 57, 83),
            Color.rgb(33, 129, 177), Color.rgb(25, 91, 134), Color.rgb(50, 79, 104), Color.rgb(126, 222, 255),
            Color.rgb(20, 43, 63), Color.rgb(45, 85, 115), 10, 15
        )
    }

    private fun keyboardSurface(context: Context): View {
        val palette = keyboardPalette()
        val keyHeightDp = when (prefs.getString(KEY_SIZE, SIZE_STANDARD)) {
            SIZE_SMALL -> 40
            SIZE_LARGE -> 54
            else -> 46
        }
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 9), dp(context, 7), dp(context, 9), dp(context, 7))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(palette.outerStart, palette.outerEnd)).apply {
                cornerRadius = dp(context, palette.outerRadiusDp).toFloat()
                setStroke(dp(context, 1), palette.outerStroke)
            }
        }

        if (utilityBarVisible) {
            val header = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
            }
            header.addView(makeKey(context, KeySpec(if (emojiMode) "⌨" else "😊", if (emojiMode) "EMOJI_EXIT" else "EMOJI_OPEN", 46), 40, 19f) {
                emojiMode = !emojiMode
                clipboardMode = false
                refreshKeyboard()
            })
            if (!sensitiveInput) {
                header.addView(makeKey(context, KeySpec(clipboardActionLabel("COPY"), "COPY", 68), 40, 11f) { copySelection(cut = false) })
                header.addView(makeKey(context, KeySpec(clipboardActionLabel("CUT"), "CUT", 72), 40, 11f) { copySelection(cut = true) })
                header.addView(makeKey(context, KeySpec(clipboardActionLabel("PASTE"), "PASTE", 68), 40, 11f) { pastePrimaryClipboard() })
                header.addView(makeKey(context, KeySpec("📋", "CLIPBOARD", 48), 40, 19f) {
                    clipboardMode = !clipboardMode
                    emojiMode = false
                    refreshKeyboard()
                })
            }
            header.addView(View(context), LinearLayout.LayoutParams(dp(context, 10), 1))
            header.addView(makeKey(context, KeySpec("←", "LEFT", 60), 40, 18f) { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) })
            header.addView(makeKey(context, KeySpec("→", "RIGHT", 60), 40, 18f) { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) })
            header.addView(makeKey(context, KeySpec("↑", "UP", 60), 40, 18f) { sendKey(KeyEvent.KEYCODE_DPAD_UP) })
            header.addView(makeKey(context, KeySpec("↓", "DOWN", 60), 40, 18f) { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) })
            header.addView(makeKey(context, KeySpec("Tab", "TAB", 55), 40, 12f) { sendKey(KeyEvent.KEYCODE_TAB) })
            header.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            header.addView(makeKey(context, KeySpec("⌄", "HIDE", 42), 40, 15f) { hideKeyboard() })
            outer.addView(header, LinearLayout.LayoutParams(-1, dp(context, 42)))
        }

        if (clipboardMode && !sensitiveInput) {
            addClipboardPanel(outer, context)
            addRow(outer, context, listOf(
                KeySpec("⌨", "CLIPBOARD_EXIT", 56),
                toolbarToggleSpec(56),
                KeySpec(enterLabel(), "ENTER", 64)
            ), keyHeightDp)
            return outer
        }

        if (numericInput || phoneInput) {
            val digitRows = listOf("123", "456", "789").map { row ->
                row.map { KeySpec(it.toString()) }
            }
            digitRows.forEach { addRow(outer, context, it, keyHeightDp) }
            if (phoneInput) {
                addRow(outer, context, listOf(KeySpec("*"), KeySpec("0"), KeySpec("#")), keyHeightDp)
                addRow(outer, context, listOf(KeySpec("+"), KeySpec("⌫", "DELETE", 76), toolbarToggleSpec(60), KeySpec(enterLabel(), "ENTER", 76)), keyHeightDp)
            } else if (dateTimeInput) {
                addRow(outer, context, listOf(KeySpec("/"), KeySpec("0"), KeySpec(":")), keyHeightDp)
                addRow(outer, context, listOf(KeySpec("-"), KeySpec("⌫", "DELETE", 76), toolbarToggleSpec(60), KeySpec(enterLabel(), "ENTER", 76)), keyHeightDp)
            } else {
                val signed = infoHasFlag(InputType.TYPE_NUMBER_FLAG_SIGNED)
                val decimal = infoHasFlag(InputType.TYPE_NUMBER_FLAG_DECIMAL)
                val lower = listOf(
                    if (signed) KeySpec("-") else KeySpec("00", "DOUBLE_ZERO", 76),
                    KeySpec("0"),
                    if (decimal) KeySpec(".") else KeySpec("⌫", "DELETE", 76)
                )
                addRow(outer, context, lower, keyHeightDp)
                addRow(outer, context, listOf(KeySpec("⌫", "DELETE", 76), toolbarToggleSpec(60), KeySpec(enterLabel(), "ENTER", 90)), keyHeightDp)
            }
            return outer
        }

        if (emojiMode) {
            addRow(outer, context, listOf("😀", "❤️", "🍕", "⚽").mapIndexed { index, icon ->
                KeySpec(icon, "EMOJI_CAT_$index")
            }, (keyHeightDp - 6).coerceAtLeast(36))
            emojiCategories.getOrElse(emojiCategory) { emojiCategories.first() }.chunked(6).forEach { row ->
                addRow(outer, context, row.map { KeySpec(it, "EMOJI") }, keyHeightDp)
            }
            addRow(outer, context, listOf(
                KeySpec("ABC", "EMOJI_EXIT", 66),
                KeySpec("⌫", "DELETE", 64),
                KeySpec("space", "SPACE"),
                toolbarToggleSpec(56),
                KeySpec("↵", "ENTER", 66)
            ), keyHeightDp)
            return outer
        }

        if (language == LANGUAGE_ZH) addPinyinBar(outer, context)

        val rows = mutableListOf<List<KeySpec>>()
        rows += "1234567890".map { KeySpec(it.toString()) }
        if (symbols) {
            rows += listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/").map { KeySpec(it) }
            rows += listOf("=", "*", "\"", "'", ":", ";", "!", "?", "⌫").map {
                if (it == "⌫") KeySpec(it, "DELETE", 64) else KeySpec(it)
            }
        } else {
            val letters = when (language) {
                LANGUAGE_RU -> listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю")
                LANGUAGE_DE -> listOf("qwertzuiopü", "asdfghjklöä", "yxcvbnmß")
                LANGUAGE_FR -> listOf("azertyuiop", "qsdfghjklm", "wxcvbn")
                LANGUAGE_ES -> listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm")
                else -> listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
            }
            rows += letters[0].map { KeySpec(it.toString()) }
            rows += letters[1].map { KeySpec(it.toString()) }
            rows += listOf(KeySpec("⇧", "SHIFT", 64)) + letters[2].map { KeySpec(it.toString()) } + KeySpec("⌫", "DELETE", 64)
        }
        val bottomKeys = mutableListOf(KeySpec(if (symbols) "ABC" else "?123", "SYMBOLS", 62))
        if (supportedLanguages.size > 1) bottomKeys += KeySpec(languageShortName(), "LANGUAGE", 54)
        if (emailInput || uriInput) {
            bottomKeys += KeySpec(".", "PUNCT_PERIOD", 42)
            bottomKeys += KeySpec("/", widthDp = 42)
            bottomKeys += KeySpec("␣", "SPACE")
        } else {
            // Fixed narrow punctuation keys; the space bar takes all remaining width.
            bottomKeys += KeySpec(",", "PUNCT_COMMA", 40)
            bottomKeys += KeySpec("␣", "SPACE")
            bottomKeys += KeySpec(".", "PUNCT_PERIOD", 40)
        }
        bottomKeys += toolbarToggleSpec(52)
        bottomKeys += KeySpec(enterLabel(), "ENTER", 62)
        rows += bottomKeys
        rows.forEach { addRow(outer, context, it, keyHeightDp) }
        return outer
    }

    private fun infoHasFlag(flag: Int): Boolean = (currentInputEditorInfo?.inputType ?: 0) and flag != 0

    private fun enterLabel(): String = when (currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)) {
        EditorInfo.IME_ACTION_GO -> "Go"
        EditorInfo.IME_ACTION_SEARCH -> "⌕"
        EditorInfo.IME_ACTION_SEND -> "➤"
        EditorInfo.IME_ACTION_NEXT -> "→|"
        EditorInfo.IME_ACTION_DONE -> "✓"
        else -> "↵"
    }

    private fun languageShortName(code: String = language): String = when (code) {
        LANGUAGE_RU -> "RU"
        LANGUAGE_DE -> "DE"
        LANGUAGE_FR -> "FR"
        LANGUAGE_ES -> "ES"
        LANGUAGE_ZH -> "中"
        else -> "EN"
    }

    private fun chooseLanguage(code: String) {
        if (code !in supportedLanguages) return
        language = code
        pinyinBuffer = ""
        symbols = false
        emojiMode = false
        refreshKeyboard()
    }

    private fun showLanguagePicker(anchor: View) {
        val context = anchor.context
        val names = mapOf(
            LANGUAGE_EN to "EN", LANGUAGE_RU to "RU", LANGUAGE_DE to "DE",
            LANGUAGE_FR to "FR", LANGUAGE_ES to "ES", LANGUAGE_ZH to "中文"
        )
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 5), dp(context, 5), dp(context, 5), dp(context, 5))
            background = GradientDrawable().apply {
                setColor(Color.rgb(11, 23, 36)); cornerRadius = dp(context, 12).toFloat()
                setStroke(dp(context, 1), Color.rgb(75, 160, 202))
            }
        }
        val popup = PopupWindow(row, ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 54), false).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation = dp(context, 8).toFloat()
        }
        supportedLanguages.forEach { code ->
            row.addView(TextView(context).apply {
                text = names[code]
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (code == language) Color.rgb(8, 19, 30) else Color.WHITE)
                background = if (code == language) keyBackground(context, "LANGUAGE") else keyBackground(context, "PUNCT_ALT")
                setOnClickListener { chooseLanguage(code); popup.dismiss() }
            }, LinearLayout.LayoutParams(dp(context, 45), dp(context, 44)).apply {
                setMargins(dp(context, 2), 0, dp(context, 2), 0)
            })
        }
        popup.showAsDropDown(anchor, 0, -(dp(context, 54) + anchor.height + dp(context, 4)))
    }

    private fun pinyinCandidates(input: String): List<String> {
        if (input.length < 2) return emptyList()
        val exact = pinyinDictionary[input].orEmpty()
        val completions = pinyinDictionary.entries.asSequence()
            .filter { it.key.startsWith(input) }
            .flatMap { it.value.asSequence() }
            .toList()
        return (exact + completions).distinct().take(5)
    }

    private fun clipboardActionLabel(action: String): String = when (language) {
        LANGUAGE_RU -> when (action) { "COPY" -> "Копия"; "CUT" -> "Вырезать"; else -> "Вставить" }
        else -> when (action) { "COPY" -> "Copy"; "CUT" -> "Cut"; else -> "Paste" }
    }

    private fun toolbarToggleSpec(widthDp: Int = 52): KeySpec = KeySpec(
        "⚙",
        if (utilityBarVisible) "TOOLBAR_TOGGLE_ON" else "TOOLBAR_TOGGLE_OFF",
        widthDp
    )

    private fun addClipboardPanel(parent: LinearLayout, context: Context) {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 3), dp(context, 4), dp(context, 3), dp(context, 4))
        }
        val items = clipboardHistory()
        if (items.isEmpty()) {
            list.addView(TextView(context).apply {
                text = if (language == LANGUAGE_RU) "Буфер обмена пуст" else "Clipboard is empty"
                gravity = Gravity.CENTER
                textSize = 15f
                setTextColor(keyboardPalette().muted)
            }, LinearLayout.LayoutParams(-1, dp(context, 220)))
        } else items.forEach { value ->
            list.addView(TextView(context).apply {
                text = value
                gravity = Gravity.CENTER_VERTICAL
                textSize = 14f
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(keyboardPalette().text)
                setPadding(dp(context, 12), dp(context, 7), dp(context, 12), dp(context, 7))
                background = GradientDrawable().apply {
                    setColor(keyboardPalette().candidateBg)
                    cornerRadius = dp(context, 10).toFloat()
                    setStroke(dp(context, 1), keyboardPalette().candidateStroke)
                }
                setOnClickListener {
                    if (!sensitiveInput) {
                        currentInputConnection?.commitText(value, 1)
                        clipboardMode = false
                        refreshKeyboard()
                    }
                }
            }, LinearLayout.LayoutParams(-1, dp(context, 56)).apply {
                setMargins(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 2))
            })
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(list, ViewGroup.LayoutParams(-1, -2))
        }
        parent.addView(scroll, LinearLayout.LayoutParams(-1, dp(context, 250)))
    }

    private fun copySelection(cut: Boolean) {
        if (sensitiveInput) return
        if (freeTyping) {
            freeTypingConnection?.ctrlShortcut(if (cut) KeyEvent.KEYCODE_X else KeyEvent.KEYCODE_C)
            return
        }
        val connection = currentInputConnection ?: return
        val selected = connection.getSelectedText(0)?.toString()?.takeIf { it.isNotEmpty() } ?: return
        try {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("text", selected))
            rememberClipboardText(selected)
            if (cut) connection.commitText("", 1)
        } catch (error: Exception) {
            Log.w(TAG, "Unable to copy selected text", error)
        }
    }

    private fun pastePrimaryClipboard() {
        if (sensitiveInput) return
        if (freeTyping) {
            freeTypingConnection?.ctrlShortcut(KeyEvent.KEYCODE_V)
            return
        }
        val text = readPrimaryClipboardText() ?: return
        currentInputConnection?.commitText(text, 1)
        rememberClipboardText(text)
    }

    private fun addPinyinBar(parent: LinearLayout, context: Context) {
        val palette = keyboardPalette()
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 5), dp(context, 2), dp(context, 5), dp(context, 2))
        }
        val composition = TextView(context).apply {
            text = if (pinyinBuffer.isEmpty()) "拼音" else pinyinBuffer
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
        }
        bar.addView(composition, LinearLayout.LayoutParams(dp(context, 72), -1))
        val candidates = pinyinCandidates(pinyinBuffer)
        if (candidates.isEmpty() && pinyinBuffer.isNotEmpty()) {
            bar.addView(TextView(context).apply {
                text = "继续输入拼音"
                gravity = Gravity.CENTER_VERTICAL
                textSize = 12f
                setTextColor(palette.muted)
            }, LinearLayout.LayoutParams(0, -1, 1f))
        } else candidates.forEach { candidate ->
            bar.addView(TextView(context).apply {
                text = candidate
                gravity = Gravity.CENTER
                textSize = 19f
                setTextColor(palette.text)
                background = GradientDrawable().apply {
                    setColor(palette.candidateBg); cornerRadius = dp(context, 8).toFloat()
                    setStroke(dp(context, 1), palette.candidateStroke)
                }
                setOnClickListener { commitChineseCandidate(candidate) }
            }, LinearLayout.LayoutParams(0, -1, 1f).apply {
                setMargins(dp(context, 2), 0, dp(context, 2), 0)
            })
        }
        parent.addView(bar, LinearLayout.LayoutParams(-1, dp(context, 40)))
    }

    private fun commitChineseCandidate(candidate: String) {
        currentInputConnection?.commitText(candidate, 1)
        pinyinBuffer = ""
        refreshKeyboard()
    }

    private fun addRow(parent: LinearLayout, context: Context, keys: List<KeySpec>, heightDp: Int) {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        keys.forEach { key ->
            val weight = if (key.widthDp == 0) 1f else 0f
            val shown = if (key.action == "SPACE") key.label else key.label.let {
                if (it.length == 1 && it[0].isLetter()) {
                    if (shift || capsLock) it.uppercase() else it.lowercase()
                } else it
            }
            val size = when (key.action) {
                "SPACE" -> 13f
                "EMOJI" -> 24f
                "EMOJI_CAT_0", "EMOJI_CAT_1", "EMOJI_CAT_2", "EMOJI_CAT_3" -> 20f
                else -> 16f
            }
            line.addView(makeKey(context, key.copy(label = shown), heightDp, size) {
                handleKey(key.action, key.label)
            }, LinearLayout.LayoutParams(
                if (weight > 0f) 0 else dp(context, key.widthDp), dp(context, heightDp), weight
            ).apply { setMargins(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 2)) })
        }
        parent.addView(line, LinearLayout.LayoutParams(-1, dp(context, heightDp + 4)))
    }

    private fun makeKey(context: Context, spec: KeySpec, heightDp: Int, textSize: Float, action: () -> Unit): View =
        TextView(context).apply {
            val palette = keyboardPalette()
            text = spec.label
            gravity = Gravity.CENTER
            setTextSize(textSize)
            setTextColor(when (spec.action) {
                "TOOLBAR_TOGGLE_ON" -> Color.rgb(8, 18, 28)
                in SPECIAL_KEYS -> palette.specialText
                else -> palette.text
            })
            typeface = when {
                prefs.getString(KEY_THEME, THEME_OCEAN) == THEME_TERMINAL -> Typeface.create("monospace", if (spec.action in SPECIAL_KEYS) Typeface.BOLD else Typeface.NORMAL)
                spec.action in SPECIAL_KEYS -> Typeface.DEFAULT_BOLD
                else -> Typeface.DEFAULT
            }
            setPadding(dp(context, 2), 0, dp(context, 2), 0)
            background = keyBackground(context, spec.action)
            isFocusable = true
            contentDescription = spec.label
            setOnClickListener {
                if (prefs.getBoolean(KEY_HAPTIC, true)) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                action()
            }
            if (spec.action == "DELETE") setOnLongClickListener {
                backspaceRepeating = true
                if (language == LANGUAGE_ZH && pinyinBuffer.isNotEmpty()) {
                    pinyinBuffer = ""
                    refreshKeyboard()
                } else currentInputConnection?.deleteSurroundingText(1, 0)
                repeatHandler.postDelayed(backspaceRepeat, 65L)
                true
            }
            if (spec.action == "DELETE") setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    backspaceRepeating = false
                    repeatHandler.removeCallbacks(backspaceRepeat)
                }
                false
            }
            val alternatives = alternativesFor(spec.action)
            if (alternatives.isNotEmpty()) setOnLongClickListener {
                showAlternatives(this, alternatives)
                true
            }
            if (spec.action == "LANGUAGE") setOnLongClickListener {
                showLanguagePicker(this)
                true
            }
            if (spec.widthDp > 0) layoutParams = LinearLayout.LayoutParams(dp(context, spec.widthDp), dp(context, heightDp))
        }

    private fun keyBackground(context: Context, action: String): Drawable {
        val palette = keyboardPalette()
        val activeToolbar = action == "TOOLBAR_TOGGLE_ON"
        val special = action in SPECIAL_KEYS || action.startsWith("PUNCT_")
        if (prefs.getString(KEY_THEME, THEME_OCEAN) == THEME_KEYCAP) {
            val density = context.resources.displayMetrics.density
            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), KeycapDrawable(palette, action, true, density))
                addState(intArrayOf(), KeycapDrawable(palette, action, false, density))
            }
        }
        if (prefs.getString(KEY_THEME, THEME_OCEAN) == THEME_ORBIT && action != "SPACE") {
            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), OrbitKeyDrawable(palette, action, true))
                addState(intArrayOf(), OrbitKeyDrawable(palette, action, false))
            }
        }
        fun shape(pressed: Boolean) = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (activeToolbar) intArrayOf(palette.accent, palette.pressedBottom)
            else if (pressed) intArrayOf(palette.pressedTop, palette.pressedBottom)
            else if (special) intArrayOf(palette.specialTop, palette.specialBottom)
            else intArrayOf(palette.keyTop, palette.keyBottom)
        ).apply {
            cornerRadius = dp(context, palette.keyRadiusDp).toFloat()
            setStroke(dp(context, if (pressed || activeToolbar) 2 else 1), when {
                activeToolbar -> palette.accent
                pressed -> palette.pressedStroke
                else -> palette.keyStroke
            })
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(true))
            addState(intArrayOf(), shape(false))
        }
    }

    /**
     * Sculpted mechanical keycap: a darker skirt below a lighter, slightly dished top face.
     * Pressing sinks the face onto the skirt, like a real key travelling down.
     */
    private class KeycapDrawable(
        private val palette: KeyboardPalette,
        private val action: String,
        private val pressed: Boolean,
        private val density: Float
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shine = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            val enter = action == "ENTER" || action == "TOOLBAR_TOGGLE_ON"
            val special = action in SPECIAL_KEYS || action.startsWith("PUNCT_")
            val (faceTop, faceBottom) = when {
                enter -> palette.pressedTop to palette.accent
                special -> palette.specialTop to blend(palette.specialTop, palette.specialBottom, 0.35f)
                else -> palette.keyTop to blend(palette.keyTop, palette.keyBottom, 0.3f)
            }
            val skirt = when {
                enter -> palette.pressedBottom
                special -> palette.specialBottom
                else -> palette.keyBottom
            }
            val inset = 2f * density
            val travel = 4f * density
            val radius = palette.keyRadiusDp * density
            val outer = android.graphics.RectF(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
            // Skirt (side walls) with a soft contact shadow.
            paint.shader = null
            paint.color = Color.argb(90, 0, 0, 0)
            canvas.drawRoundRect(outer.left, outer.top + 1.5f * density, outer.right, outer.bottom + 1.5f * density, radius, radius, paint)
            paint.color = skirt
            canvas.drawRoundRect(outer, radius, radius, paint)
            // Top face: narrower than the skirt and raised unless pressed.
            val side = 3f * density
            val lift = if (pressed) 0f else travel
            val face = android.graphics.RectF(outer.left + side, outer.top + side * 0.6f + (travel - lift) * 0.6f,
                outer.right - side, outer.bottom - lift - side * 0.4f)
            paint.shader = LinearGradient(face.left, face.top, face.left, face.bottom, faceTop, faceBottom, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(face, radius * 0.8f, radius * 0.8f, paint)
            paint.shader = null
            // Dish highlight along the top edge of the face.
            shine.color = Color.argb(if (pressed) 30 else 70, 255, 255, 255)
            canvas.drawRoundRect(face.left + density, face.top + density, face.right - density, face.top + face.height() * 0.35f,
                radius * 0.7f, radius * 0.7f, shine)
        }

        private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
            (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
            (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
            (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt()
        )

        override fun setAlpha(alpha: Int) { paint.alpha = alpha; shine.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; shine.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class OrbitKeyDrawable(
        private val palette: KeyboardPalette,
        private val action: String,
        private val pressed: Boolean
    ) : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val ovalHeight = b.height() * 0.86f
            val ovalWidth = minOf(b.width() * 0.94f, ovalHeight * 1.68f)
            val oval = android.graphics.RectF(
                cx - ovalWidth / 2f, cy - ovalHeight / 2f,
                cx + ovalWidth / 2f, cy + ovalHeight / 2f
            )
            val (top, bottom, strokeColor) = when {
                action == "TOOLBAR_TOGGLE_ON" -> Triple(palette.accent, palette.pressedBottom, palette.accent)
                pressed -> Triple(palette.pressedTop, palette.pressedBottom, palette.pressedStroke)
                action in SPECIAL_KEYS || action.startsWith("PUNCT_") -> Triple(palette.specialTop, palette.specialBottom, palette.keyStroke)
                else -> Triple(palette.keyTop, palette.keyBottom, palette.keyStroke)
            }
            fill.shader = LinearGradient(oval.left, oval.top, oval.right, oval.bottom, top, bottom, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(oval, ovalHeight / 2f, ovalHeight / 2f, fill)
            fill.shader = null
            outline.color = strokeColor
            outline.strokeWidth = if (pressed || action == "TOOLBAR_TOGGLE_ON") 2f else 1f
            canvas.drawRoundRect(oval, ovalHeight / 2f, ovalHeight / 2f, outline)
        }

        override fun setAlpha(alpha: Int) { fill.alpha = alpha; outline.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter; outline.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun alternativesFor(action: String): List<String> = when (action) {
        "PUNCT_COMMA" -> listOf(",", "?", "!", ";", ":")
        "PUNCT_PERIOD" -> listOf(".", "…", "?", "!", "-")
        else -> if (action.length != 1 || !action[0].isLetter()) emptyList() else when (language) {
            LANGUAGE_RU -> when (action.lowercase()) { "е" -> listOf("ё"); "ь" -> listOf("ъ"); else -> emptyList() }
            LANGUAGE_DE -> when (action.lowercase()) { "a" -> listOf("ä"); "o" -> listOf("ö"); "u" -> listOf("ü"); "s" -> listOf("ß"); else -> emptyList() }
            LANGUAGE_FR -> when (action.lowercase()) {
                "a" -> listOf("à", "â", "æ"); "e" -> listOf("é", "è", "ê", "ë")
                "i" -> listOf("î", "ï"); "o" -> listOf("ô", "œ"); "u" -> listOf("ù", "û", "ü"); "c" -> listOf("ç")
                else -> emptyList()
            }
            LANGUAGE_ES -> when (action.lowercase()) {
                "a" -> listOf("á"); "e" -> listOf("é"); "i" -> listOf("í"); "o" -> listOf("ó"); "u" -> listOf("ú", "ü"); else -> emptyList()
            }
            else -> when (action.lowercase()) {
                "a" -> listOf("á", "à", "â", "ä", "ã", "å")
                "e" -> listOf("é", "è", "ê", "ë")
                "i" -> listOf("í", "ì", "î", "ï")
                "o" -> listOf("ó", "ò", "ô", "ö", "õ", "ø")
                "u" -> listOf("ú", "ù", "û", "ü")
                "n" -> listOf("ñ")
                "c" -> listOf("ç")
                else -> emptyList()
            }
        }
    }

    private fun showAlternatives(anchor: View, alternatives: List<String>) {
        val context = anchor.context
        val choices = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 5), dp(context, 5), dp(context, 5), dp(context, 5))
            background = GradientDrawable().apply {
                setColor(Color.rgb(11, 23, 36))
                cornerRadius = dp(context, 12).toFloat()
                setStroke(dp(context, 1), Color.rgb(75, 160, 202))
            }
        }
        val popup = PopupWindow(choices, ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 56), false).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation = dp(context, 8).toFloat()
        }
        alternatives.forEach { value ->
            choices.addView(TextView(context).apply {
                text = value
                gravity = Gravity.CENTER
                textSize = 20f
                setTextColor(Color.WHITE)
                background = keyBackground(context, "PUNCT_ALT")
                setPadding(dp(context, 12), dp(context, 5), dp(context, 12), dp(context, 5))
                setOnClickListener {
                    val output = if ((shift || capsLock) && value.any { it.isLetter() }) value.uppercase() else value
                    currentInputConnection?.commitText(output, 1)
                    popup.dismiss()
                    if (shift && !capsLock) {
                        shift = false
                        refreshKeyboard()
                    }
                }
            }, LinearLayout.LayoutParams(dp(context, 46), dp(context, 44)).apply {
                setMargins(dp(context, 2), 0, dp(context, 2), 0)
            })
        }
        popup.showAsDropDown(anchor, 0, -(dp(context, 56) + anchor.height + dp(context, 4)))
    }

    private fun handleKey(action: String, keyLabel: String = action) {
        val connection = currentInputConnection ?: return
        when (action) {
            "DELETE" -> if (language == LANGUAGE_ZH && pinyinBuffer.isNotEmpty()) {
                pinyinBuffer = pinyinBuffer.dropLast(1)
                refreshKeyboard()
            } else connection.deleteSurroundingText(1, 0)
            "SHIFT" -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastShiftTapMs < 450L) capsLock = !capsLock else shift = !shift
                lastShiftTapMs = now
                refreshKeyboard()
            }
            "SYMBOLS" -> { symbols = !symbols; refreshKeyboard() }
            "LANGUAGE" -> {
                val next = (supportedLanguages.indexOf(language) + 1) % supportedLanguages.size
                chooseLanguage(supportedLanguages[next])
            }
            "EMOJI_OPEN" -> { emojiMode = true; refreshKeyboard() }
            "EMOJI_EXIT" -> { emojiMode = false; refreshKeyboard() }
            "CLIPBOARD" -> if (!sensitiveInput) { clipboardMode = !clipboardMode; emojiMode = false; refreshKeyboard() }
            "CLIPBOARD_EXIT" -> { clipboardMode = false; refreshKeyboard() }
            "TOOLBAR_TOGGLE_ON" -> { utilityBarVisible = false; refreshKeyboard() }
            "TOOLBAR_TOGGLE_OFF" -> { utilityBarVisible = true; refreshKeyboard() }
            "COPY" -> copySelection(cut = false)
            "CUT" -> copySelection(cut = true)
            "PASTE" -> pastePrimaryClipboard()
            "EMOJI_CAT_0", "EMOJI_CAT_1", "EMOJI_CAT_2", "EMOJI_CAT_3" -> {
                emojiCategory = action.substringAfterLast('_').toIntOrNull()?.coerceIn(0, emojiCategories.lastIndex) ?: 0
                refreshKeyboard()
            }
            "HIDE" -> hideKeyboard()
            "SPACE" -> if (language == LANGUAGE_ZH && pinyinBuffer.isNotEmpty()) {
                commitChineseCandidate(pinyinCandidates(pinyinBuffer).firstOrNull() ?: pinyinBuffer)
            } else connection.commitText(" ", 1)
            "DOUBLE_ZERO" -> connection.commitText("00", 1)
            "ENTER" -> {
                if (language == LANGUAGE_ZH && pinyinBuffer.isNotEmpty()) {
                    commitChineseCandidate(pinyinCandidates(pinyinBuffer).firstOrNull() ?: pinyinBuffer)
                } else {
                    val editorAction = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                        ?: EditorInfo.IME_ACTION_NONE
                    if (!freeTyping && editorAction != EditorInfo.IME_ACTION_NONE && editorAction != EditorInfo.IME_ACTION_UNSPECIFIED) {
                        connection.performEditorAction(editorAction)
                    } else connection.commitText("\n", 1)
                }
            }
            "LEFT" -> sendKey(KeyEvent.KEYCODE_DPAD_LEFT)
            "RIGHT" -> sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            "UP" -> sendKey(KeyEvent.KEYCODE_DPAD_UP)
            "DOWN" -> sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
            "TAB" -> sendKey(KeyEvent.KEYCODE_TAB)
            else -> if (language == LANGUAGE_ZH && keyLabel.length == 1 && keyLabel[0].isLetter()) {
                pinyinBuffer += keyLabel.lowercase()
                refreshKeyboard()
            } else connection.commitText(if (shift || capsLock) keyLabel.uppercase() else keyLabel, 1)
        }
        if (shift && action !in setOf("SHIFT", "SYMBOLS", "LANGUAGE")) {
            shift = false
            refreshKeyboard()
        }
    }

    private fun refreshKeyboard() {
        if (freeTyping || prefs.getString(KEY_PLACEMENT, PLACEMENT_BOTTOM) == PLACEMENT_BOTTOM) {
            if (bottomOverlayView != null) refreshBottomOverlay() else bottomPresentation?.refreshKeys()
        } else setInputView(keyboardSurface(this))
    }

    private fun sendKey(keyCode: Int) {
        currentInputConnection?.let { connection ->
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    private inner class KeyboardPresentation(context: Context, display: Display) : Presentation(context, display) {
        private val displayContext = context
        private var host: ViewGroup? = null
        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            super.onCreate(savedInstanceState)
            window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            window?.setGravity(Gravity.BOTTOM)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            val root = LinearLayout(displayContext).apply {
                orientation = LinearLayout.VERTICAL
                addView(keyboardSurface(displayContext), ViewGroup.LayoutParams(-1, -2))
            }
            host = root
            setContentView(root)
        }
        fun refreshKeys() {
            host?.removeAllViews()
            host?.addView(keyboardSurface(displayContext), ViewGroup.LayoutParams(-1, -2))
        }
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        /** The running keyboard service; same process as MainActivity. Null when another keyboard is selected. */
        @Volatile private var instanceRef: WeakReference<DualScreenImeService>? = null
        val instance: DualScreenImeService? get() = instanceRef?.get()
        private const val TAG = "AyaneoOverlayIme"
        const val PREFS = "ime_settings"
        const val KEY_PLACEMENT = "placement"
        const val KEY_LANGUAGE = "keyboard_language"
        const val KEY_ENABLED_LANGUAGES = "keyboard_enabled_languages"
        const val KEY_SIZE = "keyboard_key_size"
        const val KEY_HAPTIC = "keyboard_haptic"
        const val KEY_THEME = "keyboard_theme"
        const val KEY_TOOLBAR_DEFAULT_VISIBLE = "keyboard_toolbar_default_visible"
        const val KEY_CLIPBOARD_HISTORY = "keyboard_clipboard_history"
        const val PLACEMENT_BOTTOM = "bottom"
        const val PLACEMENT_ACTIVE = "active"
        const val LANGUAGE_EN = "en"
        const val LANGUAGE_RU = "ru"
        const val LANGUAGE_DE = "de"
        const val LANGUAGE_FR = "fr"
        const val LANGUAGE_ES = "es"
        const val LANGUAGE_ZH = "zh"
        val DEFAULT_ENABLED_LANGUAGES = setOf(LANGUAGE_EN)
        const val THEME_OCEAN = "ocean"
        const val THEME_GRAPHITE = "graphite"
        const val THEME_NEON = "neon"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_TERMINAL = "terminal"
        const val THEME_ORBIT = "orbit"
        const val THEME_KEYCAP = "keycap"
        const val SIZE_SMALL = "small"
        const val SIZE_STANDARD = "standard"
        const val SIZE_LARGE = "large"
        private const val MAX_CLIPBOARD_ITEMS = 20
        private val SPECIAL_KEYS = setOf(
            "SHIFT", "SYMBOLS", "LANGUAGE", "ENTER", "DELETE", "HIDE", "CLIPBOARD",
            "COPY", "CUT", "PASTE", "CLIPBOARD_EXIT", "TOOLBAR_TOGGLE_ON", "TOOLBAR_TOGGLE_OFF",
            "LEFT", "RIGHT", "UP", "DOWN", "TAB"
        )
    }
}
