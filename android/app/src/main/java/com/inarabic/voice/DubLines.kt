// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// What is spoken of a dialogue line, and by whom: «Lucia: Let's go.» is Lucia saying «هيا بنا». The name is
// not spoken (it is on screen); a line with no name has no known speaker.

package com.inarabic.voice

object DubLines
{
	class Part(val speaker: String?, val arabic: String, val end: Int)

	// one to three capitalised words, then a colon: the speaker
	private val NAME = Regex("^\\s*([A-Z][A-Za-z'.\\-]*(?:\\s[A-Z][A-Za-z'.\\-]*){0,2})\\s*:\\s*(.*)$")
	// labels that are not people
	private val NOT_PEOPLE = setOf("tip", "hint", "note", "objective", "mission", "goal", "task", "warning", "new", "info", "help")

	fun speaker(english: String): String?
	{
		val m = NAME.find(english) ?: return null
		val n = m.groupValues[1].trim()
		return n.takeUnless { it.lowercase() in NOT_PEOPLE || m.groupValues[2].isBlank() }
	}

	/** the Arabic without the speaker's name in front of it */
	private fun withoutName(arabic: String): String
	{
		val i = arabic.indexOfFirst { it == ':' || it == '：' }
		return if(i in 1..24) arabic.substring(i + 1).trim() else arabic.trim()
	}

	fun lines(text: String) = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

	/**
	 * The parts to speak of the lines [en] with their translations [ar] (a line of its own may be another
	 * person). Lines of the same speaker one after the other are said together; [Part.end] is how many of the
	 * lines are said once that part is.
	 */
	fun parts(en: List<String>, ar: List<String>): List<Part>
	{
		if(en.isEmpty() || ar.isEmpty()) return emptyList()
		if(en.size != ar.size)
		{
			// the lines do not match one to one: said as one
			val s = speaker(en[0])
			val a = ar.joinToString(" ")
			return listOf(Part(s, if(s != null) withoutName(a) else a, en.size))
		}
		val out = ArrayList<Part>()
		for(i in en.indices)
		{
			val s = speaker(en[i])
			val a = if(s != null) withoutName(ar[i]) else ar[i]
			if(a.isBlank()) continue
			val last = out.lastOrNull()
			// a line with no name after a named one is usually the same person going on
			if(last != null && (s == last.speaker || s == null)) out[out.size - 1] = Part(last.speaker, last.arabic + " " + a, i + 1)
			else out += Part(s, a, i + 1)
		}
		return out
	}
}
