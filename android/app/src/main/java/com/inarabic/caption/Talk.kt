// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Who is spoken to: in a conversation the one spoken to is the other person who spoke last (Jason, then Lucia:
// Lucia talks to Jason). Their being a man or a woman (Games) picks the corrected line's «you». Pure Kotlin.

package com.inarabic.caption

import com.inarabic.translate.Games

class Talk(private val withinMs: Long = 20000)
{
	/** who spoke lately, oldest first: name (lowercase), when */
	private val said = ArrayDeque<Pair<String, Long>>()

	/**
	 * For each line of a dialogue text (one per line, «Vicki: …» or a task): whom it is said to, 'm' a man, 'f' a
	 * woman, null not known; the speakers are remembered for the next lines.
	 */
	fun plan(lines: List<String>, at: Long): List<Char?>
	{
		while(said.isNotEmpty() && at - said.first().second > withinMs) said.removeFirst()
		return lines.map { line ->
			val who = Games.split(line).first ?: return@map null
			val to = said.lastOrNull { it.first != who }?.first
			said.addLast(who to at)
			while(said.size > 20) said.removeFirst()
			Games.person(to)?.let { if(it.female) 'f' else 'm' }
		}
	}

	fun reset() = said.clear()
}
