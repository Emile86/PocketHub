package com.example.ayaneooverlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * On-screen touchpad: a pad with left/right mouse buttons on the lower screen and a
 * cursor drawn on the upper screen. Clicks and drags are injected into the upper screen
 * through [RootInputBridge] (left = finger touch, right = mouse secondary button,
 * two-finger drag = mouse wheel).
 */
class TouchpadOverlayService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PocketHubTouchpad").apply { isDaemon = true }
    }
    @Volatile private var bridge: RootInputBridgeClient? = null
    @Volatile private var errorShown = false

    private var padManager: WindowManager? = null
    private var padView: View? = null
    private var cursorManager: WindowManager? = null
    private var cursorView: View? = null
    private var cursorParams: WindowManager.LayoutParams? = null

    private var cursorX = 0f
    private var cursorY = 0f
    private var topWidth = 1
    private var topHeight = 1
    private var leftHeld = false

    /** PocketHub's interface language (MainActivity stores it in its own preferences). */
    private val russian: Boolean
        get() = getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getString("ui_language", "en") == "ru"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> hideAndStop()
            else -> if (padView != null && intent?.action == ACTION_TOGGLE) hideAndStop() else show()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeWindows()
        running = false
        worker.execute {
            try { bridge?.close() } catch (_: Exception) { }
            bridge = null
        }
        super.onDestroy()
    }

    private fun show() {
        if (padView != null) return
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "PocketHub: allow display over other apps", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val top = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val bottom = displayManager.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        if (top == null || bottom == null) {
            Toast.makeText(this, "PocketHub: the second screen is not available", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        try {
            showCursor(top)
            showPad(bottom)
            running = true
            worker.execute { connectBridge() }
        } catch (error: Exception) {
            Log.e(TAG, "Could not show the touchpad", error)
            removeWindows()
            stopSelf()
        }
    }

    private fun hideAndStop() {
        removeWindows()
        running = false
        stopSelf()
    }

    private fun overlayContext(display: Display): Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else createDisplayContext(display)

    // ---- Cursor on the upper screen -------------------------------------------------

    private fun showCursor(display: Display) {
        val context = overlayContext(display)
        val manager = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = manager.maximumWindowMetrics.bounds
            topWidth = bounds.width(); topHeight = bounds.height()
        } else {
            val size = android.graphics.Point()
            @Suppress("DEPRECATION") display.getRealSize(size)
            topWidth = size.x; topHeight = size.y
        }
        cursorX = topWidth / 2f
        cursorY = topHeight / 2f
        val size = dp(context, 26)
        val view = CursorView(context)
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cursorX.toInt(); y = cursorY.toInt()
            title = "PocketHub cursor"
        }
        manager.addView(view, params)
        cursorManager = manager
        cursorView = view
        cursorParams = params
    }

    private fun moveCursor(dx: Float, dy: Float) {
        cursorX = (cursorX + dx).coerceIn(0f, (topWidth - 1).toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, (topHeight - 1).toFloat())
        val params = cursorParams ?: return
        params.x = cursorX.toInt(); params.y = cursorY.toInt()
        try { cursorManager?.updateViewLayout(cursorView, params) } catch (_: Exception) { }
        if (leftHeld) inject { it.touch("move", cursorX, cursorY) }
    }

    private class CursorView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f * context.resources.displayMetrics.density
            strokeJoin = Paint.Join.ROUND
        }
        override fun onDraw(canvas: Canvas) {
            // Classic arrow with its tip at (0,0): the click point.
            val w = width.toFloat(); val h = height.toFloat()
            val inset = outline.strokeWidth
            val path = Path().apply {
                moveTo(inset, inset)
                lineTo(inset, h * 0.82f)
                lineTo(w * 0.28f, h * 0.62f)
                lineTo(w * 0.46f, h * 0.96f)
                lineTo(w * 0.60f, h * 0.89f)
                lineTo(w * 0.43f, h * 0.56f)
                lineTo(w * 0.72f, h * 0.56f)
                close()
            }
            canvas.drawPath(path, fill)
            canvas.drawPath(path, outline)
        }
    }

    // ---- Pad on the lower screen ----------------------------------------------------

    private fun showPad(display: Display) {
        val context = overlayContext(display)
        val manager = context.getSystemService(WindowManager::class.java)
        // Follow PocketHub's Neo-Brutal themes; other themes keep the default dark look.
        val theme = getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getString("layout_theme", MainActivity.DEFAULT_LAYOUT_THEME)
        brutal = theme == "brutal" || theme == "brutal_dark"
        brutalDark = theme == "brutal_dark"
        aurora = theme == "aurora"
        val panelColor = when { brutalDark -> Color.rgb(22, 22, 24); brutal -> Color.rgb(246, 240, 226); aurora -> AURORA_BG; else -> Color.rgb(14, 20, 36) }
        val padColor = when { brutalDark -> Color.rgb(36, 36, 40); brutal -> Color.WHITE; aurora -> Color.argb(150, 46, 38, 98); else -> Color.rgb(24, 33, 56) }
        val accent = when { brutalDark -> Color.rgb(92, 140, 255); brutal -> Color.rgb(51, 102, 255); aurora -> AURORA_TEAL; else -> Color.rgb(77, 163, 255) }
        val textColor = when { brutalDark -> BRUTAL_PAPER; brutal -> BRUTAL_INK; aurora -> Color.rgb(242, 240, 255); else -> Color.rgb(226, 232, 245) }
        val muted = when { brutalDark -> Color.rgb(176, 170, 156); brutal -> Color.rgb(92, 86, 74); aurora -> Color.rgb(166, 160, 210); else -> Color.rgb(140, 152, 178) }
        val heavy = if (brutal) android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL) else android.graphics.Typeface.DEFAULT_BOLD

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 10))
            background = if (aurora) AuroraBackdrop(AURORA_BG, AURORA_GLOWS) else GradientDrawable().apply {
                setColor(panelColor)
                val r = dp(context, if (brutal) 0 else 18).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                if (brutal) setStroke(dp(context, 2), brutalLine())
            }
        }
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (aurora) header.addView(LivingIconView(context, "touchpad", accent), LinearLayout.LayoutParams(dp(context, 30), dp(context, 30)).apply { marginEnd = dp(context, 6) })
        header.addView(TextView(context).apply {
            text = when {
                aurora -> if (russian) "Тачпад" else "Touchpad"
                russian -> "🖱  Тачпад"
                else -> "🖱  Touchpad"
            }
            setTextColor(if (brutal) textColor else accent); textSize = 13f; typeface = heavy
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(context).apply {
            text = "✕"
            gravity = Gravity.CENTER
            setTextColor(textColor); textSize = 18f
            background = rounded(context, padColor, 12)
            setOnClickListener { hideAndStop() }
        }, LinearLayout.LayoutParams(dp(context, 48), dp(context, 36)))
        root.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 6) })

        val pad = TextView(context).apply {
            text = if (russian) "1 палец — курсор · тап — клик\n2 пальца — прокрутка · тап двумя — правый клик"
                else "1 finger: move · tap: click\n2 fingers: scroll · 2-finger tap: right click"
            gravity = Gravity.CENTER
            setTextColor(muted); textSize = 11f
            background = rounded(context, padColor, 16)
            setOnTouchListener(PadTouchListener(context))
        }
        root.addView(pad, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(context, 8) })

        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val leftButton = TextView(context).apply {
            text = if (russian) "ЛЕВАЯ" else "LEFT"
            gravity = Gravity.CENTER
            setTextColor(textColor); textSize = 13f; typeface = heavy
            background = rounded(context, padColor, 14)
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        leftHeld = true
                        view.background = rounded(context, if (aurora) AURORA_PINK else accent, 14, pressed = true)
                        if (brutal) (view as TextView).setTextColor(BRUTAL_INK)
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        val x = cursorX; val y = cursorY
                        inject { it.touch("down", x, y) }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (leftHeld) {
                            leftHeld = false
                            val x = cursorX; val y = cursorY
                            inject { it.touch("up", x, y) }
                        }
                        view.background = rounded(context, padColor, 14)
                        if (brutal) (view as TextView).setTextColor(textColor)
                    }
                }
                true
            }
        }
        val rightButton = TextView(context).apply {
            text = if (russian) "ПРАВАЯ" else "RIGHT"
            gravity = Gravity.CENTER
            setTextColor(textColor); textSize = 13f; typeface = heavy
            background = rounded(context, padColor, 14)
            setOnClickListener { view ->
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                rightClick()
            }
        }
        buttons.addView(leftButton, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(context, 5) })
        buttons.addView(rightButton, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(context, 5) })
        root.addView(buttons, LinearLayout.LayoutParams(-1, dp(context, 64)))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            title = "PocketHub touchpad"
        }
        manager.addView(root, params)
        padManager = manager
        padView = root
    }

    private inner class PadTouchListener(private val context: Context) : View.OnTouchListener {
        private var lastX = 0f
        private var lastY = 0f
        private var downTime = 0L
        private var travelled = 0f
        private var twoFinger = false
        private var scrollRemainder = 0f
        private var lastScrollY = 0f
        private val slop = dp(context, 8).toFloat()
        private val scrollStep = dp(context, 22).toFloat()

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x; lastY = event.y
                    downTime = SystemClock.uptimeMillis()
                    travelled = 0f
                    twoFinger = false
                    scrollRemainder = 0f
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    twoFinger = true
                    lastScrollY = averageY(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (twoFinger && event.pointerCount >= 2) {
                        val y = averageY(event)
                        val dy = y - lastScrollY
                        lastScrollY = y
                        travelled += abs(dy)
                        scrollRemainder += dy
                        while (abs(scrollRemainder) >= scrollStep) {
                            // Fingers moving down scroll the content up, like a laptop touchpad.
                            val direction = if (scrollRemainder > 0) 1f else -1f
                            scrollRemainder -= direction * scrollStep
                            val x = cursorX; val cy = cursorY
                            inject { it.scroll(x, cy, direction, 0f) }
                        }
                    } else if (!twoFinger) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        lastX = event.x; lastY = event.y
                        val distance = hypot(dx, dy)
                        travelled += distance
                        // Simple acceleration: slow strokes are precise, fast strokes cross the screen.
                        val gain = BASE_GAIN + min(distance / dp(context, 6), MAX_EXTRA_GAIN)
                        moveCursor(dx * gain, dy * gain)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val quick = SystemClock.uptimeMillis() - downTime < TAP_TIMEOUT_MS
                    if (quick && travelled < slop) {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        if (twoFinger) rightClick() else leftClick()
                    }
                    twoFinger = false
                }
                MotionEvent.ACTION_CANCEL -> twoFinger = false
            }
            return true
        }

        private fun averageY(event: MotionEvent): Float {
            var sum = 0f
            for (i in 0 until event.pointerCount) sum += event.getY(i)
            return sum / event.pointerCount
        }
    }

    private fun leftClick() {
        val x = cursorX; val y = cursorY
        inject { it.touch("down", x, y); it.touch("up", x, y) }
    }

    private fun rightClick() {
        val x = cursorX; val y = cursorY
        inject { it.rightClick(x, y) }
    }

    // ---- Root bridge ----------------------------------------------------------------

    private fun connectBridge(): RootInputBridgeClient? {
        bridge?.takeIf { it.isAlive }?.let { return it }
        return try {
            RootInputBridgeClient(this).also { bridge = it; errorShown = false }
        } catch (error: Exception) {
            Log.e(TAG, "Root input helper unavailable", error)
            if (!errorShown) {
                errorShown = true
                mainHandler.post {
                    Toast.makeText(this, "PocketHub touchpad: root access is needed (${error.message})", Toast.LENGTH_LONG).show()
                }
            }
            null
        }
    }

    private fun inject(action: (RootInputBridgeClient) -> Unit) {
        worker.execute {
            try { connectBridge()?.let(action) } catch (error: Exception) { Log.w(TAG, "Injection failed", error) }
        }
    }

    private fun removeWindows() {
        if (leftHeld) {
            leftHeld = false
            val x = cursorX; val y = cursorY
            inject { it.touch("up", x, y) }
        }
        padView?.let { view -> try { padManager?.removeView(view) } catch (_: Exception) { } }
        cursorView?.let { view -> try { cursorManager?.removeView(view) } catch (_: Exception) { } }
        padView = null; padManager = null
        cursorView = null; cursorManager = null; cursorParams = null
    }

    private var brutal = false
    private var brutalDark = false
    private var aurora = false

    private fun brutalLine(): Int = if (brutalDark) BRUTAL_PAPER else BRUTAL_INK

    /** Default: soft rounded block. Neo-Brutal: thick outline plus a hard offset shadow (smaller while pressed). */
    private fun rounded(context: Context, color: Int, radiusDp: Int, pressed: Boolean = false): android.graphics.drawable.Drawable {
        if (aurora) return GradientDrawable().apply {
            // Frosted glass pill so the aurora behind shows through.
            setColor(if (Color.alpha(color) == 255) LivingIconView.withAlpha(color, if (pressed) 200 else 170) else color)
            cornerRadius = dp(context, radiusDp + 10).toFloat()
            setStroke(dp(context, 1), Color.argb(if (pressed) 200 else 80, 255, 255, 255))
        }
        if (!brutal) return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(context, radiusDp).toFloat()
        }
        val offset = dp(context, if (pressed) 1 else 4)
        val radius = dp(context, 6).toFloat()
        val shadow = GradientDrawable().apply { setColor(brutalLine()); cornerRadius = radius }
        val face = GradientDrawable().apply { setColor(color); cornerRadius = radius; setStroke(dp(context, 2), brutalLine()) }
        return android.graphics.drawable.LayerDrawable(arrayOf(shadow, face)).apply {
            setLayerInset(0, offset, offset, 0, 0)
            setLayerInset(1, 0, 0, offset, offset)
        }
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val TAG = "PocketHubTouchpad"
        const val ACTION_TOGGLE = "com.example.ayaneooverlay.TOUCHPAD_TOGGLE"
        const val ACTION_HIDE = "com.example.ayaneooverlay.TOUCHPAD_HIDE"
        private const val BASE_GAIN = 1.4f
        private const val MAX_EXTRA_GAIN = 2.2f
        private const val TAP_TIMEOUT_MS = 220L
        private val BRUTAL_INK = Color.rgb(17, 17, 17)
        private val BRUTAL_PAPER = Color.rgb(240, 235, 222)
        private val AURORA_BG = Color.rgb(9, 8, 24)
        private val AURORA_TEAL = Color.rgb(64, 240, 196)
        private val AURORA_PINK = Color.rgb(255, 112, 200)
        private val AURORA_GLOWS = intArrayOf(Color.rgb(40, 220, 180), Color.rgb(130, 80, 255), Color.rgb(255, 80, 180), Color.rgb(60, 140, 255))

        /** True while the touchpad windows are shown. */
        @Volatile var running = false
            private set

        fun toggle(context: Context) {
            context.startService(Intent(context, TouchpadOverlayService::class.java).setAction(ACTION_TOGGLE))
        }
    }
}
