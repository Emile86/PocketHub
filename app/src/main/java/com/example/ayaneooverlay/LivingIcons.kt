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
    /** When false the icon is drawn in a fixed, still pose and costs nothing between redraws.
     *  In the Blueprint and Cartridge themes only the fan icon can be animated. */
    var animated: Boolean = animated && (!stillIcons() || kind == "fan")
        set(value) {
            val v = value && (!stillIcons() || kind == "fan")
            if (field == v) return
            field = v
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
        if (bridgeStyle) {
            // Console button: a rounded block in the accent colour, a dark glyph and a
            // status light that blinks while the icon is active.
            blob.shader = null
            blob.color = glow
            rect.set(4f, 18f, 96f, 82f)
            canvas.drawRoundRect(rect, 30f, 30f, blob)
            blob.color = LivingIconView.withAlpha(Color.BLACK, 120)
            canvas.drawRect(70f, 18f, 73f, 82f, blob)
            val lit = !animated || ((t * 2.5f).toInt() % 2 == 0)
            soft.color = if (lit) Color.WHITE else LivingIconView.withAlpha(Color.BLACK, 140)
            canvas.drawCircle(84f, 50f, 5f, soft)
            savedTint = tint
            tint = Color.rgb(10, 10, 14)
            stroke.color = tint; fill.color = tint
            canvas.scale(0.52f, 0.52f, 36f, 50f)
        } else if (jadeStyle) {
            // Jade: a leaf-shaped tile; the glyph is a new duotone set (see jadeGlyph).
            savedTint = tint
            jadeTile(canvas)
            canvas.scale(0.6f, 0.6f, 50f, 50f)
        } else if (bauhausStyle) {
            // Bauhaus: a square tile with a quarter circle in the accent and a small red dot.
            savedTint = tint
            bauhausTile(canvas)
            canvas.scale(0.56f, 0.56f, 50f, 50f)
        } else if (hudStyle) {
            // Hologram: a hexagon outline with a projected fill and two bright corner marks.
            savedTint = tint
            hudHex(canvas)
            canvas.scale(0.56f, 0.56f, 50f, 50f)
        } else if (hifiStyle) {
            // Hi-Fi: a small dark display window (VFD) with scan lines; the glyph glows in it.
            savedTint = tint
            hifiWindow(canvas)
            canvas.scale(0.58f, 0.58f, 50f, 50f)
        } else if (abyssStyle) {
            // Deep Sea: a glass bubble lit from inside, with a light catch on its upper edge.
            savedTint = tint
            abyssBubble(canvas)
            canvas.scale(0.6f, 0.6f, 50f, 50f)
        } else if (cartStyle) {
            // Cartridge: a console-key tile with a pixel-art glyph.
            savedTint = tint
            cartTile(canvas)
            canvas.translate(0f, -4f)
            canvas.scale(0.56f, 0.56f, 50f, 50f)
        } else if (blueprintStyle) {
            // Blueprint: four corner marks, no background; the glyph is a line drawing.
            savedTint = tint
            bpFrame(canvas)
            canvas.scale(0.68f, 0.68f, 50f, 50f)
        } else if (chronoStyle) {
            // Chronograph: a watch dial with hour ticks and a running seconds dot.
            savedTint = tint
            chronoDial(canvas, t)
            canvas.scale(0.58f, 0.58f, 50f, 50f)
        } else if (neoStyle) {
            // Obsidian: a dark squircle tile with a travelling edge light; the glyph keeps the accent.
            savedTint = tint
            neoTile(canvas, t)
            canvas.scale(0.62f, 0.62f, 50f, 50f)
        } else if (popStyle) {
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
        if (jadeStyle && jadeGlyph(canvas, t)) {
            // drawn
        } else if (cartStyle && cartGlyph(canvas, t)) {
            // drawn
        } else if (blueprintStyle) {
            bpGlyph(canvas, t)
        } else if (chronoStyle) {
            chronoGlyph(canvas, t)
        } else if (neoStyle) {
            neoGlyph(canvas, t)
        } else if (bridgeStyle && kind in BRIDGE_PROFILE_KINDS) {
            bridgeProfile(canvas, t)
        } else when (kind) {
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
            "mixer" -> faders(canvas, t)
            else -> star(canvas, t)
        }
        canvas.restore()
        if (popStyle || bridgeStyle) { tint = savedTint; stroke.color = tint; fill.color = tint }
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






    // ---- Jade icon set -----------------------------------------------------------------
    // Duotone: a soft filled shape in the icon colour behind a crisp outline, rounded joints.
    // Drawn on a leaf-shaped tile. Still pictures; only the fan turns while it spins.

    private fun jadeTile(c: Canvas) {
        blob.shader = android.graphics.LinearGradient(0f, 6f, 0f, 94f, withAlpha(glow, 60), withAlpha(glow, 16), Shader.TileMode.CLAMP)
        path.reset()
        // Leaf: big radius top-left and bottom-right, small on the other two corners.
        val big = 34f; val small = 9f
        path.addRoundRect(RectF(6f, 6f, 94f, 94f), floatArrayOf(big, big, small, small, big, big, small, small), Path.Direction.CW)
        c.drawPath(path, blob)
        blob.shader = null
        stroke.strokeWidth = 2f
        stroke.color = withAlpha(glow, 120)
        c.drawPath(path, stroke)
        // A vein from corner to corner.
        stroke.strokeWidth = 1.6f
        stroke.color = withAlpha(glow, 50)
        c.drawLine(18f, 82f, 82f, 18f, stroke)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
    }

    private fun jadeGlyph(c: Canvas, t: Float): Boolean {
        stroke.strokeWidth = 5.5f
        stroke.color = tint
        stroke.pathEffect = null
        soft.color = withAlpha(tint, 75)
        fun duo(p: Path) { c.drawPath(p, soft); c.drawPath(p, stroke) }
        fun rr(l: Float, tp: Float, r: Float, b: Float, rad: Float = 8f): Path = Path().apply { addRoundRect(RectF(l, tp, r, b), rad, rad, Path.Direction.CW) }
        fun circle(x: Float, y: Float, r: Float): Path = Path().apply { addCircle(x, y, r, Path.Direction.CW) }
        when (kind) {
            "fan" -> {
                c.save(); c.rotate(if (animated) (t * 220f) % 360f else 0f, 50f, 50f)
                for (i in 0 until 3) {
                    c.save(); c.rotate(i * 120f, 50f, 50f)
                    val p = Path(); p.moveTo(50f, 50f); p.cubicTo(44f, 34f, 50f, 16f, 66f, 14f); p.cubicTo(72f, 28f, 64f, 44f, 50f, 50f); p.close()
                    duo(p)
                    c.restore()
                }
                c.restore()
                c.drawCircle(50f, 50f, 7f, fill.apply { color = tint })
            }
            "fps" -> {
                duo(rr(12f, 20f, 88f, 80f, 12f))
                path.reset(); path.moveTo(22f, 62f); path.lineTo(36f, 48f); path.lineTo(48f, 58f); path.lineTo(64f, 36f); path.lineTo(78f, 44f)
                c.drawPath(path, stroke)
            }
            "display", "landscape" -> {
                duo(rr(12f, 22f, 88f, 68f, 10f))
                c.drawLine(40f, 80f, 60f, 80f, stroke); c.drawLine(50f, 68f, 50f, 80f, stroke)
            }
            "cpu" -> {
                duo(rr(26f, 26f, 74f, 74f, 10f))
                for (k in listOf(38f, 50f, 62f)) {
                    c.drawLine(k, 14f, k, 26f, stroke); c.drawLine(k, 74f, k, 86f, stroke)
                    c.drawLine(14f, k, 26f, k, stroke); c.drawLine(74f, k, 86f, k, stroke)
                }
                c.drawCircle(50f, 50f, 7f, fill.apply { color = tint })
            }
            "gpu" -> {
                duo(rr(10f, 28f, 90f, 72f, 10f))
                duo(circle(38f, 50f, 12f))
                c.drawLine(64f, 42f, 80f, 42f, stroke); c.drawLine(64f, 58f, 80f, 58f, stroke)
            }
            "ram" -> {
                duo(rr(10f, 32f, 90f, 62f, 6f))
                for (i in 0..5) c.drawLine(18f + i * 13f, 62f, 18f + i * 13f, 72f, stroke)
            }
            "battery", "saver" -> {
                duo(rr(12f, 30f, 80f, 70f, 10f))
                c.drawLine(88f, 44f, 88f, 56f, stroke)
                fill.color = tint
                c.drawRoundRect(RectF(20f, 38f, 48f, 62f), 5f, 5f, fill)
            }
            "storage" -> {
                duo(rr(20f, 12f, 80f, 88f, 12f))
                c.drawLine(32f, 70f, 68f, 70f, stroke)
                c.drawCircle(50f, 40f, 10f, stroke)
            }
            "eco" -> {
                val p = Path(); p.moveTo(22f, 80f); p.cubicTo(18f, 42f, 46f, 20f, 82f, 18f); p.cubicTo(84f, 54f, 60f, 80f, 22f, 80f); p.close()
                duo(p)
                c.drawLine(22f, 80f, 62f, 40f, stroke)
            }
            "balanced" -> {
                duo(circle(50f, 50f, 34f))
                c.drawLine(28f, 50f, 72f, 50f, stroke)
                c.drawCircle(50f, 50f, 6f, fill.apply { color = tint })
            }
            "streaming" -> {
                duo(circle(50f, 50f, 11f))
                for (r in listOf(22f, 34f)) {
                    c.drawArc(RectF(50f - r, 50f - r, 50f + r, 50f + r), -45f, 90f, false, stroke)
                    c.drawArc(RectF(50f - r, 50f - r, 50f + r, 50f + r), 135f, 90f, false, stroke)
                }
            }
            "gaming", "controller" -> {
                duo(rr(10f, 30f, 90f, 72f, 20f))
                c.drawLine(24f, 51f, 40f, 51f, stroke); c.drawLine(32f, 43f, 32f, 59f, stroke)
                fill.color = tint
                c.drawCircle(64f, 46f, 4.5f, fill); c.drawCircle(74f, 56f, 4.5f, fill)
            }
            "max" -> {
                val p = Path(); p.moveTo(56f, 10f); p.lineTo(24f, 56f); p.lineTo(48f, 56f); p.lineTo(42f, 90f); p.lineTo(76f, 42f); p.lineTo(52f, 42f); p.close()
                duo(p)
            }
            "profiles" -> {
                duo(Path().apply { addArc(RectF(16f, 22f, 84f, 90f), 180f, 180f); close() })
                c.drawLine(50f, 56f, 66f, 38f, stroke)
                c.drawCircle(50f, 56f, 5f, fill.apply { color = tint })
            }
            "keyboard" -> {
                duo(rr(8f, 26f, 92f, 74f, 10f))
                fill.color = tint
                for (row in 0..1) for (col in 0..4) c.drawRoundRect(RectF(18f + col * 14f, 36f + row * 12f, 26f + col * 14f, 42f + row * 12f), 2f, 2f, fill)
                c.drawLine(32f, 64f, 68f, 64f, stroke)
            }
            "touchpad" -> {
                duo(rr(18f, 14f, 82f, 86f, 12f))
                c.drawLine(18f, 64f, 82f, 64f, stroke); c.drawLine(50f, 64f, 50f, 86f, stroke)
            }
            "pc" -> {
                duo(rr(10f, 16f, 90f, 64f, 10f))
                c.drawLine(40f, 80f, 60f, 80f, stroke); c.drawLine(50f, 64f, 50f, 80f, stroke)
                c.drawLine(24f, 32f, 32f, 40f, stroke); c.drawLine(32f, 40f, 24f, 48f, stroke); c.drawLine(38f, 48f, 50f, 48f, stroke)
            }
            "mixer" -> {
                for ((i, x) in listOf(28f, 50f, 72f).withIndex()) {
                    c.drawLine(x, 16f, x, 84f, stroke)
                    duo(circle(x, listOf(36f, 62f, 46f)[i], 8f))
                }
            }
            "sound" -> {
                val p = Path(); p.moveTo(16f, 40f); p.lineTo(32f, 40f); p.lineTo(50f, 24f); p.lineTo(50f, 76f); p.lineTo(32f, 60f); p.lineTo(16f, 60f); p.close()
                duo(p)
                for (r in listOf(14f, 26f)) c.drawArc(RectF(50f - r, 50f - r, 50f + r, 50f + r), -40f, 80f, false, stroke)
            }
            "gear" -> {
                for (i in 0 until 8) { c.save(); c.rotate(i * 45f, 50f, 50f); c.drawLine(50f, 14f, 50f, 24f, stroke); c.restore() }
                duo(circle(50f, 50f, 24f))
                c.drawCircle(50f, 50f, 8f, stroke)
            }
            "plus" -> { duo(circle(50f, 50f, 34f)); c.drawLine(50f, 34f, 50f, 66f, stroke); c.drawLine(34f, 50f, 66f, 50f, stroke) }
            "help" -> {
                duo(circle(50f, 50f, 34f))
                path.reset(); path.moveTo(41f, 42f); path.cubicTo(41f, 30f, 59f, 30f, 59f, 42f); path.cubicTo(59f, 50f, 50f, 50f, 50f, 58f)
                c.drawPath(path, stroke)
                c.drawCircle(50f, 68f, 3.5f, fill.apply { color = tint })
            }
            "back" -> {
                duo(circle(50f, 50f, 34f))
                path.reset(); path.moveTo(56f, 34f); path.lineTo(40f, 50f); path.lineTo(56f, 66f); c.drawPath(path, stroke)
            }
            "screens" -> { duo(rr(16f, 10f, 84f, 46f, 8f)); duo(rr(26f, 54f, 74f, 90f, 8f)) }
            "grid" -> { for (row in 0..1) for (col in 0..1) duo(rr(16f + col * 36f, 16f + row * 36f, 48f + col * 36f, 48f + row * 36f, 8f)) }
            "wifi" -> {
                duo(Path().apply { moveTo(50f, 80f); arcTo(RectF(14f, 26f, 86f, 98f), 225f, 90f); close() })
                c.drawArc(RectF(4f, 16f, 96f, 108f), 225f, 90f, false, stroke)
            }
            "bluetooth" -> {
                duo(circle(50f, 50f, 34f))
                path.reset(); path.moveTo(38f, 38f); path.lineTo(62f, 60f); path.lineTo(50f, 72f); path.lineTo(50f, 28f); path.lineTo(62f, 40f); path.lineTo(38f, 62f)
                c.drawPath(path, stroke)
            }
            "brightness" -> {
                duo(circle(50f, 50f, 15f))
                for (i in 0 until 8) { c.save(); c.rotate(i * 45f, 50f, 50f); c.drawLine(50f, 14f, 50f, 24f, stroke); c.restore() }
            }
            "dark", "dnd" -> {
                val p = Path(); p.addCircle(50f, 50f, 32f, Path.Direction.CW)
                val cut = Path(); cut.addCircle(66f, 40f, 26f, Path.Direction.CW)
                p.op(cut, Path.Op.DIFFERENCE)
                duo(p)
            }
            "rotate" -> {
                duo(rr(32f, 22f, 68f, 78f, 8f))
                c.drawArc(RectF(12f, 12f, 88f, 88f), 200f, 60f, false, stroke)
                c.drawArc(RectF(12f, 12f, 88f, 88f), 20f, 60f, false, stroke)
            }
            "airplane" -> {
                c.save(); c.rotate(-30f, 50f, 50f)
                val p = Path(); p.moveTo(50f, 12f); p.lineTo(56f, 40f); p.lineTo(86f, 56f); p.lineTo(56f, 54f); p.lineTo(54f, 76f); p.lineTo(64f, 86f)
                p.lineTo(36f, 86f); p.lineTo(46f, 76f); p.lineTo(44f, 54f); p.lineTo(14f, 56f); p.lineTo(44f, 40f); p.close()
                duo(p)
                c.restore()
            }
            "location" -> {
                val p = Path(); p.moveTo(50f, 88f); p.cubicTo(28f, 62f, 22f, 50f, 22f, 40f); p.cubicTo(22f, 22f, 36f, 12f, 50f, 12f); p.cubicTo(64f, 12f, 78f, 22f, 78f, 40f); p.cubicTo(78f, 50f, 72f, 62f, 50f, 88f); p.close()
                duo(p)
                c.drawCircle(50f, 40f, 9f, stroke)
            }
            "flashlight" -> {
                val p = Path(); p.moveTo(30f, 18f); p.lineTo(70f, 18f); p.lineTo(62f, 44f); p.lineTo(62f, 86f); p.lineTo(38f, 86f); p.lineTo(38f, 44f); p.close()
                duo(p)
                c.drawLine(50f, 56f, 50f, 66f, stroke)
            }
            else -> return false
        }
        return true
    }

    // ---- Bauhaus frame --------------------------------------------------------------------

    private fun bauhausTile(c: Canvas) {
        blob.shader = null
        blob.color = Color.rgb(34, 34, 33)
        c.drawRect(6f, 6f, 94f, 94f, blob)
        // Quarter circle from the lower left corner.
        blob.color = withAlpha(glow, 235)
        rect.set(6f - 50f, 94f - 50f, 6f + 50f, 94f + 50f)
        c.save(); c.clipRect(6f, 6f, 94f, 94f)
        c.drawArc(rect, 270f, 90f, true, blob)
        c.restore()
        // Red dot, upper right.
        blob.color = Color.rgb(230, 57, 45)
        c.drawCircle(80f, 20f, 7f, blob)
        stroke.strokeWidth = 6f
        stroke.color = tint
    }

    // ---- Hologram frame -------------------------------------------------------------------

    private fun hudHex(c: Canvas) {
        path.reset()
        for (i in 0 until 6) {
            val a = Math.toRadians((60.0 * i) - 90.0)
            val x = 50f + 45f * cos(a).toFloat(); val y = 50f + 45f * sin(a).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        blob.shader = android.graphics.LinearGradient(0f, 5f, 0f, 95f, withAlpha(glow, 70), withAlpha(glow, 12), Shader.TileMode.CLAMP)
        c.drawPath(path, blob)
        blob.shader = null
        stroke.strokeWidth = 2f
        stroke.color = withAlpha(glow, 140)
        c.drawPath(path, stroke)
        // Bright marks on the top and bottom vertices.
        stroke.strokeWidth = 4.5f
        stroke.color = glow
        c.drawLine(50f, 5f, 50f + 45f * cos(Math.toRadians(-30.0)).toFloat() * 0.45f, 5f + 22.5f * 0.45f, stroke)
        c.drawLine(50f, 5f, 50f - 45f * cos(Math.toRadians(-30.0)).toFloat() * 0.45f, 5f + 22.5f * 0.45f, stroke)
        c.drawLine(50f, 95f, 50f + 45f * cos(Math.toRadians(-30.0)).toFloat() * 0.45f, 95f - 22.5f * 0.45f, stroke)
        c.drawLine(50f, 95f, 50f - 45f * cos(Math.toRadians(-30.0)).toFloat() * 0.45f, 95f - 22.5f * 0.45f, stroke)
        stroke.strokeWidth = 6f
        stroke.color = tint
    }

    // ---- Hi-Fi frame ---------------------------------------------------------------------

    private fun hifiWindow(c: Canvas) {
        blob.shader = null
        // Bezel.
        blob.color = Color.rgb(58, 55, 50)
        rect.set(4f, 10f, 96f, 90f); c.drawRoundRect(rect, 9f, 9f, blob)
        // Glass.
        blob.shader = android.graphics.LinearGradient(0f, 14f, 0f, 86f, Color.rgb(16, 15, 14), Color.rgb(6, 6, 6), Shader.TileMode.CLAMP)
        rect.set(9f, 15f, 91f, 85f); c.drawRoundRect(rect, 6f, 6f, blob)
        blob.shader = android.graphics.RadialGradient(50f, 50f, 42f, withAlpha(glow, 60), withAlpha(glow, 0), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, 6f, 6f, blob)
        blob.shader = null
        // Scan lines.
        blob.color = withAlpha(Color.BLACK, 70)
        var y = 17f
        while (y < 84f) { c.drawRect(10f, y, 90f, y + 1f, blob); y += 3.2f }
        // Glass reflection.
        blob.color = withAlpha(Color.WHITE, 16)
        path.reset(); path.moveTo(11f, 17f); path.lineTo(60f, 17f); path.lineTo(40f, 40f); path.lineTo(11f, 40f); path.close()
        c.drawPath(path, blob)
        stroke.strokeWidth = 6f
        stroke.color = tint
    }

    // ---- Deep Sea frame ------------------------------------------------------------------

    private fun abyssBubble(c: Canvas) {
        blob.shader = android.graphics.RadialGradient(42f, 38f, 52f,
            intArrayOf(withAlpha(glow, 95), withAlpha(glow, 38), withAlpha(glow, 10)),
            floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(50f, 50f, 45f, blob)
        blob.shader = null
        stroke.strokeWidth = 2.2f
        stroke.color = withAlpha(glow, 150)
        c.drawCircle(50f, 50f, 45f, stroke)
        // Light catch on the upper left, like a bubble.
        stroke.strokeWidth = 3f
        stroke.color = withAlpha(Color.WHITE, 70)
        rect.set(14f, 14f, 86f, 86f)
        c.drawArc(rect, 200f, 55f, false, stroke)
        stroke.strokeWidth = 6f
        stroke.color = tint
    }

    // ---- Cartridge icon set ---------------------------------------------------------
    // Modern pixel art: a 12×12 grid of softly rounded pixels on a dark console-key tile.
    // Still pictures; only the fan blades turn (and only while the fan spins).

    private val pix = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun cartTile(c: Canvas) {
        // Console key: darker lip underneath, face on top.
        blob.shader = null
        blob.color = mix(mix(glow, Color.BLACK, 0.82f), Color.BLACK, 0.35f)
        rect.set(6f, 12f, 94f, 96f); c.drawRoundRect(rect, 24f, 24f, blob)
        blob.color = mix(glow, Color.rgb(30, 30, 36), 0.86f)
        rect.set(6f, 6f, 94f, 88f); c.drawRoundRect(rect, 24f, 24f, blob)
        blob.color = withAlpha(Color.WHITE, 16)
        rect.set(18f, 10f, 82f, 13f); c.drawRoundRect(rect, 2f, 2f, blob)
    }

    private fun pixelArt(c: Canvas, rows: Array<String>) {
        val cell = 100f / 12f
        val gap = 0.8f
        val alt = mix(tint, Color.WHITE, 0.5f)
        for (y in rows.indices) {
            val row = rows[y]
            for (x in row.indices) {
                val ch = row[x]
                if (ch == '.') continue
                pix.color = if (ch == '#') tint else alt
                rect.set(x * cell + gap, y * cell + gap, (x + 1) * cell - gap, (y + 1) * cell - gap)
                c.drawRoundRect(rect, 1.8f, 1.8f, pix)
            }
        }
    }

    private fun cartKey(): String? = when (kind) {
        "controller" -> "gaming"
        "dnd" -> "dark"
        else -> if (CART_PIXELS.containsKey(kind)) kind else null
    }

    /** Draws the pixel glyph; false when this kind has no pixel picture (the default glyph is used). */
    private fun cartGlyph(c: Canvas, t: Float): Boolean {
        val key = cartKey() ?: return false
        if (key == "fan") {
            pixelArt(c, CART_PIXELS.getValue("fanring"))
            c.save()
            c.rotate(if (animated) (t * 240f) % 360f else 0f, 50f, 50f)
            c.scale(0.7f, 0.7f, 50f, 50f)
            pixelArt(c, CART_PIXELS.getValue("fan"))
            c.restore()
        } else {
            pixelArt(c, CART_PIXELS.getValue(key))
        }
        return true
    }

    // ---- Blueprint icon set ----------------------------------------------------------
    // Technical-drawing style for the Blueprint theme: thin even lines, centre marks, dashed
    // construction lines and small dimension ticks, framed by four corner marks. Still pictures:
    // only the fan turns (and only while it spins).

    private val bpDash = android.graphics.DashPathEffect(floatArrayOf(5f, 4f), 0f)
    private val bpThin = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    private fun bpFrame(c: Canvas) {
        bpThin.pathEffect = null
        bpThin.strokeWidth = 2.4f
        bpThin.color = withAlpha(glow, 150)
        val k = 13f
        c.drawLine(8f, 8f, 8f + k, 8f, bpThin); c.drawLine(8f, 8f, 8f, 8f + k, bpThin)
        c.drawLine(92f, 8f, 92f - k, 8f, bpThin); c.drawLine(92f, 8f, 92f, 8f + k, bpThin)
        c.drawLine(8f, 92f, 8f + k, 92f, bpThin); c.drawLine(8f, 92f, 8f, 92f - k, bpThin)
        c.drawLine(92f, 92f, 92f - k, 92f, bpThin); c.drawLine(92f, 92f, 92f, 92f - k, bpThin)
    }

    /** Small centre mark (a cross), as on drawings. */
    private fun bpCross(c: Canvas, x: Float, y: Float, s: Float = 5f) {
        bpThin.pathEffect = null
        bpThin.strokeWidth = 1.8f
        bpThin.color = withAlpha(tint, 200)
        c.drawLine(x - s, y, x + s, y, bpThin); c.drawLine(x, y - s, x, y + s, bpThin)
    }

    /** Dashed construction line. */
    private fun bpDashed(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        bpThin.pathEffect = bpDash
        bpThin.strokeWidth = 1.6f
        bpThin.color = withAlpha(tint, 150)
        c.drawLine(x1, y1, x2, y2, bpThin)
        bpThin.pathEffect = null
    }

    /** Dimension line with end ticks. */
    private fun bpDim(c: Canvas, x1: Float, y: Float, x2: Float) {
        bpThin.pathEffect = null
        bpThin.strokeWidth = 1.6f
        bpThin.color = withAlpha(tint, 170)
        c.drawLine(x1, y, x2, y, bpThin)
        c.drawLine(x1, y - 4f, x1, y + 4f, bpThin); c.drawLine(x2, y - 4f, x2, y + 4f, bpThin)
    }

    private fun bpGlyph(c: Canvas, t: Float) {
        stroke.strokeWidth = 3.6f
        stroke.pathEffect = null
        fun box(l: Float, tp: Float, r: Float, b: Float, rad: Float = 2f) { rect.set(l, tp, r, b); c.drawRoundRect(rect, rad, rad, stroke) }
        fun arc(l: Float, tp: Float, r: Float, b: Float, start: Float, sweep: Float) { rect.set(l, tp, r, b); c.drawArc(rect, start, sweep, false, stroke) }
        when (kind) {
            "fan" -> {
                c.drawCircle(50f, 50f, 34f, stroke)
                bpDashed(c, 50f, 10f, 50f, 90f); bpDashed(c, 10f, 50f, 90f, 50f)
                c.save(); c.rotate(if (animated) (t * 200f) % 360f else 15f, 50f, 50f)
                for (i in 0 until 4) {
                    c.save(); c.rotate(i * 90f, 50f, 50f)
                    path.reset(); path.moveTo(50f, 42f); path.cubicTo(60f, 36f, 64f, 26f, 56f, 18f); path.cubicTo(50f, 24f, 46f, 34f, 50f, 42f)
                    c.drawPath(path, stroke)
                    c.restore()
                }
                c.restore()
                c.drawCircle(50f, 50f, 7f, stroke)
            }
            "fps" -> {
                // A frame-time trace with a sample grid.
                box(14f, 22f, 86f, 78f)
                bpDashed(c, 14f, 50f, 86f, 50f)
                path.reset(); path.moveTo(18f, 62f); path.lineTo(32f, 62f); path.lineTo(40f, 36f); path.lineTo(50f, 66f); path.lineTo(60f, 42f); path.lineTo(70f, 56f); path.lineTo(82f, 56f)
                c.drawPath(path, stroke)
                bpDim(c, 14f, 86f, 86f)
            }
            "display" -> {
                box(14f, 24f, 86f, 66f)
                bpCross(c, 50f, 45f)
                c.drawLine(42f, 66f, 40f, 78f, stroke); c.drawLine(58f, 66f, 60f, 78f, stroke); c.drawLine(32f, 78f, 68f, 78f, stroke)
                bpDim(c, 14f, 16f, 86f)
            }
            "cpu" -> {
                box(28f, 28f, 72f, 72f)
                for (k in listOf(38f, 50f, 62f)) {
                    c.drawLine(k, 16f, k, 28f, stroke); c.drawLine(k, 72f, k, 84f, stroke)
                    c.drawLine(16f, k, 28f, k, stroke); c.drawLine(72f, k, 84f, k, stroke)
                }
                bpDashed(c, 28f, 28f, 72f, 72f); bpDashed(c, 72f, 28f, 28f, 72f)
                bpCross(c, 50f, 50f, 4f)
            }
            "gpu" -> {
                box(12f, 30f, 88f, 70f)
                c.drawCircle(38f, 50f, 13f, stroke)
                bpCross(c, 38f, 50f, 6f)
                for (x in listOf(62f, 70f, 78f)) c.drawLine(x, 40f, x, 60f, stroke)
                c.drawLine(20f, 70f, 20f, 80f, stroke); c.drawLine(80f, 70f, 80f, 80f, stroke)
            }
            "ram" -> {
                box(12f, 34f, 88f, 62f)
                for (i in 0..3) box(19f + i * 17f, 40f, 30f + i * 17f, 56f, 1f)
                for (i in 0..7) c.drawLine(18f + i * 9f, 62f, 18f + i * 9f, 70f, stroke)
                bpDim(c, 12f, 80f, 88f)
            }
            "battery" -> {
                box(14f, 32f, 80f, 68f, 4f)
                c.drawLine(86f, 44f, 86f, 56f, stroke)
                bpDashed(c, 36f, 32f, 36f, 68f); bpDashed(c, 58f, 32f, 58f, 68f)
                soft.color = withAlpha(tint, 120)
                c.drawRect(18f, 36f, 36f, 64f, soft)
            }
            "storage" -> {
                c.drawCircle(50f, 50f, 32f, stroke)
                c.drawCircle(50f, 50f, 14f, stroke)
                bpCross(c, 50f, 50f)
                c.drawLine(78f, 22f, 60f, 40f, stroke)
                bpDashed(c, 50f, 18f, 50f, 8f)
            }
            "eco" -> {
                path.reset(); path.moveTo(24f, 76f); path.cubicTo(24f, 40f, 48f, 22f, 76f, 24f); path.cubicTo(78f, 52f, 60f, 76f, 24f, 76f)
                c.drawPath(path, stroke)
                bpDashed(c, 24f, 76f, 76f, 24f)
            }
            "balanced" -> {
                c.drawLine(50f, 22f, 50f, 78f, stroke)
                c.drawLine(22f, 34f, 78f, 34f, stroke)
                path.reset(); path.moveTo(14f, 58f); path.lineTo(22f, 34f); path.lineTo(30f, 58f); path.close(); c.drawPath(path, stroke)
                path.reset(); path.moveTo(70f, 58f); path.lineTo(78f, 34f); path.lineTo(86f, 58f); path.close(); c.drawPath(path, stroke)
                c.drawLine(38f, 78f, 62f, 78f, stroke)
            }
            "streaming" -> {
                c.drawCircle(50f, 50f, 6f, stroke)
                for (r in listOf(18f, 30f)) { arc(50f - r, 50f - r, 50f + r, 50f + r, -40f, 80f); arc(50f - r, 50f - r, 50f + r, 50f + r, 140f, 80f) }
                bpDashed(c, 50f, 14f, 50f, 86f)
            }
            "gaming", "controller" -> {
                box(12f, 30f, 88f, 70f, 16f)
                c.drawLine(24f, 50f, 40f, 50f, stroke); c.drawLine(32f, 42f, 32f, 58f, stroke)
                for ((x, y) in listOf(66f to 42f, 74f to 50f, 66f to 58f, 58f to 50f)) c.drawCircle(x, y, 3.4f, stroke)
                bpDashed(c, 50f, 30f, 50f, 70f)
            }
            "max" -> {
                path.reset(); path.moveTo(30f, 50f); path.lineTo(50f, 30f); path.lineTo(70f, 50f); c.drawPath(path, stroke)
                path.reset(); path.moveTo(30f, 68f); path.lineTo(50f, 48f); path.lineTo(70f, 68f); c.drawPath(path, stroke)
                bpDim(c, 20f, 82f, 80f)
                bpDashed(c, 50f, 16f, 50f, 30f)
            }
            "keyboard" -> {
                box(10f, 28f, 90f, 72f)
                for (row in 0..1) for (col in 0..5) box(18f + col * 11.3f, 36f + row * 11f, 26f + col * 11.3f, 43f + row * 11f, 1f)
                c.drawLine(30f, 63f, 70f, 63f, stroke)
            }
            "touchpad" -> {
                box(20f, 16f, 80f, 84f, 4f)
                c.drawLine(20f, 66f, 80f, 66f, stroke); c.drawLine(50f, 66f, 50f, 84f, stroke)
                bpCross(c, 50f, 41f, 7f)
                bpDashed(c, 20f, 41f, 80f, 41f)
            }
            "profiles" -> {
                arc(18f, 26f, 82f, 90f, 180f, 180f)
                for (i in 0..4) {
                    val a = Math.toRadians(180.0 + i * 45.0)
                    c.drawLine(50f + 24f * cos(a).toFloat(), 58f + 24f * sin(a).toFloat(), 50f + 32f * cos(a).toFloat(), 58f + 32f * sin(a).toFloat(), stroke)
                }
                c.drawLine(50f, 58f, 66f, 40f, stroke)
                bpCross(c, 50f, 58f)
            }
            "pc" -> {
                box(12f, 18f, 88f, 62f)
                c.drawLine(42f, 62f, 40f, 76f, stroke); c.drawLine(58f, 62f, 60f, 76f, stroke); c.drawLine(30f, 76f, 70f, 76f, stroke)
                c.drawLine(24f, 32f, 32f, 40f, stroke); c.drawLine(32f, 40f, 24f, 48f, stroke); c.drawLine(38f, 48f, 50f, 48f, stroke)
            }
            "mixer" -> {
                for ((i, x) in listOf(28f, 50f, 72f).withIndex()) {
                    bpDashed(c, x, 16f, x, 84f)
                    val y = listOf(36f, 62f, 46f)[i]
                    box(x - 8f, y - 5f, x + 8f, y + 5f, 1f)
                }
            }
            "wifi" -> {
                for (i in 0..2) { val r = 14f + i * 13f; arc(50f - r, 72f - r, 50f + r, 72f + r, 222f, 96f) }
                bpCross(c, 50f, 72f)
            }
            "bluetooth" -> {
                path.reset(); path.moveTo(32f, 34f); path.lineTo(66f, 64f); path.lineTo(50f, 80f); path.lineTo(50f, 20f); path.lineTo(66f, 36f); path.lineTo(32f, 66f)
                c.drawPath(path, stroke)
                bpDashed(c, 50f, 12f, 50f, 88f)
            }
            "rotate" -> {
                arc(22f, 22f, 78f, 78f, 200f, 140f); arc(22f, 22f, 78f, 78f, 20f, 140f)
                c.drawLine(78f, 50f, 72f, 42f, stroke); c.drawLine(22f, 50f, 28f, 58f, stroke)
                bpCross(c, 50f, 50f)
            }
            "airplane" -> {
                c.save(); c.rotate(-30f, 50f, 50f)
                c.drawLine(50f, 16f, 50f, 84f, stroke); c.drawLine(18f, 52f, 82f, 52f, stroke); c.drawLine(36f, 78f, 64f, 78f, stroke)
                c.restore()
            }
            "location" -> {
                c.drawCircle(50f, 40f, 18f, stroke)
                c.drawLine(50f, 58f, 50f, 82f, stroke)
                bpCross(c, 50f, 40f)
                bpDashed(c, 26f, 82f, 74f, 82f)
            }
            "dnd" -> { arc(20f, 20f, 80f, 80f, 70f, 260f); arc(38f, 20f, 82f, 64f, 110f, 160f) }
            "saver" -> {
                box(16f, 34f, 76f, 66f, 4f); c.drawLine(83f, 44f, 83f, 56f, stroke)
                c.drawLine(46f, 42f, 46f, 58f, stroke); c.drawLine(38f, 50f, 54f, 50f, stroke)
            }
            "dark" -> {
                c.drawCircle(50f, 50f, 28f, stroke)
                rect.set(22f, 22f, 78f, 78f); c.drawArc(rect, 90f, 180f, true, fill)
                bpDashed(c, 50f, 14f, 50f, 86f)
            }
            "brightness" -> {
                c.drawCircle(50f, 50f, 13f, stroke)
                for (i in 0 until 8) { c.save(); c.rotate(i * 45f, 50f, 50f); c.drawLine(50f, 18f, 50f, 28f, stroke); c.restore() }
                bpCross(c, 50f, 50f, 4f)
            }
            "flashlight" -> {
                box(40f, 48f, 60f, 84f); c.drawLine(34f, 42f, 66f, 42f, stroke)
                bpDashed(c, 50f, 34f, 50f, 14f); bpDashed(c, 40f, 34f, 30f, 18f); bpDashed(c, 60f, 34f, 70f, 18f)
            }
            "landscape" -> { box(14f, 32f, 86f, 68f, 4f); bpCross(c, 50f, 50f); bpDim(c, 14f, 80f, 86f) }
            "gear" -> {
                c.drawCircle(50f, 50f, 20f, stroke)
                for (i in 0 until 8) { c.save(); c.rotate(i * 45f, 50f, 50f); c.drawLine(50f, 18f, 50f, 30f, stroke); c.restore() }
                bpCross(c, 50f, 50f)
            }
            "plus" -> { c.drawLine(50f, 24f, 50f, 76f, stroke); c.drawLine(24f, 50f, 76f, 50f, stroke) }
            "help" -> {
                path.reset(); path.moveTo(38f, 38f); path.cubicTo(38f, 24f, 62f, 24f, 62f, 38f); path.cubicTo(62f, 50f, 50f, 50f, 50f, 62f)
                c.drawPath(path, stroke)
                c.drawCircle(50f, 74f, 2.6f, fill)
            }
            "back" -> {
                path.reset(); path.moveTo(58f, 24f); path.lineTo(32f, 50f); path.lineTo(58f, 76f); c.drawPath(path, stroke)
                bpDashed(c, 32f, 50f, 82f, 50f)
            }
            "screens" -> { box(18f, 14f, 82f, 46f); box(28f, 54f, 72f, 86f); bpDashed(c, 50f, 46f, 50f, 54f) }
            "grid" -> { for (row in 0..1) for (col in 0..1) box(18f + col * 34f, 18f + row * 34f, 48f + col * 34f, 48f + row * 34f) }
            "sound" -> {
                path.reset(); path.moveTo(18f, 42f); path.lineTo(30f, 42f); path.lineTo(46f, 28f); path.lineTo(46f, 72f); path.lineTo(30f, 58f); path.lineTo(18f, 58f); path.close()
                c.drawPath(path, stroke)
                for (r in listOf(14f, 26f)) arc(46f - r, 50f - r, 46f + r, 50f + r, -40f, 80f)
            }
            else -> {
                box(26f, 26f, 74f, 74f)
                bpDashed(c, 26f, 26f, 74f, 74f); bpDashed(c, 74f, 26f, 26f, 74f)
                bpCross(c, 50f, 50f)
            }
        }
    }

    // ---- Chronograph icon set --------------------------------------------------------
    // Watch-complication style for the Chronograph theme: every glyph sits in a thin ring with
    // hour ticks and a "seconds" dot that runs round it; fine gold strokes, a small white
    // accent like lume. The fan stays a spinning rotor.

    private val chronoAccent = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val chronoLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    private fun chronoDial(c: Canvas, t: Float) {
        blob.shader = android.graphics.RadialGradient(50f, 42f, 50f,
            withAlpha(glow, 40), withAlpha(glow, 0), Shader.TileMode.CLAMP)
        c.drawCircle(50f, 50f, 46f, blob)
        blob.shader = null
        chronoLine.color = withAlpha(glow, 170)
        chronoLine.strokeWidth = 2f
        c.drawCircle(50f, 50f, 46f, chronoLine)
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0)
            val major = i % 3 == 0
            val r1 = if (major) 37f else 40.5f
            chronoLine.strokeWidth = if (major) 2.2f else 1.4f
            chronoLine.color = withAlpha(glow, if (major) 190 else 110)
            c.drawLine(50f + r1 * sin(a).toFloat(), 50f - r1 * cos(a).toFloat(),
                50f + 44f * sin(a).toFloat(), 50f - 44f * cos(a).toFloat(), chronoLine)
        }
        if (animated) {
            val a = Math.toRadians(((t * 60.0) % 360.0))
            chronoAccent.alpha = 235
            c.drawCircle(50f + 46f * sin(a).toFloat(), 50f - 46f * cos(a).toFloat(), 3f, chronoAccent)
        }
    }

    private fun lumeDot(c: Canvas, x: Float, y: Float, r: Float, alpha: Int = 255) {
        chronoAccent.alpha = alpha.coerceIn(0, 255)
        c.drawCircle(x, y, r, chronoAccent)
    }

    private fun chronoGlyph(c: Canvas, t: Float) {
        stroke.strokeWidth = 4.5f
        val p = 0.5f + 0.5f * sin(t * 2f)
        fun arc(l: Float, tp: Float, r: Float, b: Float, start: Float, sweep: Float) { rect.set(l, tp, r, b); c.drawArc(rect, start, sweep, false, stroke) }
        fun lume(x: Float, y: Float, r: Float, alpha: Int = 255) = lumeDot(c, x, y, r, alpha)
        fun hand(cx: Float, cy: Float, deg: Double, len: Float) {
            val a = Math.toRadians(deg)
            c.drawLine(cx, cy, cx + len * cos(a).toFloat(), cy + len * sin(a).toFloat(), stroke)
        }
        when (kind) {
            "fps" -> {
                arc(16f, 18f, 84f, 86f, 150f, 240f)
                for (i in 0..8) {
                    val a = Math.toRadians(150.0 + i * 30.0)
                    c.drawLine(50f + 26f * cos(a).toFloat(), 52f + 26f * sin(a).toFloat(), 50f + 31f * cos(a).toFloat(), 52f + 31f * sin(a).toFloat(), stroke)
                }
                hand(50f, 52f, 200.0 + 120.0 * (if (animated) 0.5 + 0.5 * sin(t * 1.1) else 0.6), 24f)
                lume(50f, 52f, 4.5f)
            }
            "fan" -> {
                c.save(); c.rotate(if (animated) (t * 240f) % 360f else 18f, 50f, 50f)
                for (i in 0 until 5) {
                    c.save(); c.rotate(i * 72f, 50f, 50f)
                    path.reset(); path.moveTo(50f, 42f); path.quadTo(66f, 34f, 62f, 16f)
                    c.drawPath(path, stroke)
                    c.restore()
                }
                c.restore()
                c.drawCircle(50f, 50f, 9f, stroke)
                lume(50f, 50f, 3.5f)
            }
            "display" -> {
                rect.set(16f, 26f, 84f, 70f); c.drawRoundRect(rect, 7f, 7f, stroke)
                stroke.strokeWidth = 2f; rect.set(22f, 32f, 78f, 64f); c.drawRoundRect(rect, 3f, 3f, stroke); stroke.strokeWidth = 4.5f
                c.drawLine(44f, 20f, 56f, 20f, stroke)
                lume(72f, 38f, 2.6f, (120 + 135 * p).toInt())
            }
            "cpu" -> {
                rect.set(30f, 30f, 70f, 70f); c.drawRoundRect(rect, 5f, 5f, stroke)
                for (k in listOf(40f, 50f, 60f)) {
                    c.drawLine(k, 18f, k, 24f, stroke); c.drawLine(k, 76f, k, 82f, stroke)
                    c.drawLine(18f, k, 24f, k, stroke); c.drawLine(76f, k, 82f, k, stroke)
                }
                c.save(); c.rotate(if (animated) (t * 45f) % 360f else 45f, 50f, 50f)
                path.reset(); path.moveTo(50f, 41f); path.lineTo(59f, 50f); path.lineTo(50f, 59f); path.lineTo(41f, 50f); path.close()
                c.drawPath(path, fill)
                c.restore()
                lume(50f, 50f, 2.4f)
            }
            "gpu" -> {
                rect.set(12f, 32f, 88f, 70f); c.drawRoundRect(rect, 7f, 7f, stroke)
                c.drawCircle(38f, 51f, 12f, stroke)
                c.save(); c.rotate(if (animated) (t * 160f) % 360f else 0f, 38f, 51f)
                stroke.strokeWidth = 2.4f
                for (i in 0 until 6) { c.save(); c.rotate(i * 60f, 38f, 51f); c.drawLine(38f, 51f, 38f, 41f, stroke); c.restore() }
                c.restore(); stroke.strokeWidth = 4.5f
                for (x in listOf(62f, 70f, 78f)) c.drawLine(x, 44f, x, 58f, stroke)
            }
            "ram" -> {
                listOf(26f, 42f, 58f, 74f).forEachIndexed { i, x ->
                    val h = 22f + 14f * (if (animated) (0.5f + 0.5f * sin(t * 2f + i)) else (i % 2) * 0.6f + 0.2f)
                    c.drawLine(x, 76f, x, 76f - h, stroke)
                }
                c.drawLine(18f, 82f, 82f, 82f, stroke)
            }
            "battery" -> {
                rect.set(36f, 22f, 64f, 82f); c.drawRoundRect(rect, 9f, 9f, stroke)
                c.drawLine(45f, 16f, 55f, 16f, stroke)
                val level = if (animated) 0.3f + 0.6f * (0.5f + 0.5f * sin(t * 0.8f)) else 0.7f
                soft.color = withAlpha(tint, 190)
                c.drawRoundRect(RectF(42f, 76f - 48f * level, 58f, 76f), 4f, 4f, soft)
                lume(50f, 76f - 48f * level, 2.4f)
            }
            "storage" -> {
                c.drawCircle(50f, 50f, 30f, stroke)
                stroke.strokeWidth = 2f; c.drawCircle(50f, 50f, 17f, stroke); stroke.strokeWidth = 4.5f
                lume(50f, 50f, 4f)
                c.save(); c.rotate(if (animated) 10f * sin(t * 1.5f) else 0f, 80f, 20f)
                c.drawLine(80f, 20f, 60f, 40f, stroke)
                c.restore()
            }
            "eco" -> {
                path.reset(); path.moveTo(26f, 74f); path.cubicTo(26f, 38f, 50f, 22f, 76f, 24f); path.cubicTo(78f, 52f, 58f, 76f, 26f, 74f)
                c.drawPath(path, stroke)
                stroke.strokeWidth = 2.4f
                c.drawLine(26f, 74f, 62f, 38f, stroke)
                c.drawLine(42f, 58f, 42f, 44f, stroke); c.drawLine(52f, 48f, 60f, 50f, stroke)
                stroke.strokeWidth = 4.5f
                lume(76f, 24f, 3f, (120 + 135 * p).toInt())
            }
            "balanced" -> {
                path.reset(); path.moveTo(42f, 80f); path.lineTo(58f, 80f); path.lineTo(50f, 58f); path.close(); c.drawPath(path, stroke)
                val tilt = if (animated) 7f * sin(t * 1.2f) else 0f
                c.drawLine(18f, 52f + tilt, 82f, 52f - tilt, stroke)
                arc(12f, 52f + tilt, 30f, 70f + tilt, 0f, 180f)
                arc(70f, 52f - tilt, 88f, 70f - tilt, 0f, 180f)
                lume(50f, 52f, 3.2f)
            }
            "streaming" -> {
                lume(50f, 50f, 5f)
                for (i in 1..3) {
                    val r = 8f + i * 10f
                    stroke.alpha = ((0.5f + 0.5f * sin(t * 3f - i)) * 200 + 55).toInt()
                    arc(50f - r, 50f - r, 50f + r, 50f + r, -35f, 70f)
                    arc(50f - r, 50f - r, 50f + r, 50f + r, 145f, 70f)
                }
                stroke.alpha = 255
            }
            "gaming", "controller" -> {
                rect.set(12f, 32f, 88f, 70f); c.drawRoundRect(rect, 19f, 19f, stroke)
                stroke.strokeWidth = 3.4f
                c.drawLine(26f, 51f, 40f, 51f, stroke); c.drawLine(33f, 44f, 33f, 58f, stroke)
                stroke.strokeWidth = 4.5f
                val lit = if (animated) ((t * 2f).toInt() % 4) else 0
                listOf(66f to 44f, 73f to 51f, 66f to 58f, 59f to 51f).forEachIndexed { i, (x, y) ->
                    if (i == lit) lume(x, y, 3.4f) else c.drawCircle(x, y, 3f, fill)
                }
            }
            "max" -> {
                val off = if (animated) 4f * sin(t * 4f) else 0f
                path.reset(); path.moveTo(32f, 46f + off); path.lineTo(50f, 28f + off); path.lineTo(68f, 46f + off); c.drawPath(path, stroke)
                path.reset(); path.moveTo(32f, 64f + off); path.lineTo(50f, 46f + off); path.lineTo(68f, 64f + off); c.drawPath(path, stroke)
                stroke.strokeWidth = 2.4f
                c.drawLine(40f, 76f, 60f, 76f, stroke); c.drawLine(44f, 82f, 56f, 82f, stroke)
                stroke.strokeWidth = 4.5f
                lume(50f, 28f + off, 3f)
            }
            "keyboard" -> {
                rect.set(12f, 30f, 88f, 70f); c.drawRoundRect(rect, 7f, 7f, stroke)
                stroke.strokeWidth = 3f
                val lit = if (animated) (t * 3f).toInt() % 6 else 2
                for (row in 0..1) for (col in 0..5) {
                    val x = 22f + col * 11.2f; val y = 41f + row * 9f
                    if (row == 0 && col == lit) lume(x + 2.5f, y, 2.4f) else c.drawLine(x, y, x + 5f, y, stroke)
                }
                c.drawLine(32f, 60f, 68f, 60f, stroke)
                stroke.strokeWidth = 4.5f
            }
            "touchpad" -> {
                rect.set(22f, 18f, 78f, 82f); c.drawRoundRect(rect, 11f, 11f, stroke)
                stroke.strokeWidth = 2.2f
                for (r in listOf(7f, 13f, 19f)) arc(50f - r, 44f - r, 50f + r, 44f + r, 200f, 140f)
                c.drawLine(22f, 68f, 78f, 68f, stroke)
                stroke.strokeWidth = 4.5f
                lume(50f + 10f * sin(t * 1.3f), 44f + 6f * sin(t * 2.6f), 3.6f)
            }
            "profiles" -> {
                arc(18f, 26f, 82f, 90f, 180f, 180f)
                for (i in 0..4) {
                    val a = Math.toRadians(180.0 + i * 45.0)
                    c.drawLine(50f + 24f * cos(a).toFloat(), 58f + 24f * sin(a).toFloat(), 50f + 30f * cos(a).toFloat(), 58f + 30f * sin(a).toFloat(), stroke)
                }
                hand(50f, 58f, 200.0 + 140.0 * (if (animated) 0.5 + 0.5 * sin(t * 0.9) else 0.7), 22f)
                lume(50f, 58f, 4f)
            }
            "pc" -> {
                rect.set(14f, 20f, 86f, 62f); c.drawRoundRect(rect, 6f, 6f, stroke)
                path.reset(); path.moveTo(42f, 62f); path.lineTo(38f, 78f); path.lineTo(62f, 78f); path.lineTo(58f, 62f); c.drawPath(path, stroke)
                stroke.strokeWidth = 3f
                c.drawLine(26f, 34f, 34f, 41f, stroke); c.drawLine(34f, 41f, 26f, 48f, stroke)
                stroke.strokeWidth = 4.5f
                if (!animated || (t * 2f).toInt() % 2 == 0) c.drawLine(40f, 48f, 50f, 48f, stroke)
            }
            "mixer" -> {
                listOf(30f, 50f, 70f).forEachIndexed { i, y ->
                    c.drawLine(18f, y, 82f, y, stroke)
                    val x = 50f + 22f * sin(t * (0.9f + i * 0.4f) + i * 2f)
                    c.drawCircle(x, y, 6f, fill)
                    lume(x, y, 2.2f)
                }
            }
            "wifi" -> {
                for (i in 0..2) {
                    val r = 14f + i * 13f
                    stroke.alpha = if (!animated || (t * 2f).toInt() % 4 > i) 255 else 70
                    arc(50f - r, 72f - r, 50f + r, 72f + r, 222f, 96f)
                }
                stroke.alpha = 255
                lume(50f, 72f, 4.5f)
            }
            "bluetooth" -> {
                path.reset(); path.moveTo(32f, 34f); path.lineTo(66f, 64f); path.lineTo(50f, 80f); path.lineTo(50f, 20f); path.lineTo(66f, 36f); path.lineTo(32f, 66f)
                c.drawPath(path, stroke)
                lume(76f, 50f, 3f, (60 + 195 * p).toInt())
            }
            "rotate" -> {
                c.save(); c.rotate(if (animated) (t * 50f) % 360f else 0f, 50f, 50f)
                arc(22f, 22f, 78f, 78f, 200f, 140f); arc(22f, 22f, 78f, 78f, 20f, 140f)
                c.drawLine(78f, 50f, 72f, 42f, stroke); c.drawLine(22f, 50f, 28f, 58f, stroke)
                c.restore()
                lume(50f, 50f, 3.4f)
            }
            "airplane" -> {
                c.save(); c.rotate(-30f, 50f, 50f); c.translate(0f, if (animated) 3f * sin(t * 1.4f) else 0f)
                c.drawLine(50f, 18f, 50f, 82f, stroke)
                c.drawLine(20f, 52f, 80f, 52f, stroke)
                c.drawLine(38f, 78f, 62f, 78f, stroke)
                c.restore()
                lume(78f, 24f, 2.6f, (80 + 175 * p).toInt())
            }
            "location" -> {
                c.drawCircle(50f, 40f, 18f, stroke)
                c.drawLine(50f, 58f, 50f, 80f, stroke)
                lume(50f, 40f, 5f)
                val r = if (animated) (t * 0.8f) % 1f else 0.5f
                stroke.strokeWidth = 2f; stroke.alpha = (200 * (1f - r)).toInt()
                c.drawOval(RectF(50f - 22f * r, 80f - 5f * r, 50f + 22f * r, 80f + 5f * r), stroke)
                stroke.alpha = 255; stroke.strokeWidth = 4.5f
            }
            "dnd" -> {
                arc(20f, 20f, 80f, 80f, 70f, 260f)
                arc(38f, 20f, 82f, 64f, 110f, 160f)
                lume(74f, 26f, 3f, (60 + 195 * p).toInt())
            }
            "saver" -> {
                rect.set(18f, 34f, 76f, 66f); c.drawRoundRect(rect, 7f, 7f, stroke)
                c.drawLine(83f, 44f, 83f, 56f, stroke)
                val s = 7f + 2f * p
                c.drawLine(47f, 50f - s, 47f, 50f + s, stroke); c.drawLine(47f - s, 50f, 47f + s, 50f, stroke)
            }
            "dark" -> {
                c.drawCircle(50f, 50f, 28f, stroke)
                c.save(); c.rotate(if (animated) (t * 30f) % 360f else -20f, 50f, 50f)
                rect.set(22f, 22f, 78f, 78f); c.drawArc(rect, 90f, 180f, true, fill)
                c.restore()
            }
            "brightness" -> {
                c.drawCircle(50f, 50f, 12f, stroke)
                lume(50f, 50f, 3.6f)
                c.save(); c.rotate(if (animated) (t * 20f) % 360f else 0f, 50f, 50f)
                for (i in 0 until 12) {
                    c.save(); c.rotate(i * 30f, 50f, 50f)
                    val len = if (i % 2 == 0) 8f + 3f * p else 5f
                    c.drawLine(50f, 30f - len, 50f, 30f, stroke)
                    c.restore()
                }
                c.restore()
            }
            "flashlight" -> {
                rect.set(40f, 50f, 60f, 84f); c.drawRoundRect(rect, 4f, 4f, stroke)
                c.drawLine(34f, 44f, 66f, 44f, stroke)
                stroke.alpha = (110 + 145 * p).toInt()
                c.drawLine(50f, 34f, 50f, 16f, stroke); c.drawLine(38f, 32f, 30f, 20f, stroke); c.drawLine(62f, 32f, 70f, 20f, stroke)
                stroke.alpha = 255
            }
            "landscape" -> {
                c.save(); c.rotate(if (animated) -10f * (0.5f + 0.5f * sin(t * 1.2f)) else 0f, 50f, 50f)
                rect.set(16f, 32f, 84f, 68f); c.drawRoundRect(rect, 7f, 7f, stroke)
                c.restore()
                lume(74f, 50f, 3f)
            }
            "gear" -> {
                c.save(); c.rotate(if (animated) (t * 25f) % 360f else 0f, 50f, 50f)
                c.drawCircle(50f, 50f, 20f, stroke)
                for (i in 0 until 6) { c.save(); c.rotate(i * 60f, 50f, 50f); c.drawLine(50f, 18f, 50f, 28f, stroke); c.restore() }
                c.restore()
                lume(50f, 50f, 5f)
            }
            "plus" -> {
                c.drawLine(50f, 26f, 50f, 74f, stroke); c.drawLine(26f, 50f, 74f, 50f, stroke)
                lume(50f, 50f, 3f + 1f * p)
            }
            "help" -> {
                path.reset(); path.moveTo(38f, 38f); path.cubicTo(38f, 24f, 62f, 24f, 62f, 38f); path.cubicTo(62f, 50f, 50f, 50f, 50f, 60f)
                c.drawPath(path, stroke)
                lume(50f, 72f, 4f, (120 + 135 * p).toInt())
            }
            "back" -> {
                val x = if (animated) -4f * p else 0f
                path.reset(); path.moveTo(58f + x, 26f); path.lineTo(34f + x, 50f); path.lineTo(58f + x, 74f); c.drawPath(path, stroke)
                lume(70f + x, 50f, 2.6f, 170)
            }
            "screens" -> {
                rect.set(20f, 16f, 80f, 48f); c.drawRoundRect(rect, 5f, 5f, stroke)
                rect.set(30f, 54f, 70f, 84f); c.drawRoundRect(rect, 5f, 5f, stroke)
                lume(50f, if (animated) 32f + 37f * (0.5f + 0.5f * sin(t * 1.4f)) else 32f, 3f)
            }
            "grid" -> {
                for (row in 0..1) for (col in 0..1) {
                    rect.set(20f + col * 34f, 20f + row * 34f, 46f + col * 34f, 46f + row * 34f)
                    if (animated && (t * 1.4f).toInt() % 4 == row * 2 + col) c.drawRoundRect(rect, 5f, 5f, fill) else c.drawRoundRect(rect, 5f, 5f, stroke)
                }
            }
            "sound" -> {
                path.reset(); path.moveTo(18f, 42f); path.lineTo(30f, 42f); path.lineTo(46f, 28f); path.lineTo(46f, 72f); path.lineTo(30f, 58f); path.lineTo(18f, 58f); path.close()
                c.drawPath(path, stroke)
                for (i in 1..2) {
                    val r = 10f + i * 11f
                    stroke.alpha = ((sin(t * 3f - i) * 0.5f + 0.5f) * 200 + 55).toInt()
                    arc(46f - r, 50f - r, 46f + r, 50f + r, -40f, 80f)
                }
                stroke.alpha = 255
            }
            else -> {
                val s = 1f + 0.1f * (if (animated) sin(t * 2f) else 0f)
                c.save(); c.scale(s, s, 50f, 50f)
                path.reset(); path.moveTo(50f, 18f); path.lineTo(56f, 44f); path.lineTo(82f, 50f); path.lineTo(56f, 56f)
                path.lineTo(50f, 82f); path.lineTo(44f, 56f); path.lineTo(18f, 50f); path.lineTo(44f, 44f); path.close()
                c.drawPath(path, stroke)
                c.restore()
                lume(50f, 50f, 3.4f)
            }
        }
        stroke.alpha = 255
    }

    // ---- Obsidian icon set -----------------------------------------------------------
    // A separate, geometric line set for the Obsidian theme: thinner even strokes, a small
    // white "signal" accent per icon and slow, precise motion instead of bouncing.

    private val neoAccent = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val neoEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var neoSweep: android.graphics.SweepGradient? = null
    private val neoMatrix = Matrix()

    /** Squircle tile behind each Obsidian icon, with a light that travels round its edge. */
    private fun neoTile(c: Canvas, t: Float) {
        rect.set(5f, 5f, 95f, 95f)
        blob.shader = android.graphics.LinearGradient(5f, 5f, 95f, 95f,
            withAlpha(glow, 52), withAlpha(glow, 8), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, 28f, 28f, blob)
        blob.shader = null
        neoEdge.shader = null
        neoEdge.strokeWidth = 2.4f
        neoEdge.color = withAlpha(glow, 85)
        c.drawRoundRect(rect, 28f, 28f, neoEdge)
        if (animated) {
            val sweep = neoSweep ?: android.graphics.SweepGradient(50f, 50f,
                intArrayOf(withAlpha(glow, 0), withAlpha(glow, 0), withAlpha(Color.WHITE, 230), withAlpha(glow, 0)),
                floatArrayOf(0f, 0.80f, 0.92f, 1f)).also { neoSweep = it }
            neoMatrix.setRotate((t * 70f) % 360f, 50f, 50f)
            sweep.setLocalMatrix(neoMatrix)
            neoEdge.shader = sweep
            neoEdge.strokeWidth = 3.2f
            c.drawRoundRect(rect, 28f, 28f, neoEdge)
            neoEdge.shader = null
        }
    }

    private fun neoDot(c: Canvas, x: Float, y: Float, r: Float, alpha: Int = 255) {
        neoAccent.alpha = alpha.coerceIn(0, 255)
        c.drawCircle(x, y, r, neoAccent)
    }

    private fun neoGlyph(c: Canvas, t: Float) {
        stroke.strokeWidth = 7f
        val p = 0.5f + 0.5f * sin(t * 2.4f)
        when (kind) {
            "eco" -> {
                path.reset()
                path.moveTo(22f, 78f)
                path.cubicTo(22f, 42f, 42f, 22f, 78f, 22f)
                path.cubicTo(78f, 58f, 58f, 78f, 22f, 78f)
                c.drawPath(path, stroke)
                val s = 0.55f + 0.2f * sin(t * 1.4f)
                c.drawLine(22f, 78f, 22f + 56f * s, 78f - 56f * s, stroke)
                neoDot(c, 78f, 22f, 5f, (140 + 115 * p).toInt())
            }
            "balanced" -> {
                c.drawLine(16f, 34f, 84f, 34f, stroke)
                c.drawLine(16f, 66f, 84f, 66f, stroke)
                val a = 50f + 20f * sin(t * 1.2f)
                val b = 50f - 20f * sin(t * 1.2f)
                soft.color = withAlpha(Color.BLACK, 255)
                c.drawCircle(a, 34f, 10f, fill); c.drawCircle(b, 66f, 10f, fill)
                neoDot(c, a, 34f, 4f); neoDot(c, b, 66f, 4f)
            }
            "streaming" -> {
                c.drawCircle(50f, 50f, 7f, fill)
                for (i in 1..2) {
                    val r = 16f + i * 13f
                    val alpha = ((sin(t * 3f - i * 1.1f) * 0.5f + 0.5f) * 200 + 55).toInt()
                    stroke.alpha = alpha
                    rect.set(50f - r, 50f - r, 50f + r, 50f + r)
                    c.drawArc(rect, -40f, 80f, false, stroke)
                    c.drawArc(rect, 140f, 80f, false, stroke)
                }
                stroke.alpha = 255
                neoDot(c, 50f, 50f, 3f)
            }
            "gaming", "controller" -> {
                rect.set(12f, 28f, 88f, 74f)
                c.drawRoundRect(rect, 23f, 23f, stroke)
                stroke.strokeWidth = 6f
                c.drawLine(26f, 51f, 42f, 51f, stroke)
                c.drawLine(34f, 43f, 34f, 59f, stroke)
                val blink = (t * 1.6f).toInt() % 2 == 0
                neoDot(c, 62f, 47f, 5f, if (blink || !animated) 255 else 110)
                c.drawCircle(72f, 57f, 5f, fill)
            }
            "max" -> {
                val off = if (animated) (t * 18f) % 18f else 6f
                for (i in 0..2) {
                    val y = 74f - i * 18f - off
                    if (y < 16f) continue
                    stroke.alpha = (255 * ((y - 16f) / 58f)).toInt().coerceIn(40, 255)
                    path.reset(); path.moveTo(26f, y + 12f); path.lineTo(50f, y - 6f); path.lineTo(74f, y + 12f)
                    c.drawPath(path, stroke)
                }
                stroke.alpha = 255
                neoDot(c, 50f, 18f, 5f, (120 + 135 * p).toInt())
            }
            "fps" -> {
                rect.set(16f, 24f, 84f, 92f)
                c.drawArc(rect, 160f, 220f, false, stroke)
                val ang = Math.toRadians((200 + 120 * (0.5f + 0.5f * sin(t * 1.3f))).toDouble())
                stroke.strokeWidth = 6f
                c.drawLine(50f, 58f, 50f + 26f * cos(ang).toFloat(), 58f + 26f * sin(ang).toFloat(), stroke)
                neoDot(c, 50f, 58f, 5f)
            }
            "fan" -> {
                c.drawCircle(50f, 50f, 34f, stroke)
                c.save(); c.rotate(if (animated) (t * 160f) % 360f else 20f, 50f, 50f)
                for (i in 0 until 3) {
                    c.save(); c.rotate(i * 120f, 50f, 50f)
                    path.reset(); path.moveTo(50f, 50f); path.quadTo(66f, 38f, 52f, 24f)
                    c.drawPath(path, stroke)
                    c.restore()
                }
                c.restore()
                neoDot(c, 50f, 50f, 5f)
            }
            "display" -> {
                rect.set(14f, 20f, 86f, 66f)
                c.drawRoundRect(rect, 9f, 9f, stroke)
                c.drawLine(38f, 80f, 62f, 80f, stroke)
                val y = 28f + 30f * (if (animated) (t * 0.6f) % 1f else 0.5f)
                stroke.strokeWidth = 3f; stroke.alpha = 150
                c.drawLine(24f, y, 76f, y, stroke)
                stroke.alpha = 255
                neoDot(c, 76f, 30f, 4f, (120 + 135 * p).toInt())
            }
            "cpu" -> {
                rect.set(28f, 28f, 72f, 72f)
                c.drawRoundRect(rect, 8f, 8f, stroke)
                stroke.strokeWidth = 5f
                for (i in 0..2) {
                    val k = 38f + i * 12f
                    c.drawLine(k, 14f, k, 24f, stroke); c.drawLine(k, 76f, k, 86f, stroke)
                    c.drawLine(14f, k, 24f, k, stroke); c.drawLine(76f, k, 86f, k, stroke)
                }
                soft.color = withAlpha(tint, (90 + 120 * p).toInt())
                c.drawRoundRect(RectF(40f, 40f, 60f, 60f), 4f, 4f, soft)
                neoDot(c, 50f, 50f, 3.5f)
            }
            "gpu" -> {
                rect.set(12f, 28f, 88f, 72f)
                c.drawRoundRect(rect, 9f, 9f, stroke)
                c.drawCircle(40f, 50f, 13f, stroke)
                c.save(); c.rotate(if (animated) (t * 200f) % 360f else 0f, 40f, 50f)
                stroke.strokeWidth = 4f
                c.drawLine(40f, 41f, 40f, 59f, stroke); c.drawLine(31f, 50f, 49f, 50f, stroke)
                c.restore()
                stroke.strokeWidth = 5f
                c.drawLine(62f, 42f, 76f, 42f, stroke); c.drawLine(62f, 58f, 76f, 58f, stroke)
                neoDot(c, 76f, 50f, 4f, (120 + 135 * p).toInt())
            }
            "ram" -> {
                rect.set(12f, 32f, 88f, 62f)
                c.drawRoundRect(rect, 6f, 6f, stroke)
                stroke.strokeWidth = 4f
                for (i in 0..5) c.drawLine(20f + i * 12f, 62f, 20f + i * 12f, 72f, stroke)
                for (i in 0..3) {
                    val on = ((t * 3f).toInt() + i) % 4 == 0 && animated
                    soft.color = withAlpha(tint, if (on) 255 else 110)
                    c.drawRect(21f + i * 16f, 40f, 31f + i * 16f, 54f, soft)
                }
            }
            "battery" -> {
                rect.set(14f, 32f, 78f, 68f)
                c.drawRoundRect(rect, 9f, 9f, stroke)
                c.drawLine(86f, 44f, 86f, 56f, stroke)
                val level = if (animated) 0.35f + 0.55f * (0.5f + 0.5f * sin(t * 0.9f)) else 0.75f
                soft.color = withAlpha(tint, 200)
                c.drawRoundRect(RectF(23f, 41f, 23f + 46f * level, 59f), 4f, 4f, soft)
                neoDot(c, 23f + 46f * level, 50f, 3.5f)
            }
            "storage" -> {
                for (i in 0..2) {
                    rect.set(18f, 20f + i * 22f, 82f, 36f + i * 22f)
                    c.drawRoundRect(rect, 7f, 7f, stroke)
                    val on = !animated || ((t * 2f).toInt() % 3 == i)
                    neoDot(c, 70f, 28f + i * 22f, 3.5f, if (on) 255 else 70)
                }
            }
            "keyboard" -> {
                rect.set(10f, 28f, 90f, 72f)
                c.drawRoundRect(rect, 9f, 9f, stroke)
                val lit = if (animated) ((t * 4f).toInt() % 7) else 3
                for (row in 0..1) for (col in 0..6) {
                    val x = 21f + col * 9.7f; val y = 40f + row * 11f
                    if (row == 0 && col == lit) neoDot(c, x, y, 3.6f) else c.drawCircle(x, y, 2.6f, fill)
                }
                stroke.strokeWidth = 5f
                c.drawLine(32f, 62f, 68f, 62f, stroke)
            }
            "touchpad" -> {
                rect.set(18f, 16f, 82f, 84f)
                c.drawRoundRect(rect, 13f, 13f, stroke)
                stroke.strokeWidth = 4f
                c.drawLine(18f, 66f, 82f, 66f, stroke); c.drawLine(50f, 66f, 50f, 84f, stroke)
                val x = 50f + 16f * sin(t * 1.4f)
                val y = 41f + 10f * sin(t * 2.8f)
                neoDot(c, x, y, 5f)
            }
            "profiles" -> {
                path.reset()
                path.moveTo(56f, 12f); path.lineTo(28f, 54f); path.lineTo(48f, 54f)
                path.lineTo(42f, 88f); path.lineTo(72f, 44f); path.lineTo(52f, 44f); path.close()
                c.drawPath(path, stroke)
                neoDot(c, 72f, 22f, 4f, (90 + 165 * p).toInt())
            }
            "pc" -> {
                rect.set(12f, 18f, 88f, 64f)
                c.drawRoundRect(rect, 8f, 8f, stroke)
                c.drawLine(50f, 64f, 50f, 78f, stroke); c.drawLine(34f, 80f, 66f, 80f, stroke)
                stroke.strokeWidth = 5f
                c.drawLine(24f, 34f, 34f, 41f, stroke); c.drawLine(34f, 41f, 24f, 48f, stroke)
                if (!animated || (t * 2f).toInt() % 2 == 0) neoDot(c, 46f, 48f, 3.5f)
            }
            "wifi" -> {
                for (i in 0..2) {
                    val r = 14f + i * 14f
                    val on = !animated || ((t * 2.2f).toInt() % 4 > i)
                    stroke.alpha = if (on) 255 else 70
                    rect.set(50f - r, 74f - r, 50f + r, 74f + r)
                    c.drawArc(rect, 225f, 90f, false, stroke)
                }
                stroke.alpha = 255
                neoDot(c, 50f, 74f, 5f)
            }
            "bluetooth" -> {
                path.reset()
                path.moveTo(30f, 32f); path.lineTo(68f, 66f); path.lineTo(50f, 84f); path.lineTo(50f, 16f)
                path.lineTo(68f, 34f); path.lineTo(30f, 68f)
                c.drawPath(path, stroke)
                neoDot(c, 78f, 50f, 4f, (60 + 195 * p).toInt()); neoDot(c, 22f, 50f, 4f, (60 + 195 * (1 - p)).toInt())
            }
            "rotate" -> {
                c.save(); c.rotate(if (animated) (t * 60f) % 360f else 0f, 50f, 50f)
                rect.set(20f, 20f, 80f, 80f)
                c.drawArc(rect, 20f, 290f, false, stroke)
                path.reset(); path.moveTo(80f, 38f); path.lineTo(80f, 54f); path.lineTo(66f, 50f)
                c.drawPath(path, stroke)
                c.restore()
                neoDot(c, 50f, 50f, 5f)
            }
            "airplane" -> {
                c.save(); c.translate(0f, if (animated) 3f * sin(t * 1.5f) else 0f); c.rotate(-35f, 50f, 50f)
                c.drawLine(50f, 14f, 50f, 86f, stroke)
                path.reset(); path.moveTo(16f, 58f); path.lineTo(50f, 40f); path.lineTo(84f, 58f)
                c.drawPath(path, stroke)
                path.reset(); path.moveTo(36f, 86f); path.lineTo(50f, 78f); path.lineTo(64f, 86f)
                c.drawPath(path, stroke)
                c.restore()
                neoDot(c, 80f, 22f, 3.5f, (80 + 175 * p).toInt())
            }
            "location" -> {
                path.reset()
                path.moveTo(50f, 80f)
                path.cubicTo(30f, 58f, 24f, 48f, 24f, 38f)
                path.cubicTo(24f, 22f, 36f, 12f, 50f, 12f)
                path.cubicTo(64f, 12f, 76f, 22f, 76f, 38f)
                path.cubicTo(76f, 48f, 70f, 58f, 50f, 80f)
                c.drawPath(path, stroke)
                neoDot(c, 50f, 38f, 6f)
                val r = if (animated) (t * 0.8f) % 1f else 0.5f
                stroke.strokeWidth = 3f; stroke.alpha = (200 * (1f - r)).toInt()
                c.drawOval(RectF(50f - 26f * r, 84f - 6f * r, 50f + 26f * r, 84f + 6f * r), stroke)
                stroke.alpha = 255
            }
            "dnd" -> {
                path.reset()
                path.addCircle(48f, 52f, 30f, Path.Direction.CW)
                c.save()
                val cut = Path().apply { addCircle(64f, 40f, 26f, Path.Direction.CW) }
                c.clipOutPath(cut)
                c.drawPath(path, stroke)
                c.restore()
                neoDot(c, 74f, 24f, 4f, (60 + 195 * p).toInt())
            }
            "saver" -> {
                rect.set(32f, 18f, 68f, 84f)
                c.drawRoundRect(rect, 9f, 9f, stroke)
                c.drawLine(44f, 12f, 56f, 12f, stroke)
                val s = 7f + 3f * p
                c.drawLine(50f, 51f - s, 50f, 51f + s, stroke); c.drawLine(50f - s, 51f, 50f + s, 51f, stroke)
            }
            "dark" -> {
                c.save(); c.rotate(if (animated) (t * 40f) % 360f else -30f, 50f, 50f)
                c.drawCircle(50f, 50f, 30f, stroke)
                rect.set(20f, 20f, 80f, 80f)
                c.drawArc(rect, 90f, 180f, true, fill)
                c.restore()
                neoDot(c, 50f, 50f, 3.5f)
            }
            "brightness" -> {
                c.drawCircle(50f, 50f, 13f, stroke)
                c.save(); c.rotate(if (animated) (t * 25f) % 360f else 0f, 50f, 50f)
                val len = 7f + 4f * p
                for (i in 0 until 8) {
                    c.save(); c.rotate(i * 45f, 50f, 50f)
                    c.drawLine(50f, 26f - len, 50f, 26f, stroke)
                    c.restore()
                }
                c.restore()
                neoDot(c, 50f, 50f, 4f)
            }
            "flashlight" -> {
                path.reset()
                path.moveTo(38f, 52f); path.lineTo(62f, 52f); path.lineTo(58f, 86f); path.lineTo(42f, 86f); path.close()
                c.drawPath(path, stroke)
                c.drawLine(34f, 44f, 66f, 44f, stroke)
                stroke.strokeWidth = 5f
                stroke.alpha = (110 + 145 * p).toInt()
                c.drawLine(50f, 32f, 50f, 14f, stroke); c.drawLine(36f, 30f, 28f, 16f, stroke); c.drawLine(64f, 30f, 72f, 16f, stroke)
                stroke.alpha = 255
                neoDot(c, 50f, 64f, 3.5f)
            }
            "landscape" -> {
                c.save(); c.rotate(if (animated) -12f * (0.5f + 0.5f * sin(t * 1.2f)) else 0f, 50f, 50f)
                rect.set(14f, 30f, 86f, 70f)
                c.drawRoundRect(rect, 9f, 9f, stroke)
                c.restore()
                neoDot(c, 74f, 50f, 4f)
            }
            "mixer" -> {
                for (i in 0..2) {
                    val x = 28f + i * 22f
                    c.drawLine(x, 16f, x, 84f, stroke)
                    val y = 50f + 22f * sin(t * (1.1f + i * 0.35f) + i * 2f)
                    c.drawRoundRect(RectF(x - 9f, y - 5f, x + 9f, y + 5f), 4f, 4f, fill)
                    neoDot(c, x, y, 2.6f)
                }
            }
            "gear" -> {
                c.save(); c.rotate(if (animated) (t * 30f) % 360f else 0f, 50f, 50f)
                c.drawCircle(50f, 50f, 22f, stroke)
                for (i in 0 until 8) {
                    c.save(); c.rotate(i * 45f, 50f, 50f)
                    c.drawLine(50f, 16f, 50f, 26f, stroke)
                    c.restore()
                }
                c.restore()
                neoDot(c, 50f, 50f, 6f)
            }
            "plus" -> {
                val s = 26f + 4f * p
                c.drawLine(50f, 50f - s, 50f, 50f + s, stroke); c.drawLine(50f - s, 50f, 50f + s, 50f, stroke)
                neoDot(c, 50f, 50f, 4f)
            }
            "help" -> {
                c.drawCircle(50f, 50f, 34f, stroke)
                path.reset(); path.moveTo(40f, 40f); path.cubicTo(40f, 28f, 60f, 28f, 60f, 40f); path.cubicTo(60f, 50f, 50f, 50f, 50f, 58f)
                c.drawPath(path, stroke)
                neoDot(c, 50f, 70f, 4.5f, (120 + 135 * p).toInt())
            }
            "back" -> {
                val x = if (animated) -4f * p else 0f
                path.reset(); path.moveTo(60f + x, 22f); path.lineTo(32f + x, 50f); path.lineTo(60f + x, 78f)
                c.drawPath(path, stroke)
                neoDot(c, 72f + x, 50f, 4f, 160)
            }
            "screens" -> {
                rect.set(18f, 12f, 82f, 46f); c.drawRoundRect(rect, 7f, 7f, stroke)
                rect.set(26f, 54f, 74f, 88f); c.drawRoundRect(rect, 7f, 7f, stroke)
                neoDot(c, 50f, if (animated) 29f + 42f * (0.5f + 0.5f * sin(t * 1.5f)) else 29f, 4f)
            }
            "grid" -> {
                for (row in 0..1) for (col in 0..1) {
                    rect.set(18f + col * 36f, 18f + row * 36f, 46f + col * 36f, 46f + row * 36f)
                    val on = animated && ((t * 1.5f).toInt() % 4) == row * 2 + col
                    if (on) c.drawRoundRect(rect, 7f, 7f, fill) else c.drawRoundRect(rect, 7f, 7f, stroke)
                }
            }
            "sound" -> {
                path.reset(); path.moveTo(16f, 40f); path.lineTo(30f, 40f); path.lineTo(48f, 24f); path.lineTo(48f, 76f); path.lineTo(30f, 60f); path.lineTo(16f, 60f); path.close()
                c.drawPath(path, stroke)
                for (i in 1..2) {
                    val r = 10f + i * 12f
                    stroke.alpha = ((sin(t * 3f - i) * 0.5f + 0.5f) * 200 + 55).toInt()
                    rect.set(48f - r, 50f - r, 48f + r, 50f + r)
                    c.drawArc(rect, -45f, 90f, false, stroke)
                }
                stroke.alpha = 255
            }
            else -> {
                val s = 1f + 0.12f * (if (animated) sin(t * 2f) else 0f)
                c.save(); c.scale(s, s, 50f, 50f)
                path.reset()
                path.moveTo(50f, 14f); path.quadTo(54f, 46f, 86f, 50f); path.quadTo(54f, 54f, 50f, 86f)
                path.quadTo(46f, 54f, 14f, 50f); path.quadTo(46f, 46f, 50f, 14f); path.close()
                c.drawPath(path, stroke)
                c.restore()
                neoDot(c, 50f, 50f, 4f)
            }
        }
        stroke.alpha = 255
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

    /**
     * Starship Console profile glyphs, drawn as ship systems:
     * Eco = impulse (one chevron), Balanced = cruise (two), Streaming = comms (signal arcs),
     * Gaming = tactical (targeting reticle), Max = full speed (three chevrons + streaks).
     */
    private fun bridgeProfile(c: Canvas, t: Float) {
        stroke.strokeWidth = w(8f)
        val pulse = if (animated) sin(t * 4f) else 0f
        fun chevron(y: Float) {
            path.reset(); path.moveTo(24f, y + 14f); path.lineTo(50f, y - 6f); path.lineTo(76f, y + 14f)
            c.drawPath(path, stroke)
        }
        when (kind) {
            "eco" -> {
                chevron(44f - 4f * pulse)
                fill.color = tint
                c.drawCircle(50f, 74f, 5f, fill)
            }
            "balanced" -> {
                chevron(32f - 3f * pulse)
                chevron(56f - 3f * pulse)
            }
            "streaming" -> {
                fill.color = tint
                c.drawCircle(30f, 70f, 7f, fill)
                for (i in 1..3) {
                    val radius = 18f * i + (if (animated) 4f * ((t * 1.5f + i * 0.3f) % 1f) else 0f)
                    rect.set(30f - radius, 70f - radius, 30f + radius, 70f + radius)
                    c.drawArc(rect, -90f, 90f, false, stroke)
                }
            }
            "gaming", "controller" -> {
                c.drawCircle(50f, 50f, 26f, stroke)
                val spin = if (animated) t * 40f else 0f
                c.save(); c.rotate(spin, 50f, 50f)
                c.drawLine(50f, 10f, 50f, 26f, stroke); c.drawLine(50f, 74f, 50f, 90f, stroke)
                c.drawLine(10f, 50f, 26f, 50f, stroke); c.drawLine(74f, 50f, 90f, 50f, stroke)
                c.restore()
                fill.color = tint
                c.drawCircle(50f, 50f, 6f + 2f * pulse, fill)
            }
            else -> {
                chevron(24f - 3f * pulse); chevron(46f - 3f * pulse); chevron(68f - 3f * pulse)
                stroke.strokeWidth = w(5f)
                val shift = if (animated) (t * 60f) % 20f else 0f
                c.drawLine(10f, 30f + shift, 10f, 50f + shift, stroke)
                c.drawLine(90f, 40f + shift, 90f, 60f + shift, stroke)
            }
        }
    }

    /** Equalizer: three faders whose knobs glide up and down. */
    private fun faders(c: Canvas, t: Float) {
        stroke.strokeWidth = w(5f)
        for (i in 0 until 3) {
            val x = 28f + i * 22f
            c.drawLine(x, 18f, x, 82f, stroke)
            val y = 50f + 22f * sin(t * (1.6f + i * 0.5f) + i * 1.7f)
            fill.color = tint
            c.drawCircle(x, y, 8f, fill)
            soft.color = Color.WHITE
            c.drawCircle(x, y, 3f, soft)
        }
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
        /** Set when the Starship Console theme is active: icons sit on console buttons. */
        @JvmStatic var bridgeStyle = false
        /** Obsidian theme: geometric line set on dark squircle tiles. */
        @JvmStatic var neoStyle = false
        /** Chronograph theme: watch-complication icons in a ticked ring. */
        @JvmStatic var chronoStyle = false
        /** Blueprint theme: technical line drawings; only the fan icon may move. */
        @JvmStatic var blueprintStyle = false
        /** Cartridge theme: modern pixel-art icons; only the fan icon may move. */
        @JvmStatic var cartStyle = false
        /** Deep Sea theme: icons in glass bubbles; only the fan icon may move. */
        @JvmStatic var abyssStyle = false
        /** Hi-Fi theme: icons in small display windows; only the fan icon may move. */
        @JvmStatic var hifiStyle = false
        /** Hologram theme: icons in hexagons; only the fan icon may move. */
        @JvmStatic var hudStyle = false
        /** Bauhaus theme: geometric tiles; only the fan icon may move. */
        @JvmStatic var bauhausStyle = false
        /** Jade theme: duotone icons on leaf tiles; only the fan icon may move. */
        @JvmStatic var jadeStyle = false
        /** Themes where every icon except the fan stays still (saves CPU). */
        @JvmStatic fun stillIcons(): Boolean = blueprintStyle || cartStyle || abyssStyle || hifiStyle || hudStyle || bauhausStyle || jadeStyle

        private val CART_PIXELS: Map<String, Array<String>> = mapOf(
            "fan" to arrayOf("............", ".##......##.", ".###....###.", "..###..###..", "...##..##...", ".....++.....", ".....++.....", "...##..##...", "..###..###..", ".###....###.", ".##......##.", "............"),
            "cpu" to arrayOf("..#.#..#.#..", "............", "#.########.#", "..#......#..", "#.#.####.#.#", "..#.#++#.#..", "#.#.#++#.#.#", "..#.####.#..", "#.#......#.#", "..########..", "............", "..#.#..#.#.."),
            "gpu" to arrayOf("............", "############", "#..........#", "#.####..##.#", "#.#++#..##.#", "#.#++#.....#", "#.####..##.#", "#.......##.#", "############", ".#..#.#.....", ".#..#.#.....", "............"),
            "ram" to arrayOf("............", "............", "############", "#..........#", "#.##.##.##.#", "#.##.##.##.#", "#..........#", "############", ".#.#.#.#.#..", "............", "............", "............"),
            "battery" to arrayOf("............", "............", "###########.", "#.........#.", "#.##.##.+.##", "#.##.##.+.##", "#.##.##.+.##", "#.........#.", "###########.", "............", "............", "............"),
            "storage" to arrayOf("...######...", "..#......#..", ".#..#.#.#.#.", ".#..#.#.#.#.", ".#.........#", ".#.........#", ".#..+++++..#", ".#..+...+..#", ".#..+++++..#", ".#.........#", ".###########", "............"),
            "display" to arrayOf("############", "#..........#", "#.++.......#", "#.+........#", "#..........#", "#..........#", "#..........#", "############", ".....##.....", ".....##.....", "...######...", "............"),
            "fps" to arrayOf("............", "..........#.", "..........#.", ".......#..#.", ".......#..#.", "....#..#..#.", "....#..#..#.", ".#..#..#..#.", ".#..#..#..#.", "............", "############", "............"),
            "eco" to arrayOf("............", ".......#####", ".....###..##", "....##...#.#", "...##...#..#", "..##...#..#.", "..#...#..##.", "..#..#..##..", "..#.#.###...", "..##.##.....", ".##.........", "##.........."),
            "balanced" to arrayOf(".....##.....", ".....##.....", "############", ".#........#.", ".#........#.", "###......###", "###......###", ".....##.....", ".....##.....", ".....##.....", "...######...", "............"),
            "streaming" to arrayOf("............", ".#........#.", "#..#....#..#", "#.#..##..#.#", "#.#.#++#.#.#", "#.#.#++#.#.#", "#.#..##..#.#", "#..#....#..#", ".#...##...#.", ".....##.....", "....####....", "............"),
            "gaming" to arrayOf("............", "............", "..########..", ".#........#.", "#..#.....#.#", "#.###...#.##", "#..#.....#.#", "#..........#", "#...####...#", ".#.#....#.#.", "..#......#..", "............"),
            "max" to arrayOf(".....#......", ".....##.....", "....###.....", "....####..#.", "...#####.##.", "..####+####.", "..###++####.", ".####++++##.", ".###++++###.", ".###+..+###.", "..###..###..", "...######..."),
            "profiles" to arrayOf("......####..", ".....####...", "....####....", "...####.....", "..#######...", ".########...", "....####....", "...####.....", "..####......", "..###.......", ".##.........", ".#.........."),
            "keyboard" to arrayOf("............", "............", "############", "#..........#", "#.#.#.#.#.##", "#..........#", "#.#.#.#.#.##", "#..........#", "#..######..#", "#..........#", "############", "............"),
            "touchpad" to arrayOf(".##########.", "#..........#", "#..........#", "#....++....#", "#....++....#", "#..........#", "#..........#", "############", "#....##....#", "#....##....#", ".##########.", "............"),
            "pc" to arrayOf("############", "#..........#", "#.#........#", "#..#.......#", "#.#..###...#", "#..........#", "#..........#", "############", ".....##.....", ".....##.....", "...######...", "............"),
            "star" to arrayOf(".....##.....", ".....##.....", "....####....", "############", ".##########.", "..########..", "...######...", "...######...", "..###..###..", "..##....##..", ".##......##.", "............"),
            "gear" to arrayOf(".....##.....", "..#.####.#..", ".##########.", "..###..###..", ".###....###.", "####....####", "####....####", ".###....###.", "..###..###..", ".##########.", "..#.####.#..", ".....##....."),
            "plus" to arrayOf("............", ".....##.....", ".....##.....", ".....##.....", ".....##.....", ".##########.", ".##########.", ".....##.....", ".....##.....", ".....##.....", ".....##.....", "............"),
            "help" to arrayOf("...######...", "..##....##..", "..##....##..", "........##..", ".......##...", "......##....", ".....##.....", ".....##.....", "............", ".....##.....", ".....##.....", "............"),
            "back" to arrayOf("............", "......##....", ".....##.....", "....##......", "...##.......", "..##########", "..##########", "...##.......", "....##......", ".....##.....", "......##....", "............"),
            "screens" to arrayOf(".##########.", ".#........#.", ".#........#.", ".#........#.", ".##########.", "............", "..########..", "..#......#..", "..#......#..", "..#......#..", "..########..", "............"),
            "grid" to arrayOf("............", ".####..####.", ".####..####.", ".####..####.", ".####..####.", "............", "............", ".####..####.", ".####..####.", ".####..####.", ".####..####.", "............"),
            "sound" to arrayOf("............", ".....#......", "....##...#..", "...###.#..#.", "####.#..#.#.", "####.#..#.#.", "####.#..#.#.", "####.#..#.#.", "...###.#..#.", "....##...#..", ".....#......", "............"),
            "wifi" to arrayOf("............", "...######...", ".##......##.", "#....##....#", "...##..##...", "..#......#..", ".....##.....", "....#..#....", "............", ".....##.....", ".....##.....", "............"),
            "bluetooth" to arrayOf(".....#......", ".....##.....", "..#..#.#....", "...#.#..#...", "....###.#...", ".....#.#....", "....###.#...", "...#.#..#...", "..#..#.#....", ".....##.....", ".....#......", "............"),
            "brightness" to arrayOf(".....##.....", ".#...##...#.", "..#......#..", "....####....", "...######...", "##.######.##", "##.######.##", "...######...", "....####....", "..#......#..", ".#...##...#.", ".....##....."),
            "dark" to arrayOf("....####....", "..###.......", ".###........", ".##.........", "###.........", "###.........", "###.........", "###........#", ".###......##", ".#####..###.", "..#######...", "....####...."),
            "mixer" to arrayOf("..#...#...#.", "..#...#...#.", "..#..###..#.", ".###.###..#.", ".###..#..###", "..#...#..###", "..#...#...#.", "..#...#...#.", "..#...#...#.", "..#...#...#.", "..#...#...#.", "............"),
            "rotate" to arrayOf("....####....", "..##....##..", ".#........#.", ".#......####", "#.........#.", "#...........", "...........#", ".#.........#", "####......#.", ".#........#.", "..##....##..", "....####...."),
            "airplane" to arrayOf(".....##.....", ".....##.....", ".....##.....", "....####....", "..########..", "############", ".....##.....", ".....##.....", ".....##.....", "....####....", "...######...", "............"),
            "location" to arrayOf("....####....", "..##....##..", ".#........#.", ".#...##...#.", ".#..####..#.", ".#...##...#.", "..#......#..", "..#......#..", "...#....#...", "....#..#....", ".....##.....", "............"),
            "saver" to arrayOf("............", "............", "###########.", "#.........#.", "#....#....##", "#...###...##", "#....#....##", "#.........#.", "###########.", "............", "............", "............"),
            "flashlight" to arrayOf(".#...#...#..", "..#..#..#...", "............", "..#######...", "..#.....#...", "...#...#....", "....###.....", "....#.#.....", "....#.#.....", "....#.#.....", "....###.....", "............"),
            "landscape" to arrayOf("............", "............", "############", "#.........##", "#.........##", "#.........##", "#.........##", "#.........##", "############", "............", "............", "............"),
            "fanring" to arrayOf("....####....", "..##....##..", ".#........#.", ".#........#.", "#..........#", "#..........#", "#..........#", "#..........#", ".#........#.", ".#........#.", "..##....##..", "....####....")
        )
        private val BRIDGE_PROFILE_KINDS = setOf("eco", "balanced", "streaming", "gaming", "max")
        private const val STILL_TIME = 0.35f
        fun withAlpha(color: Int, alpha: Int): Int =
            Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
        fun mixColors(a: Int, b: Int, t: Float): Int = Color.rgb(
            (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
            (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
            (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt()
        )
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
        if (spectrumStyle) { drawSpectrum(canvas, w, h, density, ceiling); return }

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

    private val seg = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Hi-Fi: the last samples as a spectrum analyser — columns of LED segments, green at the
     * bottom, amber in the middle and red at the top, with dim unlit segments behind.
     */
    private fun drawSpectrum(canvas: Canvas, w: Float, h: Float, density: Float, ceiling: Float) {
        val columns = 20
        val shown = values.takeLast(columns)
        val gap = 2f * density
        val cw = (w - gap * (columns - 1)) / columns
        val rows = 8
        val rh = (h - gap * (rows - 1)) / rows
        for (c in 0 until columns) {
            val v = shown.getOrNull(c - (columns - shown.size)) ?: 0
            val lit = ((v / ceiling) * rows).coerceIn(0f, rows.toFloat())
            for (r in 0 until rows) {
                val color = when {
                    r >= rows - 1 -> android.graphics.Color.rgb(255, 72, 56)
                    r >= rows - 3 -> android.graphics.Color.rgb(255, 178, 48)
                    else -> android.graphics.Color.rgb(120, 222, 110)
                }
                seg.color = if (r < lit) color else LivingIconView.withAlpha(color, 34)
                val x = c * (cw + gap)
                val y = h - (r + 1) * rh - r * gap
                canvas.drawRect(x, y, x + cw, y + rh, seg)
            }
        }
    }

    companion object {
        const val SLOTS = 60
        /** Hi-Fi theme: draw the graph as a spectrum analyser. */
        @JvmStatic var spectrumStyle = false
    }
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


/**
 * Starship Console page background: black space with a slowly drifting starfield
 * (three depth layers) and a faint scanner line.
 */
class StarfieldBackdrop(private val base: Int, private val scanColor: Int) : Drawable(), Animatable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stars = List(70) { i ->
        val r = java.util.Random(i * 7919L + 17)
        floatArrayOf(r.nextFloat(), r.nextFloat(), (i % 3).toFloat())
    }
    private var running = false
    private val tick = Runnable {
        invalidateSelf()
        if (running) scheduleNext()
    }

    private fun scheduleNext() {
        if (callback == null) { running = false; return }
        scheduleSelf(tick, SystemClock.uptimeMillis() + 66L)
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        paint.shader = null
        paint.color = base
        canvas.drawRect(bounds, paint)
        val t = SystemClock.uptimeMillis() / 1000f
        for (star in stars) {
            val layer = star[2]
            val speed = 0.004f + layer * 0.006f
            val x = bounds.left + w * ((star[0] - t * speed) % 1f + 1f) % 1f
            val y = bounds.top + h * star[1]
            val twinkle = 0.6f + 0.4f * sin(t * (1.3f + layer) + star[0] * 20f)
            paint.color = LivingIconView.withAlpha(Color.WHITE, ((70 + layer * 60) * twinkle).toInt())
            canvas.drawCircle(x, y, 1.2f + layer * 0.8f, paint)
        }
        val scanY = bounds.top + h * ((t * 0.05f) % 1f)
        paint.color = LivingIconView.withAlpha(scanColor, 26)
        canvas.drawRect(bounds.left.toFloat(), scanY, bounds.right.toFloat(), scanY + 2f, paint)
        if (!running) start()
    }

    override fun start() { if (running) return; running = true; scheduleNext() }
    override fun stop() { running = false; unscheduleSelf(tick) }
    override fun isRunning(): Boolean = running
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) start() else stop()
        return changed
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * Starship Console panels.
 * ELBOW (cards): thick accent bar down the left that bends into a segmented bar along the top,
 *   with a rounded outer corner, a curved inner corner and a small reference number.
 *   The card reserves room for it (see MainActivity.card()).
 * TOPBAR (large panels, docks): segmented bar along the top only, nothing on the sides.
 * ACCENT (tiles, buttons): short accent bar with a rounded cap along the top edge.
 * Nothing is drawn where content usually sits, so text never runs over the frame.
 */
class ConsoleFrameDrawable(
    private val accent: Int,
    private val second: Int,
    private val fill: Int,
    private val density: Float,
    private val mode: Int,
    private val strong: Boolean = false,
    private val radiusDp: Float = 18f
) : Drawable() {
    companion object {
        const val ELBOW = 0
        const val TOPBAR = 1
        const val ACCENT = 2
        /** Room the ELBOW frame needs on the left and top, in dp. */
        const val ELBOW_LEFT_DP = 10f
        const val ELBOW_TOP_DP = 9f
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(10, 10, 14)
        typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
    }
    private val rect = RectF()
    private val clip = Path()
    /** Stable pseudo reference number per panel, like the codes on real consoles. */
    private val code = String.format(java.util.Locale.ROOT, "%02d-%04d",
        (abs(System.identityHashCode(this)) % 90 + 10), (abs(System.identityHashCode(this)) / 7 % 9000 + 1000))

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val left = b.left.toFloat(); val top = b.top.toFloat()
        val right = b.right.toFloat(); val bottom = b.bottom.toFloat()
        val r = radiusDp * density
        paint.color = if (strong) blend(fill, accent, 0.30f) else fill
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, r, r, paint)
        val gap = 3f * density
        when (mode) {
            ACCENT -> {
                if (strong) {
                    // Selected button: tinted block with a solid accent outline, no extra bars.
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2f * density
                    paint.color = accent
                    rect.set(left + density, top + density, right - density, bottom - density)
                    canvas.drawRoundRect(rect, r, r, paint)
                    paint.style = Paint.Style.FILL
                    return
                }
                // Short accent bar along the top, clear of the rounded corners.
                val h = (if (strong) 5f else 4f) * density
                paint.color = accent
                rect.set(left + r, top, left + r + (b.width() - 2 * r) * 0.45f, top + h)
                canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
                paint.color = blend(accent, Color.WHITE, 0.35f)
                rect.set(rect.right + gap, top, rect.right + gap + 10f * density, top + h)
                canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
            }
            TOPBAR -> {
                val h = 6f * density
                val start = left + r
                val end = right - r
                val w = end - start
                paint.color = accent
                canvas.drawRect(start, top, start + w * 0.45f, top + h, paint)
                paint.color = second
                canvas.drawRect(start + w * 0.45f + gap, top, start + w * 0.75f, top + h, paint)
                paint.color = blend(accent, Color.WHITE, 0.35f)
                rect.set(start + w * 0.75f + gap, top, end, top + h)
                canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
            }
            else -> drawElbow(canvas, left, top, right, bottom, r, gap)
        }
    }

    private fun drawElbow(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, r: Float, gap: Float) {
        val width = right - left
        val bar = ELBOW_LEFT_DP * density
        val strip = ELBOW_TOP_DP * density
        val outer = 20f * density
        val inner = 8f * density
        clip.reset()
        clip.addRoundRect(RectF(left, top, right, bottom), r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        // Accent block with a rounded outer corner, hollowed out to leave the bent bar.
        val elbowEnd = left + width * 0.40f
        paint.color = accent
        canvas.save()
        canvas.clipRect(left, top, elbowEnd, bottom)
        canvas.drawRoundRect(left, top, elbowEnd + outer, bottom + outer, outer, outer, paint)
        canvas.restore()
        paint.color = fill
        canvas.drawRoundRect(left + bar, top + strip, elbowEnd + inner * 4, bottom + inner * 4, inner, inner, paint)
        canvas.restore()
        // Rest of the top bar: second colour with the reference number, then a rounded cap.
        val seg1Start = elbowEnd + gap
        val seg1End = left + width * 0.72f
        paint.color = second
        canvas.drawRect(seg1Start, top, seg1End, top + strip, paint)
        paint.color = blend(accent, Color.WHITE, 0.35f)
        rect.set(seg1End + gap, top, right - r * 0.5f, top + strip)
        canvas.drawRoundRect(rect, strip / 2f, strip / 2f, paint)
        text.textSize = 7.5f * density
        val textWidth = text.measureText(code)
        if (seg1End - seg1Start > textWidth + 8f * density) {
            canvas.drawText(code, seg1End - textWidth - 4f * density, top + strip - 2f * density, text)
        }
    }

    private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Obsidian page background: near-black graphite with slow flowing contour lines and two
 * very soft light pools (lime and ion blue) that drift underneath.
 */
class ObsidianBackdrop(private val base: Int, private val lineA: Int, private val lineB: Int) : Drawable(), Animatable {
    private val basePaint = Paint()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val matrix = Matrix()
    private var glowA: RadialGradient? = null
    private var glowB: RadialGradient? = null
    private var glowRadius = 0f
    private var running = false
    private val tick = Runnable {
        invalidateSelf()
        if (running) scheduleNext()
    }

    private fun scheduleNext() {
        if (callback == null) { running = false; return }
        scheduleSelf(tick, SystemClock.uptimeMillis() + 60L)
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        basePaint.color = base
        canvas.drawRect(bounds, basePaint)
        val t = SystemClock.uptimeMillis() / 1000f

        val radius = max(w, h) * 0.5f
        if (radius != glowRadius) {
            glowRadius = radius
            glowA = RadialGradient(0f, 0f, radius, intArrayOf(LivingIconView.withAlpha(lineA, 46), LivingIconView.withAlpha(lineA, 0)), null, Shader.TileMode.CLAMP)
            glowB = RadialGradient(0f, 0f, radius, intArrayOf(LivingIconView.withAlpha(lineB, 52), LivingIconView.withAlpha(lineB, 0)), null, Shader.TileMode.CLAMP)
        }
        fun pool(shader: RadialGradient?, x: Float, y: Float) {
            shader ?: return
            matrix.setTranslate(x, y)
            shader.setLocalMatrix(matrix)
            glowPaint.shader = shader
            canvas.drawCircle(x, y, radius, glowPaint)
        }
        pool(glowA, bounds.left + w * (0.2f + 0.15f * sin(t * 0.11f)), bounds.top + h * (0.15f + 0.1f * cos(t * 0.09f)))
        pool(glowB, bounds.left + w * (0.85f + 0.1f * cos(t * 0.08f)), bounds.top + h * (0.85f + 0.1f * sin(t * 0.1f)))

        // Flowing contour lines: each a slow sine ribbon, coloured from lime to blue.
        val lines = 9
        val density = max(1f, w / 400f)
        linePaint.strokeWidth = 1.2f * density
        for (i in 0 until lines) {
            val f = i / (lines - 1f)
            val baseY = bounds.top + h * (0.08f + 0.84f * f)
            val amp = h * (0.035f + 0.02f * sin(t * 0.21f + i))
            val k = 6.2832f / w * (1.1f + 0.25f * f)
            val shift = t * (0.22f + 0.05f * i)
            path.reset()
            var x = 0f
            val step = max(8f, w / 60f)
            while (x <= w + step) {
                val y = baseY + amp * sin(x * k + shift + i * 0.9f) + amp * 0.4f * sin(x * k * 2.3f - shift * 1.4f)
                if (x == 0f) path.moveTo(bounds.left + x, y) else path.lineTo(bounds.left + x, y)
                x += step
            }
            val color = LivingIconView.mixColors(lineA, lineB, f)
            val alpha = (22 + 20 * (0.5f + 0.5f * sin(t * 0.6f + i * 0.7f))).toInt()
            linePaint.color = LivingIconView.withAlpha(color, alpha)
            canvas.drawPath(path, linePaint)
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

    override fun setAlpha(alpha: Int) { linePaint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { linePaint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * Chronograph card: a watch dial. Dark radial "sunburst" from the top centre, a double bezel
 * rim (outer hairline in the accent, inner fainter line) and fine graduations along the
 * bottom inside the rim. Accent tiles get a tinted face; strong ones a brighter rim.
 */
class ChronoCardDrawable(
    private val accent: Int,
    private val face: Int,
    private val density: Float,
    private val radiusDp: Float = 20f,
    private val tinted: Boolean = false,
    private val strong: Boolean = false,
    private val ticks: Boolean = true
) : Drawable() {
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private var shaderFor: android.graphics.Rect? = null

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val r = radiusDp * density
        if (shaderFor != b) {
            shaderFor = android.graphics.Rect(b)
            val centre = if (tinted) LivingIconView.mixColors(face, accent, if (strong) 0.30f else 0.12f) else LivingIconView.mixColors(face, Color.WHITE, 0.045f)
            val edge = LivingIconView.mixColors(face, Color.BLACK, 0.55f)
            facePaint.shader = RadialGradient(b.exactCenterX(), b.top.toFloat(), maxOf(b.width(), b.height()) * 0.95f,
                intArrayOf(LivingIconView.withAlpha(centre, 244), LivingIconView.withAlpha(edge, 244)), null, Shader.TileMode.CLAMP)
        }
        rect.set(b)
        canvas.drawRoundRect(rect, r, r, facePaint)
        // Outer bezel line.
        val outer = 1f * density
        rimPaint.strokeWidth = outer
        rimPaint.color = LivingIconView.withAlpha(accent, if (strong) 235 else if (tinted) 150 else 105)
        rect.set(b.left + outer / 2, b.top + outer / 2, b.right - outer / 2, b.bottom - outer / 2)
        canvas.drawRoundRect(rect, r, r, rimPaint)
        // Inner bezel line.
        val inset = 3.5f * density
        rimPaint.strokeWidth = maxOf(1f, 0.6f * density)
        rimPaint.color = LivingIconView.withAlpha(accent, if (strong) 110 else 38)
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        canvas.drawRoundRect(rect, maxOf(0f, r - inset), maxOf(0f, r - inset), rimPaint)
        // Graduations along the bottom, like a bezel scale.
        if (ticks && b.width() > 80 * density) {
            val left = b.left + r + 4 * density
            val right = b.right - r - 4 * density
            val y = b.bottom - inset - 3f * density
            val count = ((right - left) / (7f * density)).toInt().coerceIn(6, 60)
            for (i in 0..count) {
                val x = left + (right - left) * i / count
                val major = i % 5 == 0
                tickPaint.strokeWidth = if (major) 1.2f * density else 0.8f * density
                tickPaint.color = LivingIconView.withAlpha(accent, if (major) 80 else 38)
                canvas.drawLine(x, y, x, y - (if (major) 4f else 2.2f) * density, tickPaint)
            }
        }
    }

    override fun setAlpha(alpha: Int) { facePaint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { facePaint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Blueprint page background: still dark navy with a fine millimetre grid (no animation). */
class BlueprintGridBackdrop(private val base: Int, private val line: Int, private val density: Float) : Drawable() {
    private val basePaint = Paint()
    private val minor = Paint().apply { strokeWidth = 1f }
    private val major = Paint().apply { strokeWidth = 1f }

    override fun draw(canvas: Canvas) {
        val b = bounds
        basePaint.color = base
        canvas.drawRect(b, basePaint)
        minor.color = LivingIconView.withAlpha(line, 13)
        major.color = LivingIconView.withAlpha(line, 30)
        val step = 12f * density
        var i = 0
        var x = b.left.toFloat()
        while (x <= b.right) {
            canvas.drawLine(x, b.top.toFloat(), x, b.bottom.toFloat(), if (i % 5 == 0) major else minor)
            x += step; i++
        }
        i = 0
        var y = b.top.toFloat()
        while (y <= b.bottom) {
            canvas.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, if (i % 5 == 0) major else minor)
            y += step; i++
        }
    }

    override fun setAlpha(alpha: Int) { basePaint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { basePaint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * Blueprint card: a sheet in a drawing. Square corners, a fine full outline and heavier corner
 * marks like the registration corners of a drawing frame. Accent cards get accent corners and a
 * faint tint; strong ones a solid accent edge on the left, like a selected row in a parts list.
 */
class BlueprintCardDrawable(
    private val line: Int,
    private val accent: Int?,
    private val fill: Int,
    private val density: Float,
    private val strong: Boolean = false
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val r = 2.5f * density
        val face = if (accent != null) LivingIconView.mixColors(fill, accent, if (strong) 0.18f else 0.06f) else fill
        fillPaint.color = LivingIconView.withAlpha(face, 240)
        rect.set(b)
        canvas.drawRoundRect(rect, r, r, fillPaint)
        val w = 1f * density
        linePaint.strokeWidth = w
        linePaint.color = LivingIconView.withAlpha(accent ?: line, if (strong) 200 else 70)
        rect.set(b.left + w / 2, b.top + w / 2, b.right - w / 2, b.bottom - w / 2)
        canvas.drawRoundRect(rect, r, r, linePaint)
        // Heavier corner marks.
        val cw = 2f * density
        val k = minOf(12f * density, b.width() / 4f, b.height() / 4f)
        cornerPaint.strokeWidth = cw
        cornerPaint.color = LivingIconView.withAlpha(accent ?: line, 220)
        val l = b.left + cw / 2; val t = b.top + cw / 2; val rt = b.right - cw / 2; val bt = b.bottom - cw / 2
        canvas.drawLine(l, t, l + k, t, cornerPaint); canvas.drawLine(l, t, l, t + k, cornerPaint)
        canvas.drawLine(rt, t, rt - k, t, cornerPaint); canvas.drawLine(rt, t, rt, t + k, cornerPaint)
        canvas.drawLine(l, bt, l + k, bt, cornerPaint); canvas.drawLine(l, bt, l, bt - k, cornerPaint)
        canvas.drawLine(rt, bt, rt - k, bt, cornerPaint); canvas.drawLine(rt, bt, rt, bt - k, cornerPaint)
        if (strong && accent != null) {
            fillPaint.color = accent
            canvas.drawRect(b.left.toFloat(), b.top + k + 2 * density, b.left + 3 * density, b.bottom - k - 2 * density, fillPaint)
        }
    }

    override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { fillPaint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Cartridge page background: still charcoal with a slanted speaker grille in the corner. */
class CartridgeBackdrop(private val base: Int, private val line: Int, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val slot = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    override fun draw(canvas: Canvas) {
        val b = bounds
        paint.shader = android.graphics.LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            LivingIconView.mixColors(base, Color.WHITE, 0.03f), LivingIconView.mixColors(base, Color.BLACK, 0.3f), Shader.TileMode.CLAMP)
        canvas.drawRect(b, paint)
        // Speaker grille: six slanted slots, very faint.
        slot.strokeWidth = 5f * density
        slot.color = LivingIconView.withAlpha(line, 16)
        val gap = 13f * density
        val len = 46f * density
        val x0 = b.right - 40f * density
        val y0 = b.bottom - 40f * density
        for (i in 0 until 6) {
            val ox = x0 - i * gap
            canvas.drawLine(ox, y0, ox + len * 0.5f, y0 - len * 0.87f, slot)
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * Cartridge card: a game cartridge in modern form. Rounded plastic shell with the top-right
 * corner cut off, a darker lip along the bottom for depth, a faint top highlight. Accent cards get
 * two short label stripes; strong ones a tinted face and an accent edge.
 */
class CartridgeCardDrawable(
    private val fill: Int,
    private val accent: Int?,
    private val stripe2: Int,
    private val density: Float,
    private val strong: Boolean = false
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()

    private fun shell(l: Float, t: Float, r: Float, b: Float) {
        val rad = minOf(10f * density, (b - t) / 3f)
        val notch = minOf(12f * density, (b - t) / 3f, (r - l) / 4f)
        path.reset()
        path.moveTo(l + rad, t)
        path.lineTo(r - notch, t)
        path.lineTo(r, t + notch)
        path.lineTo(r, b - rad)
        path.quadTo(r, b, r - rad, b)
        path.lineTo(l + rad, b)
        path.quadTo(l, b, l, b - rad)
        path.lineTo(l, t + rad)
        path.quadTo(l, t, l + rad, t)
        path.close()
    }

    override fun draw(canvas: Canvas) {
        val bb = bounds
        if (bb.width() <= 0 || bb.height() <= 0) return
        val l = bb.left.toFloat(); val t = bb.top.toFloat(); val r = bb.right.toFloat(); val b = bb.bottom.toFloat()
        val lip = 3f * density
        val face = if (accent != null) LivingIconView.mixColors(fill, accent, if (strong) 0.22f else 0.07f) else fill
        // Lip.
        shell(l, t + lip, r, b)
        paint.shader = null
        paint.color = LivingIconView.mixColors(face, Color.BLACK, 0.5f)
        canvas.drawPath(path, paint)
        // Face.
        shell(l, t, r, b - lip)
        paint.shader = android.graphics.LinearGradient(0f, t, 0f, b, LivingIconView.mixColors(face, Color.WHITE, 0.04f), face, Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        paint.shader = null
        edge.strokeWidth = density
        edge.color = if (strong && accent != null) LivingIconView.withAlpha(accent, 200) else LivingIconView.withAlpha(Color.WHITE, 18)
        canvas.drawPath(path, edge)
        if (accent != null && bb.height() > 40 * density) {
            // Two short label stripes in the top-left corner.
            val sx = l + 12f * density; val sy = t + 7f * density
            paint.color = LivingIconView.withAlpha(accent, 220)
            canvas.drawRoundRect(sx, sy, sx + 22f * density, sy + 3f * density, 1.5f * density, 1.5f * density, paint)
            paint.color = LivingIconView.withAlpha(stripe2, 170)
            canvas.drawRoundRect(sx + 25f * density, sy, sx + 34f * density, sy + 3f * density, 1.5f * density, 1.5f * density, paint)
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Deep Sea page background: still, near-black navy fading down, lit by two faint glows
 * (turquoise upper left, violet lower right) and a few tiny specks, like light in deep water.
 */
class AbyssBackdrop(private val base: Int, private val glowA: Int, private val glowB: Int, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specks = List(26) { i -> floatArrayOf(((i * 37) % 100) / 100f, ((i * 61 + 13) % 100) / 100f, 0.6f + (i % 4) * 0.35f) }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat(); val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        paint.shader = android.graphics.LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            LivingIconView.mixColors(base, glowA, 0.06f), LivingIconView.mixColors(base, Color.BLACK, 0.45f), Shader.TileMode.CLAMP)
        canvas.drawRect(b, paint)
        fun glow(x: Float, y: Float, r: Float, color: Int, alpha: Int) {
            paint.shader = android.graphics.RadialGradient(x, y, r,
                intArrayOf(LivingIconView.withAlpha(color, alpha), LivingIconView.withAlpha(color, 0)), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, r, paint)
        }
        glow(b.left + w * 0.12f, b.top + h * 0.1f, maxOf(w, h) * 0.55f, glowA, 34)
        glow(b.left + w * 0.95f, b.top + h * 0.9f, maxOf(w, h) * 0.5f, glowB, 30)
        paint.shader = null
        for (s in specks) {
            paint.color = LivingIconView.withAlpha(if (s[2] > 1.2f) glowA else Color.WHITE, 26)
            canvas.drawCircle(b.left + s[0] * w, b.top + s[1] * h, s[2] * density, paint)
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * Deep Sea card: a dark, softly rounded pane with depth — slightly lighter at the top, a soft
 * glow in its upper right corner in the card's colour, and a thin light line along the top
 * edge that fades out to the sides. Strong cards glow more and get a coloured edge.
 */
class AbyssCardDrawable(
    private val fill: Int,
    private val accent: Int,
    private val edge: Int,
    private val density: Float,
    private val radiusDp: Float = 20f,
    private val strong: Boolean = false,
    private val tinted: Boolean = false
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val r = minOf(radiusDp * density, b.height() / 2f)
        rect.set(b)
        val face = if (strong) LivingIconView.mixColors(fill, accent, 0.16f) else if (tinted) LivingIconView.mixColors(fill, accent, 0.06f) else fill
        paint.shader = android.graphics.LinearGradient(0f, rect.top, 0f, rect.bottom,
            LivingIconView.mixColors(face, Color.WHITE, 0.05f), LivingIconView.mixColors(face, Color.BLACK, 0.18f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, r, r, paint)
        // Inner glow in the upper right corner.
        canvas.save()
        path.reset(); path.addRoundRect(rect, r, r, Path.Direction.CW); canvas.clipPath(path)
        val gr = maxOf(rect.width(), rect.height()) * 0.7f
        paint.shader = android.graphics.RadialGradient(rect.right - rect.width() * 0.12f, rect.top, gr,
            intArrayOf(LivingIconView.withAlpha(accent, if (strong) 70 else 34), LivingIconView.withAlpha(accent, 0)), null, Shader.TileMode.CLAMP)
        canvas.drawRect(rect, paint)
        canvas.restore()
        paint.shader = null
        // Edge.
        line.strokeWidth = (if (strong) 1.5f else 1f) * density
        line.shader = null
        line.color = if (strong) LivingIconView.withAlpha(accent, 190) else LivingIconView.withAlpha(edge, 150)
        val inset = line.strokeWidth / 2
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        canvas.drawRoundRect(rect, r, r, line)
        // Light along the top edge, fading out to the sides.
        line.strokeWidth = 1.2f * density
        line.shader = android.graphics.LinearGradient(rect.left + r, 0f, rect.right - r, 0f,
            intArrayOf(LivingIconView.withAlpha(Color.WHITE, 0), LivingIconView.withAlpha(Color.WHITE, 70), LivingIconView.withAlpha(accent, 90), LivingIconView.withAlpha(Color.WHITE, 0)),
            floatArrayOf(0f, 0.4f, 0.7f, 1f), Shader.TileMode.CLAMP)
        canvas.drawLine(rect.left + r, rect.top + inset, rect.right - r, rect.top + inset, line)
        line.shader = null
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Hi-Fi page background: still, dark brushed metal (fine horizontal grain) with an 80s
 * stripe band across the top in yellow, orange, red and plum.
 */
class HiFiBackdrop(private val base: Int, private val density: Float) : Drawable() {
    private val paint = Paint()
    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        paint.shader = android.graphics.LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            LivingIconView.mixColors(base, Color.WHITE, 0.05f), LivingIconView.mixColors(base, Color.BLACK, 0.35f), Shader.TileMode.CLAMP)
        canvas.drawRect(b, paint)
        paint.shader = null
        // Brushed grain.
        var y = b.top.toFloat()
        var i = 0
        while (y < b.bottom) {
            paint.color = if (i % 3 == 0) Color.argb(9, 255, 255, 255) else Color.argb(10, 0, 0, 0)
            canvas.drawRect(b.left.toFloat(), y, b.right.toFloat(), y + density, paint)
            y += 2f * density; i++
        }
        // 80s stripes.
        val colors = intArrayOf(Color.rgb(255, 204, 64), Color.rgb(255, 140, 40), Color.rgb(232, 70, 52), Color.rgb(150, 44, 78))
        val sh = 3f * density
        colors.forEachIndexed { k, c ->
            paint.color = c
            canvas.drawRect(b.left.toFloat(), b.top + k * sh, b.right.toFloat(), b.top + (k + 1) * sh, paint)
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * Hi-Fi card: a front panel of an 80s stereo component. Brushed dark metal with a bevel
 * (light top edge, dark bottom edge), small screws in the corners of larger panels and a
 * status LED in the upper right that lights up in the card's colour. Strong panels get an
 * engraved inner frame in the accent and a lit LED.
 */
class HiFiPanelDrawable(
    private val fill: Int,
    private val accent: Int?,
    private val density: Float,
    private val radiusDp: Float = 5f,
    private val strong: Boolean = false
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val d = density
        val r = radiusDp * d
        rect.set(b)
        // Body with a dark bottom bevel.
        paint.color = Color.rgb(8, 8, 7)
        canvas.drawRoundRect(rect, r, r, paint)
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom - 1.5f * d)
        paint.shader = android.graphics.LinearGradient(0f, rect.top, 0f, rect.bottom,
            LivingIconView.mixColors(fill, Color.WHITE, 0.09f), LivingIconView.mixColors(fill, Color.BLACK, 0.12f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.shader = null
        // Brushed grain.
        canvas.save()
        canvas.clipRect(rect.left + r / 2, rect.top + d, rect.right - r / 2, rect.bottom - d)
        var y = rect.top
        var i = 0
        while (y < rect.bottom) {
            paint.color = if (i % 2 == 0) Color.argb(8, 255, 255, 255) else Color.argb(8, 0, 0, 0)
            canvas.drawRect(rect.left, y, rect.right, y + d, paint)
            y += 1.6f * d; i++
        }
        canvas.restore()
        // Top highlight.
        paint.color = Color.argb(40, 255, 255, 255)
        canvas.drawRect(rect.left + r, rect.top, rect.right - r, rect.top + d, paint)
        val big = b.height() > 70 * d && b.width() > 110 * d
        if (big) {
            // Screws.
            val sr = 2.6f * d
            val m = 7f * d
            for ((x, yy) in listOf(rect.left + m to rect.top + m, rect.right - m to rect.top + m, rect.left + m to rect.bottom - m, rect.right - m to rect.bottom - m)) {
                paint.color = Color.rgb(20, 19, 18); canvas.drawCircle(x, yy, sr, paint)
                paint.color = Color.rgb(92, 88, 82); canvas.drawCircle(x, yy, sr * 0.75f, paint)
                line.strokeWidth = 0.9f * d; line.color = Color.rgb(30, 28, 26)
                canvas.drawLine(x - sr * 0.6f, yy - sr * 0.3f, x + sr * 0.6f, yy + sr * 0.3f, line)
            }
        }
        if (accent != null) {
            // Status LED.
            val lx = rect.right - (if (big) 18f else 9f) * d
            val ly = rect.top + (if (big) 7f else 6f) * d
            paint.shader = android.graphics.RadialGradient(lx, ly, 6f * d,
                LivingIconView.withAlpha(accent, if (strong) 150 else 80), LivingIconView.withAlpha(accent, 0), Shader.TileMode.CLAMP)
            canvas.drawCircle(lx, ly, 6f * d, paint)
            paint.shader = null
            paint.color = if (strong) LivingIconView.mixColors(accent, Color.WHITE, 0.35f) else accent
            canvas.drawCircle(lx, ly, 1.8f * d, paint)
        }
        if (strong && accent != null) {
            line.strokeWidth = 1.2f * d
            line.color = LivingIconView.withAlpha(accent, 170)
            val ins = 3f * d
            rect.set(b.left + ins, b.top + ins, b.right - ins, b.bottom - 1.5f * d - ins)
            canvas.drawRoundRect(rect, r * 0.6f, r * 0.6f, line)
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Hi-Fi slider track: a row of LED segments like a VU meter (green, then amber, red at the
 * end). [lit] = all segments in full colour (used as the progress layer, which is clipped to
 * the value); otherwise the dim, unlit row.
 */
class LedSegmentDrawable(private val lit: Boolean, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val gap = 2f * density
        val segW = 5f * density
        val count = ((b.width() + gap) / (segW + gap)).toInt().coerceAtLeast(1)
        val step = b.width().toFloat() / count
        for (i in 0 until count) {
            val f = i / (count - 1f).coerceAtLeast(1f)
            val color = when {
                f > 0.86f -> Color.rgb(255, 72, 56)
                f > 0.66f -> Color.rgb(255, 178, 48)
                else -> Color.rgb(120, 222, 110)
            }
            paint.color = if (lit) color else LivingIconView.withAlpha(color, 40)
            val x = b.left + i * step
            canvas.drawRoundRect(x, b.top.toFloat(), x + step - gap, b.bottom.toFloat(), density, density, paint)
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Hi-Fi slider knob: a mixing-desk fader cap — brushed silver block with a black centre line. */
class FaderCapDrawable(private val accent: Int, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    override fun draw(canvas: Canvas) {
        val b = bounds
        val d = density
        rect.set(b.left + 1f * d, b.top.toFloat(), b.right - 1f * d, b.bottom.toFloat())
        paint.color = Color.argb(120, 0, 0, 0)
        canvas.drawRoundRect(rect.left, rect.top + 2 * d, rect.right, rect.bottom, 3 * d, 3 * d, paint)
        rect.bottom -= 2f * d
        paint.shader = android.graphics.LinearGradient(rect.left, 0f, rect.right, 0f,
            intArrayOf(Color.rgb(150, 146, 140), Color.rgb(222, 218, 210), Color.rgb(132, 128, 122)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, 3 * d, 3 * d, paint)
        paint.shader = null
        // Grip lines and the lit centre line.
        paint.color = Color.argb(70, 0, 0, 0)
        for (k in listOf(-0.28f, 0.28f)) canvas.drawRect(rect.left + 2 * d, rect.centerY() + k * rect.height() - 0.5f * d, rect.right - 2 * d, rect.centerY() + k * rect.height() + 0.5f * d, paint)
        paint.color = accent
        canvas.drawRect(rect.left + 1.5f * d, rect.centerY() - 1f * d, rect.right - 1.5f * d, rect.centerY() + 1f * d, paint)
    }
    override fun getIntrinsicWidth(): Int = (16 * density).toInt()
    override fun getIntrinsicHeight(): Int = (30 * density).toInt()
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Hologram page background: still, near-black with a faint hexagon lattice, a soft projector
 * glow from the top and small crosshair marks in the corners of the screen.
 */
class HudBackdrop(private val base: Int, private val line: Int, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hex = Path()
    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat(); val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        paint.shader = null
        paint.color = base
        canvas.drawRect(b, paint)
        paint.shader = android.graphics.RadialGradient(b.left + w / 2, b.top.toFloat(), maxOf(w, h) * 0.75f,
            LivingIconView.withAlpha(line, 40), LivingIconView.withAlpha(line, 0), Shader.TileMode.CLAMP)
        canvas.drawRect(b, paint)
        paint.shader = null
        // Hexagon lattice (pointy-top), very faint.
        val r = 16f * density
        val hw = (Math.sqrt(3.0) * r).toFloat()
        stroke.strokeWidth = density * 0.8f
        stroke.color = LivingIconView.withAlpha(line, 16)
        var row = 0
        var y = b.top.toFloat()
        while (y < b.bottom + r) {
            var x = b.left - (if (row % 2 == 0) 0f else hw / 2)
            while (x < b.right + hw) {
                hex.reset()
                for (i in 0 until 6) {
                    val a = Math.toRadians(60.0 * i - 90.0)
                    val px = x + r * Math.cos(a).toFloat(); val py = y + r * Math.sin(a).toFloat()
                    if (i == 0) hex.moveTo(px, py) else hex.lineTo(px, py)
                }
                hex.close()
                canvas.drawPath(hex, stroke)
                x += hw
            }
            y += r * 1.5f; row++
        }
        // Corner crosshairs.
        stroke.strokeWidth = density * 1.2f
        stroke.color = LivingIconView.withAlpha(line, 110)
        val m = 10f * density; val k = 14f * density
        for ((cx, cy) in listOf(b.left + m to b.top + m, b.right - m to b.top + m, b.left + m to b.bottom - m, b.right - m to b.bottom - m)) {
            canvas.drawLine(cx - k / 2, cy, cx + k / 2, cy, stroke)
            canvas.drawLine(cx, cy - k / 2, cx, cy + k / 2, stroke)
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * Hologram panel: no rounded corners at all — a projected plate with cut corners (a large cut
 * top-left and bottom-right, small ones on the other two), a see-through tinted fill, a thin
 * outline, bright brackets along the big cuts and a row of data ticks under larger panels.
 * Strong panels glow brighter and get a tinted fill.
 */
class HudPanelDrawable(
    private val fill: Int,
    private val accent: Int,
    private val density: Float,
    private val strong: Boolean = false,
    private val tinted: Boolean = false
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val d = density
        val l = b.left + 0.5f * d; val t = b.top + 0.5f * d; val r = b.right - 0.5f * d; val btm = b.bottom - 0.5f * d
        val big = minOf(14f * d, b.height() / 3f, b.width() / 4f)
        val small = minOf(5f * d, big)
        path.reset()
        path.moveTo(l + big, t); path.lineTo(r - small, t); path.lineTo(r, t + small)
        path.lineTo(r, btm - big); path.lineTo(r - big, btm); path.lineTo(l + small, btm)
        path.lineTo(l, btm - small); path.lineTo(l, t + big); path.close()
        val face = if (strong) LivingIconView.mixColors(fill, accent, 0.2f) else if (tinted) LivingIconView.mixColors(fill, accent, 0.08f) else fill
        paint.shader = android.graphics.LinearGradient(0f, t, 0f, btm,
            LivingIconView.withAlpha(face, 215), LivingIconView.withAlpha(face, 160), Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        paint.shader = null
        // Outline.
        line.strokeWidth = d
        line.color = LivingIconView.withAlpha(accent, if (strong) 190 else 80)
        canvas.drawPath(path, line)
        // Bright brackets along the big cuts.
        line.strokeWidth = 2.2f * d
        line.color = LivingIconView.withAlpha(accent, if (strong) 255 else 210)
        val k = big * 0.9f
        canvas.drawLine(l, t + big + k, l, t + big, line); canvas.drawLine(l, t + big, l + big, t, line); canvas.drawLine(l + big, t, l + big + k, t, line)
        canvas.drawLine(r, btm - big - k, r, btm - big, line); canvas.drawLine(r, btm - big, r - big, btm, line); canvas.drawLine(r - big, btm, r - big - k, btm, line)
        // Data ticks under larger panels.
        if (b.height() > 60 * d && b.width() > 100 * d) {
            line.strokeWidth = 1.4f * d
            line.color = LivingIconView.withAlpha(accent, 120)
            val y = btm - 4f * d
            for (i in 0 until 6) {
                val x = l + small + 8f * d + i * 5f * d
                canvas.drawLine(x, y - (if (i % 3 == 0) 4f else 2f) * d, x, y, line)
            }
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Hologram button: a slanted plate (parallelogram). Filled buttons are solid light; others are
 * a dark see-through plate with an outline in [edge].
 */
class HudButtonDrawable(
    private val fill: Int,
    private val edge: Int,
    private val filled: Boolean,
    private val density: Float
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val d = density
        val skew = minOf(9f * d, b.width() / 6f)
        val l = b.left + 0.6f * d; val t = b.top + 0.6f * d; val r = b.right - 0.6f * d; val btm = b.bottom - 0.6f * d
        path.reset()
        path.moveTo(l + skew, t); path.lineTo(r, t); path.lineTo(r - skew, btm); path.lineTo(l, btm); path.close()
        if (filled) {
            paint.shader = android.graphics.LinearGradient(0f, t, 0f, btm, LivingIconView.mixColors(fill, Color.WHITE, 0.25f), fill, Shader.TileMode.CLAMP)
            canvas.drawPath(path, paint)
            paint.shader = null
        } else {
            paint.color = LivingIconView.withAlpha(fill, 200)
            canvas.drawPath(path, paint)
        }
        line.strokeWidth = (if (filled) 1f else 1.2f) * d
        line.color = if (filled) LivingIconView.withAlpha(Color.WHITE, 90) else edge
        canvas.drawPath(path, line)
        // A short bright tick on the leading edge.
        line.strokeWidth = 2.4f * d
        line.color = if (filled) LivingIconView.withAlpha(Color.WHITE, 160) else LivingIconView.withAlpha(edge, 255)
        canvas.drawLine(l + skew, t, l + skew + minOf(14f * d, b.width() / 5f), t, line)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Hologram slider track: a hairline with ruler ticks; [lit] draws it bright (progress layer). */
class HudTrackDrawable(private val color: Int, private val lit: Boolean, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val d = density
        val cy = b.exactCenterY()
        paint.color = if (lit) color else LivingIconView.withAlpha(color, 60)
        canvas.drawRect(b.left.toFloat(), cy - (if (lit) 1.5f else 0.75f) * d, b.right.toFloat(), cy + (if (lit) 1.5f else 0.75f) * d, paint)
        if (lit) {
            paint.color = LivingIconView.withAlpha(color, 50)
            canvas.drawRect(b.left.toFloat(), cy - 4f * d, b.right.toFloat(), cy + 4f * d, paint)
        }
        paint.color = if (lit) color else LivingIconView.withAlpha(color, 90)
        val step = 8f * d
        var x = b.left.toFloat()
        var i = 0
        while (x <= b.right) {
            val hgt = (if (i % 5 == 0) 5f else 2.5f) * d
            canvas.drawRect(x, cy + 2.5f * d, x + d, cy + 2.5f * d + hgt, paint)
            x += step; i++
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Hologram slider knob: a diamond outline with a bright core. */
class HudThumbDrawable(private val color: Int, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        val r = minOf(b.width(), b.height()) / 2f - density
        path.reset(); path.moveTo(cx, cy - r); path.lineTo(cx + r, cy); path.lineTo(cx, cy + r); path.lineTo(cx - r, cy); path.close()
        paint.color = Color.argb(220, 2, 10, 14)
        canvas.drawPath(path, paint)
        line.strokeWidth = 2f * density
        line.color = color
        canvas.drawPath(path, line)
        val c = r * 0.35f
        path.reset(); path.moveTo(cx, cy - c); path.lineTo(cx + c, cy); path.lineTo(cx, cy + c); path.lineTo(cx - c, cy); path.close()
        paint.color = color
        canvas.drawPath(path, paint)
    }
    override fun getIntrinsicWidth(): Int = (26 * density).toInt()
    override fun getIntrinsicHeight(): Int = (26 * density).toInt()
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Bauhaus page background: still, near-black with a few large geometric figures in the three
 * primary colours, very faint — a circle, a quarter circle, a triangle and bars.
 */
class BauhausBackdrop(private val base: Int, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat(); val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        paint.color = base
        canvas.drawRect(b, paint)
        val red = Color.rgb(230, 57, 45); val yellow = Color.rgb(255, 200, 0); val blue = Color.rgb(36, 100, 220)
        // Big circle, upper right.
        paint.color = LivingIconView.withAlpha(blue, 22)
        canvas.drawCircle(b.left + w * 0.88f, b.top + h * 0.12f, minOf(w, h) * 0.38f, paint)
        // Quarter circle, lower left.
        paint.color = LivingIconView.withAlpha(red, 20)
        val qr = minOf(w, h) * 0.45f
        rect.set(b.left - qr, b.bottom - qr, b.left + qr, b.bottom + qr)
        canvas.drawArc(rect, 270f, 90f, true, paint)
        // Triangle, middle right.
        paint.color = LivingIconView.withAlpha(yellow, 16)
        path.reset()
        path.moveTo(b.right.toFloat(), b.top + h * 0.45f)
        path.lineTo(b.right - w * 0.28f, b.top + h * 0.7f)
        path.lineTo(b.right.toFloat(), b.top + h * 0.95f)
        path.close()
        canvas.drawPath(path, paint)
        // Bars at the top left.
        paint.color = LivingIconView.withAlpha(yellow, 34)
        canvas.drawRect(b.left + 14 * density, b.top + 12 * density, b.left + 80 * density, b.top + 16 * density, paint)
        paint.color = LivingIconView.withAlpha(red, 34)
        canvas.drawRect(b.left + 14 * density, b.top + 20 * density, b.left + 48 * density, b.top + 24 * density, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * Bauhaus card: a flat, square block (no rounding, no shadow). Coloured cards get a solid
 * quarter circle in their colour in the upper right corner and a thick bar along the left;
 * strong cards a full coloured top band as well.
 */
class BauhausCardDrawable(
    private val fill: Int,
    private val accent: Int?,
    private val density: Float,
    private val strong: Boolean = false
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val d = density
        paint.color = fill
        canvas.drawRect(b, paint)
        val a = accent ?: return
        canvas.save()
        canvas.clipRect(b)
        val qr = minOf(b.height() * 0.42f, 34f * d)
        paint.color = LivingIconView.withAlpha(a, if (strong) 255 else 200)
        rect.set(b.right - qr, b.top - qr, b.right + qr, b.top + qr)
        canvas.drawArc(rect, 90f, 90f, true, paint)
        paint.color = a
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.left + (if (strong) 6f else 4f) * d, b.bottom.toFloat(), paint)
        if (strong) canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right - qr, b.top + 3f * d, paint)
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/** Bauhaus slider knob: a solid red circle with a black core ring. */
class BauhausThumbDrawable(private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        val r = minOf(b.width(), b.height()) / 2f
        paint.color = Color.rgb(230, 57, 45); canvas.drawCircle(cx, cy, r, paint)
        paint.color = Color.rgb(16, 16, 16); canvas.drawCircle(cx, cy, r * 0.42f, paint)
        paint.color = Color.rgb(255, 200, 0); canvas.drawCircle(cx, cy, r * 0.2f, paint)
    }
    override fun getIntrinsicWidth(): Int = (26 * density).toInt()
    override fun getIntrinsicHeight(): Int = (26 * density).toInt()
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Jade page background: still, deep green-black, lighter towards the top, with faint
 * topographic contour lines like a map of a forested valley.
 */
class JadeBackdrop(private val base: Int, private val line: Int, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat(); val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        paint.shader = android.graphics.RadialGradient(b.left + w * 0.3f, b.top + h * 0.15f, maxOf(w, h),
            LivingIconView.mixColors(base, line, 0.12f), LivingIconView.mixColors(base, Color.BLACK, 0.4f), Shader.TileMode.CLAMP)
        canvas.drawRect(b, paint)
        paint.shader = null
        // Contours: closed wavy rings around two centres.
        stroke.strokeWidth = density
        for ((cx, cy, n) in listOf(Triple(0.78f, 0.3f, 7), Triple(0.18f, 0.82f, 6))) {
            for (k in 1..n) {
                val ring = k * 26f * density
                path.reset()
                for (i in 0..72) {
                    val a = i / 72f * 2f * Math.PI.toFloat()
                    val r = ring * (1f + 0.08f * kotlin.math.sin(a * 3f + k) + 0.05f * kotlin.math.cos(a * 5f - k * 0.7f))
                    val x = b.left + cx * w + r * kotlin.math.cos(a) * 1.25f
                    val y = b.top + cy * h + r * kotlin.math.sin(a)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                stroke.color = LivingIconView.withAlpha(line, if (k % 3 == 0) 26 else 13)
                canvas.drawPath(path, stroke)
            }
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * Jade card: a leaf-shaped pane — large rounding top-left and bottom-right, small on the other
 * corners — in deep green with a soft light from the upper left and a fine edge. Coloured
 * cards carry a thin vein of their colour along the top; strong ones a brighter edge.
 */
class JadeCardDrawable(
    private val fill: Int,
    private val accent: Int,
    private val density: Float,
    private val strong: Boolean = false,
    private val tinted: Boolean = false,
    private val radiusDp: Float = 22f
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val rect = RectF()
    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val d = density
        val big = minOf(radiusDp * d, b.height() / 2f, b.width() / 2f)
        val small = minOf(6f * d, big)
        rect.set(b.left + 0.5f * d, b.top + 0.5f * d, b.right - 0.5f * d, b.bottom - 0.5f * d)
        path.reset()
        path.addRoundRect(rect, floatArrayOf(big, big, small, small, big, big, small, small), Path.Direction.CW)
        val face = if (strong) LivingIconView.mixColors(fill, accent, 0.18f) else if (tinted) LivingIconView.mixColors(fill, accent, 0.06f) else fill
        paint.shader = android.graphics.LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
            LivingIconView.mixColors(face, Color.WHITE, 0.05f), LivingIconView.mixColors(face, Color.BLACK, 0.2f), Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        paint.shader = null
        line.strokeWidth = (if (strong) 1.5f else 1f) * d
        line.color = LivingIconView.withAlpha(accent, if (strong) 180 else 60)
        canvas.drawPath(path, line)
        if (tinted || strong) {
            line.strokeWidth = 2f * d
            line.color = LivingIconView.withAlpha(accent, if (strong) 230 else 150)
            canvas.drawLine(rect.left + big, rect.top + 4f * d, rect.left + big + minOf(rect.width() * 0.3f, 60f * d), rect.top + 4f * d, line)
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Jade slider knob: a leaf (rounded square turned 45°) with a light core. */
class JadeThumbDrawable(private val color: Int, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        val r = minOf(b.width(), b.height()) / 2f - density
        path.reset()
        path.addRoundRect(RectF(cx - r * 0.72f, cy - r * 0.72f, cx + r * 0.72f, cy + r * 0.72f),
            floatArrayOf(r, r, r * 0.2f, r * 0.2f, r, r, r * 0.2f, r * 0.2f), Path.Direction.CW)
        canvas.save(); canvas.rotate(45f, cx, cy)
        paint.color = color; canvas.drawPath(path, paint)
        canvas.restore()
        paint.color = Color.argb(150, 255, 255, 255); canvas.drawCircle(cx, cy, r * 0.22f, paint)
    }
    override fun getIntrinsicWidth(): Int = (28 * density).toInt()
    override fun getIntrinsicHeight(): Int = (28 * density).toInt()
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
