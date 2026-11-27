// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// «النظام الجديد»: one translator, chosen in the settings, and nothing after it.
//  - Haiku (Claude): told the game, its people (man or woman) and names, and the dialogue so far, so it knows who
//    speaks to whom; it writes the Arabic with all its vowel marks (the voice reads them; the screen shows the text
//    without them: [plain]), in the dialect chosen;
//  - Google's official Cloud Translation: each line as it comes, nothing added (to see how good it is on its own).
// The reading's own fixes (glued words, «l'm») still come first: they are the reading's, not the translation's.

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

	/** a text translated before in this session (the same reading comes again while it stays on screen) */
	private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true)
	{
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 300
	}

	private val MARKS = Regex("[\\u064B-\\u0652\\u0670]")

	/** the Arabic without its vowel marks (what the screen shows) */
	fun plain(arabic: String) = MARKS.replace(arabic, "")

	@Synchronized fun reset() { history.clear(); cache.clear() }

	/** [english] (one line or lines under each other) in Arabic, by the new system's translator; null if it failed */
	fun translate(english: String, prefs: InArabicPrefs): String?
	{
		val lines = english.split('\n').map { Games.ocr(it.trim()) }.filter { it.isNotBlank() }
		if(lines.isEmpty()) return null
		val k = lines.joinToString("\n")
		synchronized(this) { cache[k] }?.let { return it }
		val out = when(prefs.newTranslator)
		{
			"google" ->
			{
				if(prefs.googleKey.isBlank()) { lastError = "حط مفتاح قوقل الرسمي في الإعدادات"; return null }
				OnlineEngines.googleOfficial(lines, prefs.googleKey)
			}
			else ->
			{
				if(prefs.haikuKey.isBlank()) { lastError = "حط مفتاح Haiku في الإعدادات"; return null }
				haiku(lines, prefs)
			}
		}
		if(out == null) { lastError = OnlineEngines.lastError ?: "المترجم ما رد"; return null }
		lastError = null
		val ar = out.joinToString("\n")
		synchronized(this)
		{
			cache[k] = ar
			// only what people say is the dialogue (not a task or a menu)
			if(out.size == lines.size) for(i in lines.indices) if(DubLines.speaker(lines[i]) != null) history.addLast(lines[i] to out[i])
			while(history.size > HISTORY) history.removeFirst()
		}
		return ar
	}

	private fun haiku(lines: List<String>, prefs: InArabicPrefs): List<String>?
	{
		val before = synchronized(this) { history.joinToString("\n") { (en, ar) -> "$en\n→ ${plain(ar)}" } }
		val user = (if(before.isBlank()) "" else "الحوار اللي قبل (للفهم فقط، لا تترجمه):\n$before\n\n") +
			"ترجم هذه الأسطر (${lines.size}):\n" + lines.joinToString("\n")
		val got = OnlineEngines.haiku(system(prefs.voiceDialect), user, prefs.haikuKey) ?: return null
		val out = got.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
		// the lines did not come back one for one: said as one text
		return if(out.size == lines.size) out else listOf(out.joinToString(" "))
	}

	private fun system(dialect: String): String
	{
		val g = Games.active
		val how = when(dialect)
		{
			"sa" -> "باللهجة السعودية البيضاء"
			"gulf" -> "باللهجة الخليجية"
			"eg" -> "باللهجة المصرية"
			"sham" -> "باللهجة الشامية"
			else -> "بالعربية الفصحى المبسطة"
		}
		return buildString {
			append("أنت مترجم حوار ونصوص ألعاب فيديو من الإنجليزي إلى العربي، $how.")
			if(g != null) append(" اللعبة: ${g.title}.")
			append("""

القواعد:
- كل سطر يُعطى لك: ترجمه في سطر واحد، بنفس الترتيب ونفس عدد الأسطر. لا تكتب أي شرح أو ملاحظة أو ترقيم.
- السطر الذي يبدأ باسم متكلم ثم نقطتين ("Lucia: ..."): اكتب اسمه بالعربي ثم نقطتين ثم الترجمة.
- افهم من الحوار من يتكلم ومن يُخاطَب: المتكلم رجل أو امرأة، والمخاطَب رجل أو امرأة أو جماعة. اجعل الضمائر والأفعال والصفات على هذا الأساس.
- شكّل الترجمة تشكيلًا كاملًا (كل حرف بحركته) لأن صوت الدبلجة يقرؤها، وخصوصًا الضمائر وأواخر الكلمات.
- العامية الإنجليزية والشتائم: ترجم المعنى لا الكلمات ("ride" قد تكون سيارة، "Hey" تحية أو نداء، "Sup" كيف الحال).
- السطر الذي ليس حوارًا (مهمة، قائمة، زر، اسم مكان): ترجمه قصيرًا كما في الألعاب.
- أسماء الأماكن والمحلات والماركات: اكتبها بحروف عربية ولا تترجم معناها.""")
			if(g != null)
			{
				val names = g.names
				if(g.people.isNotEmpty())
				{
					append("\n\nالشخصيات:\n")
					append(g.people.entries.joinToString("\n") { (k, p) -> "- ${k.replaceFirstChar { it.uppercase() }} = ${names[k] ?: k}: ${if(p.female) "امرأة" else "رجل"}" })
				}
				if(g.crowd.isNotEmpty())
					append("\nأسماء عامة لأناس من الشارع (المتكلم يتغير كل سطر): " + g.crowd.keys.joinToString("، ") { k -> "${k.replaceFirstChar { it.uppercase() }} = ${names[k] ?: k}" })
				val places = names.filterKeys { it !in g.people && it !in g.crowd }
				if(places.isNotEmpty()) append("\nأسماء تكتبها هكذا: " + places.entries.joinToString("، ") { (en, ar) -> "$en = $ar" })
			}
		}
	}
}
