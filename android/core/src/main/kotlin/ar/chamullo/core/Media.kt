package ar.chamullo.core

/**
 * Attachments as sealed letters (Packet Format §5.6): a photo, a video, a file, a location, a contact or a voice note.
 * They travel like any letter, hand to hand, so they have a ceiling: [MAX_BYTES] keeps them from clogging the islands.
 */
object Media {
    const val PHOTO = 1
    const val VIDEO = 2
    const val FILE = 3
    const val LOCATION = 4
    const val CONTACT = 5
    const val VOICE = 6
    const val MAX_BYTES = 3 * 1024 * 1024

    /** What a chat list or a notification shows for an attachment. */
    fun summary(kind: Int, name: String): String = when (kind) {
        PHOTO -> "📷 Foto"
        VIDEO -> "🎬 Video"
        LOCATION -> "📍 Ubicación"
        CONTACT -> "👤 Contacto: $name"
        VOICE -> "🎤 Nota de voz"
        else -> "📎 $name"
    }
}

/** An attachment as the chat keeps it; its bytes live apart, in the store, under the message id. */
data class MediaRef(val kind: Int, val name: String, val mime: String, val size: Int)

/** Draws ICEBREAK's line icons: reads its SVG (paths, circles, rounded boxes) into moves, lines and curves. */
object Svg {
    sealed interface Op {
        data class Move(val x: Float, val y: Float) : Op
        data class Line(val x: Float, val y: Float) : Op
        data class Cubic(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : Op
        data object Close : Op
    }

    class Shape(val ops: List<Op>)

