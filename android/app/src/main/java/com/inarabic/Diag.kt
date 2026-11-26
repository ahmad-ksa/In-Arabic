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
	private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

	@Synchronized fun add(what: String)
	{
		lines.addLast(fmt.format(Date()) + "  " + what.replace('\n', '⏎'))
		while(lines.size > 600) lines.removeFirst()
	}

	@Synchronized fun text(): String = lines.joinToString("\n")
}
