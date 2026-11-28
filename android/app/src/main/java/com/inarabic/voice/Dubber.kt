// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Dubbing: each dialogue line, once translated, is spoken in Arabic, by the voice of whoever says it.
//
//  - who: the name in front of the line («Lucia: …») is the character, with a voice of its own (Voices); with no
//    name, a man's or a woman's voice by how high the voice in the game's sound is (VoicePitch).
//  - each line's voice is made as soon as it is translated, several at once, and they are said in order; only a
//    line more than a few seconds late is left out.
//  - on the phone it starts at the first piece of Microsoft's voice, not when the whole line has come (Sound.kt).
//  - how fast it talks: «سرعة الدبلجة» in the settings, or «ضبط السرعة تلقائي»: each line asked from Microsoft at
//    the speed that makes it last as long as the English one (the game's speaking speed is learnt from how long its
//    lines stay on screen, the voice's from the lines it has said).
//  - from the subtitles only lines with a speaker's name («Vicki: …») are said; one with no name is a task.
//    From the game's sound («صوت اللعبة») everything heard is speech.
//  - heard on the screens (TV, LG) or the phone, as chosen in the settings («وين تسمع الصوت»).

package com.inarabic.voice

import android.content.Context
import com.inarabic.Diag
import com.inarabic.InArabicPrefs
import com.inarabic.link.LinkServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

object Dubber
{
	/** [seenAt]: when the line began (the sound heard then tells who speaks); [readyAt]: when its text was ready */
	/** [enChars]: the length of its English (for «ضبط السرعة تلقائي») */
	private class Job(val id: Int, val part: DubLines.Part, val seenAt: Long, val enChars: Int)
	{
		val readyAt = System.currentTimeMillis()
		/** its voice, being made (the work, and the sound as it comes) */
		@Volatile var work: Future<*>? = null
		@Volatile var sound: Sound? = null
		/** its first piece of sound came, or it ended */
		val started = CountDownLatch(1)
		/** who says it with which voice, and where the time went (for the log) */
		@Volatile var info = ""
		/** how long it will last, at its speed (before its sound has all come) */
		@Volatile var estMs = 0L
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
	/** the game's speaking speed (English characters a second) and the voices' (Arabic characters a second at 100 %) */
	@Volatile private var gameCps = 14f
	@Volatile private var voiceCps = 13f
	/** the line on screen now: its id, when it came, its English characters (how long lines last: [gameCps]) */
	private var onScreen: Triple<Int, Long, Int>? = null
	/** when what the screens play now ends */
	private var busyUntil = 0L
	/** when the line being said now ends (the next lines wait for it) */
	@Volatile private var playingUntil = 0L
	/** Microsoft's voices failed: the phone's voice is used for a while before they are tried again */
	@Volatile private var edgeDownUntil = 0L

	/** a line whose text was ready longer ago than this when its turn comes is not said (the game is past it) */
	private const val STALE_MS = 6000L
	/** not said again within this time */
	private const val REPEAT_MS = 15000L
	/** how much of the line's sound is heard before telling a man from a woman */
	private const val LISTEN_MS = 700L

	fun start(c: Context)
	{
		synchronized(lock) {
			context = c.applicationContext
			prefs = InArabicPrefs(c)
			if(thread != null) return
			running = true
			PhoneTts.start(c)
			if(InArabicPrefs(c).voiceOn) EdgeTts.warm(2)
			val gen = ++generation
			thread = Thread({ loop(gen) }, "dub").also { it.isDaemon = true; it.start() }
		}
	}

	fun stop()
	{
		synchronized(lock) { running = false; generation++; thread = null; waiting.forEach { it.work?.cancel(true) }; waiting.clear(); said.clear(); recent.clear(); onScreen = null; lock.notifyAll() }
		PhoneTts.stop()
	}

