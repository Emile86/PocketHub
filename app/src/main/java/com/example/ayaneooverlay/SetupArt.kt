package com.example.ayaneooverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Small still drawing of the AYANEO Pocket DS (clamshell: wide top screen, small 4:3 bottom
 * screen between the D-pad and the face buttons) for the setup wizard.
 *  - overlay "volume": [mode] "upper", "lower" or "both" marks where the volume panel appears.
 *  - overlay "keyboard": [mode] "bottom" (keyboard always on the lower screen while you type on
 *    the upper one) or "active" (the keyboard opens on the screen you tap).
 */
class VolumeSpotView(
    context: Context,
    private val mode: String,
    private val accent: Int,
    private val shell: Int,
    private val screen: Int,
    private val overlay: String = "volume"
) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val path = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // Aspect of the drawing (about 1 : 1.05).
        setMeasuredDimension(w, (w * 1.05f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val s = w / 100f
        line.strokeWidth = 1.2f * s

        // Lid with the top screen (16:9).
        fill.color = shell
        rect.set(6 * s, 4 * s, 94 * s, 54 * s); canvas.drawRoundRect(rect, 6 * s, 6 * s, fill)
        val top = RectF(11 * s, 8 * s, 89 * s, 8 * s + 78 * s * 9f / 16f)
        if (overlay == "keyboard") drawKeyboardScreen(canvas, top, upper = true, s = s)
        else drawScreen(canvas, top, mode == "upper" || mode == "both", s)

        // Hinge.
        fill.color = mix(shell, Color.BLACK, 0.35f)
        rect.set(14 * s, 54.5f * s, 86 * s, 57.5f * s); canvas.drawRoundRect(rect, 1.5f * s, 1.5f * s, fill)

        // Base with the small bottom screen (4:3), D-pad and face buttons.
        fill.color = shell
        rect.set(6 * s, 58 * s, 94 * s, 101 * s); canvas.drawRoundRect(rect, 6 * s, 6 * s, fill)
        val bw = 36 * s
        val bottom = RectF(50 * s - bw / 2, 62 * s, 50 * s + bw / 2, 62 * s + bw * 3f / 4f)
        if (overlay == "keyboard") drawKeyboardScreen(canvas, bottom, upper = false, s = s)
        else drawScreen(canvas, bottom, mode == "lower" || mode == "both", s)
        fill.color = mix(shell, Color.WHITE, 0.18f)
        rect.set(14 * s, 76 * s, 26 * s, 80 * s); canvas.drawRoundRect(rect, 1 * s, 1 * s, fill)
        rect.set(18 * s, 72 * s, 22 * s, 84 * s); canvas.drawRoundRect(rect, 1 * s, 1 * s, fill)
        for ((x, y) in listOf(80f to 73f, 85f to 78f, 80f to 83f, 75f to 78f)) canvas.drawCircle(x * s, y * s, 2.2f * s, fill)
    }

    private fun drawScreen(canvas: Canvas, r: RectF, active: Boolean, s: Float) {
        fill.color = if (active) mix(screen, accent, 0.12f) else screen
        canvas.drawRoundRect(r, 2 * s, 2 * s, fill)
        line.color = if (active) accent else Color.argb(40, 255, 255, 255)
        canvas.drawRoundRect(r, 2 * s, 2 * s, line)
        if (!active) return
        // Volume panel: a rounded bar with a speaker and a level near the top of the screen.
        val pw = r.width() * 0.62f
        val ph = minOf(r.height() * 0.24f, 9 * s)
        val panel = RectF(r.centerX() - pw / 2, r.top + r.height() * 0.14f, r.centerX() + pw / 2, r.top + r.height() * 0.14f + ph)
        fill.color = Color.argb(230, 20, 22, 28)
        canvas.drawRoundRect(panel, ph / 2, ph / 2, fill)
        fill.color = accent
        val cy = panel.centerY()
        val sx = panel.left + ph * 0.45f
        val u = ph * 0.18f
        path.reset()
        path.moveTo(sx, cy - u); path.lineTo(sx + u, cy - u); path.lineTo(sx + 2.2f * u, cy - 2.2f * u)
        path.lineTo(sx + 2.2f * u, cy + 2.2f * u); path.lineTo(sx + u, cy + u); path.lineTo(sx, cy + u); path.close()
        canvas.drawPath(path, fill)
        val barL = sx + 3.4f * u
        val barR = panel.right - ph * 0.4f
        fill.color = Color.argb(60, 255, 255, 255)
        rect.set(barL, cy - u * 0.6f, barR, cy + u * 0.6f); canvas.drawRoundRect(rect, u, u, fill)
        fill.color = accent
        rect.set(barL, cy - u * 0.6f, barL + (barR - barL) * 0.62f, cy + u * 0.6f); canvas.drawRoundRect(rect, u, u, fill)
    }

    /** Keyboard illustration: a text field, the keys, and a tap ring where the finger is. */
    private fun drawKeyboardScreen(canvas: Canvas, r: RectF, upper: Boolean, s: Float) {
        val bottomMode = mode == "bottom"
        // Bottom mode: field on the upper screen, keys on the lower one.
        // Active mode: field and keys on both screens (whichever you tap).
        val showKeys = if (bottomMode) !upper else true
        val showField = if (bottomMode) upper else true
        val active = showKeys
        fill.color = if (active) mix(screen, accent, 0.10f) else screen
        canvas.drawRoundRect(r, 2 * s, 2 * s, fill)
        line.color = if (active) accent else Color.argb(40, 255, 255, 255)
        canvas.drawRoundRect(r, 2 * s, 2 * s, line)
        val fieldBottom: Float
        if (showField) {
            val f = RectF(r.left + r.width() * 0.1f, r.top + r.height() * 0.1f, r.right - r.width() * 0.1f, r.top + r.height() * 0.1f + maxOf(3.5f * s, r.height() * 0.16f))
            fill.color = Color.argb(70, 255, 255, 255)
            canvas.drawRoundRect(f, 1.2f * s, 1.2f * s, fill)
            fill.color = accent
            canvas.drawRect(f.left + 2 * s, f.top + f.height() * 0.2f, f.left + 2.6f * s, f.bottom - f.height() * 0.2f, fill)
            fieldBottom = f.bottom
            if (!bottomMode) {
                // Tap ring on the field.
                line.color = Color.WHITE
                canvas.drawCircle(f.centerX(), f.centerY(), 2.6f * s, line)
                fill.color = Color.argb(90, 255, 255, 255)
                canvas.drawCircle(f.centerX(), f.centerY(), 1.4f * s, fill)
            }
        } else fieldBottom = r.top
        if (showKeys) {
            val kTop = if (showField) maxOf(fieldBottom + r.height() * 0.08f, r.top + r.height() * 0.4f) else r.top + r.height() * 0.12f
            val area = RectF(r.left + r.width() * 0.06f, kTop, r.right - r.width() * 0.06f, r.bottom - r.height() * 0.08f)
            val rows = 3
            val cols = 8
            val gap = 0.6f * s
            val kw = (area.width() - gap * (cols - 1)) / cols
            val kh = (area.height() - gap * rows) / (rows + 1)
            fill.color = Color.argb(150, 255, 255, 255)
            for (row in 0 until rows) for (col in 0 until cols) {
                val x = area.left + col * (kw + gap)
                val y = area.top + row * (kh + gap)
                rect.set(x, y, x + kw, y + kh); canvas.drawRoundRect(rect, 0.6f * s, 0.6f * s, fill)
            }
            fill.color = accent
            val y = area.top + rows * (kh + gap)
            rect.set(area.left + area.width() * 0.25f, y, area.right - area.width() * 0.25f, y + kh)
            canvas.drawRoundRect(rect, 0.6f * s, 0.6f * s, fill)
        }
    }

    private fun mix(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt())
}
