// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Same drawing as the TV app (tv/.../CaptionView.kt): the phone shows the translation exactly where the TV does.
// Draws over the game: the Arabic subtitle (over the original English line, or at a fixed place), the second
// box (side texts such as objectives, drawn where they are in the picture) and a panel (story summary).

package com.inarabic.caption

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
	/** texts found off to the side (mission title, button prompts): each drawn over its English one */
	class Side(val text: String, val box: FloatArray)
	var sides: List<Side> = emptyList()
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

	private val timers = android.os.Handler(android.os.Looper.getMainLooper())
	private val hide = Runnable { text = null }
	private val hidePanel = Runnable { panelText = null }
	private val hideSide = Runnable { sides = emptyList() }

	/** A message from the phone (see the phone's LinkServer for the format). */
	fun handle(o: org.json.JSONObject)
	{
		fun box(o: org.json.JSONObject) = o.optJSONArray("box")?.takeIf { it.length() == 4 }?.let { b -> FloatArray(4) { b.optDouble(it).toFloat() } }
		when(o.optString("t"))
		{
			"sub" ->
			{
				box = box(o)
				text = o.optString("ar")
				// safety: never leave a line on screen forever if the phone disappears
				timers.removeCallbacks(hide); timers.postDelayed(hide, 15000)
			}
			"clear" -> { timers.removeCallbacks(hide); text = null }
			"box2" -> { box2 = box(o); box2Text = o.optString("ar") }
			"box2clear" -> box2Text = null
			"side" ->
			{
				val items = o.optJSONArray("items")
				sides = if(items == null) emptyList() else (0 until items.length()).mapNotNull { i ->
					val it = items.optJSONObject(i) ?: return@mapNotNull null
					val b = box(it) ?: return@mapNotNull null
					it.optString("ar").takeIf { a -> a.isNotBlank() }?.let { a -> Side(a, b) }
				}
				timers.removeCallbacks(hideSide); timers.postDelayed(hideSide, 15000)
			}
			"sideclear" -> { timers.removeCallbacks(hideSide); sides = emptyList() }
			"panel" ->
			{
				panelTitle = o.optString("title")
				panelText = o.optString("text")
				timers.removeCallbacks(hidePanel); timers.postDelayed(hidePanel, o.optLong("ttl", 30000))
			}
			"style" ->
			{
				scale = o.optDouble("scale", 1.0).toFloat()
				cover = o.optBoolean("cover", true)
				opacity = o.optDouble("opacity", 0.85).toFloat()
				color = try { Color.parseColor(o.optString("color", "#FFFFFF")) } catch(e: Exception) { Color.WHITE }
				position = o.optString("pos", "auto")
				bold = o.optBoolean("bold", true)
				invalidate()
			}
		}
	}

	/** a sample line, to check size and place */
	fun test()
	{
		box = floatArrayOf(0.25f, 0.84f, 0.5f, 0.06f)
		text = "هذا سطر تجريبي للترجمة العربية فوق اللعبة"
		timers.removeCallbacks(hide); timers.postDelayed(hide, 6000)
	}

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

		// side texts: each exactly over its English text (a bit wider if the Arabic needs it), kept on screen
		for(sd in sides)
		{
			val sb = sd.box
			textPaint.textSize = (sb[3] * h * 0.8f).coerceIn(h * 0.02f, h * 0.045f) * scale
			val area = RectF(sb[0] * w - w * 0.004f, sb[1] * h - h * 0.004f, (sb[0] + sb[2]) * w + w * 0.004f, (sb[1] + sb[3]) * h + h * 0.004f)
			val tw = textPaint.measureText(sd.text) + w * 0.012f
			if(area.width() < tw)
			{
				// grow toward the middle of the screen: right-side prompts grow left, left-side ones grow right
				if(area.centerX() > w / 2) area.left = max(0f, area.right - tw) else area.right = min(w, area.left + tw)
			}
			val inner = (area.width() - w * 0.008f).toInt().coerceAtLeast(30)
			val l = layout(sd.text, inner)
			val need = l.height + h * 0.008f
			if(area.height() < need) { val cy = area.centerY(); area.top = cy - need / 2; area.bottom = cy + need / 2 }
			if(area.bottom > h) area.offset(0f, h - area.bottom)
			if(area.top < 0) area.offset(0f, -area.top)
			canvas.drawRoundRect(area, h * 0.006f, h * 0.006f, boxPaint)
			canvas.save(); canvas.translate(area.centerX() - inner / 2f, area.centerY() - l.height / 2f); l.draw(canvas); canvas.restore()
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
			// the Arabic line may need more room than the English one: widen only as much as it needs (max 80 %)
			val minW = min(w * 0.8f, textPaint.measureText(t) + w * 0.03f)
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