    private val element = Regex("""<(path|circle|rect)\b([^>]*)/?>""")
    private val attr = Regex("""([a-z-]+)="([^"]*)"""")
    private val number = Regex("""[-+]?(?:\d*\.\d+|\d+\.?\d*)(?:[eE][-+]?\d+)?""")
    private const val K = 0.5522848f // a quarter circle as a cubic

    fun parse(markup: String): List<Shape> = element.findAll(markup).mapNotNull { m ->
        val a = attr.findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
        fun f(k: String) = a[k]?.toFloatOrNull() ?: 0f
        when (m.groupValues[1]) {
            "path" -> a["d"]?.let { Shape(path(it)) }
            "circle" -> Shape(roundBox(f("cx") - f("r"), f("cy") - f("r"), 2 * f("r"), 2 * f("r"), f("r")))
            else -> Shape(roundBox(f("x"), f("y"), f("width"), f("height"), a["rx"]?.toFloatOrNull() ?: 0f))
        }
    }.toList()

    private fun roundBox(x: Float, y: Float, w: Float, h: Float, r0: Float): List<Op> {
        val r = minOf(r0, w / 2, h / 2); val c = r * K
        return listOf(
            Op.Move(x + r, y), Op.Line(x + w - r, y), Op.Cubic(x + w - r + c, y, x + w, y + r - c, x + w, y + r),
            Op.Line(x + w, y + h - r), Op.Cubic(x + w, y + h - r + c, x + w - r + c, y + h, x + w - r, y + h),
            Op.Line(x + r, y + h), Op.Cubic(x + r - c, y + h, x, y + h - r + c, x, y + h - r),
            Op.Line(x, y + r), Op.Cubic(x, y + r - c, x + r - c, y, x + r, y), Op.Close
        )
    }

    private fun path(d: String): List<Op> {
        val ops = ArrayList<Op>()
        val tokens = Regex("""[MmLlHhVvCcSsQqAaZz]|""" + number.pattern).findAll(d).map { it.value }.toList()
        var i = 0; var cmd = 'M'
        var x = 0f; var y = 0f; var sx = 0f; var sy = 0f; var cx2 = 0f; var cy2 = 0f
        fun n(): Float = tokens[i++].toFloat()
        while (i < tokens.size) {
            if (tokens[i][0].isLetter()) cmd = tokens[i++][0]
            val rel = cmd.isLowerCase()
            val bx = if (rel) x else 0f; val by = if (rel) y else 0f
            when (cmd.uppercaseChar()) {
                'M' -> { x = bx + n(); y = by + n(); sx = x; sy = y; ops += Op.Move(x, y); cmd = if (rel) 'l' else 'L' }
                'L' -> { x = bx + n(); y = by + n(); ops += Op.Line(x, y) }
                'H' -> { x = (if (rel) x else 0f) + n(); ops += Op.Line(x, y) }
                'V' -> { y = (if (rel) y else 0f) + n(); ops += Op.Line(x, y) }
                'C' -> {
                    val x1 = bx + n(); val y1 = by + n(); cx2 = bx + n(); cy2 = by + n(); x = bx + n(); y = by + n()
                    ops += Op.Cubic(x1, y1, cx2, cy2, x, y)
                }
                'S' -> {
                    val prev = ops.lastOrNull() as? Op.Cubic
                    val x1 = if (prev != null) 2 * x - cx2 else x; val y1 = if (prev != null) 2 * y - cy2 else y
                    cx2 = bx + n(); cy2 = by + n(); x = bx + n(); y = by + n()
                    ops += Op.Cubic(x1, y1, cx2, cy2, x, y)
                }
                'Q' -> {
                    val qx = bx + n(); val qy = by + n(); val ex = bx + n(); val ey = by + n()
                    ops += Op.Cubic(x + 2f / 3 * (qx - x), y + 2f / 3 * (qy - y), ex + 2f / 3 * (qx - ex), ey + 2f / 3 * (qy - ey), ex, ey)
                    x = ex; y = ey
                }
                'A' -> {
                    val rx = n(); val ry = n(); val rot = n(); val large = n() != 0f; val sweep = n() != 0f
                    val ex = bx + n(); val ey = by + n()
                    ops += arc(x, y, rx, ry, rot, large, sweep, ex, ey)
                    x = ex; y = ey
                }
                'Z' -> { ops += Op.Close; x = sx; y = sy }
                else -> i++
            }
        }
        return ops
    }

    // SVG endpoint arc → center form → cubic segments of at most 90° (SVG 1.1, appendix F.6).
    private fun arc(x1: Float, y1: Float, rx0: Float, ry0: Float, rotDeg: Float, large: Boolean, sweep: Boolean, x2: Float, y2: Float): List<Op> {
        if (rx0 == 0f || ry0 == 0f) return listOf(Op.Line(x2, y2))
        val phi = Math.toRadians(rotDeg.toDouble()); val cosP = Math.cos(phi); val sinP = Math.sin(phi)
        val dx = (x1 - x2) / 2.0; val dy = (y1 - y2) / 2.0
        val xp = cosP * dx + sinP * dy; val yp = -sinP * dx + cosP * dy
        var rx = Math.abs(rx0.toDouble()); var ry = Math.abs(ry0.toDouble())
        val lam = (xp * xp) / (rx * rx) + (yp * yp) / (ry * ry)
        if (lam > 1) { rx *= Math.sqrt(lam); ry *= Math.sqrt(lam) }
        val num = rx * rx * ry * ry - rx * rx * yp * yp - ry * ry * xp * xp
        val den = rx * rx * yp * yp + ry * ry * xp * xp
        var coef = Math.sqrt(Math.max(0.0, num / den))
        if (large == sweep) coef = -coef
        val cxp = coef * rx * yp / ry; val cyp = -coef * ry * xp / rx
        val cx = cosP * cxp - sinP * cyp + (x1 + x2) / 2.0; val cy = sinP * cxp + cosP * cyp + (y1 + y2) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val a = Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy); return a
        }
        val t1 = ang(1.0, 0.0, (xp - cxp) / rx, (yp - cyp) / ry)
        var dt = ang((xp - cxp) / rx, (yp - cyp) / ry, (-xp - cxp) / rx, (-yp - cyp) / ry)
        if (!sweep && dt > 0) dt -= 2 * Math.PI else if (sweep && dt < 0) dt += 2 * Math.PI
        val segs = Math.ceil(Math.abs(dt) / (Math.PI / 2)).toInt().coerceAtLeast(1)
        val step = dt / segs
        val out = ArrayList<Op>()
        var t = t1
        for (s in 0 until segs) {
            val k = 4.0 / 3 * Math.tan(step / 4)
            fun pt(a: Double) = Pair(cx + rx * Math.cos(a) * cosP - ry * Math.sin(a) * sinP, cy + rx * Math.cos(a) * sinP + ry * Math.sin(a) * cosP)
            fun dv(a: Double) = Pair(-rx * Math.sin(a) * cosP - ry * Math.cos(a) * sinP, -rx * Math.sin(a) * sinP + ry * Math.cos(a) * cosP)
            val (ax, ay) = pt(t); val (bxx, byy) = pt(t + step); val (dax, day) = dv(t); val (dbx, dby) = dv(t + step)
            out += Op.Cubic((ax + k * dax).toFloat(), (ay + k * day).toFloat(), (bxx - k * dbx).toFloat(), (byy - k * dby).toFloat(),
                if (s == segs - 1) x2 else bxx.toFloat(), if (s == segs - 1) y2 else byy.toFloat())
            t += step
        }
        return out
    }
}
