// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Translation: Google Translate only (its free web service, no key). Lines are translated as they are; a list
// (lines under each other) goes in one request and keeps its lines. The fixed game words (Glossary) come first.
// A translation that still has English words in it (Google left a word it did not know) is "incomplete": it is
// shown, but not remembered as final, so the next reading of the line is translated again.

package com.inarabic.translate

import android.util.LruCache
import com.inarabic.InArabicPrefs
import com.inarabic.caption.SubtitleTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class Translator(@Suppress("unused") private val prefs: InArabicPrefs)
{
	companion object
	{
		/** an English word left in the Arabic: lowercase letters (names and brands keep their capital: Brian, FIB, BuckMe) */
		private val LEFT_ENGLISH = Regex("(?<![A-Za-z])[a-z][a-z']+(?![A-Za-z])")

		/** true when nothing English is left in the Arabic (names and brands aside) */
		fun complete(arabic: String): Boolean = !LEFT_ENGLISH.containsMatchIn(arabic)
	}

	private val cache = LruCache<String, String>(800)

	/** why the last request failed, in Arabic (shown to you), or null */
	@Volatile var lastAiError: String? = null
		private set

	/** nothing to download any more: Google Translate needs only the internet */
	suspend fun prepare(): Boolean = true

	/**
	 * [english0] in Arabic: the game's corrected lines (Games) and fixed words (Glossary) first, Google for the
	 * rest. [to]: for each line, whom it is said to ('m' a man, 'f' a woman, null not known), for the corrected
	 * lines' «you».
	 */
	suspend fun quick(english0: String, to: List<Char?> = emptyList()): String?
	{
		Games.fix(english0.trim(), to.firstOrNull())?.takeIf { '\n' !in english0.trim() }?.let { return it }
		Glossary.lookup(english0)?.let { return it }
		// a list: the lines in one request, kept under each other (Google keeps the lines of one request)
		if('\n' in english0)
		{
			val lines = english0.split('\n').filter { it.isNotBlank() }
			val fixed = lines.mapIndexed { i, l -> Games.fix(l, to.getOrNull(i)) ?: Glossary.lookup(l) }
			val todo = lines.filterIndexed { i, _ -> fixed[i] == null }
			val got = if(todo.isEmpty()) emptyList() else google(todo.joinToString("\n") { Games.english(Glossary.sentenceCase(it)) })
				?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }
			if(got != null && got.size == todo.size)
			{
				var j = 0
				return lines.indices.joinToString("\n") { i -> fixed[i] ?: Glossary.arabicNames(got[j++]) }
			}
			// the lines did not come back one for one: each on its own
			return lines.mapIndexed { i, l -> fixed[i] ?: quick(l) ?: l }.joinToString("\n")
		}
		val english = Glossary.sentenceCase(english0)
		val k = SubtitleTracker.key(english)
		cache.get(k)?.let { return it }
		val ar = google(Games.english(english))?.let { Glossary.arabicNames(it) } ?: return null
		// only a complete translation is kept: an incomplete one is asked again at the next reading
		if(complete(ar)) cache.put(k, ar)
		return ar
	}

	private suspend fun google(text: String): String? =
		withTimeoutOrNull(9000) { withContext(Dispatchers.IO) { OnlineEngines.google(text) } }
			.also { lastAiError = if(it == null) (OnlineEngines.lastError ?: "Google Translate ما رد. تأكد من الإنترنت") else null }

	private val altCache = LruCache<String, List<String>>(300)

	/** every Arabic Google offers for a word or short phrase (to find it inside a sentence's Arabic) */
	suspend fun alternatives(english: String): List<String>
	{
		val k = SubtitleTracker.key(english)
		altCache.get(k)?.let { return it }
		val got = withTimeoutOrNull(6000) { withContext(Dispatchers.IO) { OnlineEngines.googleAll(english) } } ?: return emptyList()
		if(got.isNotEmpty()) altCache.put(k, got)
		return got
	}

	/** (the AI sources are gone: kept so the callers stay simple) */
	suspend fun better(@Suppress("UNUSED_PARAMETER") english: String): String? = null
	suspend fun review(@Suppress("UNUSED_PARAMETER") lines: List<Pair<String, String>>): Map<String, String> = emptyMap()
	suspend fun ask(@Suppress("UNUSED_PARAMETER") system: String, @Suppress("UNUSED_PARAMETER") user: String, @Suppress("UNUSED_PARAMETER") timeoutMs: Long = 30000): String? = null

	fun close() {}
}
