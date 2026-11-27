package com.example.ayaneooverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * PocketHub replacement for the Android volume popup, shown on the upper screen, the lower
 * screen or both (user setting) when a hardware volume key is pressed. The keys are caught
 * by AyaButtonAccessibilityService. Three rows: overall, upper screen and lower screen.
 * The keys change the default row from Settings; tapping a row's name switches the keys to
 * it until the panel closes. Holding a key keeps changing the volume quickly.
 */
object VolumePanel {
    private const val TAG = "PocketHubVolumePanel"
    private const val HIDE_AFTER_MS = 2600L
    private const val STEP_PERCENT = 5
    private const val REPEAT_DELAY_MS = 350L
    private const val REPEAT_INTERVAL_MS = 70L

    private enum class Row { MASTER, UPPER, LOWER }

    private class PanelWindow(val manager: WindowManager, val view: View) {
        val bars = mutableMapOf<Row, BarView>()
        val values = mutableMapOf<Row, TextView>()
        val titles = mutableMapOf<Row, TextView>()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val windows = mutableListOf<PanelWindow>()
    private var appContext: Context? = null
    private var selected = Row.MASTER
    private var heldUp: Boolean? = null
    private val hideRunnable = Runnable { hide() }
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val up = heldUp ?: return
            val app = appContext ?: return
            step(app, up)
            handler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    private val ACCENT = Color.rgb(40, 220, 180)
    private val TEXT = Color.rgb(236, 242, 255)
    private val MUTED = Color.rgb(140, 155, 180)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(ScreenAudioVolumeService.KEY_VOLUME_KEYS, false)

    /** Key pressed (first down event only). One step now, then fast repeat while held. */
    fun onKeyDown(context: Context, up: Boolean) {
        val app = context.applicationContext
        appContext = app
        if (windows.isEmpty()) {
            selected = defaultRow(app)
            if (!show(app)) {
                // No overlay permission or no display: behave like the normal Android key.
                val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                return
            }
        }
        step(app, up)
        heldUp = up
        handler.removeCallbacks(repeatRunnable)
        handler.postDelayed(repeatRunnable, REPEAT_DELAY_MS)
    }

    fun onKeyUp() {
        heldUp = null
        handler.removeCallbacks(repeatRunnable)
        scheduleHide()
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(repeatRunnable)
        heldUp = null
        for (window in windows) try { window.manager.removeView(window.view) } catch (_: Exception) { }
        windows.clear()
    }

    private fun step(app: Context, up: Boolean) {
        if (windows.isEmpty()) return
        change(app, selected, if (up) STEP_PERCENT else -STEP_PERCENT)
        refresh(app)
        scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, HIDE_AFTER_MS)
    }

    // ---- values -------------------------------------------------------------------

    private fun prefs(context: Context) =
        context.getSharedPreferences(ScreenAudioVolumeService.PREFS_NAME, Context.MODE_PRIVATE)

    private fun defaultRow(context: Context) = when (prefs(context).getString(ScreenAudioVolumeService.KEY_VOLUME_KEYS_TARGET, "master")) {
        "upper" -> Row.UPPER
        "lower" -> Row.LOWER
        else -> Row.MASTER
    }

    private fun keyFor(row: Row) = when (row) {
        Row.MASTER -> ScreenAudioVolumeService.KEY_MASTER
        Row.UPPER -> ScreenAudioVolumeService.KEY_UPPER
        Row.LOWER -> ScreenAudioVolumeService.KEY_LOWER
    }

    private fun read(context: Context, row: Row): Int {
        val separate = ScreenAudioVolumeService.isEnabled(context)
        if (row == Row.MASTER && !separate) {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            return audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        }
        return prefs(context).getInt(keyFor(row), 100).coerceIn(0, 100)
    }

    private fun change(context: Context, row: Row, delta: Int) = set(context, row, read(context, row) + delta, stepMedia = delta)

