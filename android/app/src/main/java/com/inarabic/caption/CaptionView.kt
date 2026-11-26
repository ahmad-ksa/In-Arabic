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
	/** the lens's translations: each over its English, with the English's own letter and background colours */
	class Lens(val text: String, val box: FloatArray, val color: Int, val bg: Int, val spans: List<IntArray>)
	var lens: List<Lens> = emptyList()
		set(v) { field = v; invalidate() }
	/** a short notice at the top (phone lost / back) */
	private var bannerText: String? = null
	private val hideBanner = Runnable { bannerText = null; invalidate() }
	fun banner(t: String) { bannerText = t; invalidate(); timers.removeCallbacks(hideBanner); timers.postDelayed(hideBanner, 6000) }

	/** the reading frames being placed from the phone (box, dialogue?) */
	var zoneList: List<Pair<FloatArray, Boolean>> = emptyList()
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
	private fun fit(b: FloatArray): FloatArray = fit(b, false)
	/** [move]: the dialogue line, which your up/down setting moves (on the phone too); side texts stay on their English */
	private fun fit(b: FloatArray, move: Boolean): FloatArray
	{
		val dy = if(move) this.dy else 0f
		val k = if(fitToTv) 1f + zoom else 1f
		if(k == 1f && dy == 0f) return b
		val o = b.copyOf()
		o[0] = 0.5f + (b[0] - 0.5f) * k; o[2] = b[2] * k
		o[1] = 0.5f + (b[1] - 0.5f) * k + dy; o[3] = b[3] * k
		if(o.size > 4) o[4] = b[4] * k
		if(o.size > 7) { o[6] = b[6] * k; o[7] = b[7] * k }
		return o
	}

	/** text size: the English line's own height when known, else a default share of the screen */
	/**
	 * Text size from the English line's height, steady: the reading of that height wobbles a little from one
	 * picture to the next, so it is rounded to steps and the subtitle keeps its size unless it changes by 25 %+.
	 */
	private fun sizeFor(b: FloatArray?, h: Float, def: Float, lo: Float, hi: Float, keep: FloatArray? = null): Float
	{
		var v = if(b != null && b.size > 4 && b[4] > 0f) (b[4] * h * 0.82f).coerceIn(h * lo, h * hi) else h * def
		val step = h * 0.004f
		v = Math.round(v / step) * step
		if(keep != null)
		{
			if(keep[0] > 0f && kotlin.math.abs(v - keep[0]) < keep[0] * 0.25f) v = keep[0] else keep[0] = v
		}
		return v * scale
	}
	private val subSize = FloatArray(1)
	/** each side text keeps its size while it stays on screen */
	private val sideSizes = HashMap<String, FloatArray>()
	private var sideUniform = 0f
	fun styleChanged() = invalidate()

	private val timers = android.os.Handler(android.os.Looper.getMainLooper())
	private val hide = Runnable { text = null }
	private val hidePanel = Runnable { panelText = null }
	private val hideSide = Runnable { sides = emptyList() }
	private val hideZone = Runnable { zoneList = emptyList() }
	private val hideLens = Runnable { lens = emptyList() }

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
				zoneList = box(o)?.let { listOf(it to true) } ?: emptyList()
				timers.removeCallbacks(hideZone); timers.postDelayed(hideZone, 5000)
			}
			"zones" ->
			{
				val a = o.optJSONArray("zones")
				zoneList = if(a == null) emptyList() else (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { z -> box(z)?.let { it to z.optBoolean("dialog", true) } } }
				timers.removeCallbacks(hideZone); timers.postDelayed(hideZone, 8000)
			}
			"lens" ->
			{
				val items = o.optJSONArray("items")
				lens = if(items == null) emptyList() else (0 until items.length()).mapNotNull { i ->
					val it = items.optJSONObject(i) ?: return@mapNotNull null
					val b = box(it) ?: return@mapNotNull null
					val fg = try { Color.parseColor(it.optString("color")) } catch(e: Exception) { Color.WHITE }
					val bg = try { Color.parseColor(it.optString("bg")) } catch(e: Exception) { Color.BLACK }
					it.optString("ar").takeIf { a -> a.isNotBlank() }?.let { a -> Lens(a, b, fg, bg, spans(it)) }
				}
				timers.removeCallbacks(hideLens); timers.postDelayed(hideLens, 15000)
			}
			"lensclear" -> { timers.removeCallbacks(hideLens); lens = emptyList() }
			"notice" -> banner(o.optString("text"))
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

	/** what has been drawn so far in this picture (screen rectangles): nothing new may cover it */
	private val placed = ArrayList<RectF>()

	/** moves [r] down (or, at the bottom of the screen, up) until it touches nothing already placed; the offset */
	private fun avoid(r: RectF, h: Float): Float
	{
		val gap = h * 0.004f
		val y0 = r.top
		var up = false
		repeat(24) {
			val hit = placed.firstOrNull { RectF.intersects(it, r) } ?: return r.top - y0
			if(!up && hit.bottom + gap + r.height() <= h) r.offsetTo(r.left, hit.bottom + gap)
			else { up = true; r.offsetTo(r.left, hit.top - gap - r.height()) }
		}
		return r.top - y0
	}

	/** the largest text size, from [start] down to [start]×[minK], whose layout fits [width]×[maxH] */
	private fun fitLayout(t: CharSequence, start: Float, width: Int, maxH: Float, align: Layout.Alignment, minK: Float = 0.45f): StaticLayout
	{
		var size = start
		while(true)
		{
			textPaint.textSize = size
			val l = layout(t, width, align)
			if(l.height <= maxH || size <= start * minK) return l
			size *= 0.92f
		}
	}

	/**
	 * One text over its English one: inside the English area (smaller letters when the Arabic is longer, never
	 * spilling onto what is below), with the English's tilt when it is tilted, and moved off anything already drawn.
	 * [b]: x, y, w, h, line height, and for tilted text angle, own width, own height (see the phone's engine).
	 */
	private fun drawItem(canvas: Canvas, text: String, spans: List<IntArray>, b: FloatArray, size: Float?, fg: Int, bg: Paint, withOutline: Boolean)
	{
		val w = width.toFloat(); val h = height.toFloat()
		val tilted = b.size > 7
		val angle = if(tilted) b[5] else 0f
		val ownW = if(tilted) b[6] * h else b[2] * w
		val ownH = if(tilted) b[7] * h else b[3] * h
		val lineH = if(b.size > 4 && b[4] > 0f) b[4] * h else ownH
		val cx = (b[0] + b[2] / 2) * w
		val cy = (b[1] + b[3] / 2) * h
		val padX = lineH * 0.15f; val padY = lineH * 0.08f
		// a paragraph (a message, a description): wraps inside the English width; a list or a prompt: one line each
		val para = ownH > lineH * 1.6f && '\n' !in text
		val start = size ?: (lineH * 0.82f * scale)
		textPaint.color = fg
		val inner: Int
		val l: StaticLayout
		if(para)
		{
			inner = ownW.toInt().coerceAtLeast(20)
			l = fitLayout(styled(text, spans), start, inner, ownH + padY, Layout.Alignment.ALIGN_NORMAL)
		}
		else
		{
			// keep the English width: smaller letters first, a bit wider only if it still does not fit
			textPaint.textSize = start
			val tw = text.split('\n').maxOf { textPaint.measureText(it) }
			if(tw > ownW) textPaint.textSize = start * max(0.6f, ownW / tw)
			val tw2 = text.split('\n').maxOf { textPaint.measureText(it) }
			inner = max(ownW, tw2 + 2f).toInt().coerceAtLeast(20)
			l = fitLayout(styled(text, spans), textPaint.textSize, inner, max(ownH, lineH) * 1.15f + padY, Layout.Alignment.ALIGN_CENTER, 0.75f)
		}
		val aw = max(ownW, inner.toFloat()) + padX * 2
		val ah = max(ownH, l.height.toFloat()) + padY * 2
		val local = RectF(cx - aw / 2, cy - ah / 2, cx + aw / 2, cy + ah / 2)
		// on screen: its turned outline, kept inside the picture and off what is already there
		val bounds = RectF(local)
		if(angle != 0f) android.graphics.Matrix().apply { setRotate(angle, cx, cy) }.mapRect(bounds)
		var dx = 0f; var dy = 0f
		if(bounds.left < 0) dx = -bounds.left else if(bounds.right > w) dx = w - bounds.right
		if(bounds.top < 0) dy = -bounds.top else if(bounds.bottom > h) dy = h - bounds.bottom
		bounds.offset(dx, dy)
		dy += avoid(bounds, h)
		placed += bounds
		canvas.save()
		canvas.translate(dx, dy)
		if(angle != 0f) canvas.rotate(angle, cx, cy)
		canvas.drawRoundRect(local, lineH * 0.12f, lineH * 0.12f, bg)
		canvas.translate(cx - inner / 2f, cy - l.height / 2f)
		val o = outline; outline = withOutline
		drawText(l, canvas)
		outline = o
		canvas.restore()
	}

	override fun onDraw(canvas: Canvas)
	{
		val w = width.toFloat()
		val h = height.toFloat()
		textPaint.color = color
		textPaint.typeface = if(bold) fontBold else fontRegular
		boxPaint.color = Color.argb((opacity * 255).toInt(), Color.red(boxColor), Color.green(boxColor), Color.blue(boxColor))

		// second box: in its own area of the picture, smaller text, top aligned
		val b2t = box2Text
		val b2 = box2?.let { fit(it, true) }
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

		// notice at the top
		bannerText?.let { bt ->
			textPaint.textSize = h * 0.026f; textPaint.color = Color.WHITE
			val l = layout(bt, (w * 0.5f).toInt())
			val r = RectF(w * 0.25f, h * 0.06f, w * 0.75f, h * 0.06f + l.height + h * 0.02f)
			val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 23, 44, 36) }
			canvas.drawRoundRect(r, h * 0.03f, h * 0.03f, bp)
			canvas.save(); canvas.translate(w * 0.25f, r.top + h * 0.01f); l.draw(canvas); canvas.restore()
			textPaint.color = color
		}

		// the reading frames (only on the TV: the phone shows its own handles)
		if(fitToTv) zoneList.forEachIndexed { i, (zb0, dialog) ->
			val zb = fit(zb0)
			val c = if(dialog) Color.rgb(159, 211, 180) else Color.rgb(232, 194, 122)
			val r = RectF(zb[0] * w, zb[1] * h, (zb[0] + zb[2]) * w, (zb[1] + zb[3]) * h)
			val zp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
				style = Paint.Style.STROKE; strokeWidth = h * 0.004f; color = c
				pathEffect = android.graphics.DashPathEffect(floatArrayOf(h * 0.02f, h * 0.012f), 0f)
			}
			canvas.drawRoundRect(r, h * 0.01f, h * 0.01f, zp)
			textPaint.textSize = h * 0.024f; textPaint.color = c
			val l = layout((if(dialog) "حوار" else "نص جانبي") + " ${i + 1}", (w * 0.3f).toInt())
			canvas.save(); canvas.translate(r.centerX() - w * 0.15f, (r.top - l.height - h * 0.005f).coerceAtLeast(0f)); l.draw(canvas); canvas.restore()
			textPaint.color = color
		}

		// Nothing Arabic is ever drawn over other Arabic: each text is placed in turn (the subtitle first, then the
		// lens, then the side texts) and moved off anything already placed.
		placed.clear()

		// subtitle
		val t = text
		if(!t.isNullOrBlank())
		{
			val b = box?.let { fit(it, true) }
			textPaint.textSize = sizeFor(b, h, 0.042f, 0.026f, 0.055f, subSize)
			val area = when
			{
				position == "top" -> RectF(w * 0.1f, h * 0.05f, w * 0.9f, h * 0.17f)
				position == "auto" && b != null && cover ->
					RectF(b[0] * w - w * 0.015f, b[1] * h - h * 0.012f, (b[0] + b[2]) * w + w * 0.015f, (b[1] + b[3]) * h + h * 0.012f)
				else -> RectF(w * 0.1f, h * 0.78f, w * 0.9f, h * 0.92f)
			}
			// the Arabic line may need more room than the English one: widen only as much as it needs (max 80 %)
			val minW = min(w * 0.8f, t.split('\n').maxOf { textPaint.measureText(it) } + w * 0.03f)
			if(area.width() < minW)
			{
				val cx = area.centerX().coerceIn(minW / 2, w - minW / 2)
				area.left = cx - minW / 2; area.right = cx + minW / 2
			}
			val inner = (area.width() - w * 0.02f).toInt().coerceAtLeast(50)
			// at most three lines: a longer translation gets smaller letters, it never spreads over the picture
			val l = fitLayout(styled(t, textSpans), textPaint.textSize, inner, max(area.height(), textPaint.textSize * 1.25f * 3) , Layout.Alignment.ALIGN_CENTER, 0.6f)
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
			placed += RectF(area)
		}

		// the lens: over each English text, its own size and colours (letters and background), its own tilt
		for(ln in lens)
		{
			val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ln.bg }
			textPaint.clearShadowLayer() // the game's own look: no added shadow
			val tf = textPaint.typeface; textPaint.typeface = fontBold // bold, like the game's text
			drawItem(canvas, ln.text, ln.spans, fit(ln.box), null, ln.color, bp, false)
			textPaint.setShadowLayer(4f, 0f, 2f, Color.BLACK)
			textPaint.typeface = tf
		}
		textPaint.color = color

		// side texts: each over its English text, kept inside it. One size for all of them (the English of a menu
		// or a stats panel is one size; the reading of each line's height is not exact): the middle size, kept
		// while it stays close
		val rawSizes = sides.map { sizeFor(fit(it.box), h, 0.03f, 0.02f, 0.045f) }
		val mid = rawSizes.sorted().getOrNull(rawSizes.size / 2)
		if(mid != null) { if(sideUniform <= 0f || kotlin.math.abs(mid - sideUniform) > sideUniform * 0.25f) sideUniform = mid }
		for((si, sd) in sides.withIndex())
		{
			val raw = rawSizes[si]
			val size = if(raw in sideUniform * 0.65f..sideUniform * 1.5f) sideUniform else raw
			drawItem(canvas, sd.text, sd.spans, fit(sd.box), size, color, boxPaint, outline)
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
