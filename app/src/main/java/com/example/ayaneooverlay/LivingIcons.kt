package com.example.ayaneooverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Animated line icons for the "Aurora Live" theme. Every icon is drawn on a 100×100 grid
 * and animated from the clock, so all icons stay in sync and nothing needs to be started
 * or stopped: the view simply redraws itself (~30 fps) while it is on screen.
 *
 * Kinds: eco, balanced, streaming, gaming, max, fps, fan, display, cpu, gpu, ram, battery,
 * storage, keyboard, touchpad, controller, profiles, pc, star.
 */
class LivingIconView(
    context: Context,
    private val kind: String,
    private var tint: Int,
    private val glow: Int = tint,
    animated: Boolean = true
) : View(context) {
    /** When false the icon is drawn in a fixed, still pose and costs nothing between redraws. */
    var animated: Boolean = animated
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    fun setTint(color: Int) {
        if (tint == color) return
        tint = color
        stroke.color = color
        fill.color = color
        invalidate()
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = tint
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = tint }
    private val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val blob = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Stroke width helper (kept as a single hook for theme-specific pen widths). */
    private fun w(v: Float) = v

    private fun mix(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt()
    )
    private val rect = RectF()
    private val phase = (kind.hashCode() and 0xFFFF) / 65535f // de-synchronise icons of different kinds
    private var framePending = false
    private val frame = Runnable {
        framePending = false
        invalidate()
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        // Still icons freeze at a pleasant pose instead of an arbitrary moment.
        val t = if (animated) SystemClock.uptimeMillis() / 1000f + phase * 7f else STILL_TIME
        canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        canvas.scale(size / 100f, size / 100f)
        stroke.strokeWidth = w(6f)
        var savedTint = tint
        if (popStyle) {
            // Pop: the icon sits on a vivid gradient blob and bounces when it is active.
            val bounce = if (animated) sin(t * 5f) else 0f
            canvas.scale(1f + 0.05f * bounce, 1f - 0.05f * bounce, 50f, 90f)
            blob.shader = android.graphics.LinearGradient(8f, 8f, 92f, 92f,
                glow, LivingIconView.withAlpha(mix(glow, Color.WHITE, 0.35f), 255), Shader.TileMode.CLAMP)
            canvas.drawCircle(50f, 50f, 46f, blob)
            savedTint = tint
            tint = Color.WHITE
            stroke.color = tint; fill.color = tint
            canvas.scale(0.72f, 0.72f, 50f, 50f)
        } else {
            savedTint = tint
            // Breathing halo behind every icon.
            val breath = if (animated) 0.5f + 0.5f * sin(t * 2.2f) else 0.3f
            soft.color = withAlpha(glow, (40 + 40 * breath).toInt())
            canvas.drawCircle(50f, 50f, 40f + 5f * breath, soft)
        }
        when (kind) {
            "eco" -> eco(canvas, t)
            "balanced" -> balanced(canvas, t)
            "streaming" -> streaming(canvas, t)
            "gaming", "controller" -> gaming(canvas, t)
            "max" -> flame(canvas, t)
            "fps" -> gauge(canvas, t)
            "fan" -> fan(canvas, t)
            "display" -> display(canvas, t)
            "cpu" -> chip(canvas, t)
            "gpu" -> wave(canvas, t)
            "ram" -> equalizer(canvas, t)
            "battery" -> battery(canvas, t)
            "storage" -> storage(canvas, t)
            "keyboard" -> keyboard(canvas, t)
            "touchpad" -> touchpad(canvas, t)
            "profiles" -> bolt(canvas, t)
            "pc" -> monitor(canvas, t)
            "wifi" -> wifi(canvas, t)
            "bluetooth" -> bluetooth(canvas, t)
            "rotate" -> rotate(canvas, t)
            "airplane" -> airplane(canvas, t)
            "location" -> location(canvas, t)
            "dnd" -> moon(canvas, t)
            "saver" -> saver(canvas, t)
            "dark" -> darkMode(canvas, t)
            "brightness" -> sun(canvas, t)
            "flashlight" -> flashlight(canvas, t)
            "landscape" -> landscape(canvas, t)
            else -> star(canvas, t)
        }
        canvas.restore()
        if (popStyle) { tint = savedTint; stroke.color = tint; fill.color = tint }
        // One pending frame at a time, however often the parent redraws us.
        if (animated && isShown && !framePending) {
            framePending = true
            postDelayed(frame, FRAME_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        framePending = false
        super.onDetachedFromWindow()
    }

    // ---- Icons ---------------------------------------------------------------------

    private fun eco(c: Canvas, t: Float) {
        c.save()
        c.rotate(10f * sin(t * 1.6f), 50f, 82f)
        path.reset()
        path.moveTo(50f, 82f)
        path.cubicTo(18f, 70f, 20f, 30f, 62f, 18f)
        path.cubicTo(80f, 40f, 76f, 74f, 50f, 82f)
        path.close()
        soft.color = withAlpha(tint, 70)
        c.drawPath(path, soft)
        c.drawPath(path, stroke)
        stroke.strokeWidth = w(4f)
        c.drawLine(50f, 82f, 60f, 32f, stroke)
        c.restore()
        sparkle(c, 78f, 26f, t * 3f)
    }

    private fun balanced(c: Canvas, t: Float) {
        val tilt = 14f * sin(t * 1.8f)
        c.drawLine(50f, 30f, 50f, 80f, stroke)
        c.drawLine(36f, 80f, 64f, 80f, stroke)
        c.save()
        c.rotate(tilt, 50f, 30f)
        c.drawLine(18f, 30f, 82f, 30f, stroke)
        val bob = 3f * sin(t * 3.6f)
        c.drawCircle(18f, 46f + bob, 9f, fill)
        c.drawCircle(82f, 46f - bob, 9f, fill)
        stroke.strokeWidth = w(3f)
        c.drawLine(18f, 30f, 18f, 37f + bob, stroke)
        c.drawLine(82f, 30f, 82f, 37f - bob, stroke)
        c.restore()
    }

    private fun streaming(c: Canvas, t: Float) {
        for (i in 0 until 3) {
            val p = ((t * 0.6f + i / 3f) % 1f)
            stroke.color = withAlpha(tint, ((1f - p) * 255).toInt())
            stroke.strokeWidth = w(5f)
            c.drawCircle(50f, 50f, 10f + p * 34f, stroke)
        }
        stroke.color = tint
        c.drawCircle(50f, 50f, 9f, fill)
    }

    private fun gaming(c: Canvas, t: Float) {
        c.save()
        c.translate(0f, 4f * sin(t * 2.4f))
        c.rotate(5f * sin(t * 1.2f), 50f, 50f)
        rect.set(14f, 32f, 86f, 70f)
        c.drawRoundRect(rect, 19f, 19f, stroke)
        stroke.strokeWidth = w(5f)
        c.drawLine(28f, 51f, 40f, 51f, stroke)
        c.drawLine(34f, 45f, 34f, 57f, stroke)
        val blink = ((t * 2f).toInt() % 2 == 0)
        soft.color = if (blink) tint else withAlpha(tint, 90)
        c.drawCircle(64f, 46f, 4.5f, soft)
        soft.color = if (!blink) tint else withAlpha(tint, 90)
        c.drawCircle(72f, 55f, 4.5f, soft)
        c.restore()
    }

    private fun flame(c: Canvas, t: Float) {
        val f1 = sin(t * 9f) * 4f
        val f2 = cos(t * 7f) * 5f
        path.reset()
        path.moveTo(50f, 86f)
        path.cubicTo(22f, 82f, 24f, 52f, 40f, 38f + f1)
        path.cubicTo(40f, 50f, 48f, 52f, 50f, 50f)
        path.cubicTo(46f, 34f, 56f, 22f, 58f + f2, 12f)
        path.cubicTo(70f, 30f, 82f, 52f, 74f, 70f)
        path.cubicTo(70f, 80f, 62f, 86f, 50f, 86f)
        path.close()
        soft.color = withAlpha(tint, 90)
        c.drawPath(path, soft)
        c.drawPath(path, stroke)
        val inner = 0.85f + 0.15f * sin(t * 11f)
        soft.color = withAlpha(Color.WHITE, 200)
        c.drawCircle(52f, 70f, 8f * inner, soft)
    }

    private fun gauge(c: Canvas, t: Float) {
        rect.set(14f, 22f, 86f, 94f)
        c.drawArc(rect, 160f, 220f, false, stroke)
        val angle = (200f + 140f * (0.5f + 0.5f * sin(t * 1.7f))) * (PI.toFloat() / 180f)
        stroke.strokeWidth = w(5f)
        c.drawLine(50f, 58f, 50f + 28f * cos(angle), 58f + 28f * sin(angle), stroke)
        c.drawCircle(50f, 58f, 6f, fill)
    }

    private fun fan(c: Canvas, t: Float) {
        c.drawCircle(50f, 50f, 38f, stroke)
        c.save()
        c.rotate((t * 300f) % 360f, 50f, 50f)
        soft.color = withAlpha(tint, 150)
        for (i in 0 until 3) {
            c.save()
            c.rotate(i * 120f, 50f, 50f)
            path.reset()
            path.moveTo(50f, 50f)
            path.cubicTo(44f, 34f, 52f, 18f, 66f, 20f)
            path.cubicTo(66f, 34f, 60f, 44f, 50f, 50f)
            c.drawPath(path, soft)
            c.restore()
        }
        c.restore()
        c.drawCircle(50f, 50f, 7f, fill)
    }

    private fun display(c: Canvas, t: Float) {
        stroke.strokeWidth = w(5f)
        rect.set(18f, 12f, 82f, 48f); c.drawRoundRect(rect, 7f, 7f, stroke)
        rect.set(24f, 56f, 76f, 88f); c.drawRoundRect(rect, 7f, 7f, stroke)
        val y = 16f + ((t * 0.9f) % 1f) * 68f
        if (y < 46f || y > 58f) {
            stroke.color = withAlpha(tint, 150)
            val x0 = if (y < 50f) 22f else 28f
            val x1 = if (y < 50f) 78f else 72f
            c.drawLine(x0, y, x1, y, stroke)
            stroke.color = tint
        }
    }

    private fun chip(c: Canvas, t: Float) {
        rect.set(26f, 26f, 74f, 74f)
        c.drawRoundRect(rect, 8f, 8f, stroke)
        stroke.strokeWidth = w(4f)
        for (i in 0 until 3) {
            val p = 38f + i * 12f
            c.drawLine(p, 14f, p, 26f, stroke); c.drawLine(p, 74f, p, 86f, stroke)
            c.drawLine(14f, p, 26f, p, stroke); c.drawLine(74f, p, 86f, p, stroke)
        }
        // A signal pulse runs around the die.
        val p = (t * 0.7f) % 1f
        val d = p * 192f
        val (x, y) = when {
            d < 48f -> 26f + d to 26f
            d < 96f -> 74f to 26f + (d - 48f)
            d < 144f -> 74f - (d - 96f) to 74f
            else -> 26f to 74f - (d - 144f)
        }
        soft.color = Color.WHITE
        c.drawCircle(x, y, 5f, soft)
        soft.color = withAlpha(tint, (120 + 100 * sin(t * 5f)).toInt().coerceIn(0, 255))
        rect.set(38f, 38f, 62f, 62f)
        c.drawRoundRect(rect, 4f, 4f, soft)
    }

    private fun wave(c: Canvas, t: Float) {
        rect.set(12f, 22f, 88f, 78f)
        c.drawRoundRect(rect, 10f, 10f, stroke)
        stroke.strokeWidth = w(5f)
        path.reset()
        var x = 18f
        path.moveTo(x, 50f)
        while (x <= 82f) {
            path.lineTo(x, 50f + 14f * sin((x / 10f) + t * 5f) * sin(t * 1.3f + x / 40f))
            x += 2f
        }
        c.drawPath(path, stroke)
    }

    private fun equalizer(c: Canvas, t: Float) {
        for (i in 0 until 5) {
            val h = 14f + 50f * abs(sin(t * (2f + i * 0.7f) + i))
            rect.set(16f + i * 15f, 82f - h, 26f + i * 15f, 82f)
            c.drawRoundRect(rect, 4f, 4f, fill)
        }
    }

    private fun battery(c: Canvas, t: Float) {
        rect.set(14f, 30f, 80f, 70f); c.drawRoundRect(rect, 9f, 9f, stroke)
        rect.set(82f, 42f, 90f, 58f); c.drawRoundRect(rect, 3f, 3f, fill)
        val level = 0.35f + 0.6f * (0.5f + 0.5f * sin(t * 0.9f))
        path.reset()
        val top = 66f - 32f * level
        path.moveTo(20f, 66f)
        var x = 20f
        while (x <= 74f) { path.lineTo(x, top + 2.5f * sin(x / 6f + t * 6f)); x += 3f }
        path.lineTo(74f, 66f); path.close()
        soft.color = withAlpha(tint, 170)
        c.drawPath(path, soft)
    }

    private fun storage(c: Canvas, t: Float) {
        for (i in 0 until 3) {
            val lift = if ((t * 1.5f).toInt() % 3 == i) -4f else 0f
            rect.set(20f, 18f + i * 24f + lift, 80f, 36f + i * 24f + lift)
            c.drawRoundRect(rect, 6f, 6f, stroke)
            c.drawCircle(70f, 27f + i * 24f + lift, 3.5f, fill)
        }
    }

    private fun keyboard(c: Canvas, t: Float) {
        rect.set(10f, 26f, 90f, 76f); c.drawRoundRect(rect, 10f, 10f, stroke)
        val lit = ((t * 5f).toInt() * 7) % 12
        for (row in 0 until 3) for (col in 0 until 4) {
            val idx = row * 4 + col
            soft.color = if (idx == lit) Color.WHITE else withAlpha(tint, 130)
            if (row == 2 && col in 1..2) continue
            c.drawCircle(25f + col * 17f, 38f + row * 13f, 4f, soft)
        }
        stroke.strokeWidth = w(5f)
        c.drawLine(38f, 64f, 62f, 64f, stroke)
    }

    private fun touchpad(c: Canvas, t: Float) {
        rect.set(14f, 18f, 86f, 82f); c.drawRoundRect(rect, 12f, 12f, stroke)
        for (i in 6 downTo 0) {
            val tt = t - i * 0.06f
            val x = 50f + 22f * sin(tt * 1.6f)
            val y = 50f + 16f * sin(tt * 3.2f)
            soft.color = withAlpha(tint, if (i == 0) 255 else 160 - i * 20)
            c.drawCircle(x, y, if (i == 0) 7f else 5f - i * 0.5f, soft)
        }
    }

    private fun bolt(c: Canvas, t: Float) {
        val s = 1f + 0.08f * sin(t * 6f)
        c.save()
        c.scale(s, s, 50f, 50f)
        path.reset()
        path.moveTo(56f, 10f); path.lineTo(26f, 56f); path.lineTo(48f, 56f)
        path.lineTo(42f, 90f); path.lineTo(74f, 42f); path.lineTo(52f, 42f); path.close()
        soft.color = withAlpha(tint, 110)
        c.drawPath(path, soft)
        c.drawPath(path, stroke)
        c.restore()
    }

    private fun monitor(c: Canvas, t: Float) {
        rect.set(12f, 16f, 88f, 68f); c.drawRoundRect(rect, 8f, 8f, stroke)
        c.drawLine(50f, 68f, 50f, 82f, stroke)
        c.drawLine(34f, 84f, 66f, 84f, stroke)
        stroke.strokeWidth = w(4f)
        c.drawLine(22f, 34f, 32f, 42f, stroke)
        c.drawLine(32f, 42f, 22f, 50f, stroke)
        if ((t * 2f).toInt() % 2 == 0) c.drawLine(38f, 50f, 54f, 50f, stroke)
    }

    private fun star(c: Canvas, t: Float) {
        c.save()
        c.rotate((t * 40f) % 360f, 50f, 50f)
        path.reset()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) 38f else 16f
            val a = (i * 36f - 90f) * (PI.toFloat() / 180f)
            val x = 50f + r * cos(a); val y = 50f + r * sin(a)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        soft.color = withAlpha(tint, 110)
        c.drawPath(path, soft)
        c.drawPath(path, stroke)
        c.restore()
    }

    private fun wifi(c: Canvas, t: Float) {
        // Arcs light up one after another like a signal sweep.
        val active = ((t * 2.5f).toInt() % 4)
        rect.set(0f, 0f, 0f, 0f)
        for (i in 0 until 3) {
            val r = 18f + i * 15f
            stroke.color = if (!animated || i <= active - 1 || active == 3) tint else withAlpha(tint, 90)
            rect.set(50f - r, 78f - r, 50f + r, 78f + r)
            c.drawArc(rect, 225f, 90f, false, stroke)
        }
        stroke.color = tint
        c.drawCircle(50f, 76f, 6f, fill)
    }

    private fun bluetooth(c: Canvas, t: Float) {
        path.reset()
        path.moveTo(32f, 34f); path.lineTo(66f, 64f); path.lineTo(50f, 80f); path.lineTo(50f, 20f)
        path.lineTo(66f, 36f); path.lineTo(32f, 66f)
        c.drawPath(path, stroke)
        if (animated) {
            val p = (t * 1.2f) % 1f
            stroke.color = withAlpha(tint, ((1f - p) * 200).toInt())
            stroke.strokeWidth = w(4f)
            c.drawCircle(50f, 50f, 30f + p * 14f, stroke)
            stroke.color = tint
            stroke.strokeWidth = w(6f)
        }
    }

    private fun rotate(c: Canvas, t: Float) {
        c.save()
        if (animated) c.rotate((t * 90f) % 360f, 50f, 50f)
        rect.set(20f, 20f, 80f, 80f)
        c.drawArc(rect, 200f, 260f, false, stroke)
        path.reset()
        path.moveTo(22f, 34f); path.lineTo(22f, 52f); path.lineTo(38f, 46f); path.close()
        c.drawPath(path, fill)
        c.restore()
        rect.set(40f, 34f, 60f, 66f)
        stroke.strokeWidth = w(4f)
        c.drawRoundRect(rect, 4f, 4f, stroke)
        stroke.strokeWidth = w(6f)
    }

    private fun airplane(c: Canvas, t: Float) {
        c.save()
        c.translate(0f, if (animated) 4f * sin(t * 2f) else 0f)
        c.rotate(-35f, 50f, 50f)
        path.reset()
        path.moveTo(50f, 12f); path.lineTo(56f, 40f); path.lineTo(84f, 56f); path.lineTo(84f, 62f)
        path.lineTo(56f, 54f); path.lineTo(54f, 76f); path.lineTo(62f, 84f); path.lineTo(38f, 84f)
        path.lineTo(46f, 76f); path.lineTo(44f, 54f); path.lineTo(16f, 62f); path.lineTo(16f, 56f)
        path.lineTo(44f, 40f); path.close()
        soft.color = withAlpha(tint, 110)
        c.drawPath(path, soft)
        stroke.strokeWidth = w(4f)
        c.drawPath(path, stroke)
        stroke.strokeWidth = w(6f)
        c.restore()
    }

    private fun location(c: Canvas, t: Float) {
        val bounce = if (animated) -5f * abs(sin(t * 3f)) else 0f
        if (animated) {
            val p = (t * 0.8f) % 1f
            soft.color = withAlpha(tint, ((1f - p) * 120).toInt())
            rect.set(50f - 24f * p - 6f, 84f - 6f * p - 3f, 50f + 24f * p + 6f, 84f + 6f * p + 3f)
            c.drawOval(rect, soft)
        }
        c.save()
        c.translate(0f, bounce)
        path.reset()
        path.moveTo(50f, 82f)
        path.cubicTo(28f, 58f, 24f, 46f, 24f, 38f)
        path.cubicTo(24f, 22f, 36f, 12f, 50f, 12f)
        path.cubicTo(64f, 12f, 76f, 22f, 76f, 38f)
        path.cubicTo(76f, 46f, 72f, 58f, 50f, 82f)
        path.close()
        c.drawPath(path, stroke)
        c.drawCircle(50f, 38f, 9f, fill)
        c.restore()
    }

    private fun moon(c: Canvas, t: Float) {
        path.reset()
        path.addCircle(48f, 50f, 30f, Path.Direction.CW)
        val cut = Path().apply { addCircle(64f, 40f, 26f, Path.Direction.CW) }
        path.op(cut, Path.Op.DIFFERENCE)
        soft.color = withAlpha(tint, 120)
        c.drawPath(path, soft)
        c.drawPath(path, stroke)
        if (animated) sparkle(c, 76f, 70f, t * 2.5f)
    }

    private fun saver(c: Canvas, t: Float) {
        rect.set(18f, 30f, 78f, 70f); c.drawRoundRect(rect, 9f, 9f, stroke)
        rect.set(80f, 42f, 88f, 58f); c.drawRoundRect(rect, 3f, 3f, fill)
        val pulse = if (animated) 0.5f + 0.5f * sin(t * 3f) else 0.6f
        path.reset()
        path.moveTo(48f, 64f); path.cubicTo(34f, 58f, 36f, 40f, 58f, 36f)
        path.cubicTo(62f, 50f, 58f, 62f, 48f, 64f); path.close()
        soft.color = withAlpha(tint, (120 + 120 * pulse).toInt())
        c.drawPath(path, soft)
    }

    private fun darkMode(c: Canvas, t: Float) {
        c.save()
        if (animated) c.rotate(20f * sin(t * 1.4f), 50f, 50f)
        c.drawCircle(50f, 50f, 30f, stroke)
        rect.set(20f, 20f, 80f, 80f)
        c.drawArc(rect, 90f, 180f, true, fill)
        c.restore()
    }

    private fun sun(c: Canvas, t: Float) {
        c.drawCircle(50f, 50f, 15f, fill)
        c.save()
        if (animated) c.rotate((t * 30f) % 360f, 50f, 50f)
        val len = if (animated) 8f + 3f * sin(t * 4f) else 9f
        for (i in 0 until 8) {
            c.save(); c.rotate(i * 45f, 50f, 50f)
            c.drawLine(50f, 26f - len, 50f, 26f, stroke)
            c.restore()
        }
        c.restore()
    }

    private fun flashlight(c: Canvas, t: Float) {
        if (animated) {
            soft.color = withAlpha(tint, (90 + 60 * sin(t * 6f)).toInt())
            path.reset()
            path.moveTo(36f, 34f); path.lineTo(64f, 34f); path.lineTo(86f, 6f); path.lineTo(14f, 6f); path.close()
            c.drawPath(path, soft)
        }
        path.reset()
        path.moveTo(32f, 34f); path.lineTo(68f, 34f); path.lineTo(60f, 50f); path.lineTo(60f, 88f)
        path.lineTo(40f, 88f); path.lineTo(40f, 50f); path.close()
        c.drawPath(path, stroke)
        c.drawCircle(50f, 64f, 4f, fill)
    }

    private fun landscape(c: Canvas, t: Float) {
        // A device turned on its side with a lock: gently tilts back to level when active.
        c.save()
        if (animated) c.rotate(6f * sin(t * 2f), 50f, 50f)
        rect.set(12f, 28f, 88f, 70f)
        c.drawRoundRect(rect, 8f, 8f, stroke)
        stroke.strokeWidth = w(4f)
        c.drawLine(80f, 42f, 80f, 56f, stroke)
        c.restore()
        rect.set(40f, 76f, 60f, 92f)
        c.drawRoundRect(rect, 3f, 3f, fill)
        rect.set(43f, 66f, 57f, 82f)
        c.drawArc(rect, 180f, 180f, false, stroke)
        stroke.strokeWidth = w(6f)
    }

    private fun sparkle(c: Canvas, x: Float, y: Float, t: Float) {
        val s = max(0f, sin(t))
        if (s <= 0f) return
        stroke.strokeWidth = w(3f)
        c.drawLine(x - 7f * s, y, x + 7f * s, y, stroke)
        c.drawLine(x, y - 7f * s, x, y + 7f * s, stroke)
        stroke.strokeWidth = w(6f)
    }

    companion object {
        private const val FRAME_MS = 33L
        /** Set by PocketHub when the Pop Gradient theme is active (same process as all icons). */
        @JvmStatic var popStyle = false
        private const val STILL_TIME = 0.35f
        fun withAlpha(color: Int, alpha: Int): Int =
            Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }
}

/**
 * Line chart of recent FPS samples (newest on the right) for the FPS card, so drops and
 * recoveries are easy to see. A faint guide marks 30 FPS.
 */
class FpsGraphView(context: Context, private val tint: Int, private val track: Int) : View(context) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = tint
    }
    private val area = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = tint }
    private val linePath = Path()
    private val areaPath = Path()
    private var values: List<Int> = emptyList()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setValues(samples: Collection<Int>) {
        values = samples.toList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val density = resources.displayMetrics.density
        val pad = 2f * density
        val top = pad; val bottom = h - pad
        val ceiling = max(60, values.maxOrNull() ?: 0).toFloat()
        fun yOf(v: Int) = bottom - (bottom - top) * (v / ceiling).coerceIn(0f, 1f)

        guide.strokeWidth = 1f * density
        guide.color = LivingIconView.withAlpha(track, 70)
        val y30 = yOf(30)
        canvas.drawLine(0f, y30, w, y30, guide)
        canvas.drawLine(0f, bottom, w, bottom, guide)
        if (values.size < 2) return

        val right = w - 3f * density
        val step = right / (SLOTS - 1)
        val startX = right - step * (values.size - 1)
        linePath.reset(); areaPath.reset()
        values.forEachIndexed { i, v ->
            val x = startX + i * step
            val y = yOf(v)
            if (i == 0) { linePath.moveTo(x, y); areaPath.moveTo(x, bottom); areaPath.lineTo(x, y) }
            else { linePath.lineTo(x, y); areaPath.lineTo(x, y) }
        }
        areaPath.lineTo(right, bottom)
        areaPath.close()
        area.shader = android.graphics.LinearGradient(0f, top, 0f, bottom,
            LivingIconView.withAlpha(tint, 110), LivingIconView.withAlpha(tint, 0), Shader.TileMode.CLAMP)
        canvas.drawPath(areaPath, area)
        line.strokeWidth = 2f * density
        canvas.drawPath(linePath, line)
        canvas.drawCircle(right, yOf(values.last()), 2.6f * density, dot)
    }

    companion object { const val SLOTS = 60 }
}