	/**
	 * The dialogue line [id] is shown as [arabic] ([english] on screen since [seenAt]). Said once: the same line
	 * shown again (a better reading) is not said again, only lines added to it.
	 */
	/** a new line came on screen: connections to the voices are made ready while it is translated */
	fun lineCame()
	{
		if(prefs?.voiceOn == true && running) EdgeTts.warm(2)
	}

	/** the line on screen left at [at] (or another took its place): how long the game took to say it */
	fun lineLeft(at: Long)
	{
		synchronized(lock) {
			val (_, start, chars) = onScreen ?: return
			onScreen = null
			val ms = at - start
			if(chars >= 8 && ms in 700..12000) gameCps = (gameCps * 0.8f + chars * 1000f / ms * 0.2f).coerceIn(6f, 24f)
		}
	}

	fun say(id: Int, english: String, arabic: String, seenAt: Long, namedOnly: Boolean)
	{
		val p = prefs ?: return
		if(!p.voiceOn || !running) return
		val en = DubLines.lines(english); val ar = DubLines.lines(arabic)
		synchronized(lock) {
			if(onScreen?.first != id)
			{
				if(onScreen != null) lineLeft(seenAt)
				onScreen = Triple(id, seenAt, en.filter { !namedOnly || DubLines.speaker(it) != null }.sumOf { it.substringAfter(':').trim().length })
			}
			val done = said[id] ?: 0
			// a newer reading of this line: its parts not yet said are made again from it
			waiting.filter { it.id == id }.forEach { it.work?.cancel(true) }
			waiting.removeAll { it.id == id }
			// the lines no longer match one to one, after part of it was said: not said twice
			if(done > 0 && en.size != ar.size) return
			if(done >= en.size) return
			val now = System.currentTimeMillis()
			recent.entries.removeAll { now - it.value.second > REPEAT_MS }
			var from = done
			for(pt in DubLines.parts(en.drop(done), ar.drop(done), namedOnly))
			{
				// its English (a task line among it does not count)
				val spoken = en.subList(from, done + pt.end).filter { !namedOnly || DubLines.speaker(it) != null }.map { it.substringAfter(':').trim() }
				val enChars = spoken.sumOf { it.length }
				from = done + pt.end
				// the same words from another line (it left the screen and came back, or was read again with something
				// from the picture around it, as a new line): said once. Known by the English words said (its Arabic
				// may differ by a dot or two between readings, and the name read with it may carry a word more)
				val key = com.inarabic.caption.SubtitleTracker.key(spoken.joinToString(" "))
					.let { if(it.length < 8) (pt.speaker ?: "") + ":" + it else it }
				val before = recent[key]
				if(before != null && before.first != id)
				{
					Diag.count("dub: repeated line not said again")
					continue
				}
				recent[key] = id to now
				val j = Job(id, DubLines.Part(pt.speaker, pt.arabic, done + pt.end), seenAt, enChars)
				waiting.addLast(j)
				j.work = makers.submit { try { make(j) } finally { j.started.countDown() } }
			}
			lock.notifyAll()
		}
	}

