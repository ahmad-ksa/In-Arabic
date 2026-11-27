// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// What the app read and what it showed, the last few minutes: shared from the settings when something looks
// wrong, so the cause is seen instead of guessed.

package com.inarabic

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Diag
{
	private val lines = ArrayDeque<String>()
	/** lines added since the app started (the first one kept is number count - lines.size) */
	private var count = 0L
	private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

	@Synchronized fun add(what: String)
	{
		lines.addLast(fmt.format(Date()) + "  " + what.replace('\n', '⏎'))
		count++
		while(lines.size > 600) lines.removeFirst()
	}

	@Synchronized fun text(): String = lines.joinToString("\n")

	// ---------- counts for the summary at the top of each upload (translation and dubbing at a glance)
	private class Stat(var n: Long = 0, var sum: Long = 0, var max: Long = 0)
	private val stats = LinkedHashMap<String, Stat>()

	/** one more [what] (with a time in ms, for its average and its worst) */
	@Synchronized fun count(what: String, ms: Long = -1)
	{
		val s = stats.getOrPut(what) { Stat() }
		s.n++
		if(ms >= 0) { s.sum += ms; if(ms > s.max) s.max = ms }
	}

	/** the counts since the last time, one line each; started again from nothing */
	@Synchronized fun takeCounts(): String
	{
		val t = stats.entries.joinToString("\n") { (k, s) -> "$k: ${s.n}" + if(s.sum > 0) " (avg ${s.sum / s.n} ms, max ${s.max} ms)" else "" }
		stats.clear()
		return t
	}

	/** the lines from number [from] on (those still kept), and the number the next one will have */
	@Synchronized fun since(from: Long): Pair<Long, String>
	{
		val first = count - lines.size
		return count to lines.drop((from - first).coerceIn(0, lines.size.toLong()).toInt()).joinToString("\n")
	}
}
