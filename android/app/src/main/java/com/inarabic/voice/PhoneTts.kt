// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// The phone's own Arabic voice (Android's text-to-speech), for when Microsoft's voices do not answer: no
// internet needed, standard Arabic only. A man and a woman differ by pitch; characters by the phone's voices.

package com.inarabic.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object PhoneTts
{
	private var tts: TextToSpeech? = null
	@Volatile private var ready = false
	private val waits = HashMap<String, CountDownLatch>()

	@Synchronized fun start(context: Context)
	{
		if(tts != null) return
		tts = TextToSpeech(context.applicationContext) { status ->
			ready = status == TextToSpeech.SUCCESS
		}.also { t ->
			t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
				override fun onStart(id: String?) {}
				override fun onDone(id: String?) { synchronized(waits) { waits.remove(id) }?.countDown() }
				@Deprecated("Deprecated in Java") override fun onError(id: String?) { synchronized(waits) { waits.remove(id) }?.countDown() }
			})
		}
	}

	@Synchronized fun stop() { try { tts?.shutdown() } catch(e: Exception) { }; tts = null; ready = false }

	/** a WAV of [text] said by a man or a woman; [variant] picks one of the phone's Arabic voices; [rate] 1 = normal */
	fun say(context: Context, text: String, male: Boolean, variant: Int, rate: Float = 1f): ByteArray?
	{
		val t = synchronized(this) { tts } ?: return null
		if(!ready) return null
		val voices = try { t.voices?.filter { it.locale.language == "ar" && !it.isNetworkConnectionRequired }?.sortedBy { it.name } } catch(e: Exception) { null }
		// several lines are made at once: each in its own file
		val id = "ia" + System.nanoTime()
		val file = File(context.cacheDir, "phone-voice-$id.wav")
		val latch = CountDownLatch(1)
		synchronized(waits) { waits[id] = latch }
		synchronized(this) {
			if(t.isLanguageAvailable(Locale("ar")) < TextToSpeech.LANG_AVAILABLE) { synchronized(waits) { waits.remove(id) }; return null }
			t.language = Locale("ar")
			if(!voices.isNullOrEmpty()) t.voice = voices[Math.floorMod(variant, voices.size)]
			t.setPitch(if(male) 0.85f else 1.25f)
			t.setSpeechRate(rate)
			if(t.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) { synchronized(waits) { waits.remove(id) }; return null }
		}
		if(!latch.await(8, TimeUnit.SECONDS)) { file.delete(); return null }
		return try { file.readBytes().takeIf { it.size > 44 } } catch(e: Exception) { null } finally { file.delete() }
	}
}
