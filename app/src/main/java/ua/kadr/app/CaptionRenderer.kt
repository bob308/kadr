package ua.kadr.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan

/**
 * Draws one subtitle into a bitmap sized in video pixels. The same bitmap is used
 * for the on-screen preview and for burning into the exported video, so both match.
 */
class CaptionRenderer(private val videoW: Int, private val videoH: Int) {
    private val empty: Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    private var lastKey: String? = null
    private var lastBitmap: Bitmap = empty

    fun render(cue: Cue?, timeMs: Long, style: CaptionStyle): Bitmap {
        if (cue == null || cue.text.isBlank()) { lastKey = null; return empty }
        val active = if (style.highlightWord) cue.activeWord(timeMs) else -1
        val key = "${cue.startMs}|${cue.text}|$active|$style"
        if (key == lastKey) return lastBitmap
        lastKey = key
        lastBitmap = draw(cue, active, style)
        return lastBitmap
    }

    private fun draw(cue: Cue, active: Int, style: CaptionStyle): Bitmap {
        val textPx = minOf(videoW, videoH) * style.sizePct
        val maxWidth = (videoW * 0.86f).toInt()

        val words = if (cue.textOverride != null) listOf(cue.text) else cue.words.map { it.text }
        val shown = words.map { if (style.uppercase) it.uppercase() else it }
        val full = shown.joinToString(" ")
        val span = SpannableString(full)
        if (active >= 0) {
            var pos = 0
            shown.forEachIndexed { i, w ->
                if (i == active) span.setSpan(ForegroundColorSpan(style.highlightColor), pos, pos + w.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                pos += w.length + 1
            }
        }

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textPx
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = style.textColor
        }
        val desired = StaticLayout.getDesiredWidth(full, paint).toInt() + 1
        val layoutW = minOf(desired, maxWidth)
        val layout = StaticLayout.Builder.obtain(span, 0, span.length, paint, layoutW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(false)
            .build()

        val pad = (textPx * 0.35f).toInt()
        val w = layout.width + pad * 2
        val h = layout.height + pad * 2
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        when (style.look) {
            CaptionLook.BOX -> {
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 0, 0, 0) }
                c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), textPx * 0.25f, textPx * 0.25f, bg)
                c.save(); c.translate(pad.toFloat(), pad.toFloat()); layout.draw(c); c.restore()
            }
            CaptionLook.OUTLINE -> {
                // Draw a black stroke version first, then the coloured text on top.
                val strokePaint = TextPaint(paint).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = textPx * 0.16f
                    strokeJoin = Paint.Join.ROUND
                    color = Color.BLACK
                }
                val strokeLayout = StaticLayout.Builder.obtain(full, 0, full.length, strokePaint, layoutW)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
                c.save(); c.translate(pad.toFloat(), pad.toFloat())
                strokeLayout.draw(c); layout.draw(c)
                c.restore()
            }
            CaptionLook.PLAIN -> {
                paint.setShadowLayer(textPx * 0.12f, 0f, textPx * 0.05f, Color.argb(220, 0, 0, 0))
                c.save(); c.translate(pad.toFloat(), pad.toFloat()); layout.draw(c); c.restore()
            }
        }
        return bmp
    }
}
