package com.example.ayaneooverlay

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * PocketHub's own short messages ("Profile applied", "Could not …"). A drop-in replacement
 * for android.widget.Toast with the same calls (`Toast.makeText(context, text, length).show()`):
 * files that do not import android.widget.Toast get this class from the package.
 *
 * Looks: a small rounded capsule near the bottom of the screen, in the current theme's
 * colours, with a coloured dot (accent; amber for problems). It slides in, fades out, and a
 * new message replaces the old one instead of queueing behind it. It is shown as a window of
 * the activity (or as an overlay from services); without either it falls back to the system
 * toast.
 */
class Toast private constructor(
    private val context: Context,
    private val text: CharSequence,
    private val duration: Int
) {
    fun show() {
        handler.post { showNow(context, text, duration) }
    }

    companion object {
        const val LENGTH_SHORT = android.widget.Toast.LENGTH_SHORT
        const val LENGTH_LONG = android.widget.Toast.LENGTH_LONG

        private val handler = Handler(Looper.getMainLooper())
        private var current: View? = null
        private var currentManager: WindowManager? = null
        private val hideRunnable = Runnable { hideCurrent(animated = true) }

        // Theme colours, set by MainActivity whenever it draws a screen.
        @Volatile private var bg = Color.rgb(24, 28, 36)
        @Volatile private var accent = Color.rgb(110, 200, 175)
        @Volatile private var textColor = Color.rgb(236, 240, 246)
        @Volatile private var line = Color.rgb(60, 68, 82)

        fun setColors(background: Int, accentColor: Int, text: Int, border: Int) {
            bg = background; accent = accentColor; textColor = text; line = border
        }

        @JvmStatic fun makeText(context: Context, text: CharSequence, duration: Int): Toast = Toast(context, text, duration)

        @JvmStatic fun makeText(context: Context, resId: Int, duration: Int): Toast =
            Toast(context, context.getText(resId), duration)

        private val PROBLEM = Regex("(?i)is needed|not available|allow display|could not|couldn't|failed|error|not found|no root|denied|не удалось|ошибк|нет root|нужен root|mislukt|fout|no se pudo|error|fehlgeschlagen|échec|impossibile|失败|无法")

        private fun showNow(context: Context, text: CharSequence, duration: Int) {
            val activity = context as? Activity
            val manager: WindowManager
            val type: Int
            val overlayOk = Settings.canDrawOverlays(context)
            when {
                // Overlay on the activity's own screen: it stays visible even if that activity
                // closes right after (the quick profile window over a game does exactly that).
                activity != null && overlayOk && android.os.Build.VERSION.SDK_INT >= 30 && activity.display != null -> {
                    manager = activity.createWindowContext(activity.display!!, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                        .getSystemService(WindowManager::class.java)
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                }
                activity != null && !activity.isFinishing && !activity.isDestroyed -> {
                    manager = activity.windowManager
                    type = WindowManager.LayoutParams.TYPE_APPLICATION
                }
                overlayOk -> {
                    manager = context.applicationContext.getSystemService(WindowManager::class.java)
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                }
                else -> {
                    android.widget.Toast.makeText(context.applicationContext, text, duration).show()
                    return
                }
            }
            hideCurrent(animated = false)
            val density = context.resources.displayMetrics.density
            fun dp(v: Float) = (v * density).toInt()
            val problem = PROBLEM.containsMatchIn(text)
            val dotColor = if (problem) Color.rgb(240, 170, 70) else accent

            val pill = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13f), dp(8f), dp(15f), dp(8f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(18f).toFloat()
                    // Solid and clearly darker than the cards behind it, with an accent edge, so
                    // it never blends into the screen underneath.
                    setColor(solidBackground())
                    setStroke(dp(1.5f), Color.argb(220, Color.red(dotColor), Color.green(dotColor), Color.blue(dotColor)))
                }
                elevation = dp(10f).toFloat()
            }
            pill.addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dotColor) }
            }, LinearLayout.LayoutParams(dp(7f), dp(7f)).apply { marginEnd = dp(9f) })
            pill.addView(TextView(context).apply {
                this.text = text
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = (context.resources.displayMetrics.widthPixels * 0.78f).toInt()
                setLineSpacing(0f, 1.1f)
            })

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(112f)
                title = "PocketHubToast"
            }
            try {
                manager.addView(pill, params)
            } catch (_: Exception) {
                android.widget.Toast.makeText(context.applicationContext, text, duration).show()
                return
            }
            current = pill
            currentManager = manager
            pill.alpha = 0f
            pill.translationY = dp(14f).toFloat()
            pill.animate().alpha(1f).translationY(0f).setDuration(170).start()
            handler.removeCallbacks(hideRunnable)
            handler.postDelayed(hideRunnable, if (duration == LENGTH_LONG) 3200L else 1900L)
        }

        /**
         * Near-black with just a hint of the theme colour, fully opaque. A light theme (light
         * card colour) gets a solid near-white capsule instead, with dark text.
         */
        private fun solidBackground(): Int {
            val c = opaque(bg)
            val light = (Color.red(c) * 0.299 + Color.green(c) * 0.587 + Color.blue(c) * 0.114) > 150
            return if (light) Color.rgb(248, 248, 246)
            else Color.rgb((Color.red(c) * 0.35f + 6).toInt(), (Color.green(c) * 0.35f + 6).toInt(), (Color.blue(c) * 0.35f + 8).toInt())
        }

        /** The colour as it looks over a dark background, with no transparency left. */
        private fun opaque(color: Int): Int {
            val a = Color.alpha(color) / 255f
            val base = 14
            fun ch(c: Int) = (c * a + base * (1 - a)).toInt().coerceIn(0, 255)
            return Color.rgb(ch(Color.red(color)), ch(Color.green(color)), ch(Color.blue(color)))
        }

        private fun hideCurrent(animated: Boolean) {
            handler.removeCallbacks(hideRunnable)
            val view = current ?: return
            val manager = currentManager
            current = null
            currentManager = null
            fun remove() { try { manager?.removeViewImmediate(view) } catch (_: Exception) { } }
            if (animated && view.isAttachedToWindow) {
                view.animate().alpha(0f).translationY(view.height * 0.2f).setDuration(160).withEndAction { remove() }.start()
            } else remove()
        }
    }
}