    /**
     * stepMedia: when per-screen volume is off the overall row is Android's media volume,
     * which has fewer steps (usually 15); a key press then moves exactly one step.
     */
    private fun set(context: Context, row: Row, percent: Int, stepMedia: Int = 0) {
        val value = percent.coerceIn(0, 100)
        val separate = ScreenAudioVolumeService.isEnabled(context)
        if (row == Row.MASTER && !separate) {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val target = when {
                stepMedia > 0 -> current + 1
                stepMedia < 0 -> current - 1
                else -> Math.round(value / 100f * max)
            }.coerceIn(0, max)
            try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) } catch (error: Exception) {
                Log.w(TAG, "Could not set media volume", error)
            }
            return
        }
        if (row != Row.MASTER && !separate) {
            // A screen row needs per-screen volume: switch it on.
            try { ScreenAudioVolumeService.enable(context) } catch (error: Exception) {
                Log.w(TAG, "Could not enable screen audio", error)
                return
            }
        }
        prefs(context).edit().putInt(keyFor(row), value).apply()
        try { ScreenAudioVolumeService.gainChanged(context, keyFor(row)) } catch (error: Exception) {
            Log.w(TAG, "Could not notify screen audio service", error)
        }
    }

    // ---- windows ------------------------------------------------------------------

    private fun lowerDisplay(manager: DisplayManager): Display? = manager.displays.firstOrNull { display ->
        display.displayId != Display.DEFAULT_DISPLAY && (display.flags and Display.FLAG_PRIVATE) == 0 &&
            display.name?.contains("virtual", true) != true
    }

    private fun show(app: Context): Boolean {
        if (!Settings.canDrawOverlays(app)) return false
        val displayManager = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val upper = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val lower = lowerDisplay(displayManager)
        val targets = when (prefs(app).getString(ScreenAudioVolumeService.KEY_VOLUME_PANEL_DISPLAY, "upper")) {
            "lower" -> listOfNotNull(lower ?: upper)   // falls back to the upper screen in single-screen mode
            "both" -> listOfNotNull(upper, lower)
            else -> listOfNotNull(upper)
        }
        for (display in targets) addWindow(app, display)
        if (windows.isEmpty()) return false
        refresh(app)
        return true
    }

    private fun addWindow(app: Context, display: Display) {
        try {
            val windowContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                app.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            else app.createDisplayContext(display)
            val wm = windowContext.getSystemService(WindowManager::class.java)
            val view = buildPanel(windowContext)
            val window = PanelWindow(wm, view)
            registerViews(window, view)
            val params = WindowManager.LayoutParams(
                minOf(dp(windowContext, 420), windowContext.resources.displayMetrics.widthPixels - dp(windowContext, 24)),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(windowContext, 22)
                title = "PocketHub volume"
            }
            wm.addView(view, params)
            windows += window
        } catch (error: Exception) {
            Log.w(TAG, "Could not show the volume panel on display ${display.displayId}", error)
        }
    }

    /** Finds the row views by their tags (set in buildPanel). */
    private fun registerViews(window: PanelWindow, root: View) {
        for (row in Row.values()) {
            (root.findViewWithTag<View>("bar_$row") as? BarView)?.let { window.bars[row] = it }
            (root.findViewWithTag<View>("value_$row") as? TextView)?.let { window.values[row] = it }
            (root.findViewWithTag<View>("title_$row") as? TextView)?.let { window.titles[row] = it }
        }
    }

    private fun buildPanel(context: Context): View {
        val ru = context.resources.configuration.locales[0]?.language == "ru"
        val panel = object : LinearLayout(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    // With panels on both screens, a touch on one panel is "outside" the
                    // other, so only a single panel closes on an outside touch.
                    if (windows.size <= 1) hide() else scheduleHide()
                    return true
                }
                scheduleHide()
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 10))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 22).toFloat()
                setColor(Color.argb(236, 16, 20, 32))
                setStroke(dp(context, 1), Color.argb(150, 40, 220, 180))
            }
            elevation = dp(context, 8).toFloat()
        }
        panel.addView(TextView(context).apply {
            text = if (ru) "♫  ГРОМКОСТЬ" else "♫  VOLUME"
            setTextColor(ACCENT)
            textSize = 12f
            typeface = Typeface.create("monospace", Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 6) })
        val names = mapOf(
            Row.MASTER to if (ru) "Общая" else "Overall",
            Row.UPPER to if (ru) "Верхний экран" else "Upper screen",
            Row.LOWER to if (ru) "Нижний экран" else "Lower screen"
        )
        for (row in Row.values()) {
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(context, 4), 0, dp(context, 4))
            }
            val title = TextView(context).apply {
                text = names[row]
                textSize = 12f
                gravity = Gravity.CENTER_VERTICAL
                tag = "title_$row"
                setOnClickListener {
                    selected = row
                    refresh(context.applicationContext)
                }
            }
            line.addView(title, LinearLayout.LayoutParams(dp(context, 118), dp(context, 40)))
            val bar = BarView(context) { fraction ->
                selected = row
                set(context.applicationContext, row, Math.round(fraction * 100))
                refresh(context.applicationContext)
            }.apply { tag = "bar_$row" }
            line.addView(bar, LinearLayout.LayoutParams(0, dp(context, 40), 1f))
            val value = TextView(context).apply {
                textSize = 12f
                setTextColor(TEXT)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                typeface = Typeface.DEFAULT_BOLD
                tag = "value_$row"
            }
            line.addView(value, LinearLayout.LayoutParams(dp(context, 48), -2))
            panel.addView(line)
        }
        return panel
    }

    private fun refresh(context: Context) {
        val separate = ScreenAudioVolumeService.isEnabled(context)
        for (row in Row.values()) {
            val percent = read(context, row)
            val active = row == Row.MASTER || separate
            for (window in windows) {
                window.bars[row]?.apply {
                    fraction = percent / 100f
                    dimmed = !active
                    highlighted = row == selected
                    invalidate()
                }
                window.values[row]?.text = if (active) "$percent%" else "—"
                window.titles[row]?.apply {
                    setTextColor(if (row == selected) ACCENT else if (active) TEXT else MUTED)
                    typeface = if (row == selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                }
            }
        }
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /** Simple horizontal level bar; drag or tap to set. */
    private class BarView(context: Context, private val onChange: (Float) -> Unit) : View(context) {
        var fraction = 0f
        var dimmed = false
        var highlighted = false
        private val track = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val h = height * if (highlighted) 0.42f else 0.30f
            val top = (height - h) / 2f
            val radius = h / 2f
            track.color = Color.argb(70, 255, 255, 255)
            rect.set(0f, top, width.toFloat(), top + h)
            canvas.drawRoundRect(rect, radius, radius, track)
            fill.color = if (dimmed) Color.argb(110, 140, 155, 180) else ACCENT
            val w = (width * fraction.coerceIn(0f, 1f)).coerceAtLeast(h)
            rect.set(0f, top, w, top + h)
            canvas.drawRoundRect(rect, radius, radius, fill)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    if (width > 0) onChange((event.x / width).coerceIn(0f, 1f))
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
