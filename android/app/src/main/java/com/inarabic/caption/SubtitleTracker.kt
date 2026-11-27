// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Decides when the subtitle on screen has really changed. A new line is reported from its first reading (a fast
// line may be gone before a second one); a line typing in reports its growth; a line whose end changed (the second
// half of a two-line subtitle replaced) is reported once two readings agree on the change (OCR noise is not).
// Cleared after it is gone for a while.
// Pure Kotlin (no Android), so it is unit-tested.

package com.inarabic.caption

import kotlin.math.max
import kotlin.math.min

/** one read text: its words, its place, its coloured words, and the game's own colours for it (letters, background) */
data class OcrRead(val text: String, val box: FloatArray? = null, val colored: List<Colored> = emptyList(),
	val fg: Int = -1 /* white */, val bg: Int = -16777216 /* black */)

sealed class TrackerEvent
{
	/** A new subtitle line is on screen. */
	data class NewLine(val text: String, val box: FloatArray?) : TrackerEvent()
	/** The line grew (typewriter effect) or OCR improved: same subtitle, better text. */
	data class Update(val text: String, val box: FloatArray?) : TrackerEvent()
	/** No subtitle any more. */
	object Clear : TrackerEvent()
}

class SubtitleTracker(
	private val agreeSimilarity: Double = 0.85,
	private val sameLineSimilarity: Double = 0.75,
	private val emptyReadsToClear: Int = 3,
)
{
	private var pending: String? = null
	private var committed: String? = null
	private var emptyReads = 0

	fun reset()
	{
		pending = null
		committed = null
		emptyReads = 0
	}

	fun feed(read: OcrRead): TrackerEvent?
	{
		val text = clean(read.text)
		if(!looksLikeSubtitle(text))
		{
			pending = null
			emptyReads++
			if(committed != null && emptyReads >= emptyReadsToClear)
			{
				committed = null
				return TrackerEvent.Clear
			}
			return null
		}
		emptyReads = 0
		val prev = pending
		pending = text
		val agree = prev != null && similarity(prev, text, prefixBonus = false) >= agreeSimilarity
		val last = committed
		if(last == null || similarity(last, text) < sameLineSimilarity)
		{
			// another line: shown from its first reading (a fast line would be gone before a second one)
			committed = text
			return TrackerEvent.NewLine(text, read.box)
		}
		// the same subtitle, longer: typing in
		if(key(text).length > key(last).length + 2)
		{
			committed = text
			return TrackerEvent.Update(text, read.box)
		}
		// the same subtitle with part of it changed (not just a letter or two of OCR noise), seen twice
		if(agree && levenshtein(key(last), key(text)) >= 4)
		{
			committed = text
			return TrackerEvent.Update(text, read.box)
		}
		return null
	}

	companion object
	{
		private val spaces = Regex("\\s+")

		private val blanks = Regex("[ \t]+")
		/** spaces tidied; the line breaks between separate texts (a dialogue line over a task line) are kept */
		fun clean(s: String): String = s.split('\n').map { it.replace(blanks, " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")

		/** At least a few letters, not just UI noise ("A", "x2", "100%"). */
		fun looksLikeSubtitle(s: String): Boolean
		{
			var letters = 0
			for(c in s) if(c.isLetter()) letters++
			return letters >= 4 && s.contains(' ')
		}

		fun key(s: String): String = buildString { for(c in s.lowercase()) if(c.isLetterOrDigit()) append(c) }

		/** 0..1 similarity of two lines, ignoring case, spaces and punctuation (Levenshtein ratio). */
		fun similarity(a: String, b: String, prefixBonus: Boolean = true): Double
		{
			val x = key(a)
			val y = key(b)
			if(x.isEmpty() && y.isEmpty()) return 1.0
			if(x.isEmpty() || y.isEmpty()) return 0.0
			// one is a prefix of the other (text typing in): very similar
			if(prefixBonus && (x.startsWith(y) || y.startsWith(x)))
				return 0.9 + 0.1 * min(x.length, y.length) / max(x.length, y.length)
			val d = levenshtein(x, y)
			return 1.0 - d.toDouble() / max(x.length, y.length)
		}

		internal fun levenshtein(a: String, b: String): Int
		{
			var prev = IntArray(b.length + 1) { it }
			var cur = IntArray(b.length + 1)
			for(i in 1..a.length)
			{
				cur[0] = i
				for(j in 1..b.length)
				{
					val cost = if(a[i - 1] == b[j - 1]) 0 else 1
					cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
				}
				val t = prev; prev = cur; cur = t
			}
			return prev[b.length]
		}
	}
}
