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
	private var nextId = 0
	@Volatile private var currentId = -1
	private var loop: Job? = null
	private var lastBox2Read = 0L

	fun start()
	{
		if(loop != null || !prefs.enabled) return
		TranslationMemory.load(context)
		SessionLog.begin(context)
		LinkServer.start(context)
		scope.launch { translator.prepare() }
		if(prefs.secondReader) scope.launch { paddle = PaddleReader.open(context) }
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
		LinkServer.sendSub(c.id, arabic, c.english, true, c.box)
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
			val read = readArea(frame, Rect(0, top, frame.width, bottom), 0.018f)
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

	private fun show(id: Int, english: String, arabic: String, final: Boolean, box: FloatArray?)
	{
		current = Current(id, english, arabic, box)
		LinkServer.sendSub(id, arabic, english, final, box)
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

	/** OCR of one area of the picture; lines joined top to bottom; box is in 0..1 of the whole picture. */
	private suspend fun readArea(frame: Bitmap, area: Rect, minLineH: Float): OcrRead
	{
		val crop = Bitmap.createBitmap(frame, area.left, area.top, area.width(), area.height())
		try
		{
			val result: Text = suspendCancellableCoroutine<Text?> { c ->
				recognizer.process(InputImage.fromBitmap(crop, 0))
					.addOnSuccessListener { if(c.isActive) c.resume(it) }
					.addOnFailureListener { if(c.isActive) c.resume(null) }
			} ?: return OcrRead("")
			val lines = result.textBlocks.flatMap { it.lines }
				.filter { (it.boundingBox?.height() ?: 0) >= frame.height * minLineH } // skip tiny HUD text
				.sortedBy { it.boundingBox?.top ?: 0 }
			if(lines.isEmpty()) return OcrRead("")
			val texts = lines.map { line -> reread(crop, line) }
			val r = Rect(lines[0].boundingBox ?: Rect())
			for(l in lines) l.boundingBox?.let { r.union(it) }
			val box = floatArrayOf((r.left + area.left) / frame.width.toFloat(), (r.top + area.top) / frame.height.toFloat(),
				r.width() / frame.width.toFloat(), r.height() / frame.height.toFloat())
			return OcrRead(texts.joinToString(" "), box)
		}
		finally { crop.recycle() }
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
