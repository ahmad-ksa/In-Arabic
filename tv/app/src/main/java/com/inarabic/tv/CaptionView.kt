// Draws the Arabic subtitle over the game: a box over the original English line (where the phone found it)
// with the translation inside, or at the bottom centre when no position is known.

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
	var scale = 1f
		set(v) { field = v; invalidate() }
	var cover = true
		set(v) { field = v; invalidate() }
	var opacity = 0.85f
		set(v) { field = v; invalidate() }

	private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.WHITE
		typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
		setShadowLayer(4f, 0f, 2f, Color.BLACK)
	}
	private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)

	override fun onDraw(canvas: Canvas)
	{
		val t = text ?: return
		if(t.isBlank()) return
		val w = width.toFloat()
		val h = height.toFloat()
		textPaint.textSize = h * 0.042f * scale
		val b = box
		val area = if(b != null && cover)
		{
			val padX = w * 0.015f
			val padY = h * 0.012f
			RectF(b[0] * w - padX, b[1] * h - padY, (b[0] + b[2]) * w + padX, (b[1] + b[3]) * h + padY)
		}
		else RectF(w * 0.1f, h * 0.78f, w * 0.9f, h * 0.92f)
		// the Arabic line may need more room than the English one: widen to at least 60 % of the screen
		val minW = w * 0.6f
		if(area.width() < minW)
		{
			val cx = area.centerX().coerceIn(minW / 2, w - minW / 2)
			area.left = cx - minW / 2; area.right = cx + minW / 2
		}
		val inner = (area.width() - w * 0.02f).toInt().coerceAtLeast(50)
		val layout = StaticLayout.Builder.obtain(t, 0, t.length, textPaint, inner)
			.setAlignment(Layout.Alignment.ALIGN_CENTER)
			.setTextDirection(TextDirectionHeuristics.RTL)
			.setLineSpacing(0f, 1.1f)
			.build()
		val need = layout.height + h * 0.024f
		if(area.height() < need)
		{
			// grow upward, keep the bottom edge (subtitles sit at the bottom)
			area.top = max(0f, area.bottom - need)
			if(area.height() < need) area.bottom = min(h, area.top + need)
		}
		boxPaint.color = Color.argb((opacity * 255).toInt(), 0, 0, 0)
		canvas.drawRoundRect(area, h * 0.01f, h * 0.01f, boxPaint)
		canvas.save()
		canvas.translate(area.centerX() - inner / 2f, area.centerY() - layout.height / 2f)
		layout.draw(canvas)
		canvas.restore()
	}
}
