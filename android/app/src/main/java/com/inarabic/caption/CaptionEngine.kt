// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Reads the game's English text from the Remote Play picture and sends the Arabic translation to the TVs.
// Loop: copy the stream picture (PixelCopy of the SurfaceView) -> for each area (the subtitle, and optionally a
// second box for side texts) OCR with ML Kit, re-reading unsure lines with PaddleOCR -> SubtitleTracker decides
// when the text changed -> translation memory, then the free on-device model at once, then the AI one.

package com.inarabic.caption

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.inarabic.InArabicPrefs
import com.inarabic.link.LinkServer
import com.inarabic.memory.SessionLog
import com.inarabic.memory.TranslationMemory
import com.inarabic.translate.Translator
import kotlinx.coroutines.*
import kotlin.coroutines.resume

class CaptionEngine(
	appContext: android.content.Context,
	private val prefs: InArabicPrefs,
	/** the picture to read: the Remote Play stream, or the camera pointed at the TV */
	private val source: suspend () -> Bitmap?,
	/** called on the main thread with the Arabic line (null = clear), for the caption on the phone */
	private val onCaption: (String?) -> Unit,
)
{
	/** reading the Remote Play stream */
	constructor(surfaceView: SurfaceView, prefs: InArabicPrefs, onCaption: (String?) -> Unit) :
		this(surfaceView.context, prefs, { grabSurface(surfaceView) }, onCaption)

	companion object
	{
		private const val TAG = "InArabic"
		private val copyThread = HandlerThread("ia-pixelcopy").apply { start() }
		private val copyHandler = Handler(copyThread.looper)

		/** A copy of the stream picture, at most 1280 px wide (enough for subtitles, cheap to read). */
		suspend fun grabSurface(surfaceView: SurfaceView): Bitmap? = withContext(Dispatchers.Main) {
			val w = surfaceView.width
			val h = surfaceView.height
			if(w < 16 || h < 16 || !surfaceView.holder.surface.isValid) return@withContext null
			val scale = minOf(1f, 1280f / w)
			val bmp = Bitmap.createBitmap((w * scale).toInt(), (h * scale).toInt(), Bitmap.Config.ARGB_8888)
			suspendCancellableCoroutine { c ->
				try
				{
					PixelCopy.request(surfaceView, bmp, { result ->
						if(c.isActive) c.resume(if(result == PixelCopy.SUCCESS) bmp else { bmp.recycle(); null })
					}, copyHandler)
				}
				catch(e: Exception) { bmp.recycle(); if(c.isActive) c.resume(null) }
			}
		}
	}

	/** The line on screen now, for the "correct this line" dialog. */
	data class Current(val id: Int, val english: String, val arabic: String, val box: FloatArray?)
	@Volatile var current: Current? = null
		private set

	private val context = appContext.applicationContext
	private val mainHandler = Handler(android.os.Looper.getMainLooper())
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	private val translator = Translator(prefs)
	private var paddle: PaddleReader? = null
	private val subTracker = SubtitleTracker()
	private val box2Tracker = SubtitleTracker(emptyReadsToClear = 2)
	private val sideTracker = SubtitleTracker(emptyReadsToClear = 2)
	private var nextId = 0
	@Volatile private var currentId = -1
	private var loop: Job? = null
	private var lastBox2Read = 0L
	private var lastScreenRead = 0L
	/** lines found by the last read of an area (a PlayStation menu fills the screen with text) */
	private var lastLineCount = 0
	private var screenLineCount = 0
	/** the PlayStation's own screens (menus, control centre) are on: translation paused */
	@Volatile var systemUi = false
		private set
	/** called on the main thread when [systemUi] changes */
	var onSystemUi: ((Boolean) -> Unit)? = null
	/** coloured phrases of the subtitle on screen now */
	@Volatile private var subColored: List<Colored> = emptyList()
	/** prompts found in the rest of the picture ("OPEN" next to a car), from the last whole-screen read */
	private var screenItems: List<OcrRead> = emptyList()
	@Volatile private var lastLineAt = 0L
	private var zonesCache: List<InArabicPrefs.Zone> = emptyList()
	private var zonesAt = 0L
	/** the reading frames, read again from the settings every 2 s (they change while you drag them) */
	private fun zonesNow(): List<InArabicPrefs.Zone>
	{
		val t = System.currentTimeMillis()
		if(t - zonesAt > 2000) { zonesCache = prefs.zones; zonesAt = t }
		return zonesCache
	}
	/** frames set right now (while placing them), used at once */
	fun setZones(z: List<InArabicPrefs.Zone>) { zonesCache = z; zonesAt = System.currentTimeMillis() }

	fun start()
	{
		if(loop != null || !prefs.enabled) return
		TranslationMemory.load(context)
		SessionLog.begin(context, prefs.gameName)
		LinkServer.start(context)
		LinkServer.sendStyle(prefs)
		scope.launch { translator.prepare() }
		if(prefs.secondReader) scope.launch { paddle = PaddleReader.open(context) }
		// review: while nobody is talking, the AI corrects lines the free model translated (memory gets better)
		scope.launch {
			while(isActive)
			{
				delay(20000)
				if(!prefs.aiEnabled || System.currentTimeMillis() - lastLineAt < 8000) continue
				val todo = TranslationMemory.unreviewed(15)
				if(todo.isEmpty()) continue
				for((en, ar) in translator.review(todo.map { it.english to it.arabic }))
					TranslationMemory.put(en, ar, TranslationMemory.Source.AI)
			}
		}
		loop = scope.launch {
			while(isActive)
			{
				val t0 = System.currentTimeMillis()
				try { step() } catch(e: CancellationException) { throw e } catch(e: Exception) { Log.w(TAG, "step", e) }
				delay((prefs.readEveryMs - (System.currentTimeMillis() - t0)).coerceAtLeast(40))
			}
		}
	}

	fun stop()
	{
		loop?.cancel()
		loop = null
		LinkServer.sendClear()
		LinkServer.sendBox2Clear()
		LinkServer.sendSideClear()
		scope.cancel()
		recognizer.close()
		translator.close()
		paddle?.close()
	}

	// ---- finding the subtitle area by itself: watch the whole picture for a few seconds ----
	@Volatile private var autoUntil = 0L
	private var autoBox: android.graphics.RectF? = null
	private var autoDone: ((android.graphics.RectF?) -> Unit)? = null

	/** Reads the whole picture for [ms]; centred lines in the lower part are the subtitles. */
	fun autoZone(ms: Long = 8000, done: (android.graphics.RectF?) -> Unit)
	{
		autoBox = null; autoDone = done; autoUntil = System.currentTimeMillis() + ms
	}

	private suspend fun autoStep(frame: Bitmap)
	{
		val items = ArrayList<OcrRead>()
		readArea(frame, Rect(0, 0, frame.width, frame.height), 0.016f, items, everyLine = true)
		for(it in items)
		{
			val b = it.box ?: continue
			val cx = b[0] + b[2] / 2
			if(kotlin.math.abs(cx - 0.5f) > 0.12f || b[1] < 0.45f || it.text.count(Char::isLetter) < 6) continue
			val r = android.graphics.RectF(b[0], b[1], b[0] + b[2], b[1] + b[3])
			autoBox = autoBox?.apply { union(r) } ?: r
		}
		if(System.currentTimeMillis() >= autoUntil)
		{
			autoUntil = 0L
			val found = autoBox?.let { r ->
				// room for longer lines and an extra line above
				val half = maxOf(0.35f, r.width() / 2 + 0.08f)
				android.graphics.RectF((0.5f - half).coerceAtLeast(0f), (r.top - r.height() - 0.03f).coerceAtLeast(0f),
					(0.5f + half).coerceAtMost(1f), (r.bottom + 0.03f).coerceAtMost(1f))
			}
			val cb = autoDone; autoDone = null
			mainHandler.post { cb?.invoke(found) }
		}
	}

	/** Your own wording for the current line: remembered for good and shown at once. */
	fun correct(arabic: String)
	{
		val c = current ?: return
		TranslationMemory.put(c.english, arabic, TranslationMemory.Source.USER)
		current = c.copy(arabic = arabic)
		LinkServer.sendSub(c.id, arabic, c.english, true, c.box, emptyList())
		post(arabic)
	}

	private suspend fun step()
	{
		val frame = source() ?: return
		try
		{
			if(autoUntil > 0L) { autoStep(frame); return }
			val zones = zonesNow()
			fun px(r: android.graphics.RectF) = Rect((r.left * frame.width).toInt(), (r.top * frame.height).toInt(),
				(r.right * frame.width).toInt(), (r.bottom * frame.height).toInt()).apply { intersect(0, 0, frame.width, frame.height) }
			// the dialogue: every dialogue frame; the first with text is the subtitle
			val side = ArrayList<OcrRead>()
			var read = OcrRead("")
			var subLines = 0
			for(z in zones.filter { it.dialog })
			{
				val r = px(z.r); if(r.width() < 16 || r.height() < 8) continue
				val got = readArea(frame, r, 0.018f, if(prefs.centerOnly) side else null)
				subLines += lastLineCount
				if(read.text.isBlank() && got.text.isNotBlank()) read = got
			}
			// side frames (tasks, prompts, messages) and, if on, the whole picture: read less often
			val t1 = System.currentTimeMillis()
			if(t1 - lastScreenRead > 700)
			{
				lastScreenRead = t1
				val all = ArrayList<OcrRead>()
				for(z in zones.filter { !it.dialog && !prefs.dialogOnly })
				{
					val r = px(z.r); if(r.width() < 16 || r.height() < 8) continue
					readArea(frame, r, 0.012f, all, everyLine = true)
				}
				screenLineCount = 0
				if(prefs.scanScreen)
				{
					val top = zones.filter { it.dialog }.minOfOrNull { it.r.top } ?: 0.62f
					if(top > 0.05f)
					{
						val found = ArrayList<OcrRead>()
						readArea(frame, Rect(0, 0, frame.width, (top * frame.height).toInt()), 0.014f, found, everyLine = true)
						screenLineCount = lastLineCount
						// "menus": only what the game draws for you (OPEN, Hold to skip, a button next to a word),
						// not the world (restaurant boards, shop signs, posters)
						all += if(!prefs.scanOnlyPrompts) found else found.filter { r ->
							val st = SideText.clean(r.text)
							st != null && (st.button != null || com.inarabic.translate.Glossary.lookup(st.text) != null)
						}
					}
				}
				screenItems = all.filter { it.text.split(' ').size <= 60 }
			}
			// a PlayStation menu (control centre, home, store…) is text everywhere: pause, don't translate it
			val busy = subLines > 5 || screenLineCount > 14
			if(busy != systemUi)
			{
				systemUi = busy
				if(busy) { subTracker.reset(); sideTracker.reset(); currentId = -1; current = null; LinkServer.sendClear(); LinkServer.sendSideClear(); post(null) }
				mainHandler.post { onSystemUi?.invoke(busy) }
			}
			if(busy) return
			if(prefs.dialogOnly) side.clear() else side.addAll(screenItems)
			val items = side.mapNotNull { r -> SideText.clean(r.text)?.let { Triple(it, r.box!!, r.colored) } }
			when(sideTracker.feed(OcrRead(items.joinToString(" | ") { it.first.text })))
			{
				is TrackerEvent.NewLine, is TrackerEvent.Update -> translateSide(items)
				TrackerEvent.Clear -> LinkServer.sendSideClear()
				null -> {}
			}
			if(read.text.isNotBlank()) subColored = read.colored
			when(val ev = subTracker.feed(read))
			{
				is TrackerEvent.NewLine -> translate(++nextId, ev.text, ev.box)
				is TrackerEvent.Update -> translate(currentId.takeIf { it >= 0 } ?: ++nextId, ev.text, ev.box)
				TrackerEvent.Clear -> { currentId = -1; current = null; LinkServer.sendClear(); post(null) }
				null -> {}
			}
		}
		finally { frame.recycle() }
	}

	private fun translate(id: Int, english: String, box: FloatArray?)
	{
		currentId = id
		lastLineAt = System.currentTimeMillis()
		scope.launch {
			val known0 = TranslationMemory.get(english)
			// the fixed game words win over anything the free translator said before (not over your own corrections)
			val fixed = if(known0?.source == TranslationMemory.Source.USER) null else com.inarabic.translate.Glossary.lookup(english)
			if(fixed != null) { show(id, english, fixed, true, box); return@launch }
			val known = known0
			val wantAi = prefs.aiEnabled
			// a line seen before: from memory, final at once (unless only the free model had translated it)
			if(known != null && (known.source != TranslationMemory.Source.QUICK || !wantAi))
			{
				show(id, english, known.arabic, true, box)
				return@launch
			}
			val quick = known?.arabic ?: translator.quick(english)
			if(currentId != id) return@launch
			if(quick != null)
			{
				TranslationMemory.put(english, quick, TranslationMemory.Source.QUICK)
				show(id, english, quick, !wantAi, box)
			}
			if(wantAi)
			{
				val better = translator.better(english) ?: return@launch
				TranslationMemory.put(english, better, TranslationMemory.Source.AI)
				if(currentId != id) { SessionLog.add(english, better); return@launch }
				show(id, english, better, true, box)
			}
		}
	}

	private suspend fun show(id: Int, english: String, arabic: String, final: Boolean, box: FloatArray?)
	{
		current = Current(id, english, arabic, box)
		LinkServer.sendSub(id, arabic, english, final, box, spans(english, arabic, subColored))
		if(final) SessionLog.add(english, arabic)
		post(arabic)
	}

	private fun translateBox2(english: String, box: FloatArray)
	{
		scope.launch {
			val known = TranslationMemory.get(english)
			val ar = known?.arabic ?: translator.better(english) ?: translator.quick(english) ?: return@launch
			if(known == null) TranslationMemory.put(english, ar, if(prefs.aiEnabled) TranslationMemory.Source.AI else TranslationMemory.Source.QUICK)
			LinkServer.sendBox2(ar, box)
		}
	}

	private fun translateSide(items: List<Triple<SideText, FloatArray, List<Colored>>>)
	{
		if(items.isEmpty()) return
		scope.launch {
			val out = items.mapNotNull { (st, box, colored) ->
				val known = TranslationMemory.get(st.text)?.takeIf { it.source == TranslationMemory.Source.USER || com.inarabic.translate.Glossary.lookup(st.text) == null }
				// longer help texts are worth the AI (when on); short prompts take the quick model
				val ai = if(known == null && prefs.aiEnabled && st.text.split(' ').size > 4) translator.better(st.text) else null
				val ar = known?.arabic ?: ai?.also { TranslationMemory.put(st.text, it, TranslationMemory.Source.AI) }
					?: translator.quick(st.text)?.also { TranslationMemory.put(st.text, it, TranslationMemory.Source.QUICK) } ?: return@mapNotNull null
				Triple(if(st.button != null) "$ar  ${st.button}" else ar, box, spans(st.text, ar, colored))
			}
			if(out.isNotEmpty()) LinkServer.sendSide(out)
		}
	}

	/** Arabic stretches to colour like the English: each coloured phrase translated alone, then found in the line. */
	private suspend fun spans(english: String, arabic: String, colored: List<Colored>): List<ColorSpan>
	{
		val mine = colored.filter { english.contains(it.english, ignoreCase = true) }
		if(mine.isEmpty()) return emptyList()
		// the whole line in one colour: colour all of it
		if(mine.size == 1 && mine[0].english.length >= english.trim { !it.isLetterOrDigit() }.length - 1)
			return listOf(ColorSpan(0, arabic.length, mine[0].color))
		val phrases = mine.mapNotNull { c ->
			val ar = TranslationMemory.get(c.english)?.arabic ?: translator.quick(c.english) ?: return@mapNotNull null
			ar to c.color
		}
		return ColorText.place(arabic, phrases)
	}

	private fun post(text: String?) { mainHandler.post { if(prefs.showOnPhone) onCaption(text) else onCaption(null) } }

	/**
	 * OCR of one area of the picture; lines joined top to bottom; box is in 0..1 of the whole picture.
	 * With [sideOut]: only lines centred on the picture are the subtitle; the others (a mission title or a button
	 * prompt in a corner) are put in [sideOut], one read per line, so they neither mix into the subtitle nor move it.
	 */
	private suspend fun readArea(frame: Bitmap, area: Rect, minLineH: Float, sideOut: MutableList<OcrRead>? = null, everyLine: Boolean = false): OcrRead
	{
		lastLineCount = 0
		val crop = Bitmap.createBitmap(frame, area.left, area.top, area.width(), area.height())
		try
		{
			val result: Text = suspendCancellableCoroutine<Text?> { c ->
				recognizer.process(InputImage.fromBitmap(crop, 0))
					.addOnSuccessListener { if(c.isActive) c.resume(it) }
					.addOnFailureListener { if(c.isActive) c.resume(null) }
			} ?: return OcrRead("")
			val ok = { l: Text.Line -> (l.boundingBox?.height() ?: 0) >= frame.height * minLineH } // skip tiny HUD text
			val all = result.textBlocks.flatMap { it.lines }.filter(ok).sortedBy { it.boundingBox?.top ?: 0 }
			lastLineCount = all.size
			fun centred(l: Text.Line): Boolean
			{
				val b = l.boundingBox ?: return false
				// a sentence is dialogue wherever it sits; only short texts away from the middle are side texts
				if(l.text.trim().split(Regex("\\s+")).size >= 4) return true
				val cx = b.exactCenterX() / area.width()
				return kotlin.math.abs(cx - 0.5f) < 0.25f || b.width() > area.width() * 0.45f
			}
			val lines = if(sideOut == null) all else all.filter(::centred)
			if(sideOut != null)
			{
				// side texts by block: a help box ("Ammu-Nations will expand / their stock…") is one paragraph,
				// but stacked button prompts ("SHOOT R2 / AIM L2") stay one item per line
				for(block in result.textBlocks)
				{
					val bl = block.lines.filter(ok).filter { it.boundingBox != null && (everyLine || !centred(it)) }
						.sortedBy { it.boundingBox!!.top }
					if(bl.isEmpty()) continue
					val prompts = bl.size == 1 || bl.any { SideText.clean(it.text)?.button != null }
					if(prompts) for(l in bl) sideOut.add(join(crop, listOf(l), area, frame))
					else sideOut.add(join(crop, bl, area, frame))
				}
			}
			if(lines.isEmpty()) return OcrRead("")
			return join(crop, lines, area, frame)
		}
		finally { crop.recycle() }
	}

	private fun join(crop: Bitmap, lines: List<Text.Line>, area: Rect, frame: Bitmap): OcrRead
	{
		val texts = lines.map { line -> reread(crop, line) }
		val r = Rect(lines[0].boundingBox ?: Rect())
		for(l in lines) l.boundingBox?.let { r.union(it) }
		// box[4]: the height of one English line, so the Arabic is drawn the same size
		val lineH = lines.mapNotNull { it.boundingBox?.height() }.sorted().let { it[it.size / 2] }
		val box = floatArrayOf((r.left + area.left) / frame.width.toFloat(), (r.top + area.top) / frame.height.toFloat(),
			r.width() / frame.width.toFloat(), r.height() / frame.height.toFloat(), lineH / frame.height.toFloat())
		return OcrRead(texts.joinToString(" "), box, coloredPhrases(crop, lines))
	}

	/** Runs of words drawn in the same (non-white) colour. */
	private fun coloredPhrases(crop: Bitmap, lines: List<Text.Line>): List<Colored>
	{
		val out = ArrayList<Colored>()
		val run = ArrayList<String>()
		var runColor = 0
		fun flush()
		{
			if(run.isNotEmpty()) out += Colored(run.joinToString(" ").trim { !it.isLetterOrDigit() }, runColor)
			run.clear()
		}
		for(line in lines) for(el in line.elements)
		{
			val c = el.boundingBox?.let { colorOf(crop, it) }
			if(c == null) { flush(); continue }
			if(run.isNotEmpty() && !sameHue(c, runColor)) flush()
			if(run.isEmpty()) runColor = c
			run += el.text
		}
		flush()
		return out.filter { it.english.any(Char::isLetter) }
	}

	private fun sameHue(a: Int, b: Int): Boolean
	{
		val x = FloatArray(3); val y = FloatArray(3)
		android.graphics.Color.colorToHSV(a, x); android.graphics.Color.colorToHSV(b, y)
		val d = kotlin.math.abs(x[0] - y[0]).let { minOf(it, 360 - it) }
		return d < 30
	}

	/**
	 * The colour of a word: the brightest pixels in its box are the letters (text is drawn bright over the
	 * game); white or grey letters -> null, clearly coloured ones -> that colour, made bright enough to read.
	 */
	private fun colorOf(bmp: Bitmap, r: Rect): Int?
	{
		val box = Rect(r); if(!box.intersect(0, 0, bmp.width, bmp.height) || box.width() < 3 || box.height() < 3) return null
		val sx = maxOf(1, box.width() / 28); val sy = maxOf(1, box.height() / 10)
		val px = ArrayList<Int>(300)
		var y = box.top
		while(y < box.bottom) { var x = box.left; while(x < box.right) { px += bmp.getPixel(x, y); x += sx }; y += sy }
		if(px.size < 8) return null
		fun lum(c: Int) = 0.299 * ((c shr 16) and 255) + 0.587 * ((c shr 8) and 255) + 0.114 * (c and 255)
		val top = px.sortedByDescending(::lum).take(maxOf(4, px.size / 5))
		val rr = top.sumOf { (it shr 16) and 255 } / top.size
		val gg = top.sumOf { (it shr 8) and 255 } / top.size
		val bb = top.sumOf { it and 255 } / top.size
		val hsv = FloatArray(3)
		android.graphics.Color.RGBToHSV(rr, gg, bb, hsv)
		if(hsv[1] < 0.35f || hsv[2] < 0.4f) return null
		hsv[1] = minOf(1f, hsv[1] * 1.1f); hsv[2] = maxOf(hsv[2], 0.9f)
		return android.graphics.Color.HSVToColor(hsv)
	}

	/** ML Kit's text, or PaddleOCR's when ML Kit is unsure and Paddle is surer. */
	private fun reread(crop: Bitmap, line: Text.Line): String
	{
		val p = paddle ?: return line.text
		val conf = try { line.confidence } catch(e: Throwable) { 1f }
		if(conf >= 0.8f) return line.text
		val b = line.boundingBox ?: return line.text
		val pad = (b.height() * 0.15f).toInt()
		val r = Rect(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
		if(!r.intersect(0, 0, crop.width, crop.height)) return line.text
		val img = Bitmap.createBitmap(crop, r.left, r.top, r.width(), r.height())
		val res = try { p.read(img) } finally { img.recycle() }
		return if(res != null && res.text.isNotBlank() && res.confidence > conf) res.text else line.text
	}
}
