// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Microsoft's read-aloud voices (the ones Edge uses), free and without a key: one WebSocket per line, the text
// goes in, an MP3 comes back. Not an official service (like the free Google Translate we use): if it stops
// answering, the phone's own voice speaks instead (PhoneTts).

package com.inarabic.voice

import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object EdgeTts
{
	private const val URL = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=" + EdgeToken.CLIENT_TOKEN
	private val MAJOR = EdgeToken.CHROMIUM.substringBefore('.')
	/** the MP3 that comes back: 48 kbit/s, so its length in time is known from its size */
	const val BITRATE = 48000

	/** why the last line failed (for the log) */
	@Volatile var lastError: String? = null

	/** where the time of one line went: connecting, until the first sound, all of it (ms) */
	class Timing(val connect: Long, val firstAudio: Long, val total: Long)
	{
		override fun toString() = "connect $connect, first sound $firstAudio, all $total ms"
	}

	private fun id() = UUID.randomUUID().toString().replace("-", "")

	private fun date(): String
	{
		val f = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
		f.timeZone = TimeZone.getTimeZone("UTC")
		return f.format(Date())
	}

	private fun escape(t: String) = t.map { ch ->
		when(ch)
		{
			'&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&apos;"
			else -> if(ch.code in 0..8 || ch.code in 11..12 || ch.code in 14..31) " " else ch.toString()
		}
	}.joinToString("")

	/** one connection to the voices: opened ahead of time and used for one line after another */
	private class Conn(url: String, headers: Map<String, String>) : WebSocketClient(URI(url), Draft_6455(), headers, 8000)
	{
		/** the line being said on it now */
		class Turn(val requestId: String, val onAudio: (ByteArray) -> Unit)
		{
			val audio = ByteArrayOutputStream()
			val done = CountDownLatch(1)
			@Volatile var ended = false
			@Volatile var first = 0L
		}
		@Volatile var turn: Turn? = null
		@Volatile var closed = false
		val openedAt = System.currentTimeMillis()

		override fun onOpen(h: ServerHandshake?)
		{
			// once for the connection: MP3 pieces
			send("X-Timestamp:${date()}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
				"{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"}," +
				"\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n")
		}
		/** the turn a message belongs to (by its request id) */
		private fun mine(head: String): Turn? = turn?.takeIf { head.contains("X-RequestId:" + it.requestId) }
		override fun onMessage(message: String?)
		{
			val m = message ?: return
			val t = mine(m) ?: return
			if(m.contains("Path:turn.end")) { t.ended = true; t.done.countDown() }
		}
		override fun onMessage(bytes: ByteBuffer)
		{
			if(bytes.remaining() < 2) return
			val b = ByteArray(bytes.remaining()); bytes.get(b)
			val hl = ((b[0].toInt() and 255) shl 8) or (b[1].toInt() and 255)
			if(hl + 2 > b.size) return
			val head = String(b, 2, hl, Charsets.UTF_8)
			val t = mine(head) ?: return
			if(head.contains("Path:audio") && head.contains("Content-Type:audio/mpeg"))
			{
				val piece = b.copyOfRange(hl + 2, b.size)
				synchronized(t.audio) { if(t.first == 0L) t.first = System.currentTimeMillis(); t.audio.write(piece) }
				t.onAudio(piece)
			}
		}
		override fun onClose(code: Int, reason: String?, remote: Boolean)
		{
			closed = true
			turn?.let { if(!it.ended) lastError = "closed $code ${reason ?: ""}"; it.done.countDown() }
		}
		override fun onError(ex: Exception?) { lastError = ex?.toString(); closed = true; turn?.done?.countDown() }
	}

	/** open connections nobody is using: a line takes one, and gives it back when it is said */
	private val idle = java.util.concurrent.ConcurrentLinkedQueue<Conn>()
	/** a connection is not kept longer than this (Microsoft closes long-idle ones anyway) */
	private const val KEEP_MS = 4 * 60 * 1000L

	private fun open(timeoutMs: Long): Conn?
	{
		val url = "$URL&ConnectionId=${id()}&Sec-MS-GEC=${EdgeToken.token()}&Sec-MS-GEC-Version=${EdgeToken.VERSION}"
		val headers = mapOf(
			"Pragma" to "no-cache",
			"Cache-Control" to "no-cache",
			"Origin" to "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold",
			"User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$MAJOR.0.0.0 Safari/537.36 Edg/$MAJOR.0.0.0",
			"Accept-Language" to "en-US,en;q=0.9",
			"Cookie" to "muid=${id().uppercase()};")
		val c = Conn(url, headers)
		return try { if(c.connectBlocking(timeoutMs, TimeUnit.MILLISECONDS)) c else { c.close(); lastError = "connect timeout"; null } }
		catch(e: InterruptedException) { Thread.currentThread().interrupt(); c.close(); null }
		catch(e: Exception) { lastError = e.toString(); null }
	}

	/** an open connection: one waiting, or a new one */
	private fun take(timeoutMs: Long): Pair<Conn, Boolean>?
	{
		while(true)
		{
			val c = idle.poll() ?: break
			if(!c.closed && c.isOpen && System.currentTimeMillis() - c.openedAt < KEEP_MS) return c to true
			try { c.close() } catch(e: Exception) { }
		}
		return open(timeoutMs)?.let { it to false }
	}

	/**
	 * [n] connections open and waiting, so the next lines do not wait for one to open (called when a line appears,
	 * while it is still being translated). In the background.
	 */
	fun warm(n: Int)
	{
		val missing = n - idle.count { !it.closed && it.isOpen && System.currentTimeMillis() - it.openedAt < KEEP_MS }
		repeat(missing.coerceAtLeast(0)) { Thread { open(8000)?.let { idle.add(it) } }.start() }
	}

	/**
	 * [voice] says [text] ([rate], [volume] e.g. "+15%"); the MP3 and where its time went, or null. [onAudio] gets
	 * each piece of it as it comes (it can be played before the rest has come). Interrupted (the line was
	 * replaced): null, and the thread stays interrupted so the caller knows it was not a failure.
	 */
	fun say(text: String, voice: String, rate: String = "+0%", volume: String = "+0%", timeoutMs: Long = 8000,
		onAudio: (ByteArray) -> Unit = {}): Pair<ByteArray, Timing>?
	{
		val t0 = System.currentTimeMillis()
		val lang = voice.split('-').take(2).joinToString("-")
		val name = voice.split('-').drop(2).joinToString("-")
		val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
			"<voice name='Microsoft Server Speech Text to Speech Voice ($lang, $name)'>" +
			"<prosody pitch='+0Hz' rate='$rate' volume='$volume'>${escape(text)}</prosody></voice></speak>"
		// a kept connection may have been closed by Microsoft meanwhile: then once more on a new one
		for(attempt in 0..1)
		{
			val (c, reused) = take(timeoutMs) ?: return null
			val connected = System.currentTimeMillis()
			val turn = Conn.Turn(id(), onAudio)
			c.turn = turn
			try
			{
				c.send("X-RequestId:${turn.requestId}\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${date()}Z\r\nPath:ssml\r\n\r\n$ssml")
				if(!turn.done.await(timeoutMs, TimeUnit.MILLISECONDS)) lastError = "timeout"
			}
			catch(e: InterruptedException) { Thread.currentThread().interrupt(); c.close(); return null }
			catch(e: Exception) { lastError = e.toString() }
			c.turn = null
			val out = synchronized(turn.audio) { turn.audio.toByteArray() }
			if(turn.ended && out.isNotEmpty())
			{
				idle.add(c) // kept for the next line
				val t = System.currentTimeMillis()
				return out to Timing(connected - t0, (if(turn.first > 0) turn.first else t) - t0, t - t0)
			}
			try { c.close() } catch(e: Exception) { }
			// nothing came on a kept connection: it was stale, a new one is tried (not if some sound was already given)
			if(!reused || out.isNotEmpty()) return null
		}
		return null
	}
}
