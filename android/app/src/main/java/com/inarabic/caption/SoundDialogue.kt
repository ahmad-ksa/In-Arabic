// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// «مصدر الحوار: صوت اللعبة» — for games without subtitles: the game's sound from the stream goes to Google's
// speech recognition on the phone itself (ML Kit GenAI, free, no internet, Android 12+), and each English
// sentence it hears becomes a dialogue line (translated, shown, dubbed like a subtitle).

package com.inarabic.caption

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import com.inarabic.Diag
import com.inarabic.voice.GameSound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.util.Locale

class SoundDialogue(
	private val context: Context,
	/** an English sentence heard, and when it began */
	private val onLine: (english: String, startedAt: Long) -> Unit,
	/** a short notice for you (not available on this phone, downloading…) */
	private val onNotice: (String) -> Unit,
	/** the recognition cannot run on this phone: the subtitles are read instead */
	private val onUnavailable: () -> Unit)
{
	private var job: Job? = null
	private var recognizer: SpeechRecognizer? = null

	/** false: this phone cannot (the caller reads the subtitles instead) */
	fun start(scope: CoroutineScope): Boolean
	{
		if(Build.VERSION.SDK_INT < 31) { onNotice("صوت اللعبة يحتاج أندرويد ١٢ أو أحدث — رجعت للترجمة المكتوبة"); return false }
		// Android's speech recognition will not run without the microphone permission (asked for in the settings
		// when «صوت اللعبة» is chosen), even though the sound given to it is the game's
		if(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
		{
			onNotice("صوت اللعبة يحتاج إذن الميكروفون: اختره مرة ثانية من الإعدادات ووافق — رجعت للترجمة المكتوبة")
			return false
		}
		job = scope.launch(Dispatchers.Default) {
			val r = ready() ?: return@launch
			recognizer = r
			// one recognition at a time; when it ends (a long silence, an error) the next one starts. Errors one
			// after another wait longer each time; a refused permission stops it
			var pause = 300L
			while(isActive)
			{
				val heard = try { listen(r) } catch(e: kotlinx.coroutines.CancellationException) { throw e } catch(e: Exception) { Diag.add("sound: $e"); Ended.ERROR }
				if(heard == Ended.REFUSED)
				{
					onNotice("النظام رفض التعرف على الكلام (إذن الميكروفون) — رجعت للترجمة المكتوبة")
					onUnavailable()
					return@launch
				}
				pause = if(heard == Ended.ERROR) (pause * 2).coerceAtMost(10000) else 300L
				delay(pause)
			}
		}
		return true
	}

	fun stop()
	{
		job?.cancel(); job = null
		val r = recognizer; recognizer = null
		if(r != null) CoroutineScope(Dispatchers.Default).launch { try { r.stopRecognition() } catch(e: Exception) { }; try { r.close() } catch(e: Exception) { } }
	}

	/** the recogniser, its model downloaded; the better one where the phone has it */
	private suspend fun ready(): SpeechRecognizer?
	{
		for(mode in listOf(SpeechRecognizerOptions.Mode.MODE_ADVANCED, SpeechRecognizerOptions.Mode.MODE_BASIC))
		{
			val r = SpeechRecognition.getClient(speechRecognizerOptions { locale = Locale.US; preferredMode = mode })
			var status = try { r.checkStatus() } catch(e: Exception) { Diag.add("sound: status $e"); FeatureStatus.UNAVAILABLE }
			Diag.add("sound: mode $mode status $status")
			if(status == FeatureStatus.DOWNLOADABLE)
			{
				onNotice("أنزّل التعرف على الكلام الإنجليزي (مرة وحدة)…")
				r.download().collect { d ->
					when(d)
					{
						is DownloadStatus.DownloadCompleted -> status = FeatureStatus.AVAILABLE
						is DownloadStatus.DownloadFailed -> Diag.add("sound: download failed ${d.e.message}")
						else -> {}
					}
				}
			}
			if(status == FeatureStatus.AVAILABLE) { onNotice("أسمع صوت اللعبة وأترجمه"); return r }
			try { r.close() } catch(e: Exception) { }
		}
		onNotice("التعرف على الكلام مو متوفر في هذا الجوال — رجعت للترجمة المكتوبة")
		onUnavailable()
		return null
	}

	private enum class Ended { OK, ERROR, REFUSED }

	/** one recognition: the game's sound goes in as it plays, the sentences come out */
	private suspend fun listen(r: SpeechRecognizer): Ended = kotlinx.coroutines.coroutineScope {
		val pipe = ParcelFileDescriptor.createPipe()
		// the sound, written at the pace it plays (16 kHz, 16-bit, one channel)
		val writer = launch(Dispatchers.IO) {
			// the recognition ended and closed its end: writing stops (not an error)
			try { FileOutputStream(pipe[1].fileDescriptor).use { out ->
				var at = -1L
				val bytes = ByteArray(16000 * 2)
				while(isActive)
				{
					val c = GameSound.read(if(at < 0) -1 else at, if(at < 0) 1 else 16000)
					if(c == null) { delay(200); continue }
					if(at < 0) { at = c.start + c.count; continue } // from now on, not the past
					if(c.rate != 16000) { Diag.add("sound: rate ${c.rate}, needs 16000"); delay(1000); continue }
					for(i in 0 until c.count) { val v = c.samples[i].toInt(); bytes[2 * i] = v.toByte(); bytes[2 * i + 1] = (v shr 8).toByte() }
					if(c.count > 0) out.write(bytes, 0, c.count * 2)
					at = c.start + c.count
					delay(100)
				}
			} }
			catch(e: java.io.IOException) { }
		}
		var ended = Ended.OK
		try
		{
			AudioSource.fromPfd(pipe[0], AudioSource.Mode.STREAMING).use { source ->
				var heardAt = 0L
				r.startRecognition(speechRecognizerRequest { audioSource = source }).collect { resp ->
					when(resp)
					{
						is SpeechRecognizerResponse.PartialTextResponse -> if(heardAt == 0L) heardAt = System.currentTimeMillis()
						is SpeechRecognizerResponse.FinalTextResponse ->
						{
							val t = resp.text.trim()
							val began = if(heardAt > 0L) heardAt - 500 else System.currentTimeMillis() - 1500
							heardAt = 0L
							if(t.length >= 2) { Diag.add("sound heard: $t"); onLine(t, began) }
						}
						is SpeechRecognizerResponse.ErrorResponse ->
						{
							Diag.add("sound: error ${resp.e.message}")
							ended = if(resp.e.message?.contains("PERMISSION") == true) Ended.REFUSED else Ended.ERROR
						}
						else -> {}
					}
				}
			}
		}
		finally { writer.cancel(); try { pipe[1].close() } catch(e: Exception) { } }
		ended
	}
}
