// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Dubbing: each dialogue line, once translated, is spoken in Arabic, by the voice of whoever says it.
//
//  - who: the name in front of the line («Lucia: …») is the character, with a voice of its own (Voices); with no
//    name, a man's or a woman's voice by how high the voice in the game's sound is (VoicePitch).
//  - each line's voice is made as soon as it is translated, several at once (Microsoft takes 1–3 s a line), and
//    they are said in order; only a line more than a few seconds late is left out.
//  - its length fits the original: the game's speaking speed is learnt from how long its lines stay on screen,
//    and each Arabic line is played faster or slower (same pitch) to last as long as the English one.
//  - from the subtitles only lines with a speaker's name («Vicki: …») are said; one with no name is a task.
//    From the game's sound («صوت اللعبة») everything heard is speech.
//  - heard on the screens (TV, LG) or the phone, as chosen in the settings («وين تسمع الصوت»).

package com.inarabic.voice

import android.content.Context
import android.media.MediaPlayer
import com.inarabic.Diag
import com.inarabic.InArabicPrefs
import com.inarabic.link.LinkServer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

object Dubber
{
	/** [seenAt]: when the line began (the sound heard then tells who speaks); [readyAt]: when its text was ready */
	private class Job(val id: Int, val part: DubLines.Part, val seenAt: Long, val english: String)
	{
		val readyAt = System.currentTimeMillis()
		/** its voice, being made */
		@Volatile var clip: Future<Clip?>? = null
		/** who says it with which voice (for the log) */
		@Volatile var info = ""
	}

	private val lock = Object()
	/** the lines to say, in order */
	private val waiting = ArrayDeque<Job>()
	/** line id → how many of its lines are said (or being said) */
	private val said = LinkedHashMap<Int, Int>()
	/** the voices are made several at once */
	private val makers = Executors.newFixedThreadPool(3)
	private var thread: Thread? = null
	@Volatile private var running = false
	/** each start has its own worker: an old one still asleep stops when it wakes */
	@Volatile private var generation = 0
	private var context: Context? = null
	private var prefs: InArabicPrefs? = null
	/** what was said lately (a line that leaves the screen and comes back is not said twice): text → its line, when */
	private val recent = LinkedHashMap<String, Pair<Int, Long>>()
	/** the game's speaking speed, English characters a second, learnt from how long its lines stay on screen */
	@Volatile private var cps = 14f
	private var lastLine: Pair<Int, Long>? = null
	private var lastLineChars = 0
	/** when what is playing now ends */
	private var busyUntil = 0L
	/** Microsoft's voices failed: the phone's voice is used for a while before they are tried again */
	@Volatile private var edgeDownUntil = 0L
	private var player: MediaPlayer? = null