	/** the voice of one line: who says it, in the dialect */
	private fun make(job: Job)
	{
		val c = context ?: return
		val p = prefs ?: return
		// the game's own people have their voice (unless you chose another one for them in the settings)
		val games = com.inarabic.translate.Games
		val known = games.person(job.part.speaker)?.takeIf { job.part.speaker?.let { Voices.fixed(c, it) } != true }
		// a passer-by («Local»): someone else each line, a man or a woman as heard; no voice kept for them
		val crowd = games.crowd(job.part.speaker)
		// a man or a woman: a passer-by's as the name says it; one of the game's people's, always
		val named = if(crowd) games.crowdGender(job.part.speaker) else known?.let { if(it.female) 'f' else 'm' }
		// who speaks is not known yet: enough of the line heard to tell a man from a woman
		val listen = p.voiceWho == "auto" && known == null &&
			(if(crowd) named == null else job.part.speaker == null || !Voices.settled(c, job.part.speaker))
		val wait = job.seenAt + LISTEN_MS - System.currentTimeMillis()
		if(listen && wait > 0) Thread.sleep(wait)
		val heard = named ?: if(listen) VoicePitch.gender(heardPitch(job.seenAt)) else null
		val voice = when(p.voiceWho)
		{
			"male" -> Voices.order(p.voiceDialect, true).first()
			"female" -> Voices.order(p.voiceDialect, false).first()
			else -> known?.voice?.let { Voices.byId(it) } ?: when
			{
				// any voice of theirs but a character's
				crowd -> Voices.order(p.voiceDialect, heard != 'f').let { all ->
					val taken = Voices.taken(c)
					all.filter { it.id !in taken }.ifEmpty { all }.random() }
				job.part.speaker != null -> Voices.voiceOf(c, job.part.speaker, heard, p.voiceDialect)
				else -> Voices.order(p.voiceDialect, heard != 'f').first()
			}
		}
		// names said right (vowel marks for the voice only: the screen shows them plain). The new system's translation
		// comes in the dialect and with all its vowel marks already
		val text = if(p.newSystem) job.part.arabic else com.inarabic.translate.Games.forVoice(Dialect.say(job.part.arabic, p.voiceDialect))
		// how long it is: its letters (the vowel marks are not said on their own)
		val letters = com.inarabic.translate.NewTranslator.plain(text).length
		// «ضبط السرعة تلقائي»: as fast as makes it end when the English does. A line whose turn comes late (the lines
		// before it are still being said) is said faster by as much, so the dub catches up with the game instead of
		// falling further behind until lines are left out; never faster than 180 %
		val rate = if(p.voiceAutoRate && job.enChars >= 4)
		{
			val enMs = job.enChars / gameCps * 1000
			val late = (startsAt(job) - job.seenAt).coerceAtLeast(0)
			(100f * (letters / voiceCps * 1000) / maxOf(enMs - late, enMs * 0.55f)).toInt().coerceIn(80, 180)
		}
		else p.voiceRate
		job.estMs = (letters / voiceCps * 1000 * 100 / rate).toLong()
		job.info = "${job.part.speaker ?: "?"} heard=${heard ?: '-'} → ${voice.label} ${rate}%: ${text.take(50)}"
		var sound: Sound? = null
		val made = speak(c, text, voice, rate) { sound = it; job.sound = it; job.started.countDown() }
		Diag.add("dub: made ($made): ${text.take(30)}")
		// the voice's own speed, learnt from what it said
		val s = sound
		if(s != null && s.ok && s.mime == "audio/mpeg" && letters >= 8)
		{
			val ms = s.ms()
			if(ms > 300) voiceCps = (voiceCps * 0.8f + letters * 1000f / ms * (100f / rate) * 0.2f).coerceIn(6f, 30f)
		}
	}

	/** when [job]'s turn comes: after the line being said and the ones waiting before it */
	private fun startsAt(job: Job): Long = synchronized(lock) {
		val now = System.currentTimeMillis()
		var at = maxOf(playingUntil, now)
		for(j in waiting) { if(j === job) break; at += if(j.estMs > 0) j.estMs else (com.inarabic.translate.NewTranslator.plain(j.part.arabic).length / voiceCps * 1000).toLong() }
		at
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
			// its first piece of sound (or its end)
			try { job.started.await(12, TimeUnit.SECONDS) } catch(e: InterruptedException) { }
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
			val sound = job.sound
			if(sound == null) { Diag.add("dub: no voice for \"${job.part.arabic.take(40)}\" (${EdgeTts.lastError})"); continue }
			val now = System.currentTimeMillis()
			if(now - job.readyAt > STALE_MS) { Diag.count("dub: left out (too late)"); Diag.add("dub: LEFT OUT, ${now - job.readyAt} ms after its text was ready: ${job.info}"); job.work?.cancel(true); continue }
			if(gen != generation) return
			val late = now - job.seenAt
			playingUntil = now + (if(sound.finished) sound.ms() else job.estMs)
			Diag.count("dub: starts, ms after the line began", late)
			Diag.add("dub: starts $late ms after the line: ${job.info}")
			// «الشاشة»: on the screens, or on the phone when no screen is linked (so it is heard anyway)
			val toScreens = p.voiceOut != "phone" && LinkServer.screens.isNotEmpty()
			val toPhone = p.voiceOut != "tv" || !toScreens
			// the screens get the whole line (when it has all come); the phone plays it as it comes
			if(toScreens) Thread {
				while(!sound.finished && gen == generation) Thread.sleep(20)
				if(sound.ok && gen == generation) LinkServer.sendVoice(sound.mime, sound.bytes())
			}.start()
			if(toPhone) PhoneAudio.play(sound) { gen != generation }
			else
			{
				// only the screens: the next one when this one is over there
				while(!sound.finished && gen == generation) Thread.sleep(20)
				val gap = maxOf(busyUntil, System.currentTimeMillis()) + sound.ms() + 150 - System.currentTimeMillis()
				busyUntil = System.currentTimeMillis() + gap
				if(gap > 0) Thread.sleep(gap)
			}
		}
	}

