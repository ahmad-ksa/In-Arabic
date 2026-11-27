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
	class Side(val text: String, val box: FloatArray, val spans: List<IntArray> = emptyList(), val fg: Int = Color.WHITE, val bg: Int = Color.BLACK)
	var sides: List<Side> = emptyList()
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
	var position = "auto"
	/**
	 * The look of the dialogue ([dLook]) and of the menus ([mLook]), one choice each, as on the LG screen; the
	 * letters are always the game's own colour: "special" (تنسيق خاص) the game's own background too, "box" a
	 * background of your colour ([dBg] / [mBg], #AARRGGBB), "outline" a black edge and no background.
	 */
	var dLook = "box"
	var dBg = Color.argb(217, 0, 0, 0)
	var mLook = "box"
	var mBg = Color.argb(217, 0, 0, 0)
	/** the menus' own size and height (from the phone's «شكل القوائم») */
	var mScale = 1f
	var mDy = 0f
	/** the dialogue line's own colours on screen (letters, background), from the phone */
	var subFg = Color.WHITE
	var subBg = Color.BLACK
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
		return o
	}

	/** text size: the English line's own height when known, else a default share of the screen */
	/**
	 * Text size from the English line's height, steady: the reading of that height wobbles a little from one
	 * picture to the next, so it is rounded to steps and the subtitle keeps its size unless it changes by 25 %+.
	 */
	private fun sizeFor(b: FloatArray?, h: Float, def: Float, lo: Float, hi: Float, keep: FloatArray? = null): Float
	{
		var v = if(b != null && b.size > 4 && b[4] > 0f) (b[4] * h * 0.95f).coerceIn(h * lo, h * hi) else h * def
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

	/** "#RRGGBB" or "#AARRGGBB" from the phone */
	private fun color(s: String?, def: Int) = try { if(s.isNullOrBlank()) def else Color.parseColor(s) } catch(e: Exception) { def }

	/** a text's background for its look: the game's own (special), yours (box), or none (outline) */
	private fun lookBg(look: String, mine: Int, game: Int) = when(look) { "special" -> game or (0xFF shl 24); "box" -> mine; else -> Color.TRANSPARENT }

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
				subFg = color(o.optString("fg"), Color.WHITE)
				subBg = color(o.optString("bg"), Color.BLACK)
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
					it.optString("ar").takeIf { a -> a.isNotBlank() }?.let { a -> Side(a, b, spans(it), color(it.optString("fg"), Color.WHITE), color(it.optString("bg"), Color.BLACK)) }
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
				position = o.optString("pos", "auto")
				dLook = o.optString("dlook", "box"); dBg = color(o.optString("dbg"), Color.argb(217, 0, 0, 0))
				mLook = o.optString("mlook", "box"); mBg = color(o.optString("mbg"), Color.argb(217, 0, 0, 0))
				mScale = o.optDouble("mscale", 1.0).toFloat()
				mDy = o.optDouble("mdy", 0.0).toFloat()
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

	/** [outline] («حد»): a black stroke under the letters, then the letters */
	private fun drawText(l: StaticLayout, canvas: Canvas, outline: Boolean)
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
		textPaint.color = Color.WHITE
		textPaint.typeface = fontBold

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
			boxPaint.color = dBg
			canvas.drawRoundRect(area, h * 0.01f, h * 0.01f, boxPaint)
			canvas.save(); canvas.translate(area.left + w * 0.008f, area.top + h * 0.01f); drawText(l, canvas, false); canvas.restore()
		}

		// notice at the top
		bannerText?.let { bt ->
			textPaint.textSize = h * 0.026f; textPaint.color = Color.WHITE
			val l = layout(bt, (w * 0.5f).toInt())
			val r = RectF(w * 0.25f, h * 0.06f, w * 0.75f, h * 0.06f + l.height + h * 0.02f)
			val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 23, 44, 36) }
			canvas.drawRoundRect(r, h * 0.03f, h * 0.03f, bp)
			canvas.save(); canvas.translate(w * 0.25f, r.top + h * 0.01f); l.draw(canvas); canvas.restore()
			textPaint.color = Color.WHITE
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
			textPaint.color = Color.WHITE
		}

		// side texts: each exactly over its English text (a bit wider if the Arabic needs it), kept on screen
		// one size for all of them (the English of a menu or a stats panel is one size; the reading of each
		// line's height is not exact): the middle size, kept while it stays close
		// the menus' own height setting moves them; their own size setting scales them
		fun menuBox(b: FloatArray) = fit(b).copyOf().also { it[1] += mDy }
		val rawSizes = sides.map { sizeFor(menuBox(it.box), h, 0.03f, 0.02f, 0.045f) * mScale }
		val mid = rawSizes.sorted().getOrNull(rawSizes.size / 2)
		if(mid != null) { if(sideUniform <= 0f || kotlin.math.abs(mid - sideUniform) > sideUniform * 0.25f) sideUniform = mid }
		for((si, sd) in sides.withIndex())
		{
			val sb = menuBox(sd.box)
			val raw = rawSizes[si]
			textPaint.textSize = if(raw in sideUniform * 0.65f..sideUniform * 1.5f) sideUniform else raw
			val area = RectF(sb[0] * w - w * 0.004f, sb[1] * h - h * 0.004f, (sb[0] + sb[2]) * w + w * 0.004f, (sb[1] + sb[3]) * h + h * 0.004f)
			// a paragraph (help box) keeps the English box's width and wraps; a short prompt may widen
			// a paragraph (help box, description) keeps the English box's width and wraps; a list or a prompt does not
			val para = sb.size > 4 && sb[4] > 0f && sb[3] > sb[4] * 1.6f && sd.text.split('\n').any { it.length > 30 }
			val tw = if(para) area.width() else min(sd.text.split('\n').maxOf { textPaint.measureText(it) } + w * 0.012f, w * 0.6f)
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
			// its look: the game's own letters always; the background as chosen for the menus
			boxPaint.color = lookBg(mLook, mBg, sd.bg)
			if(Color.alpha(boxPaint.color) > 0) canvas.drawRoundRect(area, h * 0.006f, h * 0.006f, boxPaint)
			textPaint.color = sd.fg
			canvas.save(); canvas.translate(area.centerX() - inner / 2f, area.centerY() - l.height / 2f); drawText(l, canvas, mLook == "outline"); canvas.restore()
		}

		// subtitle
		val t = text
		if(!t.isNullOrBlank())
		{
			val b = box?.let { fit(it, true) }
			textPaint.textSize = sizeFor(b, h, 0.042f, 0.026f, 0.055f, subSize)
			val area = when
			{
				position == "top" -> RectF(w * 0.1f, h * 0.05f, w * 0.9f, h * 0.17f)
				position == "auto" && b != null ->
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
			// its look: the game's own letters always; the background as chosen for the dialogue
			boxPaint.color = lookBg(dLook, dBg, subBg)
			if(Color.alpha(boxPaint.color) > 0) canvas.drawRoundRect(area, h * 0.01f, h * 0.01f, boxPaint)
			textPaint.color = subFg
			canvas.save(); canvas.translate(area.centerX() - inner / 2f, area.centerY() - l.height / 2f); drawText(l, canvas, dLook == "outline"); canvas.restore()
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
