package ar.chamullo.app

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import ar.chamullo.core.Svg

/**
 * ICEBREAK's line icons (24×24, stroke 2, from its icon set), drawn without any library: the core reads the SVG
 * ([Svg]) and this turns it into an Android path. Same look as ICEBREAK's top bar and chat.
 */
object Icons {
    private val markup = mapOf(
        "usuarios" to """<circle cx="10" cy="8" r="3.6"/><path d="M3 20c0-3.8 3-6 7-6 1 0 1.9.15 2.7.4"/><circle cx="17" cy="16" r="3"/><path d="m19.2 18.2 2.3 2.3"/>""",
        "chat" to """<path d="M4 4.5h10.5a2 2 0 0 1 2 2V12a2 2 0 0 1-2 2H9l-4 3v-3H4a2 2 0 0 1-2-2V6.5a2 2 0 0 1 2-2z"/><path d="M19 8.5h.5a2 2 0 0 1 2 2V15a2 2 0 0 1-2 2H19v2.5L16 17h-3.5"/><path d="M6 9.3h.01M9.3 9.3h.01M12.6 9.3h.01"/>""",
        "apps" to """<rect x="3" y="3" width="7.5" height="7.5" rx="2"/><rect x="13.5" y="3" width="7.5" height="7.5" rx="2"/><rect x="3" y="13.5" width="7.5" height="7.5" rx="2"/><path d="M17.25 13.5v7.5M13.5 17.25H21"/>""",
        "guia" to """<path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v15H6.5A2.5 2.5 0 0 0 4 20.5z"/><path d="M4 20.5A2.5 2.5 0 0 0 6.5 23H20v-5"/><path d="M8.5 7.5h7M8.5 11h5"/>""",
        "gear" to """<circle cx="12" cy="12" r="3"/><path d="M19.4 13.5a7.6 7.6 0 0 0 0-3l2-1.6-2-3.4-2.4 1a7.5 7.5 0 0 0-2.6-1.5L14 2.5h-4l-.4 2.5A7.5 7.5 0 0 0 7 6.5l-2.4-1-2 3.4 2 1.6a7.6 7.6 0 0 0 0 3l-2 1.6 2 3.4 2.4-1a7.5 7.5 0 0 0 2.6 1.5l.4 2.5h4l.4-2.5a7.5 7.5 0 0 0 2.6-1.5l2.4 1 2-3.4z"/>""",
        "star" to """<path d="m12 3 2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z"/>""",
        "trash" to """<path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/>""",
        "phone" to """<path d="M5 3.5h3.5l1.5 5-2.2 1.4a11 11 0 0 0 6.3 6.3l1.4-2.2 5 1.5V19a2 2 0 0 1-2 2A16.5 16.5 0 0 1 3 5.5a2 2 0 0 1 2-2z"/>""",
        "video" to """<rect x="2.5" y="6" width="13" height="12" rx="2.5"/><path d="m15.5 10.5 6-3.5v10l-6-3.5"/>""",
        "clip" to """<path d="m20 11.5-8.2 8.2a5 5 0 0 1-7.1-7.1L13 4.3a3.3 3.3 0 0 1 4.7 4.7l-8.2 8.3a1.7 1.7 0 0 1-2.4-2.4l7.6-7.6"/>""",
        "camera" to """<path d="M3 8.5A2.5 2.5 0 0 1 5.5 6h2l1.5-2h6l1.5 2h2A2.5 2.5 0 0 1 21 8.5v9a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 17.5z"/><circle cx="12" cy="13" r="3.6"/>""",
        "send" to """<path d="M21 3 10 14"/><path d="M21 3 14.5 21l-4.5-7-7-4.5z"/>""",
        "back" to """<path d="M20 12H5M11 6l-6 6 6 6"/>""",
        "up" to """<path d="m6 15 6-6 6 6"/>""",
        "down" to """<path d="m6 9 6 6 6-6"/>""",
        "image" to """<rect x="5" y="3" width="16" height="13" rx="2"/><path d="M3 7v12a2 2 0 0 0 2 2h12"/><path d="m8 13 3.2-3.5 2.3 2.3L15.6 9.6 19 13"/><circle cx="16.5" cy="6.5" r="1.2"/>""",
        "map" to """<path d="m3 6 6-2.5 6 2.5 6-2.5v14.5L15 20.5l-6-2.5-6 2.5z"/><path d="M9 3.5V18M15 6v14.5"/>""",
        "perfil" to """<circle cx="12" cy="8" r="4"/><path d="M4 21c0-4.4 3.6-7 8-7s8 2.6 8 7"/>""",
        "download" to """<path d="M12 4v11M7.5 10.5 12 15l4.5-4.5M5 19.5h14"/>""",
        "plus" to """<path d="M12 5v14M5 12h14"/>"""
    )

    private val parsed = HashMap<String, Path>()

    private fun path(key: String): Path = parsed.getOrPut(key) {
        Path().apply {
            for (shape in Svg.parse(markup.getValue(key))) for (op in shape.ops) when (op) {
                is Svg.Op.Move -> moveTo(op.x, op.y)
                is Svg.Op.Line -> lineTo(op.x, op.y)
                is Svg.Op.Cubic -> cubicTo(op.x1, op.y1, op.x2, op.y2, op.x, op.y)
                Svg.Op.Close -> close()
            }
        }
    }

    /** An icon as a drawable of the given color; [filled] paints the inside too (the favorite star). */
    fun of(key: String, color: Int, filled: Boolean = false): Drawable = object : Drawable() {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = if (filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
            strokeWidth = 2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; this.color = color
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            canvas.scale(b.width() / 24f, b.height() / 24f)
            canvas.drawPath(path(key), stroke)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) { stroke.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { stroke.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
