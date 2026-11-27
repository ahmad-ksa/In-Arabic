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

	/** the lines from number [from] on (those still kept), and the number the next one will have */
	@Synchronized fun since(from: Long): Pair<Long, String>
	{
		val first = count - lines.size
		return count to lines.drop((from - first).coerceIn(0, lines.size.toLong()).toInt()).joinToString("\n")
	}
}
