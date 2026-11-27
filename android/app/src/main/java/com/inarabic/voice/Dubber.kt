// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Dubbing: each dialogue line, once translated, is spoken in Arabic, by the voice of whoever says it.
//
//  - who: the name in front of the line («Lucia: …») is the character, with a voice of its own (Voices); with no
//    name, a man's or a woman's voice by how high the voice in the game's sound is (VoicePitch).
//  - one line at a time: the next waits for the one being spoken; a newer line replaces any line still waiting,
//    so the voice never falls behind the game.
//  - heard on the screens (TV, LG) or the phone, as chosen in the settings («وين تسمع الصوت»).

package com.inarabic.voice

import android.content.Context
import android.media.MediaPlayer
import com.inarabic.Diag
import com.inarabic.InArabicPrefs
import com.inarabic.link.LinkServer
import java.io.File

object Dubber
{
	private class Job(val id: Int, val part: DubLines.Part, val seenAt: Long)

	private val lock = Object()
	private val waiting = ArrayDeque<Job>()
	private var thread: Thread? = null
	@Volatile private var running = false
	/** each start has its own worker: an old one still asleep stops when it wakes */
	@Volatile private var generation = 0
	private var context: Context? = null
	private var prefs: InArabicPrefs? = null
	/** the line being spoken, and how many of its lines are said or being said */
	private var lineId = -1
	private var lineSaid = 0
	/** when what is playing now ends */
	private var busyUntil = 0L
	/** Microsoft's voices failed: the phone's voice is used for a while before they are tried again */
	private var edgeDownUntil = 0L
	private var player: MediaPlayer? = null

	/** a line older than this when its turn comes is not spoken any more (the game is past it) */
	private const val STALE_MS = 7000L
	/** how much of the line's sound is heard before telling a man from a woman */
	private const val LISTEN_MS = 700L
	/** Arabic takes longer to say than English: a little faster */
	private const val RATE = "+10%"

	fun start(c: Context)
	{
		synchronized(lock) {
			context = c.applicationContext
			prefs = InArabicPrefs(c)
			if(thread != null) return
			running = true
			PhoneTts.start(c)
			val gen = ++generation
			thread = Thread({ loop(gen) }, "dub").also { it.isDaemon = true; it.start() }
		}
	}

	fun stop()
	{
		synchronized(lock) { running = false; generation++; thread = null; waiting.clear(); lineId = -1; lineSaid = 0; lock.notifyAll() }
		stopPhone()
		PhoneTts.stop()
	}

	/**
	 * The dialogue line [id] is shown as [arabic] ([english] on screen since [seenAt]). Said once: the same line
	 * shown again (a better reading) is not said again, only lines added to it.
	 */
	fun say(id: Int, english: String, arabic: String, seenAt: Long)
	{
		val p = prefs ?: return
		if(!p.voiceOn || !running) return
		val en = DubLines.lines(english); val ar = DubLines.lines(arabic)
		synchronized(lock) {
			if(id != lineId)
			{
				// a new line: whatever still waits is the past
				lineId = id; lineSaid = 0; waiting.clear()
			}
			else
			{
				waiting.removeAll { it.id == id }
				// the lines no longer match one to one, after part of it was said: not said twice
				if(lineSaid > 0 && en.size != ar.size) return
			}
			if(lineSaid >= en.size) return
			val parts = DubLines.parts(en.drop(lineSaid), ar.drop(lineSaid))
			val base = lineSaid
			for(pt in parts) waiting.addLast(Job(id, DubLines.Part(pt.speaker, pt.arabic, base + pt.end), seenAt))
			lock.notifyAll()
		}
	}

