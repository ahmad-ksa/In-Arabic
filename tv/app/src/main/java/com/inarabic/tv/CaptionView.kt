// Draws over the game: the Arabic subtitle (over the original English line, or at a fixed place), the second
// box (side texts such as objectives, drawn where they are in the picture) and a panel (story summary).

package com.inarabic.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.view.View
import kotlin.math.max
import kotlin.math.min

class CaptionView(context: Context) : View(context)
{
	var text: String? = null
		set(v) { field = v; invalidate() }
	/** original subtitle box, 0..1 of the screen: x, y, w, h */
	var box: FloatArray? = null
		set(v) { field = v; invalidate() }
	var box2Text: String? = null
		set(v) { field = v; invalidate() }
	var box2: FloatArray? = null
		set(v) { field = v; invalidate() }
	var panelTitle: String? = null
	var panelText: String? = null
		set(v) { field = v; invalidate() }

	var scale = 1f
	var cover = true
	var opacity = 0.85f
	var color = Color.WHITE
	var position = "auto"
	var bold = true
	fun styleChanged() = invalidate()

	private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(4f, 0f, 2f, Color.BLACK) }
	private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)

	private fun layout(t: String, width: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_CENTER) =
		StaticLayout.Builder.obtain(t, 0, t.length, textPaint, width)
			.setAlignment(align).setTextDirection(TextDirectionHeuristics.RTL).setLineSpacing(0f, 1.1f).build()

	override fun onDraw(canvas: Canvas)
	{
		val w = width.toFloat()
		val h = height.toFloat()
		textPaint.color = color
		textPaint.typeface = Typeface.create(Typeface.DEFAULT, if(bold) Typeface.BOLD else Typeface.NORMAL)
		boxPaint.color = Color.argb((opacity * 255).toInt(), 0, 0, 0)

		// second box: in its own area of the picture, smaller text, top aligned
		val b2t = box2Text
		val b2 = box2
		if(!b2t.isNullOrBlank() && b2 != null)
		{
			textPaint.textSize = h * 0.028f * scale
			val area = RectF(b2[0] * w, b2[1] * h, (b2[0] + b2[2]) * w, (b2[1] + b2[3]) * h)
			val inner = (area.width() - w * 0.016f).toInt().coerceAtLeast(50)
			val l = layout(b2t, inner, Layout.Alignment.ALIGN_NORMAL)
			area.bottom = max(area.bottom, area.top + l.height + h * 0.02f)
			canvas.drawRoundRect(area, h * 0.01f, h * 0.01f, boxPaint)
			canvas.save(); canvas.translate(area.left + w * 0.008f, area.top + h * 0.01f); l.draw(canvas); canvas.restore()
		}

		// subtitle
		val t = text
		if(!t.isNullOrBlank())
		{
			textPaint.textSize = h * 0.042f * scale
			val b = box
			val area = when
			{
				position == "top" -> RectF(w * 0.1f, h * 0.05f, w * 0.9f, h * 0.17f)
				position == "auto" && b != null && cover ->
					RectF(b[0] * w - w * 0.015f, b[1] * h - h * 0.012f, (b[0] + b[2]) * w + w * 0.015f, (b[1] + b[3]) * h + h * 0.012f)
				else -> RectF(w * 0.1f, h * 0.78f, w * 0.9f, h * 0.92f)
			}
			// the Arabic line may need more room than the English one: widen to at least 60 % of the screen
			val minW = w * 0.6f
			if(area.width() < minW)
			{
				val cx = area.centerX().coerceIn(minW / 2, w - minW / 2)
				area.left = cx - minW / 2; area.right = cx + minW / 2
			}
			val inner = (area.width() - w * 0.02f).toInt().coerceAtLeast(50)
			val l = layout(t, inner)
			val need = l.height + h * 0.024f
			if(area.height() < need)
			{
				if(position == "top") area.bottom = area.top + need
				else
				{
					// grow upward, keep the bottom edge (subtitles sit at the bottom)
					area.top = max(0f, area.bottom - need)
					if(area.height() < need) area.bottom = min(h, area.top + need)
				}
			}
			canvas.drawRoundRect(area, h * 0.01f, h * 0.01f, boxPaint)
			canvas.save(); canvas.translate(area.centerX() - inner / 2f, area.centerY() - l.height / 2f); l.draw(canvas); canvas.restore()
		}

		// panel (story summary): centred card
		val pt = panelText
		if(!pt.isNullOrBlank())
		{
			val area = RectF(w * 0.18f, h * 0.12f, w * 0.82f, h * 0.88f)
			val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 10, 25, 41) }
			canvas.drawRoundRect(area, h * 0.02f, h * 0.02f, p)
			val inner = (area.width() - w * 0.06f).toInt()
			textPaint.color = Color.WHITE
			textPaint.textSize = h * 0.045f
			val title = layout(panelTitle ?: "", inner)
			textPaint.textSize = h * 0.032f
			val body = layout(pt, inner, Layout.Alignment.ALIGN_NORMAL)
			canvas.save(); canvas.translate(area.left + w * 0.03f, area.top + h * 0.04f); textPaint.textSize = h * 0.045f; title.draw(canvas)
			canvas.translate(0f, title.height + h * 0.03f); textPaint.textSize = h * 0.032f; body.draw(canvas); canvas.restore()
		}
	}
}
