// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// «النظام الجديد»: one translator, chosen in the settings, as if it were a new app: nothing of the old system goes
// into it or comes after it (no game file, no names, no corrections, no reading fixes, no memory).
//  - Haiku (Claude) or Gemini Flash (Google), told the same: shown the dialogue so far, to work out by itself who speaks to whom; it writes the Arabic
//    with all its vowel marks (the voice reads them; the screen shows the text without them: [plain]), in the
//    dialect chosen;
//  - Google's official Cloud Translation: each line as it comes.

package com.inarabic.translate

import com.inarabic.InArabicPrefs
import com.inarabic.voice.DubLines

object NewTranslator
{
	/** why the last translation failed, in Arabic */
	@Volatile var lastError: String? = null
		private set

	/** the dialogue so far (English line, its Arabic): what Haiku is shown to understand who speaks to whom */
	private val history = ArrayDeque<Pair<String, String>>()
	private const val HISTORY = 12

	/** a line translated before in this session (a task stays under each new line of dialogue; a line is read
	 * again while it stays on screen) */
	private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true)
	{
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 300
	}

	private val MARKS = Regex("[\\u064B-\\u0652\\u0670]")

	/** the Arabic without its vowel marks (what the screen shows) */
	fun plain(arabic: String) = MARKS.replace(arabic, "")

	@Synchronized fun reset() { history.clear(); cache.clear(); pausedUntil = 0 }

	/** the translator said it is over its limit: no request is sent before this time */
	@Volatile private var pausedUntil = 0L

	/**
	 * [english] (one line or lines under each other) in Arabic, by the new system's translator; null if it failed.
	 * Each line is kept once translated: only the lines not translated yet are sent («Find Dunk» under a new line of
	 * dialogue is not asked again), so as few requests as can be.
	 */
	fun translate(english: String, prefs: InArabicPrefs): String?
	{
		val lines = english.split('\n').map { it.trim() }.filter { it.isNotBlank() }
		if(lines.isEmpty()) return null
		val known = synchronized(this) { lines.map { cache[it] } }
		val todo = lines.indices.filter { known[it] == null }
		if(todo.isEmpty()) return known.joinToString("\n")
		val now = System.currentTimeMillis()
		if(now < pausedUntil) { lastError = "خلص حد المترجم: يرجع بعد ${(pausedUntil - now + 59_999) / 60_000} دقيقة"; return null }
		val ask = todo.map { lines[it] }
		val out = when(prefs.newTranslator)
		{
			"google" ->
			{
				if(prefs.googleKey.isBlank()) { lastError = "حط مفتاح قوقل الرسمي في الإعدادات"; return null }
				OnlineEngines.googleOfficial(ask, prefs.googleKey)
			}
			"gemini" ->
			{
				if(prefs.geminiKey.isBlank()) { lastError = "حط مفتاح Gemini في الإعدادات"; return null }
				ai(ask, prefs) { system, user -> OnlineEngines.gemini(system, user, prefs.geminiKey, prefs.rawSwears) }
			}
			else ->
			{
				if(prefs.haikuKey.isBlank()) { lastError = "حط مفتاح Haiku في الإعدادات"; return null }
				ai(ask, prefs) { system, user -> OnlineEngines.haiku(system, user, prefs.haikuKey) }
			}
		}
		if(out == null)
		{
			// over its limit: nothing sent until it says it takes requests again (every request would be refused)
			if(OnlineEngines.retryMs > 0) pausedUntil = now + OnlineEngines.retryMs
			lastError = OnlineEngines.lastError ?: "المترجم ما رد"
			return null
		}
		lastError = null
		// the lines did not come back one for one: shown as they came, none kept
		if(out.size != ask.size) return (known.filterNotNull() + out).joinToString("\n")
		synchronized(this)
		{
			for(i in ask.indices)
			{
				cache[ask[i]] = out[i]
				// only what people say is the dialogue (not a task or a menu)
				if(DubLines.speaker(ask[i]) != null) history.addLast(ask[i] to out[i])
			}
			while(history.size > HISTORY) history.removeFirst()
		}
		return lines.indices.joinToString("\n") { known[it] ?: out[todo.indexOf(it)] }
	}

	/** Haiku or Gemini ([ask]: system, user → its answer): the lines, with the dialogue before them */
	private fun ai(lines: List<String>, prefs: InArabicPrefs, ask: (String, String) -> String?): List<String>?
	{
		val before = synchronized(this) { history.joinToString("\n") { (en, ar) -> "$en\n→ ${plain(ar)}" } }
		val user = (if(before.isBlank()) "" else "الحوار اللي قبل (للفهم فقط، لا تترجمه):\n$before\n\n") +
			"ترجم هذه الأسطر (${lines.size}):\n" + lines.joinToString("\n")
		val got = ask(system(prefs.voiceDialect, prefs.rawSwears), user) ?: return null
		val out = got.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
		// the lines did not come back one for one: said as one text
		return if(out.size == lines.size) out else listOf(out.joinToString(" "))
	}

	private fun system(dialect: String, raw: Boolean): String
	{
		val how = when(dialect)
		{
			"sa" -> "باللهجة السعودية البيضاء"
			"gulf" -> "باللهجة الخليجية"
			"eg" -> "باللهجة المصرية"
			"sham" -> "باللهجة الشامية"
			else -> "بالعربية الفصحى المبسطة"
		}
		return """أنت مترجم حوار ونصوص ألعاب فيديو من الإنجليزي إلى العربي، $how.

القواعد:
- كل سطر يُعطى لك: ترجمه في سطر واحد، بنفس الترتيب ونفس عدد الأسطر. لا تكتب أي شرح أو ملاحظة أو ترقيم.
- السطر الذي يبدأ باسم متكلم ثم نقطتين ("Lucia: ..."): اكتب اسمه بالعربي ثم نقطتين ثم الترجمة.
- افهم من الحوار من يتكلم ومن يُخاطَب (رجل أو امرأة أو جماعة)، واجعل الضمائر والأفعال على هذا الأساس.
- شكّل الترجمة تشكيلًا كاملًا لأن صوت الدبلجة يقرؤها.
- ${if(raw) "اللعبة للكبار: ترجم الشتائم والكلام البذيء بمعناه الحقيقي كما هو، بلا تلطيف ولا نجوم ولا حذف."
		else "الشتائم والكلام البذيء: ترجمها بمرادف مخفف (مثل «تبًا لك»، «اللعنة»)، بلا نجوم ولا حذف."}"""
	}
}