	/**
	 * [voice] saying [text] at [rate] % («سرعة الدبلجة»): Microsoft's voice in pieces as they come, or the phone's
	 * own when that does not answer. [begin] gets the sound as soon as it starts; the answer is where the time
	 * went (for the log).
	 */
	private fun speak(c: Context, text: String, voice: Voices.V, rate: Int, begin: (Sound) -> Unit): String
	{
		// «صوت الدبلجة»: Microsoft's voices, or the phone's own (at once, but less natural)
		if(InArabicPrefs(c).voiceEngine != "phone" && System.currentTimeMillis() > edgeDownUntil)
		{
			val s = Sound("audio/mpeg")
			var begun = false
			// a shout is said louder
			val r = EdgeTts.say(text, voice.id, (if(rate >= 100) "+" else "") + (rate - 100) + "%", if(text.trimEnd().endsWith("!")) "+20%" else "+0%") { piece ->
				s.add(piece)
				if(!begun) { begun = true; begin(s) }
			}
			s.end(r != null)
			if(r != null)
			{
				Diag.count("dub: Microsoft, first sound after", r.second.firstAudio)
				Diag.count("dub: Microsoft, connect", r.second.connect)
				return r.second.toString()
			}
			// the line was replaced while its voice was made: not a failure of Microsoft's
			if(Thread.currentThread().isInterrupted || begun) return "stopped"
			edgeDownUntil = System.currentTimeMillis() + 60000
			Diag.count("dub: Microsoft failed")
			Diag.add("dub: Microsoft voice failed (${EdgeTts.lastError}), phone voice for a minute")
		}
		val t0 = System.currentTimeMillis()
		val wav = PhoneTts.say(c, text, voice.male, Voices.all.indexOf(voice) / 2, rate / 100f) ?: return "no voice"
		val s = Sound("audio/wav")
		s.add(wav); s.end(true)
		begin(s)
		return "phone voice ${System.currentTimeMillis() - t0} ms"
	}

	/** how high the voice in the game's sound was since the line came (null: no voice in it) */
	private fun heardPitch(seenAt: Long): Float?
	{
		val ms = (System.currentTimeMillis() - seenAt + 150).coerceIn(400, 2900)
		val c = GameSound.recent(ms) ?: return null
		return VoicePitch.pitch(c.samples, c.rate, 0, c.count)
	}

	/** the settings: hear [voice] once on the phone */
	fun preview(c: Context, voice: Voices.V)
	{
		Thread {
			PhoneTts.start(c)
			var sound: Sound? = null
			speak(c.applicationContext, "أهلًا، هذا صوتي في الترجمة", voice, InArabicPrefs(c).voiceRate) { sound = it }
			sound?.let { PhoneAudio.play(it) { false } } ?: Diag.add("dub preview failed (${EdgeTts.lastError})")
		}.start()
	}
}
