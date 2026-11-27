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
import com.inarabic.Diag
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
	/** a small copy of the same picture, at most this wide, quick enough to follow movement (null: not offered) */
	private val small: (suspend (Int) -> Bitmap?)? = null,
)
{
	/** reading the Remote Play stream */
	constructor(surfaceView: SurfaceView, prefs: InArabicPrefs, onCaption: (String?) -> Unit) :
		this(surfaceView.context, prefs, { grabSurface(surfaceView) }, onCaption, { w -> grabSurface(surfaceView, w) })

	companion object
	{
		private const val TAG = "InArabic"
		private val copyThread = HandlerThread("ia-pixelcopy").apply { start() }
		private val copyHandler = Handler(copyThread.looper)

		/** A copy of the stream picture, at most 1280 px wide (enough for subtitles, cheap to read). */
		suspend fun grabSurface(surfaceView: SurfaceView, maxWidth: Int = 1280): Bitmap? = withContext(Dispatchers.Main) {
			val w = surfaceView.width
			val h = surfaceView.height
			if(w < 16 || h < 16 || !surfaceView.holder.surface.isValid) return@withContext null
			// always this wide, whatever the view's size: the stream's own frames are copied, so the small floating
			// window is read as well as the full screen
			val scale = maxWidth.toFloat() / w
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
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> Log.w("InArabic", "caption job", e); Diag.add("error: " + e) })
	private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	private val translator = Translator(prefs)
	private var paddle: PaddleReader? = null
	// a subtitle stays until it is really gone (several empty reads), not at the first missed read
	private val subTracker = SubtitleTracker(emptyReadsToClear = 4)
	/**
	 * One text beside the dialogue (a menu entry, a prompt, a help box) while it is on screen. It is the same
	 * item as long as it stays in the same place, even if one read spells it a little differently; it is
	 * translated once, and that translation stays until the item has really left the screen.
	 */
	private class SideItem(var st: SideText, var box: FloatArray, var colored: List<Colored>, var seen: Int, val fg: Int, val bg: Int)
	{
		@Volatile var arabic: String? = null
		@Volatile var spans: List<ColorSpan> = emptyList()
		var asked = false
		/** times an incomplete translation (English left in it) was asked again */
		var tries = 0
		var retryAt = 0L
	}
	private val sideItems = ArrayList<SideItem>()
	private var sideSent = ""
	private var refreshedAt = 0L
	private val box2Tracker = SubtitleTracker(emptyReadsToClear = 2)
	private val sideTracker = SubtitleTracker(emptyReadsToClear = 2)
	private var nextId = 0
	@Volatile private var currentId = -1
	private var loop: Job? = null
	private var lastBox2Read = 0L
	private var lastScreenRead = 0L
	/** how many times the side frames and the screen were read (an item is gone after two reads without it) */
	private var screenReads = 0
	/** lines found by the last read of an area (a PlayStation menu fills the screen with text) */
	private var lastLineCount = 0
	/** how many separate texts the dialogue frame held at the last read, and lines too small to count (for the log) */
	private var lastGroups = 0
	private var lastSmall = 0
	private var diagAt = 0L
	private var screenLineCount = 0
	/** the PlayStation's own screens (menus, control centre) are on: translation paused */
	@Volatile var systemUi = false
		private set
	/** called on the main thread with a short notice for you (AI limit reached…) */
	var onNotice: ((String) -> Unit)? = null
	private var aiErrorShownAt = 0L
	private fun aiFailed(why: String)
	{
		val t = System.currentTimeMillis()
		if(t - aiErrorShownAt < 20000) return
		aiErrorShownAt = t
		Diag.add("AI failed: $why")
		val msg = "$why — الترجمة واقفة لين يرجع"
		LinkServer.sendNotice(msg)
		mainHandler.post { onNotice?.invoke(msg) }
	}

	/** called on the main thread when [systemUi] changes */
	var onSystemUi: ((Boolean) -> Unit)? = null
	/** coloured phrases of the subtitle on screen now (all its reads together) */
	@Volatile private var subColored: List<Colored> = emptyList()
	/** phrase -> colour -> how many reads saw it, for the line on screen */
	private val lineColors = LinkedHashMap<String, HashMap<Int, Int>>()

	private fun addLineColors(found: List<Colored>)
	{
		var added = false
		synchronized(lineColors) {
			for(c in found)
			{
				val k = c.english.lowercase()
				val m = lineColors.getOrPut(k) { added = true; HashMap() }
				// colours a little apart in light are the same colour: counted together
				val same = m.keys.firstOrNull { sameHue(it, c.color) } ?: c.color
				m[same] = (m[same] ?: 0) + 1
			}
			// a shorter phrase inside a longer one is the same words: the longer one is kept
			val keys = lineColors.keys.toList()
			for(k in keys) if(keys.any { it != k && it.length > k.length && it.contains(k) }) lineColors.remove(k)
			subColored = lineColors.map { (k, m) -> Colored(k, m.maxByOrNull { it.value }!!.key) }
		}
		// a colour seen only now, after the line was already shown: shown again with it
		if(added) current?.let { c -> if(c.id == currentId) scope.launch {
			LinkServer.sendSub(c.id, c.arabic, c.english, true, c.box, spans(c.english, c.arabic, subColored))
		} }
	}
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
		TranslationMemory.engine = prefs.engine
		SessionLog.begin(context, prefs.gameName)
		com.inarabic.translate.Games.select(prefs.gameName)
		LinkServer.start(context)
		LinkServer.sendStyle(prefs)
		com.inarabic.voice.Dubber.start(context)
		com.inarabic.DiagUpload.start(context)
		// «صوت اللعبة»: the dialogue comes from the game's speech, not from the subtitles
		fromSound = prefs.dialogFromSound
		if(fromSound)
		{
			val notice = { msg: String -> LinkServer.sendNotice(msg); mainHandler.post { onNotice?.invoke(msg) }; Unit }
			sound = SoundDialogue(context, { en, at -> heard(en, at) }, notice) { fromSound = false }.also { if(!it.start(scope)) { fromSound = false; sound = null } }
		}
		if(prefs.secondReader) scope.launch { paddle = PaddleReader.open(context) }
		loop = scope.launch {
			while(isActive)
			{
				val t0 = System.currentTimeMillis()
				try { step() } catch(e: CancellationException) { throw e } catch(e: Exception) { Log.w(TAG, "step", e) }
				// how long one reading of the screen takes: a line on screen is found at most this late
				Diag.count("read: one reading of the screen", System.currentTimeMillis() - t0)
				// while there is dialogue (a line now or in the last 3 s): every 0.2 s at least, so a fast line is caught
				val every = if(current != null || System.currentTimeMillis() - lastLineAt < 3000) minOf(prefs.readEveryMs, 200L) else prefs.readEveryMs
				delay((every - (System.currentTimeMillis() - t0)).coerceAtLeast(40))
			}
		}
	}

	fun stop()
	{
		loop?.cancel()
		loop = null
		com.inarabic.voice.Dubber.stop()
		sound?.stop(); sound = null
		com.inarabic.DiagUpload.stop(context)
		lensJob?.cancel(); lensRect = null
		LinkServer.sendLensClear()
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
		scope.launch { LinkServer.sendSub(c.id, arabic, c.english, true, c.box, spans(c.english, arabic, subColored)) } // your words, the game's colours
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
			var subSmall = 0; var subGroups = 0 // for the log: lines too small for dialogue, separate texts read
			for(z in zones.filter { it.dialog && !fromSound })
			{
				val r = px(z.r); if(r.width() < 16 || r.height() < 8) continue
				val got = readArea(frame, r, 0.018f, if(prefs.centerOnly) side else null, keep = subTracker.current)
				subLines += lastLineCount
				subSmall += lastSmall
				if(read.text.isBlank() && got.text.isNotBlank()) { read = got; subGroups = lastGroups }
			}
			// side frames (tasks, prompts, messages) and, if on, the whole picture: read less often
			val t1 = System.currentTimeMillis()
			if(t1 - lastScreenRead > 400)
			{
				lastScreenRead = t1
				screenReads++
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
							st != null && (st.button != null || com.inarabic.translate.Glossary.lookup(st.text) != null ||
								st.text.split(Regex("\\s+")).size >= 8) // a help box or a description, not a sign
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
				if(busy) { synchronized(sideItems) { sideItems.clear() }; sideSent = ""; subTracker.reset(); sideTracker.reset(); currentId = -1; current = null; LinkServer.sendClear(); LinkServer.sendSideClear(); post(null) }
				mainHandler.post { onSystemUi?.invoke(busy) }
			}
			if(busy)
			{
				val t9 = System.currentTimeMillis()
				if(t9 - diagAt > 1000) { diagAt = t9; Diag.add("read: موقف، الشاشة فيها قائمة بلايستيشن ($subLines سطر في الحوار، $screenLineCount في الشاشة)") }
				return
			}
			if(prefs.dialogOnly) side.clear() else side.addAll(screenItems)
			// never the same text twice: a side text lying on the subtitle (two frames over each other) is dropped
			val sb = read.box
			fun onSubtitle(b: FloatArray): Boolean
			{
				if(sb == null || read.text.isBlank()) return false
				val ix = minOf(b[0] + b[2], sb[0] + sb[2]) - maxOf(b[0], sb[0])
				val iy = minOf(b[1] + b[3], sb[1] + sb[3]) - maxOf(b[1], sb[1])
				return ix > 0 && iy > 0 && ix * iy > 0.3f * b[2] * b[3]
			}
			val seen = HashSet<String>()
			val now = System.currentTimeMillis()
			// texts inside the lens are the lens's (drawn with their own colours): not twice
			updateSide(side.filter { r -> r.box != null && !onSubtitle(r.box) && !inLens(r.box) && seen.add(SubtitleTracker.key(r.text)) }, now)
			// what is on screen is sent again every few seconds: the TVs keep it as long as the English is there
			if(now - refreshedAt > 4000) { refreshedAt = now; LinkServer.refresh() }
			// the dialogue comes from the game's speech (heard): nothing more to read
			if(fromSound) return
			val ev0 = subTracker.feed(read)
			if(now - diagAt > 1000 || ev0 != null)
			{
				diagAt = now
				Diag.add("read: " + when
				{
					read.text.isNotBlank() -> "\"${read.text.replace('\n', '/').take(80)}\" ($subGroups نص) → " + when(ev0)
					{
						is TrackerEvent.NewLine -> "سطر جديد"; is TrackerEvent.Update -> "تحديث"; TrackerEvent.Clear -> "مسح"
						else -> if(SubtitleTracker.looksLikeSubtitle(SubtitleTracker.clean(read.text))) "نفس السطر" else "مرفوض: ما يشبه حوار"
					}
					subSmall > 0 -> "ما فيه حوار ($subSmall سطر أصغر من حد الحوار)"
					else -> "ما فيه كلام في مربع الحوار"
				})
			}
			// the line's coloured words, gathered over all its reads (one read may miss a colour: light behind the
			// letters, a frame in motion); a new line starts from nothing
			if(ev0 is TrackerEvent.NewLine || ev0 == TrackerEvent.Clear) synchronized(lineColors) { lineColors.clear() }
			if(read.text.isNotBlank()) addLineColors(read.colored)
			if(read.text.isNotBlank()) lastSubRead = read.text
			// «تنسيق خاص» for the dialogue: its own colours, from its first clear reading of the line
			if(ev0 is TrackerEvent.NewLine || ev0 == TrackerEvent.Clear) LinkServer.subLook = null
			if(LinkServer.subLook == null && read.box != null && read.text.isNotBlank()) LinkServer.subLook = intArrayOf(read.fg, read.bg)
			if(ev0 != null) Diag.add("sub ${ev0.javaClass.simpleName}: " + read.text)
			when(val ev = ev0)
			{
				is TrackerEvent.NewLine -> { com.inarabic.voice.Dubber.lineCame(); translate(++nextId, ev.text, ev.box) }
				is TrackerEvent.Update -> translate(currentId.takeIf { it >= 0 } ?: ++nextId, ev.text, ev.box)
				TrackerEvent.Clear -> { com.inarabic.voice.Dubber.lineLeft(now); currentId = -1; current = null; LinkServer.sendClear(); post(null) }
				null -> {}
			}
		}
		finally { frame.recycle() }
	}

	/** the dialogue as last read (a retry of an incomplete translation uses the newest reading) */
	@Volatile private var lastSubRead = ""

	/** who speaks to whom (the corrected lines' «you») */
	private val talk = Talk()
	private val talkTo = LinkedHashMap<Int, List<Char?>>()

	/** «صوت اللعبة» on, and working */
	@Volatile private var fromSound = false
	private var sound: SoundDialogue? = null

	/** a sentence heard in the game's speech (begun at [startedAt]): a dialogue line like a subtitle */
	private fun heard(english: String, startedAt: Long)
	{
		val id = ++nextId
		translate(id, english, null, seenAt = startedAt)
		// a spoken line has no end on screen: it stays long enough to read, then goes if nothing newer came
		scope.launch {
			delay((2500L + english.length * 60L).coerceAtMost(8000L))
			if(currentId == id) { currentId = -1; current = null; LinkServer.sendClear(); post(null) }
		}
	}

	/** [seenAt]: when the line came (a heard one: when it began to be said) */
	private fun translate(id: Int, english: String, box: FloatArray?, tries: Int = 0, seenAt: Long = System.currentTimeMillis())
	{
		currentId = id
		lastLineAt = seenAt
		if(tries == 0) Diag.count("lines")
		// whom each line is said to (for the corrected lines' «you»): worked out once, when the line comes
		val lines = english.split('\n').filter { it.isNotBlank() }
		val to = if(tries == 0) talk.plan(lines, seenAt).also { synchronized(talkTo) { talkTo[id] = it; while(talkTo.size > 30) talkTo.remove(talkTo.keys.first()) } }
			else synchronized(talkTo) { talkTo[id] } ?: emptyList()
		// a line the game file corrects: never from memory (an earlier Google translation of it is wrong)
		val corrected = lines.indices.any { com.inarabic.translate.Games.fix(lines[it], to.getOrNull(it)) != null }
		if(corrected && tries == 0) Diag.add("corrected #$id (said to ${to.joinToString { (it ?: '?').toString() }}): ${english.replace('\n', '/')}")
		scope.launch {
			val known0 = if(corrected) null else TranslationMemory.get(english)
			// the fixed game words win over anything translated before (not over your own corrections)
			val fixed = if(known0?.source == TranslationMemory.Source.USER) null else com.inarabic.translate.Glossary.lookup(english)
			if(fixed != null) { Diag.count("translated: game words"); show(id, english, fixed, true, box, seenAt); return@launch }
			// a line seen before: from memory at once. Your corrections always; anything else only if it is complete
			// (an old translation with English left in it is asked again)
			val known = known0?.takeIf { it.source == TranslationMemory.Source.USER || com.inarabic.translate.Translator.complete(it.arabic) }
			if(known != null) { Diag.count("translated: from memory"); show(id, english, known.arabic, true, box, seenAt); return@launch }
			val t0 = System.currentTimeMillis()
			val ar = translator.quick(english, to)
			val took = System.currentTimeMillis() - t0
			if(ar == null)
			{
				Diag.count("translation failed")
				Diag.add("translate #$id FAILED after $took ms: ${translator.lastAiError}: $english")
				if(currentId == id) aiFailed(translator.lastAiError ?: "Google Translate ما رد")
				return@launch
			}
			val done = com.inarabic.translate.Translator.complete(ar)
			Diag.count("translated: google", took)
			// how long after the line came its translation is ready, and whether English is left in it
			Diag.add("translate #$id: $took ms (line seen ${System.currentTimeMillis() - seenAt} ms ago)" + if(done) "" else " INCOMPLETE, English left: $ar")
			if(!done) Diag.count("translation incomplete (English left)")
			// only a complete translation is remembered: an incomplete one would come back every time (and not a
			// corrected one: it depends on whom it is said to)
			if(done && !corrected) TranslationMemory.put(english, ar, TranslationMemory.Source.QUICK)
			if(currentId != id)
			{
				// the dialogue moved on before the answer came: still shown if nothing newer is on screen yet; else
				// it is kept in the session log (never lost)
				if(lastShownId > id)
				{
					Diag.count("translation MISSED (newer line already shown)")
					Diag.add("MISSED #$id: its translation came after a newer line was shown, never on screen: $english")
					SessionLog.add(english, com.inarabic.translate.Glossary.arabicNames(ar)); return@launch
				}
				Diag.count("translation late (line already gone)")
				Diag.add("LATE #$id: shown after its line left the screen: $english")
				showLate(id, english, ar, seenAt)
				return@launch
			}
			show(id, english, ar, true, box, seenAt)
			// English left in it: translated again from the next readings (a cleaner reading usually fixes it), 3 times at most
			if(!done && tries < 3)
			{
				delay(1500)
				if(currentId == id) translate(id, lastSubRead.ifBlank { english }, current?.box ?: box, tries + 1)
			}
		}
	}

	@Volatile private var lastShownId = -1

	/** a translation that came after its line left: shown where the current line is, until the next one */
	private suspend fun showLate(id: Int, english: String, arabic: String, seenAt: Long)
	{
		val box = current?.box ?: lastBox
		lastShownId = id
		val a = com.inarabic.translate.Glossary.arabicNames(arabic)
		LinkServer.sendSub(id, a, english, true, box, spans(english, a, subColored))
		SessionLog.add(english, a)
		com.inarabic.voice.Dubber.say(id, english, a, seenAt, namedOnly = !fromSound)
		post(a)
		// on screen long enough to read, then gone if nothing newer came
		scope.launch {
			delay(3000)
			if(lastShownId == id && currentId != id) { LinkServer.sendClear(); post(null) }
		}
	}
	@Volatile private var lastBox: FloatArray? = null

	private suspend fun show(id: Int, english: String, arabic0: String, final: Boolean, box: FloatArray?, seenAt: Long)
	{
		if(id > lastShownId) lastShownId = id
		if(box != null) lastBox = box
		val arabic = com.inarabic.translate.Glossary.arabicNames(arabic0)
		Diag.add("show #$id ${if(final) "final" else "first"}: $english → $arabic")
		current = Current(id, english, arabic, box)
		LinkServer.sendSub(id, arabic, english, final, box, spans(english, arabic, subColored))
		if(final) { SessionLog.add(english, arabic); com.inarabic.voice.Dubber.say(id, english, arabic, seenAt, namedOnly = !fromSound) }
		post(arabic)
	}

	private fun translateBox2(english: String, box: FloatArray)
	{
		scope.launch {
			val known = TranslationMemory.get(english)
			val ar = known?.arabic?.takeIf { known.source == TranslationMemory.Source.USER || com.inarabic.translate.Translator.complete(it) } ?: translator.quick(english) ?: return@launch
			if(known == null && com.inarabic.translate.Translator.complete(ar)) TranslationMemory.put(english, ar, TranslationMemory.Source.QUICK)
			LinkServer.sendBox2(ar, box)
		}
	}

	private fun overlap(a: FloatArray, b: FloatArray): Float
	{
		val ix = minOf(a[0] + a[2], b[0] + b[2]) - maxOf(a[0], b[0])
		val iy = minOf(a[1] + a[3], b[1] + b[3]) - maxOf(a[1], b[1])
		if(ix <= 0 || iy <= 0) return 0f
		return ix * iy / minOf(a[2] * a[3], b[2] * b[3])
	}

	/** this read's side texts: matched to the items already on screen by place, new ones added and translated once */
	private fun updateSide(reads: List<OcrRead>, now: Long)
	{
		synchronized(sideItems) {
			for(r in reads)
			{
				val st = SideText.clean(r.text) ?: continue
				val box = r.box ?: continue
				val same = sideItems.firstOrNull { overlap(it.box, box) > 0.5f }
				if(same != null)
				{
					same.seen = screenReads
					// a coloured word this read saw and the earlier ones missed: the item is coloured again
					val more = r.colored.filter { c -> same.colored.none { it.english.equals(c.english, true) } }
					if(more.isNotEmpty())
					{
						same.colored = same.colored + more
						val ar = same.arabic
						if(ar != null) scope.launch { same.spans = spans(same.st.text, ar.removeSuffix("  ${same.st.button}"), same.colored); sideSent = ""; sendSideIfChanged() }
					}
					// a longer, fuller reading of the same thing (still untranslated) replaces a cut one
					if(same.arabic == null && !same.asked && st.text.length > same.st.text.length) { same.st = st; same.box = box }
					continue
				}
				sideItems += SideItem(st, box, r.colored, screenReads, r.fg, r.bg)
			}
			// gone once it has been missing from two readings in a row (one missed reading is not enough: no flicker).
			// Counted in readings, not time: a full screen takes a second or more to read
			sideItems.removeAll { screenReads - it.seen >= 2 }
			for(it in sideItems) if(!it.asked && now >= it.retryAt) { it.asked = true; translateItem(it) }
		}
		sendSideIfChanged()
	}

	@Synchronized private fun sendSideIfChanged()
	{
		val ready = synchronized(sideItems) { sideItems.filter { it.arabic != null }.map { LinkServer.SideOut(it.arabic!!, it.box, it.spans, it.fg, it.bg) } }
		val sig = ready.joinToString("|") { it.arabic + "@" + (it.box[0] * 100).toInt() + "," + (it.box[1] * 100).toInt() + (it.spans.hashCode()) }
		if(sig == sideSent) return
		sideSent = sig
		if(ready.isEmpty()) { Diag.add("side clear"); LinkServer.sendSideClear() }
		else { Diag.add("side show: " + ready.joinToString(" | ") { it.arabic }); LinkServer.sendSide(ready) }
	}

	/** one item, once: the fixed game word, your correction, the AI (when on) or the on-device translator */
	private fun translateItem(item: SideItem)
	{
		scope.launch {
			val st = item.st
			val g = com.inarabic.translate.Glossary.lookup(st.text)
			val known = TranslationMemory.get(st.text)
			val ar: String? = when
			{
				known?.source == TranslationMemory.Source.USER -> known.arabic
				g != null -> g
				// remembered only if complete; else Google Translate (remembered only if complete)
				else -> known?.arabic?.takeIf { com.inarabic.translate.Translator.complete(it) }
					?: translator.quick(st.text)?.also { if(com.inarabic.translate.Translator.complete(it)) TranslationMemory.put(st.text, it, TranslationMemory.Source.QUICK) }
			}
			if(ar == null) { item.retryAt = System.currentTimeMillis() + 6000; item.asked = false; return@launch } // try again a bit later
			val arN = com.inarabic.translate.Glossary.arabicNames(ar)
			// English left in it: shown, and asked again in a moment (3 times at most); a complete one replaces it
			if(!com.inarabic.translate.Translator.complete(arN) && item.tries < 3) { item.tries++; item.retryAt = System.currentTimeMillis() + 1500; item.asked = false }
			if(item.arabic != null && !com.inarabic.translate.Translator.complete(arN) && leftEnglish(arN) >= leftEnglish(item.arabic!!)) return@launch
			item.spans = spans(st.text, arN, item.colored)
			item.arabic = if(st.button != null) "$arN  ${st.button}" else arN
			Diag.add("side item: ${st.text} → ${item.arabic}")
			sendSideIfChanged()
		}
	}

	/** how many English words are left in a translation (names and brands aside) */
	private fun leftEnglish(ar: String) = Regex("(?<![A-Za-z])[a-z][a-z']+(?![A-Za-z])").findAll(ar).count()


	/** Arabic stretches to colour: only the game's own coloured words (the Arabic keeps the English colours) */
	private suspend fun spans(english: String, arabic: String, colored: List<Colored>): List<ColorSpan> =
		gameSpans(english, arabic, colored).sortedBy { it.start }

	// ---- the lens: a box you place on the phone with two fingers; whatever English is inside is translated
	// at once and drawn over it, same size and same colours (letters and background), on the phone and the TVs ----
	@Volatile private var lensRect: android.graphics.RectF? = null
	private var lensJob: Job? = null
	private val lensCache = java.util.concurrent.ConcurrentHashMap<String, String>()
	@Volatile private var lensSent = ""

	/** the whole screen is translated (a double tap): the lens over all of the picture, the dialogue left to itself */
	@Volatile private var lensFull = false

	/** the box in 0..1 of the picture; null: closed. [full]: the whole screen (no frame on the phone) */
	fun setLens(r: android.graphics.RectF?, full: Boolean = false)
	{
		lensFull = r != null && full
		lensRect = r?.let { android.graphics.RectF(it) }
		lensJob?.cancel(); lensJob = null
		trackJob?.cancel(); trackJob = null
		lensSent = ""
		synchronized(lensItems) { lensItems.clear() }
		synchronized(trackLock) { trackRef = null }
		if(r == null) { LinkServer.sendLensClear(); return }
		// following the shaking is off (not exact enough: the translations stay still, steadied by the reads instead);
		// set FOLLOW_MOVEMENT to try it again
		if(FOLLOW_MOVEMENT && !full && small != null) trackJob = scope.launch {
			while(isActive)
			{
				val t0 = System.currentTimeMillis()
				try { trackStep() } catch(e: CancellationException) { throw e } catch(e: Exception) { Log.w(TAG, "track", e) }
				delay((66 - (System.currentTimeMillis() - t0)).coerceAtLeast(15))
			}
		}
		lensJob = scope.launch {
			while(isActive)
			{
				val t0 = System.currentTimeMillis()
				try { lensStep() } catch(e: CancellationException) { throw e } catch(e: Exception) { Log.w(TAG, "lens", e) }
				delay((1000 - (System.currentTimeMillis() - t0)).coerceAtLeast(200))
			}
		}
	}

	private fun inLens(b: FloatArray): Boolean
	{
		val l = lensRect ?: return false
		val cx = b[0] + b[2] / 2; val cy = b[1] + b[3] / 2
		return cx >= l.left && cx <= l.right && cy >= l.top && cy <= l.bottom
	}

	private suspend fun lensStep()
	{
		val lr = lensRect ?: return
		val frame = source() ?: return
		// the follower's reference: the same moment as the picture read here
		val ref = if(trackJob != null) small?.invoke(TRACK_W)?.let { gray(it).also { _ -> it.recycle() } } else null
		try
		{
			val area = Rect((lr.left * frame.width).toInt(), (lr.top * frame.height).toInt(), (lr.right * frame.width).toInt(), (lr.bottom * frame.height).toInt())
			if(!area.intersect(0, 0, frame.width, frame.height) || area.width() < 16 || area.height() < 8) return
			val reads = ArrayList<OcrRead>()
			// the whole screen: not the tiny HUD text, and not the dialogue (it has its own line already)
			readArea(frame, area, if(lensFull) 0.014f else 0.008f, reads, everyLine = true, countLines = false)
			val sub = if(lensFull && current != null) (current?.box ?: lastBox) else null
			fun onSub(b: FloatArray): Boolean
			{
				if(sub == null) return false
				val cx = b[0] + b[2] / 2; val cy = b[1] + b[3] / 2
				return cx >= sub[0] - 0.02f && cx <= sub[0] + sub[2] + 0.02f && cy >= sub[1] - 0.03f && cy <= sub[1] + sub[3] + 0.03f
			}
			val found = reads.filter { it.box != null && it.text.any(Char::isLetter) && !onSub(it.box) }.mapTo(ArrayList()) { rd ->
				LensItem(rd.text.trim(), rd.box!!, rd.fg, rd.bg, rd.colored)
			}
			// a message read in pieces (its last line as a block of its own): the pieces are put together first, so the
			// whole message is translated as one and its Arabic covers all of its English
			val joined = joinPieces(found)
			found.clear(); found.addAll(joined)
			// steady: a text read again a little differently (the picture shook: a letter lost, a line cut) is the
			// same message, its translation and colours kept; it only moves to where it is now
			val prev = synchronized(lensItems) { lensItems.toList() }
			val used = HashSet<LensState>()
			val keep = ArrayList<LensState>()
			val fresh = ArrayList<LensItem>()
			for(f in found)
			{
				// the same message: in the same place with much the same text, or close by with nearly the same text.
				// A message read in two pieces this time (or two read as one) still belongs to the one already shown.
				val m = prev.filter { (overlap(it.box, f.box) > 0.5f && similar(it.english, f.english) >= 0.5f) ||
						(near(it.box, f.box) && similar(it.english, f.english) >= 0.8f) ||
						(overlap(it.box, f.box) > 0.7f && (it.english.contains(f.english.take(12), true) || f.english.contains(it.english.take(12), true))) }
					.maxByOrNull { overlap(it.box, f.box) + similar(it.english, f.english) }
				if(m != null)
				{
					if(m !in used)
					{
						used += m; m.missed = 0
						// it stays where it is unless it really moved (no jitter from one read to the next)
						val dx = kotlin.math.abs((m.box[0] + m.box[2] / 2) - (f.box[0] + f.box[2] / 2))
						val dy = kotlin.math.abs((m.box[1] + m.box[3] / 2) - (f.box[1] + f.box[3] / 2))
						if(dx > 0.012f || dy > 0.012f) m.box = f.box
						m.lastRead = f.english
						keep += m
					}
				}
				else fresh += f
			}
			// a message missing from a few reads (shaking, a read in pieces) stays; after 4 s without it, it is gone.
			// A new text over the same place replaces it only if it really is another message.
			for(p in prev) if(p !in used && ++p.missed <= 4 && fresh.none { overlap(it.box, p.box) > 0.5f && similar(it.english, p.english) < 0.5f }) keep += p
			val done = coroutineScope { fresh.map { async { it to lensTranslate(it.english) } }.awaitAll() }
			// English left in a shown translation: asked again from the newest reading; kept only if it has less English
			val redo = keep.filter { !com.inarabic.translate.Translator.complete(it.arabic) && it.tries < 3 }
			coroutineScope { redo.map { st -> async { st.tries++; st to lensTranslate(st.lastRead) } }.awaitAll() }.forEach { (st, ar) ->
				if(ar != null && leftEnglish(ar) < leftEnglish(st.arabic)) { st.arabic = ar; st.spans = spans(st.lastRead, ar, emptyList()) }
			}
			if(lensRect == null) return
			for((item, ar) in done) if(ar != null && keep.none { overlap(it.box, item.box) > 0.5f })
				keep += LensState(item.english, item.box, ar, item.fg, item.bg, spans(item.english, ar, item.colored))
			synchronized(lensItems) { lensItems.clear(); lensItems.addAll(keep) }
			val ready = keep.map { LinkServer.LensOut(it.arabic, it.box, it.fg, it.bg, it.spans) }
			val sig = ready.joinToString("|") { it.arabic + "@" + (it.box[0] * 400).toInt() + "," + (it.box[1] * 400).toInt() }
			if(sig == lensSent) return
			lensSent = sig
			// the new places and the new reference go together: the follower measures from this picture on
			synchronized(trackLock) {
				trackRef = ref; trackGen++
				lastMove = floatArrayOf(0f, 0f, 0f)
				if(ready.isEmpty()) LinkServer.sendLensClear() else LinkServer.sendLens(ready)
			}
		}
		finally { frame.recycle() }
	}

	private class LensItem(val english: String, val box: FloatArray, val fg: Int, val bg: Int, val colored: List<Colored>)

	/** pieces of one message: right under each other, same width side by side, same letter size and same bubble colour */
	private fun joinPieces(items: List<LensItem>): List<LensItem>
	{
		val out = ArrayList<LensItem>()
		for(it in items.sortedBy { it.box[1] })
		{
			val last = out.lastOrNull()
			if(last != null && samePiece(last, it)) { out[out.size - 1] = merge(last, it); continue }
			out += it
		}
		return out
	}

	private fun lineH(b: FloatArray) = if(b.size > 4 && b[4] > 0f) b[4] else b[3]

	private fun samePiece(a: LensItem, b: LensItem): Boolean
	{
		val ha = lineH(a.box); val hb = lineH(b.box)
		if(kotlin.math.abs(ha - hb) > maxOf(ha, hb) * 0.25f) return false
		val gap = b.box[1] - (a.box[1] + a.box[3])
		if(gap > ha * 0.9f || gap < -ha * 0.5f) return false
		val ox = minOf(a.box[0] + a.box[2], b.box[0] + b.box[2]) - maxOf(a.box[0], b.box[0])
		if(ox < minOf(a.box[2], b.box[2]) * 0.5f) return false
		fun d(x: Int, y: Int) = kotlin.math.abs(((x shr 16) and 255) - ((y shr 16) and 255)) + kotlin.math.abs(((x shr 8) and 255) - ((y shr 8) and 255)) + kotlin.math.abs((x and 255) - (y and 255))
		return d(a.bg, b.bg) < 60 && d(a.fg, b.fg) < 120
	}

	private fun merge(a: LensItem, b: LensItem): LensItem
	{
		val l = minOf(a.box[0], b.box[0]); val t = minOf(a.box[1], b.box[1])
		val r = maxOf(a.box[0] + a.box[2], b.box[0] + b.box[2]); val btm = maxOf(a.box[1] + a.box[3], b.box[1] + b.box[3])
		val base = floatArrayOf(l, t, r - l, btm - t, (lineH(a.box) + lineH(b.box)) / 2)
		// tilted: the own width is the wider one; the own height runs from the top of the first to the bottom of the second
		val box = if(a.box.size > 7 && b.box.size > 7)
		{
			val ang = Math.toRadians(a.box[5].toDouble())
			// centres in parts of the picture's height (the own sizes are measured that way); 16:9 picture
			val ax = (a.box[0] + a.box[2] / 2) * 16f / 9f; val ay = a.box[1] + a.box[3] / 2
			val bx = (b.box[0] + b.box[2] / 2) * 16f / 9f; val by = b.box[1] + b.box[3] / 2
			val along = (-(bx - ax) * kotlin.math.sin(ang) + (by - ay) * kotlin.math.cos(ang)).toFloat()
			base + floatArrayOf(a.box[5], maxOf(a.box[6], b.box[6]), a.box[7] / 2 + b.box[7] / 2 + kotlin.math.abs(along))
		}
		else base
		return LensItem(a.english.trimEnd() + " " + b.english.trimStart(), box, a.fg, a.bg, a.colored + b.colored)
	}
	/** a message in the lens: its translation and colours fixed once, its place updated at each read */
	private class LensState(val english: String, var box: FloatArray, var arabic: String, val fg: Int, val bg: Int, var spans: List<ColorSpan>, var missed: Int = 0)
	{
		var tries = 0
		/** the newest reading of this message (a retry uses it) */
		var lastRead = english
	}
	private val lensItems = ArrayList<LensState>()

	private fun near(a: FloatArray, b: FloatArray): Boolean
	{
		val dx = (a[0] + a[2] / 2) - (b[0] + b[2] / 2); val dy = (a[1] + a[3] / 2) - (b[1] + b[3] / 2)
		return kotlin.math.abs(dx) < 0.08f && kotlin.math.abs(dy) < 0.06f
	}

	/** how alike two readings are, 0..1 (letters and digits only, ignoring case) */
	private fun similar(a0: String, b0: String): Float
	{
		val a = a0.lowercase().filter(Char::isLetterOrDigit); val b = b0.lowercase().filter(Char::isLetterOrDigit)
		if(a == b) return 1f
		if(a.isEmpty() || b.isEmpty()) return 0f
		var prevRow = IntArray(b.length + 1) { it }
		for(i in 1..a.length)
		{
			val row = IntArray(b.length + 1); row[0] = i
			for(j in 1..b.length) row[j] = minOf(prevRow[j] + 1, row[j - 1] + 1, prevRow[j - 1] + if(a[i - 1] == b[j - 1]) 0 else 1)
			prevRow = row
		}
		return 1f - prevRow[b.length].toFloat() / maxOf(a.length, b.length)
	}

	// ---- following the movement: between two reads (once a second) the picture inside the box is compared,
	// about 15 times a second, with the one read last; the shift (and a small turn) moves the translations ----
	private val TRACK_W = 320
	private val FOLLOW_MOVEMENT = false
	private class Gray(val w: Int, val h: Int, val px: IntArray)
	private val trackLock = Any()
	private var trackRef: Gray? = null
	private var trackGen = 0
	private var trackJob: Job? = null
	/** last sent: dx, dy (0..1 of the picture), turn in degrees */
	private var lastMove = floatArrayOf(0f, 0f, 0f)

	private fun gray(b: Bitmap): Gray
	{
		val w = b.width; val h = b.height
		val c = IntArray(w * h); b.getPixels(c, 0, w, 0, 0, w, h)
		for(i in c.indices) { val v = c[i]; c[i] = (((v shr 16) and 255) * 3 + ((v shr 8) and 255) * 6 + (v and 255)) / 10 }
		return Gray(w, h, c)
	}

	/** the best shift (dx, dy in pixels) of the patch [x0,y0,x1,y1) of [ref] in [cur]; null if nothing matches */
	private fun shift(ref: Gray, cur: Gray, x0: Int, y0: Int, x1: Int, y1: Int, r: Int): IntArray?
	{
		var best = Long.MAX_VALUE; var bx = 0; var by = 0; var n = 0
		fun sad(dx: Int, dy: Int, step: Int, limit: Long): Long
		{
			var sum = 0L; var cnt = 0
			var y = y0
			while(y < y1)
			{
				val ry = y * ref.w; val cy = (y + dy) * cur.w
				var x = x0
				while(x < x1) { sum += kotlin.math.abs(ref.px[ry + x] - cur.px[cy + x + dx]); cnt++; x += step }
				if(sum > limit) return Long.MAX_VALUE
				y += step
			}
			n = cnt
			return sum
		}
		// coarse (every 2nd shift, every 3rd pixel), then the pixels around the best
		var dy = -r
		while(dy <= r) { var dx = -r; while(dx <= r) { val v = sad(dx, dy, 3, best); if(v < best) { best = v; bx = dx; by = dy }; dx += 2 }; dy += 2 }
		val cx = bx; val cy = by; best = Long.MAX_VALUE
		for(ddy in -1..1) for(ddx in -1..1)
		{
			val dx2 = cx + ddx; val dy2 = cy + ddy
			if(kotlin.math.abs(dx2) > r || kotlin.math.abs(dy2) > r) continue
			val v = sad(dx2, dy2, 2, best); if(v < best) { best = v; bx = dx2; by = dy2 }
		}
		// a poor match (the scene changed, not shaken): no answer
		if(n == 0 || best / n > 28) return null
		return intArrayOf(bx, by)
	}

	private suspend fun trackStep()
	{
		val lr = lensRect ?: return
		val (ref, gen) = synchronized(trackLock) { (trackRef ?: return) to trackGen }
		val bmp = small?.invoke(TRACK_W) ?: return
		val cur = try { gray(bmp) } finally { bmp.recycle() }
		if(cur.w != ref.w || cur.h != ref.h) return
		val r = (cur.w * 0.05f).toInt().coerceAtLeast(4) // up to 5 % of the picture each way
		val x0 = (lr.left * cur.w).toInt() + r; val x1 = (lr.right * cur.w).toInt() - r
		val y0 = (lr.top * cur.h).toInt() + r; val y1 = (lr.bottom * cur.h).toInt() - r
		if(x1 - x0 < 16 || y1 - y0 < 8) return
		val mid = (x0 + x1) / 2
		val left = shift(ref, cur, x0, y0, mid, y1, r) ?: return
		val right = shift(ref, cur, mid, y0, x1, y1, r) ?: return
		val dx = (left[0] + right[0]) / 2f; val dy = (left[1] + right[1]) / 2f
		// a small turn: the right half moved up or down more than the left one
		var turn = Math.toDegrees(kotlin.math.atan2((right[1] - left[1]).toDouble(), ((x1 - x0) / 2).toDouble())).toFloat()
		if(kotlin.math.abs(turn) < 0.6f) turn = 0f
		val move = floatArrayOf(dx / cur.w, dy / cur.h, turn.coerceIn(-8f, 8f))
		synchronized(trackLock) {
			if(gen != trackGen || lensRect == null) return // a newer read came meanwhile: measured from the old picture
			val l = lastMove
			if(kotlin.math.abs(move[0] - l[0]) * cur.w < 0.6f && kotlin.math.abs(move[1] - l[1]) * cur.h < 0.6f && kotlin.math.abs(move[2] - l[2]) < 0.4f) return
			lastMove = move
			LinkServer.sendLensMove(move[0], move[1], move[2], (lr.left + lr.right) / 2, (lr.top + lr.bottom) / 2)
		}
	}

	/** the same choice as for side texts: your correction, the fixed game word, the AI (when on) or the on-device model */
	private suspend fun lensTranslate(english: String): String?
	{
		lensCache[english]?.let { return it }
		val st = SideText.clean(english) ?: return null
		val g = com.inarabic.translate.Glossary.lookup(st.text)
		val known = TranslationMemory.get(st.text)
		val ar: String = when
		{
			known?.source == TranslationMemory.Source.USER -> known.arabic
			g != null -> g
			else -> known?.arabic?.takeIf { com.inarabic.translate.Translator.complete(it) }
				?: translator.quick(st.text)?.also { if(com.inarabic.translate.Translator.complete(it)) TranslationMemory.put(st.text, it, TranslationMemory.Source.QUICK) }
		} ?: return null
		val arN = com.inarabic.translate.Glossary.arabicNames(ar)
		val out = if(st.button != null) "$arN  ${st.button}" else arN
		// only a complete translation is kept for the box: an incomplete one is asked again
		if(com.inarabic.translate.Translator.complete(arN)) lensCache[english] = out
		return out
	}


	/**
	 * The colours of one line of text: the background is what lies around the letters (the edge of the box),
	 * the letters are the pixels farthest from it.
	 */
	private fun lineColors(bmp: Bitmap, r: Rect): Pair<Int, Int>
	{
		val box = Rect(r)
		if(!box.intersect(0, 0, bmp.width, bmp.height) || box.width() < 3 || box.height() < 3)
			return android.graphics.Color.WHITE to android.graphics.Color.BLACK
		fun ch(c: Int, s: Int) = (c shr s) and 255
		// the background: a ring just outside the letters (inside the bubble, clear of the strokes and their soft
		// edges), so the Arabic's background is the bubble's own colour and does not show as a box of its own
		val pad = maxOf(3, (minOf(box.height(), 40) * 0.3f).toInt())
		val ring = Rect(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad)
		ring.intersect(0, 0, bmp.width, bmp.height)
		val edge = ArrayList<Int>()
		val sx = maxOf(1, ring.width() / 48); val sy = maxOf(1, ring.height() / 12)
		var x = ring.left
		while(x < ring.right) { edge += bmp.getPixel(x, ring.top); edge += bmp.getPixel(x, ring.bottom - 1); x += sx }
		var y = ring.top
		while(y < ring.bottom) { edge += bmp.getPixel(ring.left, y); edge += bmp.getPixel(ring.right - 1, y); y += sy }
		fun med(list: List<Int>, s: Int) = list.map { ch(it, s) }.sorted()[list.size / 2]
		val bg = android.graphics.Color.rgb(med(edge, 16), med(edge, 8), med(edge, 0))
		val inner = ArrayList<Int>()
		val ix = maxOf(1, box.width() / 60); val iy = maxOf(1, box.height() / 16)
		y = box.top
		while(y < box.bottom) { x = box.left; while(x < box.right) { inner += bmp.getPixel(x, y); x += ix }; y += iy }
		fun dist(c: Int) = kotlin.math.abs(ch(c, 16) - ch(bg, 16)) + kotlin.math.abs(ch(c, 8) - ch(bg, 8)) + kotlin.math.abs(ch(c, 0) - ch(bg, 0))
		// the letters' own colour: the few pixels farthest from the background (the middle of the strokes)
		val far = inner.sortedByDescending(::dist).take(maxOf(3, inner.size / 25))
		if(far.isEmpty() || dist(far[0]) < 60) return (if(lum(bg) > 128) android.graphics.Color.BLACK else android.graphics.Color.WHITE) to bg
		val hsv = FloatArray(3)
		android.graphics.Color.RGBToHSV(med(far, 16), med(far, 8), med(far, 0), hsv)
		val darker = lum(android.graphics.Color.rgb(med(far, 16), med(far, 8), med(far, 0))) < lum(bg)
		// the video is a little soft: grey letters are made crisp (black text black, white text white); coloured
		// letters keep their colour (dark blue stays dark blue), only a little deeper or brighter
		if(hsv[1] < 0.25f) { if(darker) hsv[2] *= 0.55f else hsv[2] = maxOf(hsv[2], 0.95f) }
		else { if(darker) hsv[2] *= 0.85f else hsv[2] = maxOf(hsv[2], 0.9f); hsv[1] = minOf(1f, hsv[1] * 1.1f) }
		return android.graphics.Color.HSVToColor(hsv) to bg
	}
	/** the colour of the brightest strokes in the line (white stays white; a light yellow stays yellow) */
	private fun brightLetters(bmp: Bitmap, r: Rect): Int
	{
		val box = Rect(r)
		if(!box.intersect(0, 0, bmp.width, bmp.height) || box.width() < 3 || box.height() < 3) return android.graphics.Color.WHITE
		val px = ArrayList<Int>()
		val sx = maxOf(1, box.width() / 80); val sy = maxOf(1, box.height() / 16)
		var y = box.top
		while(y < box.bottom) { var x = box.left; while(x < box.right) { px += bmp.getPixel(x, y); x += sx }; y += sy }
		val top = px.sortedByDescending { lum(it) }.take(maxOf(3, px.size / 15))
		fun med(s: Int) = top.map { (it shr s) and 255 }.sorted()[top.size / 2]
		val hsv = FloatArray(3)
		android.graphics.Color.RGBToHSV(med(16), med(8), med(0), hsv)
		if(hsv[1] < 0.25f) return android.graphics.Color.WHITE
		hsv[2] = maxOf(hsv[2], 0.9f)
		return android.graphics.Color.HSVToColor(hsv)
	}

	/**
	 * The game's own colours of a text (one place for all: dialogue, menus, the lens): its background is what lies
	 * around it; its letters, on a background of its own (a band, a bubble), are the pixels farthest from it, and
	 * over the moving scene the brightest strokes (game text is light with a dark edge).
	 */
	private fun letterColors(bmp: Bitmap, r: Rect): Pair<Int, Int>
	{
		val (fg, bg) = lineColors(bmp, r)
		return (if(plainBehind(bmp, r)) fg else brightLetters(bmp, r)) to bg
	}

	/** the text has a background of its own (one even colour around it: a band, a box), not the moving scene */
	private fun plainBehind(bmp: Bitmap, r: Rect): Boolean
	{
		val pad = maxOf(3, (minOf(r.height(), 40) * 0.3f).toInt())
		val ring = Rect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)
		if(!ring.intersect(0, 0, bmp.width, bmp.height) || ring.width() < 4 || ring.height() < 4) return false
		val v = ArrayList<Double>()
		val sx = maxOf(1, ring.width() / 48); val sy = maxOf(1, ring.height() / 12)
		var x = ring.left
		while(x < ring.right) { v += lum(bmp.getPixel(x, ring.top)); v += lum(bmp.getPixel(x, ring.bottom - 1)); x += sx }
		var y = ring.top
		while(y < ring.bottom) { v += lum(bmp.getPixel(ring.left, y)); v += lum(bmp.getPixel(ring.right - 1, y)); y += sy }
		val mean = v.average()
		val sd = kotlin.math.sqrt(v.sumOf { (it - mean) * (it - mean) } / v.size)
		return sd < 14
	}

	private fun lum(c: Int) = 0.299 * ((c shr 16) and 255) + 0.587 * ((c shr 8) and 255) + 0.114 * (c and 255)

	private suspend fun gameSpans(english: String, arabic: String, colored: List<Colored>): List<ColorSpan>
	{
		val mine = colored.filter { english.contains(it.english, ignoreCase = true) }
		if(mine.isEmpty()) return emptyList()
		// the whole line in one colour: colour all of it
		if(mine.size == 1 && mine[0].english.length >= english.trim { !it.isLetterOrDigit() }.length - 1)
			return listOf(ColorSpan(0, arabic.length, mine[0].color))
		val out = ArrayList<ColorSpan>()
		for(c in mine)
		{
			fun free(sp: ColorSpan) = out.none { sp.start < it.end && sp.end > it.start }
			// its own translation first ("bag" → حقيبة)
			val first = TranslationMemory.get(c.english)?.arabic ?: translator.quick(c.english)
			var got = first?.let { ColorText.place(arabic, listOf(it to c.color)).filter(::free) }.orEmpty()
			// not in the sentence as such (the sentence says شنطة where the word alone gave كيس): Google's other words for it
			if(got.isEmpty()) for(alt in translator.alternatives(c.english))
			{
				got = ColorText.place(arabic, listOf(alt to c.color)).filter(::free)
				if(got.isNotEmpty()) break
			}
			out += got
		}
		return out.sortedBy { it.start }
	}

	private fun post(text: String?) { mainHandler.post { if(prefs.showOnPhone) onCaption(text) else onCaption(null) } }

	/**
	 * OCR of one area of the picture; lines joined top to bottom; box is in 0..1 of the whole picture.
	 * With [sideOut]: only lines centred on the picture are the subtitle; the others (a mission title or a button
	 * prompt in a corner) are put in [sideOut], one read per line, so they neither mix into the subtitle nor move it.
	 */
	/**
	 * [keep]: the line on screen now. Its letters read a little smaller in one picture than the next (a task line
	 * is about the size limit): while it is on screen it is still taken down to 70 % of [minLineH], so it does not
	 * vanish and come back (blinking, and translated and said again).
	 */
	private suspend fun readArea(frame: Bitmap, area: Rect, minLineH: Float, sideOut: MutableList<OcrRead>? = null, everyLine: Boolean = false, countLines: Boolean = true, keep: String? = null): OcrRead
	{
		if(countLines) lastLineCount = 0
		val crop = Bitmap.createBitmap(frame, area.left, area.top, area.width(), area.height())
		try
		{
			val result: Text = suspendCancellableCoroutine<Text?> { c ->
				recognizer.process(InputImage.fromBitmap(crop, 0))
					.addOnSuccessListener { if(c.isActive) c.resume(it) }
					.addOnFailureListener { if(c.isActive) c.resume(null) }
			} ?: return OcrRead("")
			val keepKey = keep?.let { SubtitleTracker.key(it) }
			val ok = { l: Text.Line ->
				val h = l.boundingBox?.height() ?: 0
				// skip tiny HUD text; the line on screen keeps a little slack
				h >= frame.height * minLineH || (keepKey != null && h >= frame.height * minLineH * 0.7f &&
					SubtitleTracker.key(l.text).let { k -> k.length >= 4 && keepKey.contains(k) })
			}
			val all = result.textBlocks.flatMap { it.lines }.filter(ok).sortedBy { it.boundingBox?.top ?: 0 }
			if(countLines) { lastLineCount = all.size; lastSmall = result.textBlocks.sumOf { it.lines.size } - all.size }
			fun centred(l: Text.Line): Boolean
			{
				val b = l.boundingBox ?: return false
				// a menu bar ("SELECT   CONFIRM   REDRAW   SKIP") is never the subtitle
				if(segments(l).size > 1) return false
				// the subtitle sits in the middle of its frame (a wide line always does)
				val cx = b.exactCenterX() / area.width()
				return kotlin.math.abs(cx - 0.5f) < 0.15f || b.width() > area.width() * 0.6f
			}
			// the dialogue: the centred groups of lines stacked at the bottom of the frame (a dialogue line with a task
			// line under it, "Cal: Bodies don't…" over "Drive the boat": both). Each group is its own text, translated
			// on its own and drawn on its own line, in the same order. A centred group far above them (a screen
			// title, a card's text) is a side text.
			val groups = ArrayList<ArrayList<Text.Line>>()
			if(sideOut == null) { if(all.isNotEmpty()) groups += ArrayList(all) }
			else
			{
				val c = all.filter(::centred).sortedBy { it.boundingBox!!.top }
				val g = ArrayList<ArrayList<Text.Line>>()
				for(l in c)
				{
					val last = g.lastOrNull()?.last()
					val gap = if(last == null) Int.MAX_VALUE else l.boundingBox!!.top - last.boundingBox!!.bottom
					if(last != null && gap < last.boundingBox!!.height() * 1.2f) g.last() += l else g += arrayListOf(l)
				}
				// from the lowest group up, while the next one is close above it (within 2.5 lines)
				for(i in g.indices.reversed())
				{
					val first = groups.firstOrNull()?.first()
					if(first != null && first.boundingBox!!.top - g[i].last().boundingBox!!.bottom > first.boundingBox!!.height() * 2.5f) break
					groups.add(0, g[i])
				}
			}
			val subtitle = groups.flatten()
			lastGroups = groups.size
			if(sideOut != null)
			{
				// side texts by block: a help box ("Ammu-Nations will expand / their stock…") is one paragraph,
				// but stacked button prompts ("SHOOT R2 / AIM L2") stay one item per line
				for(block in result.textBlocks)
				{
					val bl = block.lines.filter(ok).filter { it.boundingBox != null && (everyLine || it !in subtitle) }
						.sortedBy { it.boundingBox!!.top }
					if(bl.isEmpty()) continue
					val prompts = bl.size == 1 || bl.any { SideText.clean(it.text)?.button != null || segments(it).size > 1 }
					if(prompts) for(l in bl) for(seg in segments(l)) sideOut.add(seg.let { (txt, r) -> one(crop, txt, r, l, area, frame) })
					else sideOut.add(join(crop, bl, area, frame))
				}
			}
			if(groups.isEmpty()) return OcrRead("")
			if(groups.size == 1) return join(crop, groups[0], area, frame)
			// several texts: one read, each on its own line, the box around all of them, the colours of the first
			val parts = groups.map { join(crop, it, area, frame) }
			val b = parts.mapNotNull { it.box }
			val l = b.minOf { it[0] }; val t = b.minOf { it[1] }
			val r = b.maxOf { it[0] + it[2] }; val btm = b.maxOf { it[1] + it[3] }
			return OcrRead(parts.joinToString("\n") { it.text.replace('\n', ' ') }, floatArrayOf(l, t, r - l, btm - t, b[0][4]),
				parts.flatMap { it.colored }, parts[0].fg, parts[0].bg)
		}
		finally { crop.recycle() }
	}

	/**
	 * A line cut where the words are far apart: a menu bar ("MAP   BRIEF   PROGRESS") is several items, each
	 * translated over its own word. Normal word spacing never cuts.
	 */
	private fun segments(l: Text.Line): List<Pair<String, Rect>>
	{
		val els = l.elements.filter { it.boundingBox != null }.sortedBy { it.boundingBox!!.left }
		val lb = l.boundingBox ?: return emptyList()
		if(els.size < 2) return listOf(l.text to lb)
		val h = lb.height().toFloat()
		val out = ArrayList<Pair<String, Rect>>()
		var words = arrayListOf(els[0].text); var r = Rect(els[0].boundingBox)
		for(i in 1 until els.size)
		{
			val b = els[i].boundingBox!!
			if(b.left - r.right > h * 1.3f) { out += words.joinToString(" ") to r; words = arrayListOf(); r = Rect(b) } else r.union(b)
			words += els[i].text
		}
		out += words.joinToString(" ") to r
		return if(out.size == 1) listOf(l.text to lb) else out
	}

	/** one piece of a line as its own read */
	private fun one(crop: Bitmap, text: String, r: Rect, line: Text.Line, area: Rect, frame: Bitmap): OcrRead
	{
		if(r == line.boundingBox) return join(crop, listOf(line), area, frame)
		// a piece of a line: drawn level, at the line's real letter height
		val lineH = geometry(listOf(line))?.get(3) ?: r.height().toFloat()
		val box = floatArrayOf((r.left + area.left) / frame.width.toFloat(), (r.top + area.top) / frame.height.toFloat(),
			r.width() / frame.width.toFloat(), r.height() / frame.height.toFloat(), minOf(lineH, r.height().toFloat()) / frame.height.toFloat())
		val (fg, bg) = letterColors(crop, r)
		return OcrRead(text, box, fg = fg, bg = bg)
	}

	private fun join(crop: Bitmap, lines: List<Text.Line>, area: Rect, frame: Bitmap): OcrRead
	{
		val texts = lines.map { line -> reread(crop, line) }
		val r = Rect(lines[0].boundingBox ?: Rect())
		for(l in lines) l.boundingBox?.let { r.union(it) }
		// box[4]: the real height of one English line (not the height of its tilted box), so the Arabic is drawn
		// the same size; box[5..7]: when the text is tilted (a phone in the game), its angle and its own
		// width and height (in parts of the picture's height), so the Arabic is drawn with the same tilt
		val g = geometry(lines)
		val lineH = g?.get(3) ?: lines.mapNotNull { it.boundingBox?.height() }.sorted().let { it[it.size / 2].toFloat() }
		val fh = frame.height.toFloat()
		val base = floatArrayOf((r.left + area.left) / frame.width.toFloat(), (r.top + area.top) / fh,
			r.width() / frame.width.toFloat(), r.height() / fh, lineH / fh)
		val box = if(g != null && kotlin.math.abs(g[0]) >= 2f) base + floatArrayOf(g[0], g[1] / fh, g[2] / fh) else base
		// a list (menu entries, short lines under each other) stays one line each; a wrapped sentence is joined
		// (a wrapped sentence goes on in small letters: "Where are" / "you going?"; menu entries each start with a capital)
		val list = texts.size >= 2 && texts.all { it.trim().split(Regex("\\s+")).size <= 4 } &&
			(1 until texts.size).all { i ->
				val c = texts[i].trimStart().firstOrNull()
				c != null && (c.isUpperCase() || c.isDigit()) && !texts[i - 1].trimEnd().endsWith(",")
			}
		// a heading over a paragraph ("KENTON" / "Kenton runs diving tours…"): the heading on its own line
		val heading = !list && texts.size >= 3 && texts[0].trim().split(Regex("\\s+")).size <= 3 && texts.drop(1).count { it.trim().split(Regex("\\s+")).size >= 4 } >= 1
		val joined = when
		{
			list -> texts.joinToString("\n")
			heading -> texts[0] + "\n" + texts.drop(1).joinToString(" ")
			// a wrapped sentence is one line, but a line that starts with a speaker's name («Local: …») is the next
			// person speaking, never the sentence going on: its own line (else the voice says both, with the name)
			else -> texts.indices.joinToString("") { i -> (if(i == 0) "" else if(com.inarabic.voice.DubLines.speaker(texts[i]) != null) "\n" else " ") + texts[i] }
		}
		val (fg, bg) = letterColors(crop, r)
		return OcrRead(joined, box, coloredPhrases(crop, lines), fg, bg)
	}

	/**
	 * The lines' tilt and real size from their corner points: [angle in degrees, width, height, one line's height]
	 * (pixels; width and height measured along the tilt). Null when ML Kit gave no corners.
	 */
	private fun geometry(lines: List<Text.Line>): FloatArray?
	{
		val corners = lines.mapNotNull { l -> l.cornerPoints?.takeIf { it.size == 4 } }
		if(corners.isEmpty()) return null
		fun d(a: android.graphics.Point, b: android.graphics.Point) = kotlin.math.hypot((b.x - a.x).toFloat(), (b.y - a.y).toFloat())
		val angles = corners.map { c -> Math.toDegrees(kotlin.math.atan2((c[1].y - c[0].y).toDouble(), (c[1].x - c[0].x).toDouble())).toFloat() }.sorted()
		val angle = angles[angles.size / 2]
		val heights = corners.map { c -> (d(c[0], c[3]) + d(c[1], c[2])) / 2 }.sorted()
		val lineH = heights[heights.size / 2]
		// all corners turned back level around their middle: their extent is the text's own width and height
		val pts = corners.flatMap { it.toList() }
		val cx = (pts.minOf { it.x } + pts.maxOf { it.x }) / 2f; val cy = (pts.minOf { it.y } + pts.maxOf { it.y }) / 2f
		val a = Math.toRadians(-angle.toDouble()); val cs = kotlin.math.cos(a).toFloat(); val sn = kotlin.math.sin(a).toFloat()
		val xs = pts.map { (it.x - cx) * cs - (it.y - cy) * sn }; val ys = pts.map { (it.x - cx) * sn + (it.y - cy) * cs }
		return floatArrayOf(angle, xs.maxOf { it } - xs.minOf { it }, ys.maxOf { it } - ys.minOf { it }, lineH)
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
	 * The colour of one word's letters, or null if they are white / grey / black. The background is what lies just
	 * around the word; the letters are the pixels farthest from it (every pixel of the word is looked at, so thin
	 * strokes are not missed, and a bright wall or sky behind the word is not taken for its colour). Of those, the
	 * most coloured half decides (a dark edge around pink letters is not their colour).
	 */
	private fun colorOf(bmp: Bitmap, r: Rect): Int?
	{
		val box = Rect(r); if(!box.intersect(0, 0, bmp.width, bmp.height) || box.width() < 3 || box.height() < 3) return null
		fun ch(c: Int, s: Int) = (c shr s) and 255
		val pad = maxOf(2, box.height() / 4)
		val ring = Rect(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad)
		ring.intersect(0, 0, bmp.width, bmp.height)
		val edge = ArrayList<Int>()
		for(x in ring.left until ring.right step maxOf(1, ring.width() / 40)) { edge += bmp.getPixel(x, ring.top); edge += bmp.getPixel(x, ring.bottom - 1) }
		for(y in ring.top until ring.bottom step maxOf(1, ring.height() / 10)) { edge += bmp.getPixel(ring.left, y); edge += bmp.getPixel(ring.right - 1, y) }
		fun med(l: List<Int>, s: Int) = l.map { ch(it, s) }.sorted()[l.size / 2]
		val bg = intArrayOf(med(edge, 16), med(edge, 8), med(edge, 0))
		// every pixel (at most ~2400)
		val step = maxOf(1, kotlin.math.sqrt(box.width() * box.height() / 2400.0).toInt())
		val px = ArrayList<Int>()
		var y = box.top
		while(y < box.bottom) { var x = box.left; while(x < box.right) { px += bmp.getPixel(x, y); x += step }; y += step }
		if(px.size < 8) return null
		fun dist(c: Int) = kotlin.math.abs(ch(c, 16) - bg[0]) + kotlin.math.abs(ch(c, 8) - bg[1]) + kotlin.math.abs(ch(c, 0) - bg[2])
		val far = px.sortedByDescending(::dist).take(maxOf(6, px.size / 7))
		if(dist(far[0]) < 70) return null // no letters stand out
		val hsv = FloatArray(3)
		fun sat(c: Int): Float { android.graphics.Color.RGBToHSV(ch(c, 16), ch(c, 8), ch(c, 0), hsv); return hsv[1] * minOf(1f, hsv[2] * 1.5f) }
		val colored = far.sortedByDescending(::sat).take(maxOf(3, far.size / 2))
		android.graphics.Color.RGBToHSV(med(colored, 16), med(colored, 8), med(colored, 0), hsv)
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