	private fun loop(gen: Int)
	{
		while(true)
		{
			val job: Job = synchronized(lock) {
				while(gen == generation && waiting.isEmpty()) lock.wait()
				if(gen != generation) return
				waiting.first()
			}
			val c = context ?: return
			val p = prefs ?: return
			// who speaks is not known yet: enough of the line heard to tell a man from a woman
			val listen = p.voiceWho == "auto" && (job.part.speaker == null || !Voices.settled(c, job.part.speaker))
			val wait = job.seenAt + LISTEN_MS - System.currentTimeMillis()
			if(listen && wait > 0) Thread.sleep(wait)
			val heard = if(listen) VoicePitch.gender(heardPitch(job.seenAt)) else null
			val voice = when(p.voiceWho)
			{
				"male" -> Voices.order(p.voiceDialect, true).first()
				"female" -> Voices.order(p.voiceDialect, false).first()
				else -> if(job.part.speaker != null) Voices.voiceOf(c, job.part.speaker, heard, p.voiceDialect)
					else Voices.order(p.voiceDialect, heard != 'f').first()
			}
			val t0 = System.currentTimeMillis()
			val said = Dialect.say(job.part.arabic, p.voiceDialect)
			val clip = speak(c, said, voice)
			synchronized(lock) {
				// replaced while it was being made: not this one
				if(waiting.firstOrNull() !== job) return@synchronized null
				waiting.removeFirst()
				if(job.id == lineId) lineSaid = maxOf(lineSaid, job.part.end)
				job
			} ?: continue
			if(clip == null) { Diag.add("dub: no voice for \"${job.part.arabic.take(40)}\" (${EdgeTts.lastError})"); continue }
			val gap = busyUntil - System.currentTimeMillis()
			if(gap > 0) Thread.sleep(gap)
			if(System.currentTimeMillis() - job.seenAt > STALE_MS) { Diag.add("dub: late, skipped \"${job.part.arabic.take(40)}\""); continue }
			// a newer line came while this one waited for its turn: the newer one is said, not this
			if(synchronized(lock) { job.id != lineId }) { Diag.add("dub: newer line came, skipped \"${job.part.arabic.take(40)}\""); continue }
			Diag.add("dub: ${job.part.speaker ?: "?"} heard=${heard ?: '-'} → ${voice.label} (${clip.mime}, ${System.currentTimeMillis() - t0} ms): ${said.take(50)}")
			if(gen != generation) return
			play(c, p, clip)
			busyUntil = System.currentTimeMillis() + clip.ms + 150
		}
	}

	class Clip(val bytes: ByteArray, val mime: String, val ms: Long)

	/** [voice] saying [text]: Microsoft's voice, or the phone's own when that does not answer */
	private fun speak(c: Context, text: String, voice: Voices.V): Clip?
	{
		if(System.currentTimeMillis() > edgeDownUntil)
		{
			val mp3 = EdgeTts.say(text, voice.id, RATE)
			if(mp3 != null) return Clip(mp3, "audio/mpeg", mp3.size * 8000L / EdgeTts.BITRATE)
			edgeDownUntil = System.currentTimeMillis() + 60000
			Diag.add("dub: Microsoft voice failed (${EdgeTts.lastError}), phone voice for a minute")
		}
		val wav = PhoneTts.say(c, text, voice.male, Voices.all.indexOf(voice) / 2) ?: return null
		return Clip(wav, "audio/wav", wavMs(wav))
	}

	private fun wavMs(w: ByteArray): Long
	{
		fun le(o: Int, n: Int): Long { var v = 0L; for(i in 0 until n) v = v or ((w[o + i].toLong() and 255) shl (8 * i)); return v }
		val byteRate = le(28, 4).takeIf { it > 0 } ?: return 0
		return (w.size - 44) * 1000L / byteRate
	}

	/** how high the voice in the game's sound was since the line came (null: no voice in it) */
	private fun heardPitch(seenAt: Long): Float?
	{
		val ms = (System.currentTimeMillis() - seenAt + 150).coerceIn(400, 2900)
		val c = GameSound.recent(ms) ?: return null
		return VoicePitch.pitch(c.samples, c.rate, 0, c.count)
	}

	private fun play(c: Context, p: InArabicPrefs, clip: Clip)
	{
		// «الشاشة»: on the screens, or on the phone when no screen is linked (so it is heard anyway)
		val toScreens = p.voiceOut != "phone" && LinkServer.screens.isNotEmpty()
		val toPhone = p.voiceOut != "tv" || !toScreens
		if(toScreens) LinkServer.sendVoice(clip.mime, clip.bytes)
		if(toPhone) playOnPhone(c, clip)
	}

	private fun playOnPhone(c: Context, clip: Clip)
	{
		stopPhone()
		try
		{
			val f = File(c.cacheDir, if(clip.mime == "audio/mpeg") "dub.mp3" else "dub.wav")
			f.writeBytes(clip.bytes)
			player = MediaPlayer().apply {
				setDataSource(f.path)
				setOnCompletionListener { it.release(); if(player === it) player = null }
				prepare(); start()
			}
		}
		catch(e: Exception) { Diag.add("dub: phone play failed $e") }
	}

	private fun stopPhone() { try { player?.release() } catch(e: Exception) { }; player = null }

	/** the settings: hear [voice] once on the phone */
	fun preview(c: Context, voice: Voices.V)
	{
		Thread {
			PhoneTts.start(c)
			val clip = speak(c.applicationContext, "أهلًا، هذا صوتي في الترجمة", voice)
			if(clip != null) playOnPhone(c.applicationContext, clip)
			else Diag.add("dub preview failed (${EdgeTts.lastError})")
		}.start()
	}
}
