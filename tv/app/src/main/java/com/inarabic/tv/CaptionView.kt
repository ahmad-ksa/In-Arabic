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

class CaptionView @JvmOverloads constructor(context: Context, attrs: android.util.AttributeSet? = null) : View(context, attrs)
{
	var text: String? = null
		set(v) { field = v; invalidate() }
	/** coloured stretches of [text]: start, end, colour (the game's coloured words) */
	var textSpans: List<IntArray> = emptyList()
	/** original subtitle box, 0..1 of the screen: x, y, w, h */
	var box: FloatArray? = null
		set(v) { field = v; invalidate() }
	var box2Text: String? = null
		set(v) { field = v; invalidate() }
	var box2: FloatArray? = null
		set(v) { field = v; invalidate() }
	/** texts found off to the side (mission title, button prompts): each drawn over its English one */
	class Side(val text: String, val box: FloatArray, val spans: List<IntArray> = emptyList())
	var sides: List<Side> = emptyList()
		set(v) { field = v; invalidate() }
	/** the reading frame being placed from the phone */
	var zone: FloatArray? = null
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
	var outline = false
	var boxColor = Color.BLACK
	/** TV picture fit (from the phone): the TV enlarges the HDMI picture by [zoom] and moves it by [dy] */
	var zoom = 0f
	var dy = 0f
	/** false on the phone: its picture is the stream itself */
	var fitToTv = true

	/** the box from the phone (0..1 of the PS5 picture) in 0..1 of this screen */
	private fun fit(b: FloatArray): FloatArray
	{
		if(!fitToTv || (zoom == 0f && dy == 0f)) return b
		val k = 1f + zoom
		val o = b.copyOf()
		o[0] = 0.5f + (b[0] - 0.5f) * k; o[2] = b[2] * k
		o[1] = 0.5f + (b[1] - 0.5f) * k + dy; o[3] = b[3] * k
		if(o.size > 4) o[4] = b[4] * k
		return o
	}

	/** text size: the English line's own height when known, else a default share of the screen */
	private fun sizeFor(b: FloatArray?, h: Float, def: Float, lo: Float, hi: Float) =
		(if(b != null && b.size > 4 && b[4] > 0f) (b[4] * h * 0.95f).coerceIn(h * lo, h * hi) else h * def) * scale
	fun styleChanged() = invalidate()

	private val timers = android.os.Handler(android.os.Looper.getMainLooper())
	private val hide = Runnable { text = null }
	private val hidePanel = Runnable { panelText = null }
	private val hideSide = Runnable { sides = emptyList() }
	private val hideZone = Runnable { zone = null }

