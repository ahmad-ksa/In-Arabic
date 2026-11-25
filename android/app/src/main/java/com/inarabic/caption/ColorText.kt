// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Coloured words: games colour a word or two (a name, a place, an item) in red, green, pink…
// The colour of each English word is measured on the picture; the coloured phrase is translated on its own
// and found again in the Arabic sentence, which then gets the same colour there.
// The matching part is pure Kotlin (unit-tested); measuring is in CaptionEngine.

package com.inarabic.caption

/** an English phrase drawn in [color] (0xAARRGGBB) */
data class Colored(val english: String, val color: Int)

/** a coloured stretch of the Arabic text: [start, end) */
data class ColorSpan(val start: Int, val end: Int, val color: Int)

object ColorText
{
	private val PREFIXES = listOf("وال", "بال", "فال", "كال", "لل", "ال", "و", "ب", "ل", "ف")
	private fun bare(w: String): String
	{
		var x = w.trim { !it.isLetterOrDigit() }
		for(p in PREFIXES) if(x.startsWith(p) && x.length - p.length >= 3) { x = x.substring(p.length); break }
		return x
	}

	/**
	 * Where each translated phrase sits in [arabic]: the whole phrase if it is there as it is, otherwise
	 * its longest word (without ال / و / ب …), stretched to the whole Arabic word it is part of.
	 */
	fun place(arabic: String, phrases: List<Pair<String, Int>>): List<ColorSpan>
	{
		val out = ArrayList<ColorSpan>()
		fun free(s: Int, e: Int) = out.none { s < it.end && e > it.start }
		for((ph, color) in phrases)
		{
			val p = ph.trim { !it.isLetterOrDigit() }
			if(p.isEmpty()) continue
			val i = arabic.indexOf(p)
			if(i >= 0)
			{
				// stretched to whole words: "المطار" inside "بالمطار" colours "بالمطار"
				var s = i; var e = i + p.length
				while(s > 0 && arabic[s - 1].isLetter()) s--
				while(e < arabic.length && arabic[e].isLetter()) e++
				if(free(s, e)) { out += ColorSpan(s, e, color); continue }
			}
			val words = p.split(Regex("\\s+")).map(::bare).filter { it.length >= 3 }.sortedByDescending { it.length }
			for(w in words)
			{
				val j = arabic.indexOf(w)
				if(j < 0) continue
				var s = j; var e = j + w.length
				while(s > 0 && arabic[s - 1].isLetter()) s--
				while(e < arabic.length && arabic[e].isLetter()) e++
				if(free(s, e)) { out += ColorSpan(s, e, color); break }
			}
		}
		return out.sortedBy { it.start }
	}
}
