// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Reads the game's English subtitle from the Remote Play picture and sends the Arabic translation to the TVs.
// Loop: copy the stream picture (PixelCopy of the SurfaceView) -> crop the subtitle area -> OCR (ML Kit, on the
// phone) -> SubtitleTracker decides when the line changed -> quick translation now, better AI one after.

package com.inarabic.caption

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.inarabic.InArabicPrefs
import com.inarabic.link.LinkServer
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

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private val copyThread = HandlerThread("ia-pixelcopy").apply { start() }
	private val copyHandler = Handler(copyThread.looper)
	private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	private val translator = Translator(prefs)
	private val tracker = SubtitleTracker()
	private var nextId = 0
	@Volatile private var currentId = -1
	private var loop: Job? = null

	fun start()
	{
		if(loop != null || !prefs.enabled) return
		LinkServer.start(surfaceView.context)
		LinkServer.sendStyle(prefs.textScale, prefs.coverOriginal, prefs.boxOpacity)
		scope.launch { translator.prepare() }
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
		scope.cancel()
		copyThread.quitSafely()
		recognizer.close()
		translator.close()
	}

	private suspend fun step()
	{
		val frame = grab() ?: return
		val top = (frame.height * prefs.areaTop).toInt().coerceIn(0, frame.height - 8)
		val bottom = (frame.height * prefs.areaBottom).toInt().coerceIn(top + 8, frame.height)
		val crop = Bitmap.createBitmap(frame, 0, top, frame.width, bottom - top)
		val read = ocr(crop, frame.width, frame.height, top)
		crop.recycle()
		frame.recycle()
		when(val ev = tracker.feed(read))
		{
			is TrackerEvent.NewLine -> translate(++nextId, ev.text, ev.box)
			is TrackerEvent.Update -> translate(currentId.takeIf { it >= 0 } ?: ++nextId, ev.text, ev.box)
			TrackerEvent.Clear -> { currentId = -1; LinkServer.sendClear(); post(null) }
			null -> {}
		}
	}

	private fun translate(id: Int, english: String, box: FloatArray?)
	{
		currentId = id
		scope.launch {
			val quick = translator.quick(english)
			if(currentId != id) return@launch
			val wantAi = prefs.aiEnabled
			if(quick != null)
			{
				LinkServer.sendSub(id, quick, english, !wantAi, box)
				post(quick)
			}
			if(wantAi)
			{
				val better = translator.better(english) ?: return@launch
				if(currentId != id) return@launch
				LinkServer.sendSub(id, better, english, true, box)
				post(better)
			}
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

	/** OCR of the subtitle area; lines are joined top to bottom; box is in 0..1 of the whole picture. */
	private suspend fun ocr(crop: Bitmap, frameW: Int, frameH: Int, cropTop: Int): OcrRead =
		suspendCancellableCoroutine { c ->
			recognizer.process(InputImage.fromBitmap(crop, 0))
				.addOnSuccessListener { result ->
					val lines = result.textBlocks.flatMap { it.lines }
						.filter { (it.boundingBox?.height() ?: 0) >= frameH * 0.018 } // skip tiny HUD text
						.sortedBy { it.boundingBox?.top ?: 0 }
					if(lines.isEmpty()) { if(c.isActive) c.resume(OcrRead("")); return@addOnSuccessListener }
					val r = Rect(lines[0].boundingBox ?: Rect())
					for(l in lines) l.boundingBox?.let { r.union(it) }
					val box = floatArrayOf(r.left / frameW.toFloat(), (r.top + cropTop) / frameH.toFloat(),
						r.width() / frameW.toFloat(), r.height() / frameH.toFloat())
					if(c.isActive) c.resume(OcrRead(lines.joinToString(" ") { it.text }, box))
				}
				.addOnFailureListener { if(c.isActive) c.resume(OcrRead("")) }
		}
}