	/** A message from the phone (see the phone's LinkServer for the format). */
	fun handle(o: org.json.JSONObject)
	{
		fun spans(o: org.json.JSONObject): List<IntArray>
		{
			val a = o.optJSONArray("spans") ?: return emptyList()
			return (0 until a.length()).mapNotNull { i ->
				val x = a.optJSONArray(i) ?: return@mapNotNull null
				val c = try { Color.parseColor(x.optString(2)) } catch(e: Exception) { return@mapNotNull null }
				intArrayOf(x.optInt(0), x.optInt(1), c)
			}
		}
		fun box(o: org.json.JSONObject) = o.optJSONArray("box")?.takeIf { it.length() >= 4 }?.let { b -> FloatArray(b.length()) { b.optDouble(it).toFloat() } }
		when(o.optString("t"))
		{
			"sub" ->
			{
				box = box(o)
				textSpans = spans(o)
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
					it.optString("ar").takeIf { a -> a.isNotBlank() }?.let { a -> Side(a, b, spans(it)) }
				}
				timers.removeCallbacks(hideSide); timers.postDelayed(hideSide, 15000)
			}
			"zone" ->
			{
				zone = box(o)
				timers.removeCallbacks(hideZone); timers.postDelayed(hideZone, 5000)
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
				outline = o.optBoolean("outline", false)
				boxColor = try { Color.parseColor(o.optString("boxColor", "#000000")) } catch(e: Exception) { Color.BLACK }
				zoom = o.optDouble("zoom", 0.0).toFloat()
				dy = o.optDouble("dy", 0.0).toFloat()
				invalidate()
			}
		}
	}

	/** a sample line, to check size and place */
	fun test()
	{
		box = floatArrayOf(0.25f, 0.84f, 0.5f, 0.06f)
		val t = "هذا سطر تجريبي للترجمة العربية فوق اللعبة"
		val i = t.indexOf("العربية")
		textSpans = listOf(intArrayOf(i, i + "العربية".length, Color.rgb(90, 220, 110))) // a coloured word, like games do
		text = t
		timers.removeCallbacks(hide); timers.postDelayed(hide, 6000)
	}

	// IBM Plex Sans Arabic from the app's assets (clear Arabic on a TV), the system font if missing
	private val fontRegular = try { Typeface.createFromAsset(context.assets, "fonts/plex_regular.ttf") } catch(e: Exception) { Typeface.DEFAULT }
	private val fontBold = try { Typeface.createFromAsset(context.assets, "fonts/plex_bold.ttf") } catch(e: Exception) { Typeface.DEFAULT_BOLD }

	private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(4f, 0f, 2f, Color.BLACK) }
	private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)

	/** the text with its coloured words (spans outside the text are ignored) */
	private fun styled(t: String, spans: List<IntArray>): CharSequence
	{
		if(spans.isEmpty()) return t
		val s = android.text.SpannableString(t)
		for(sp in spans)
			if(sp[0] in 0 until sp[1] && sp[1] <= t.length)
				s.setSpan(android.text.style.ForegroundColorSpan(sp[2]), sp[0], sp[1], android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		return s
	}

	/** with the outline on: a black stroke under the letters, then the letters */
	private fun drawText(l: StaticLayout, canvas: Canvas)
	{
		if(outline)
		{
			val st = textPaint.style; val sw = textPaint.strokeWidth; val c = textPaint.color
			textPaint.style = Paint.Style.STROKE; textPaint.strokeWidth = textPaint.textSize * 0.12f; textPaint.color = Color.BLACK
			textPaint.strokeJoin = Paint.Join.ROUND
			l.draw(canvas)
			textPaint.style = st; textPaint.strokeWidth = sw; textPaint.color = c
		}
		l.draw(canvas)
	}

	private fun layout(t: CharSequence, width: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_CENTER) =
		StaticLayout.Builder.obtain(t, 0, t.length, textPaint, width)
			.setAlignment(align).setTextDirection(TextDirectionHeuristics.RTL).setLineSpacing(0f, 1.1f).build()

	override fun onDraw(canvas: Canvas)
	{
		val w = width.toFloat()
		val h = height.toFloat()
		textPaint.color = color
		textPaint.typeface = if(bold) fontBold else fontRegular
		boxPaint.color = Color.argb((opacity * 255).toInt(), Color.red(boxColor), Color.green(boxColor), Color.blue(boxColor))

		// second box: in its own area of the picture, smaller text, top aligned
		val b2t = box2Text
		val b2 = box2?.let(::fit)
		if(!b2t.isNullOrBlank() && b2 != null)
		{
			textPaint.textSize = h * 0.028f * scale
			val area = RectF(b2[0] * w, b2[1] * h, (b2[0] + b2[2]) * w, (b2[1] + b2[3]) * h)
			val inner = (area.width() - w * 0.016f).toInt().coerceAtLeast(50)
			val l = layout(b2t, inner, Layout.Alignment.ALIGN_NORMAL)
			area.bottom = max(area.bottom, area.top + l.height + h * 0.02f)
			canvas.drawRoundRect(area, h * 0.01f, h * 0.01f, boxPaint)
			canvas.save(); canvas.translate(area.left + w * 0.008f, area.top + h * 0.01f); drawText(l, canvas); canvas.restore()
		}

		// the reading frame (only on the TV: the phone shows its own handles)
		val zb = zone?.let(::fit)
		if(zb != null && fitToTv)
		{
			val r = RectF(zb[0] * w, zb[1] * h, (zb[0] + zb[2]) * w, (zb[1] + zb[3]) * h)
			val zp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
				style = Paint.Style.STROKE; strokeWidth = h * 0.004f; color = Color.rgb(159, 211, 180)
				pathEffect = android.graphics.DashPathEffect(floatArrayOf(h * 0.02f, h * 0.012f), 0f)
			}
			canvas.drawRoundRect(r, h * 0.01f, h * 0.01f, zp)
			textPaint.textSize = h * 0.028f; textPaint.color = Color.rgb(159, 211, 180)
			val l = layout("مكان قراءة الترجمة", (w * 0.4f).toInt())
			canvas.save(); canvas.translate(r.centerX() - w * 0.2f, (r.top - l.height - h * 0.01f).coerceAtLeast(0f)); drawText(l, canvas); canvas.restore()
			textPaint.color = color
		}

		// side texts: each exactly over its English text (a bit wider if the Arabic needs it), kept on screen
		for(sd in sides)
		{
			val sb = fit(sd.box)
			textPaint.textSize = sizeFor(sb, h, 0.03f, 0.02f, 0.045f)
			val area = RectF(sb[0] * w - w * 0.004f, sb[1] * h - h * 0.004f, (sb[0] + sb[2]) * w + w * 0.004f, (sb[1] + sb[3]) * h + h * 0.004f)
			// a paragraph (help box) keeps the English box's width and wraps; a short prompt may widen
			val para = sb.size > 4 && sb[4] > 0f && sb[3] > sb[4] * 1.6f
			val tw = if(para) area.width() else min(textPaint.measureText(sd.text) + w * 0.012f, w * 0.6f)
			if(area.width() < tw)
			{
				// grow toward the middle of the screen: right-side prompts grow left, left-side ones grow right
				if(area.centerX() > w / 2) area.left = max(0f, area.right - tw) else area.right = min(w, area.left + tw)
			}
			val inner = (area.width() - w * 0.008f).toInt().coerceAtLeast(30)
			val l = layout(styled(sd.text, sd.spans), inner, if(para) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_CENTER)
			val need = l.height + h * 0.008f
			if(area.height() < need)
			{
				if(para) area.bottom = area.top + need // paragraphs grow down from the box's top, like the English
				else { val cy = area.centerY(); area.top = cy - need / 2; area.bottom = cy + need / 2 }
			}
			if(area.bottom > h) area.offset(0f, h - area.bottom)
			if(area.top < 0) area.offset(0f, -area.top)
			canvas.drawRoundRect(area, h * 0.006f, h * 0.006f, boxPaint)
			canvas.save(); canvas.translate(area.centerX() - inner / 2f, area.centerY() - l.height / 2f); drawText(l, canvas); canvas.restore()
		}

		// subtitle
		val t = text
		if(!t.isNullOrBlank())
		{
			val b = box?.let(::fit)
			textPaint.textSize = sizeFor(b, h, 0.042f, 0.026f, 0.055f)
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
			val l = layout(styled(t, textSpans), inner)
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
			canvas.save(); canvas.translate(area.centerX() - inner / 2f, area.centerY() - l.height / 2f); drawText(l, canvas); canvas.restore()
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