/**
 * Slowly drifting aurora behind the whole page: soft glowing blobs moving on Lissajous
 * curves over a deep night gradient. Redraws at ~20 fps while attached to a view.
 */
class AuroraBackdrop(private val base: Int, private val colors: IntArray, private val glowAlpha: Int = 120) : Drawable(), Animatable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val basePaint = Paint()
    private val matrix = Matrix()
    private var shaders: List<RadialGradient> = emptyList()
    private var shaderRadius = 0f
    private var running = false
    private val tick = Runnable {
        invalidateSelf()
        if (running) scheduleNext()
    }

    private fun scheduleNext() {
        // Without a host view nothing would redraw us; stop so a later draw restarts the loop.
        if (callback == null) { running = false; return }
        scheduleSelf(tick, SystemClock.uptimeMillis() + 50L)
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        basePaint.color = base
        canvas.drawRect(bounds, basePaint)
        val radius = max(w, h) * 0.55f
        if (radius != shaderRadius) {
            shaderRadius = radius
            shaders = colors.map { color ->
                RadialGradient(0f, 0f, radius, intArrayOf(LivingIconView.withAlpha(color, glowAlpha), LivingIconView.withAlpha(color, 0)),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            }
        }
        val t = SystemClock.uptimeMillis() / 1000f
        shaders.forEachIndexed { i, shader ->
            val speed = 0.07f + i * 0.025f
            val x = bounds.left + w * (0.5f + 0.42f * sin(t * speed * 2f + i * 1.9f))
            val y = bounds.top + h * (0.5f + 0.38f * cos(t * speed * 3f + i * 2.7f))
            matrix.setTranslate(x, y)
            shader.setLocalMatrix(matrix)
            paint.shader = shader
            canvas.drawCircle(x, y, radius, paint)
        }
        if (!running) start()
    }

    override fun start() {
        if (running) return
        running = true
        scheduleNext()
    }

    override fun stop() {
        running = false
        unscheduleSelf(tick)
    }

    override fun isRunning(): Boolean = running

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) start() else stop()
        return changed
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
