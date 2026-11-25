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
	private val surfaceView: SurfaceView,
	private val prefs: InArabicPrefs,
	/** called on the main thread with the Arabic line (null = clear), for the caption on the phone */
	private val onCaption: (String?) -> Unit,
)
{
	companion object { private const val TAG = "InArabic" }

	/** The line on screen now, for the "correct this line" dialog. */
	data class Current(val id: Int, val english: String, val arabic: String, val box: FloatArray?)
	@Volatile var current: Current? = null
		private set

	private val context = surfaceView.context.applicationContext
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private val copyThread = HandlerThread("ia-pixelcopy").apply { start() }
	private val copyHandler = Handler(copyThread.looper)
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
	/** coloured phrases of the subtitle on screen now */
	@Volatile private var subColored: List<Colored> = emptyList()
	/** prompts found in the rest of the picture ("OPEN" next to a car), from the last whole-screen read */
	private var screenItems: List<OcrRead> = emptyList()
	@Volatile private var lastLineAt = 0L

	fun start()
	{
		if(loop != null || !prefs.enabled) return
		TranslationMemory.load(context)
		SessionLog.begin(context)
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
		copyThread.quitSafely()
		recognizer.close()
		translator.close()
		paddle?.close()
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
		val frame = grab() ?: return
		try
		{
			// the subtitle
			val top = (frame.height * prefs.areaTop).toInt().coerceIn(0, frame.height - 8)
			val bottom = (frame.height * prefs.areaBottom).toInt().coerceIn(top + 8, frame.height)
			val side = ArrayList<OcrRead>()
			val read = readArea(frame, Rect(0, top, frame.width, bottom), 0.018f, if(prefs.centerOnly) side else null)
			// the rest of the picture (above the subtitle area): interaction prompts can be anywhere; read less often
			val t1 = System.currentTimeMillis()
			if(prefs.scanScreen && top > 16 && t1 - lastScreenRead > 700)
			{
				lastScreenRead = t1
				val all = ArrayList<OcrRead>()
				readArea(frame, Rect(0, 0, frame.width, top), 0.014f, all, everyLine = true)
				screenItems = all.filter { it.text.split(' ').size <= 40 } // prompts and help boxes, not pages of text
			}
			side.addAll(screenItems)
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
			// side texts: read less often
			val now = System.currentTimeMillis()
			if(prefs.box2Enabled && now - lastBox2Read > 900)
			{
				lastBox2Read = now
				val b = prefs.box2
				val r = Rect((b[0] * frame.width).toInt(), (b[1] * frame.height).toInt(),
					((b[0] + b[2]) * frame.width).toInt(), ((b[1] + b[3]) * frame.height).toInt())
				r.intersect(0, 0, frame.width, frame.height)
				if(r.width() > 16 && r.height() > 16)
				{
					when(val ev = box2Tracker.feed(readArea(frame, r, 0.012f)))
					{
						is TrackerEvent.NewLine -> translateBox2(ev.text, b)
						is TrackerEvent.Update -> translateBox2(ev.text, b)
						TrackerEvent.Clear -> LinkServer.sendBox2Clear()
						null -> {}
					}
				}
			}
		}
		finally { frame.recycle() }
	}

	private fun translate(id: Int, english: String, box: FloatArray?)
	{
		currentId = id
		lastLineAt = System.currentTimeMillis()
		scope.launch {
			val known = TranslationMemory.get(english)
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
				val known = TranslationMemory.get(st.text)
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

	private fun post(text: String?) { surfaceView.post { if(prefs.showOnPhone) onCaption(text) else onCaption(null) } }

	/** A copy of the stream picture, at most 1280 px wide (enough for subtitles, cheap to read). */
	private suspend fun grab(): Bitmap? = withContext(Dispatchers.Main) {
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

	/**
	 * OCR of one area of the picture; lines joined top to bottom; box is in 0..1 of the whole picture.
	 * With [sideOut]: only lines centred on the picture are the subtitle; the others (a mission title or a button
	 * prompt in a corner) are put in [sideOut], one read per line, so they neither mix into the subtitle nor move it.
	 */
	private suspend fun readArea(frame: Bitmap, area: Rect, minLineH: Float, sideOut: MutableList<OcrRead>? = null, everyLine: Boolean = false): OcrRead
	{
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
			fun centred(l: Text.Line): Boolean
			{
				val b = l.boundingBox ?: return false
				val cx = (b.exactCenterX() + area.left) / frame.width
				return kotlin.math.abs(cx - 0.5f) < 0.12f || b.width() > frame.width * 0.55f
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
