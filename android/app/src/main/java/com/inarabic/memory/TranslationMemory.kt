// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Remembers every translated line on the phone (a small text file), so a line seen before is shown at once
// and never costs an AI call again. Your own corrections win over the AI, the AI wins over the free model.

package com.inarabic.memory

import android.content.Context
import com.inarabic.caption.SubtitleTracker
import java.io.File

object TranslationMemory
{
	enum class Source(val rank: Int) { QUICK(1), AI(2), USER(3) }
	data class Entry(val english: String, val arabic: String, val source: Source, val engine: String = "")

	/**
	 * The translation source in use ("mlkit", "ai", "deepl"…): each one keeps its own translations, so you can
	 * switch and compare. Your corrections belong to all of them.
	 */
	@Volatile var engine: String = "google"

	// each source keeps its own: "google:…" now (the old on-device "mlkit:…" lines are kept in the file but not used)
	private fun slot(k: String, src: Source, eng: String) = when(src) { Source.USER -> "u:$k"; else -> "$eng:$k" }

	private val map = LinkedHashMap<String, Entry>()
	private var file: File? = null

	@Synchronized fun load(context: Context)
	{
		if(file != null) return
		val f = File(context.filesDir, "translation-memory.tsv")
		file = f
		if(!f.exists()) return
		f.forEachLine { line ->
			val p = line.split('\t')
			if(p.size >= 3)
			{
				val src = runCatching { Source.valueOf(p[0]) }.getOrNull() ?: return@forEachLine
				val eng = p.getOrNull(3) ?: if(src == Source.AI) "ai" else "mlkit"
				val e = Entry(unescape(p[1]), unescape(p[2]), src, eng)
				map[slot(SubtitleTracker.key(e.english), src, eng)] = e
			}
		}
	}

	/** your correction, else this source's translation, else the on-device one */
	@Synchronized fun get(english: String): Entry?
	{
		val k = SubtitleTracker.key(english)
		return map["u:$k"] ?: map["$engine:$k"]
	}

	/** Stores a translation unless a better one (higher source) is already known. */
	@Synchronized fun put(english: String, arabic: String, source: Source)
	{
		val k = SubtitleTracker.key(english)
		if(k.isEmpty() || arabic.isBlank()) return
		val eng = engine
		val slot = slot(k, source, eng)
		if(map[slot]?.arabic == arabic) return
		map[slot] = Entry(english, arabic, source, eng)
		try { file?.appendText("${source.name}\t${escape(english)}\t${escape(arabic)}\t$eng\n") } catch(e: Exception) { }
	}

	/** Lines only the free model has translated, newest first: the ones the AI review improves. */
	@Synchronized fun unreviewed(limit: Int): List<Entry> =
		map.values.filter { it.source == Source.QUICK }.takeLast(limit).reversed()

	@Synchronized fun size() = map.size
	@Synchronized fun count(source: Source) = map.values.count { it.source == source }

	@Synchronized fun clear()
	{
		map.clear()
		try { file?.delete() } catch(e: Exception) { }
	}

	private fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
	private fun unescape(s: String) = buildString {
		var i = 0
		while(i < s.length)
		{
			val c = s[i]
			if(c == '\\' && i + 1 < s.length)
			{
				when(s[i + 1]) { 't' -> append('\t'); 'n' -> append('\n'); else -> append(s[i + 1]) }
				i += 2
			}
			else { append(c); i++ }
		}
	}
}

/** Every line of a play session, for the story summary. One file per session, the last 20 kept. */
object SessionLog
{
	private var file: File? = null

	/** one past play session: its file, when it started, the game, how many lines */
	data class Session(val file: File, val started: Long, val game: String, val lines: Int, val lastAt: Long)

	@Synchronized fun begin(context: Context, game: String = "")
	{
		val dir = File(context.filesDir, "sessions").apply { mkdirs() }
		dir.listFiles()?.sortedBy { it.name }?.dropLast(39)?.forEach { it.delete() }
		file = File(dir, "${System.currentTimeMillis()}.tsv").apply { if(game.isNotBlank()) try { writeText("#game\t$game\n") } catch(e: Exception) { } }
	}

	@Synchronized fun add(english: String, arabic: String)
	{
		try { file?.appendText("${System.currentTimeMillis()}\t${english.replace('\t', ' ')}\t${arabic.replace('\t', ' ').replace('\n', ' ')}\n") } catch(e: Exception) { }
	}

	fun lines(f: File): List<Pair<String, String>> =
		try { f.readLines().filter { !it.startsWith("#") }.mapNotNull { l -> l.split('\t').takeIf { it.size >= 3 }?.let { it[1] to it[2] } } } catch(e: Exception) { emptyList() }

	/** past sessions with at least a few lines, newest first */
	fun sessions(context: Context): List<Session>
	{
		val dir = File(context.filesDir, "sessions")
		return (dir.listFiles() ?: emptyArray()).filter { it.length() > 0 }.mapNotNull { f ->
			val all = try { f.readLines() } catch(e: Exception) { return@mapNotNull null }
			val game = all.firstOrNull { it.startsWith("#game\t") }?.substringAfter('\t') ?: ""
			val body = all.filter { !it.startsWith("#") }
			if(body.size < 3) return@mapNotNull null
			Session(f, f.nameWithoutExtension.toLongOrNull() ?: f.lastModified(), game, body.size,
				body.last().substringBefore('\t').toLongOrNull() ?: f.lastModified())
		}.sortedByDescending { it.started }
	}

	/** Lines of the latest session that has any (English, Arabic). */
	fun lastSession(context: Context): List<Pair<String, String>> = sessions(context).firstOrNull()?.let { lines(it.file) } ?: emptyList()
}
