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
import android.text.style.RelativeSizeSpan

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
        val spoken = cue.activeWord(timeMs)
        val active = if (style.highlightWord || style.anim == CaptionAnim.GROW) spoken else -1
        val visible = if (style.anim == CaptionAnim.WORDS && spoken >= 0) spoken else Int.MAX_VALUE
        // Pop-in: grow from 75% to full size during the first 160 ms, in 4 steps.
        val popStep = if (style.anim == CaptionAnim.POP) ((timeMs - cue.startMs).coerceIn(0, 160) / 40).toInt() else 4
        val key = "${cue.startMs}|${cue.text}|$active|$visible|$popStep|$style"
        if (key == lastKey) return lastBitmap
        lastKey = key
        lastBitmap = draw(cue, active, visible, 0.75f + 0.25f * popStep / 4f, style)
        return lastBitmap
    }

    private fun draw(cue: Cue, active: Int, visible: Int, scale: Float, style: CaptionStyle): Bitmap {
        val textPx = minOf(videoW, videoH) * style.sizePct
        val maxWidth = (videoW * 0.86f).toInt()

        val words = if (cue.textOverride != null) listOf(cue.text) else cue.words.map { it.text }
        val shown = words.map { if (style.uppercase) it.uppercase() else it }
        val full = shown.joinToString(" ")
        val fill = SpannableString(full)
        val stroke = SpannableString(full)
        var pos = 0
        shown.forEachIndexed { i, w ->
            val end = pos + w.length
            if (i > visible) {
                fill.setSpan(ForegroundColorSpan(Color.TRANSPARENT), pos, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                stroke.setSpan(ForegroundColorSpan(Color.TRANSPARENT), pos, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (i == active) {
                if (style.highlightWord) fill.setSpan(ForegroundColorSpan(style.highlightColor), pos, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (style.anim == CaptionAnim.GROW) {
                    fill.setSpan(RelativeSizeSpan(1.18f), pos, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    stroke.setSpan(RelativeSizeSpan(1.18f), pos, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            pos = end + 1
        }

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textPx
            typeface = style.font.typeface()
            color = style.textColor
        }
        // Width is measured with the growing word at full size, so lines don't jump while it grows.
        val desired = (StaticLayout.getDesiredWidth(full, paint) * (if (style.anim == CaptionAnim.GROW) 1.1f else 1f)).toInt() + 2
        val layoutW = minOf(desired, maxWidth)
        fun layoutOf(text: CharSequence, p: TextPaint) = StaticLayout.Builder.obtain(text, 0, text.length, p, layoutW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
        val layout = layoutOf(fill, paint)

        val pad = (textPx * 0.4f).toInt()
        val w = layout.width + pad * 2
        val h = layout.height + pad * 2
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale, w / 2f, h / 2f)

        when (style.look) {
            CaptionLook.BOX -> {
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 0, 0, 0) }
                c.drawRoundRect(RectF(pad * 0.3f, pad * 0.3f, w - pad * 0.3f, h - pad * 0.3f), textPx * 0.25f, textPx * 0.25f, bg)
                c.save(); c.translate(pad.toFloat(), pad.toFloat()); layout.draw(c); c.restore()
            }
            CaptionLook.OUTLINE -> {
                // A black stroke copy first, then the coloured text on top.
                val strokePaint = TextPaint(paint).apply {
                    this.style = Paint.Style.STROKE
                    strokeWidth = textPx * 0.16f
                    strokeJoin = Paint.Join.ROUND
                    color = Color.BLACK
                }
                c.save(); c.translate(pad.toFloat(), pad.toFloat())
                layoutOf(stroke, strokePaint).draw(c); layout.draw(c)
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
