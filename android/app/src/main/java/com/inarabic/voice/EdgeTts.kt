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

	/**
	 * [voice] says [text] ([volume] e.g. "+15%"); the MP3 and where its time went, or null. Interrupted (the line
	 * was replaced): null, and the thread stays interrupted so the caller knows it was not a failure.
	 */
	fun say(text: String, voice: String, volume: String = "+0%", timeoutMs: Long = 8000): Pair<ByteArray, Timing>?
	{
		val t0 = System.currentTimeMillis()
		var opened = 0L
		var first = 0L
		val url = "$URL&ConnectionId=${id()}&Sec-MS-GEC=${EdgeToken.token()}&Sec-MS-GEC-Version=${EdgeToken.VERSION}"
		val headers = mapOf(
			"Pragma" to "no-cache",
			"Cache-Control" to "no-cache",
			"Origin" to "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold",
			"User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$MAJOR.0.0.0 Safari/537.36 Edg/$MAJOR.0.0.0",
			"Accept-Language" to "en-US,en;q=0.9",
			"Cookie" to "muid=${id().uppercase()};")
		val audio = ByteArrayOutputStream()
		val done = CountDownLatch(1)
		var ended = false
		val lang = voice.split('-').take(2).joinToString("-")
		val name = voice.split('-').drop(2).joinToString("-")
		val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
			"<voice name='Microsoft Server Speech Text to Speech Voice ($lang, $name)'>" +
			"<prosody pitch='+0Hz' rate='+0%' volume='$volume'>${escape(text)}</prosody></voice></speak>"
		val ws = object : WebSocketClient(URI(url), Draft_6455(), headers, timeoutMs.toInt())
		{
			override fun onOpen(h: ServerHandshake?)
			{
				opened = System.currentTimeMillis()
				send("X-Timestamp:${date()}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
					"{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"}," +
					"\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n")
				send("X-RequestId:${id()}\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${date()}Z\r\nPath:ssml\r\n\r\n$ssml")
			}
			override fun onMessage(message: String?)
			{
				if(message?.contains("Path:turn.end") == true) { ended = true; done.countDown() }
			}
			override fun onMessage(bytes: ByteBuffer)
			{
				if(bytes.remaining() < 2) return
				val b = ByteArray(bytes.remaining()); bytes.get(b)
				val hl = ((b[0].toInt() and 255) shl 8) or (b[1].toInt() and 255)
				if(hl + 2 > b.size) return
				val head = String(b, 2, hl, Charsets.UTF_8)
				if(head.contains("Path:audio") && head.contains("Content-Type:audio/mpeg")) synchronized(audio) {
					if(first == 0L) first = System.currentTimeMillis()
					audio.write(b, hl + 2, b.size - hl - 2)
				}
			}
			override fun onClose(code: Int, reason: String?, remote: Boolean) { if(!ended) lastError = "closed $code ${reason ?: ""}"; done.countDown() }
			override fun onError(ex: Exception?) { lastError = ex?.toString(); done.countDown() }
		}
		try
		{
			ws.connect()
			if(!done.await(timeoutMs, TimeUnit.MILLISECONDS)) lastError = "timeout"
		}
		catch(e: InterruptedException) { Thread.currentThread().interrupt() }
		catch(e: Exception) { lastError = e.toString() }
		finally { try { ws.close() } catch(e: Exception) { } }
		val out = synchronized(audio) { audio.toByteArray() }
		val t = System.currentTimeMillis()
		return if(ended && out.isNotEmpty()) out to Timing(opened - t0, (if(first > 0) first else t) - t0, t - t0) else null
	}
}