	/** a line whose text was ready longer ago than this when its turn comes is not said (the game is past it) */
	private const val STALE_MS = 6000L
	/** not said again within this time */
	private const val REPEAT_MS = 15000L
	/** how much of the line's sound is heard before telling a man from a woman */
	private const val LISTEN_MS = 700L
	/** how much faster or slower a line may be played to last as long as the English one */
	private const val SLOWEST = 0.9f
	private const val FASTEST = 1.6f

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
		synchronized(lock) { running = false; generation++; thread = null; waiting.forEach { it.clip?.cancel(true) }; waiting.clear(); said.clear(); recent.clear(); lastLine = null; lock.notifyAll() }
		stopPhone()
		PhoneTts.stop()
	}

	/**
	 * The dialogue line [id] is shown as [arabic] ([english] on screen since [seenAt]). Said once: the same line
	 * shown again (a better reading) is not said again, only lines added to it.
	 */
	fun say(id: Int, english: String, arabic: String, seenAt: Long, namedOnly: Boolean)
	{
		val p = prefs ?: return
		if(!p.voiceOn || !running) return
		val en = DubLines.lines(english); val ar = DubLines.lines(arabic)
		synchronized(lock) {
			// the game's speaking speed: the line before this one stayed this long for its characters
			lastLine?.let { (lid, at) ->
				val ms = seenAt - at
				if(lid != id && lastLineChars >= 8 && ms in 800..9000) cps = (cps * 0.8f + (lastLineChars * 1000f / ms) * 0.2f).coerceIn(8f, 22f)
			}
			if(lastLine?.first != id) { lastLine = id to seenAt }
			lastLineChars = en.sumOf { (if(DubLines.speaker(it) != null) it.substringAfter(':') else it).trim().length }
			val done = said[id] ?: 0
			// a newer reading of this line: its parts not yet said are made again from it
			waiting.filter { it.id == id }.forEach { it.clip?.cancel(true) }
			waiting.removeAll { it.id == id }
			// the lines no longer match one to one, after part of it was said: not said twice
			if(done > 0 && en.size != ar.size) return
			if(done >= en.size) return
			val now = System.currentTimeMillis()
			recent.entries.removeAll { now - it.value.second > REPEAT_MS }
			var from = done
			for(pt in DubLines.parts(en.drop(done), ar.drop(done), namedOnly))
			{
				// its English lines (for how long it should last): a task line among them does not count
				val enPart = en.subList(from, done + pt.end).filter { !namedOnly || DubLines.speaker(it) != null }.joinToString(" ") { it.substringAfter(':').trim() }
				from = done + pt.end
				val key = (pt.speaker ?: "") + ":" + pt.arabic
				// the same words from another line (it left the screen and came back): said once
				val before = recent[key]
				if(before != null && before.first != id)
				{
					Diag.count("dub: repeated line not said again")
					continue
				}
				recent[key] = id to now
				val j = Job(id, DubLines.Part(pt.speaker, pt.arabic, done + pt.end), seenAt, enPart)
				waiting.addLast(j)
				j.clip = makers.submit<Clip?> { make(j) }
			}
			lock.notifyAll()
		}
	}

	/** the voice of one line: who says it, in the dialect */
	private fun make(job: Job): Clip?
	{
		val c = context ?: return null
		val p = prefs ?: return null
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
		val text = Dialect.say(job.part.arabic, p.voiceDialect)
		val clip = speak(c, text, voice)
		job.info = "${job.part.speaker ?: "?"} heard=${heard ?: '-'} → ${voice.label} (${clip?.mime}; ${clip?.made}): ${text.take(50)}"
		return clip
	}

	/** says the lines in order, each when the one before it is over */
	private fun loop(gen: Int)
	{
		while(true)
		{
			val job: Job = synchronized(lock) {
				while(gen == generation && waiting.isEmpty()) lock.wait()
				if(gen != generation) return
				waiting.first()
			}
			val clip = try { job.clip?.get(12, TimeUnit.SECONDS) } catch(e: Exception) { null }
			synchronized(lock) {
				// replaced by a newer reading while its voice was made: the newer one is said
				if(waiting.firstOrNull() !== job) return@synchronized null
				waiting.removeFirst()
				said[job.id] = maxOf(said[job.id] ?: 0, job.part.end)
				while(said.size > 50) said.remove(said.keys.first())
				job
			} ?: continue
			val c = context ?: return
			val p = prefs ?: return
			if(clip == null) { Diag.add("dub: no voice for \"${job.part.arabic.take(40)}\" (${EdgeTts.lastError})"); continue }
			val gap = busyUntil - System.currentTimeMillis()
			if(gap > 0) Thread.sleep(gap)
			val now = System.currentTimeMillis()
			val late = now - job.seenAt
			if(now - job.readyAt > STALE_MS) { Diag.count("dub: left out (too late)"); Diag.add("dub: LEFT OUT, ${now - job.readyAt} ms after its text was ready: ${job.info}"); continue }
			if(gen != generation) return
			// as long as the English line: its length at the game's speaking speed
			val english = job.english.length.coerceAtLeast(4)
			val target = (english * 1000f / cps).coerceAtLeast(700f)
			val speed = (clip.ms / target).coerceIn(SLOWEST, FASTEST)
			Diag.count("dub: said, ms after the line began", late)
			Diag.add("dub: $late ms after the line, speed ×${"%.2f".format(speed)} (${clip.ms} ms for ${target.toLong()} ms of English): ${job.info}")
			play(c, p, clip, speed)
			busyUntil = System.currentTimeMillis() + (clip.ms / speed).toLong() + 150
		}
	}

	/** [made]: where the time went while it was made (for the log) */
	class Clip(val bytes: ByteArray, val mime: String, val ms: Long, val made: String)

	/** [voice] saying [text]: Microsoft's voice, or the phone's own when that does not answer */
	private fun speak(c: Context, text: String, voice: Voices.V): Clip?
	{
		if(System.currentTimeMillis() > edgeDownUntil)
		{
			// a shout is said louder
			val r = EdgeTts.say(text, voice.id, if(text.trimEnd().endsWith("!")) "+20%" else "+0%")
			if(r != null)
			{
				Diag.count("dub: Microsoft, first sound after", r.second.firstAudio)
				Diag.count("dub: Microsoft, connect", r.second.connect)
				return Clip(r.first, "audio/mpeg", r.first.size * 8000L / EdgeTts.BITRATE, r.second.toString())
			}
			// the line was replaced while its voice was made: not a failure of Microsoft's
			if(Thread.currentThread().isInterrupted) return null
			edgeDownUntil = System.currentTimeMillis() + 60000
			Diag.count("dub: Microsoft failed")
			Diag.add("dub: Microsoft voice failed (${EdgeTts.lastError}), phone voice for a minute")
		}
		val t0 = System.currentTimeMillis()
		val wav = PhoneTts.say(c, text, voice.male, Voices.all.indexOf(voice) / 2) ?: return null
		return Clip(wav, "audio/wav", wavMs(wav), "phone voice ${System.currentTimeMillis() - t0} ms")
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

	private fun play(c: Context, p: InArabicPrefs, clip: Clip, speed: Float = 1f)
	{
		// «الشاشة»: on the screens, or on the phone when no screen is linked (so it is heard anyway)
		val toScreens = p.voiceOut != "phone" && LinkServer.screens.isNotEmpty()
		val toPhone = p.voiceOut != "tv" || !toScreens
		if(toScreens) LinkServer.sendVoice(clip.mime, clip.bytes, speed)
		if(toPhone) playOnPhone(c, clip, speed)
	}

	/** [speed]: faster or slower, the pitch kept */
	private fun playOnPhone(c: Context, clip: Clip, speed: Float = 1f)
	{
		stopPhone()
		try
		{
			val f = File(c.cacheDir, if(clip.mime == "audio/mpeg") "dub.mp3" else "dub.wav")
			f.writeBytes(clip.bytes)
			player = MediaPlayer().apply {
				setDataSource(f.path)
				setOnCompletionListener { it.release(); if(player === it) player = null }
				prepare()
				if(speed != 1f) playbackParams = android.media.PlaybackParams().setSpeed(speed).setPitch(1f)
				start()
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
