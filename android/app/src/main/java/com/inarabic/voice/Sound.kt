// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// One dubbed line's sound while it is being made, and the phone's player for it. Microsoft's voice comes in
// pieces (MP3): the phone starts playing at the first piece instead of waiting for the whole line, which is most
// of the dubbing's delay. The phone's own voice (the fallback) comes whole (WAV).

package com.inarabic.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** the sound of one line: [mime] audio/mpeg (in pieces) or audio/wav (whole) */
class Sound(val mime: String)
{
	private val pieces = LinkedBlockingQueue<ByteArray>()
	private val whole = ByteArrayOutputStream()
	@Volatile var finished = false
		private set
	@Volatile var ok = false
		private set

	fun add(b: ByteArray) { synchronized(whole) { whole.write(b) }; pieces.add(b) }
	fun end(ok: Boolean) { this.ok = ok; finished = true; pieces.add(END) }
	/** the next piece; null at the end (or after [waitMs] with nothing) */
	fun next(waitMs: Long): ByteArray? = pieces.poll(waitMs, TimeUnit.MILLISECONDS)?.takeIf { it !== END }
	fun bytes(): ByteArray = synchronized(whole) { whole.toByteArray() }

	/** how long it lasts (ms), once it is all here */
	fun ms(): Long
	{
		val b = bytes()
		if(mime == "audio/mpeg") return b.size * 8000L / EdgeTts.BITRATE
		fun le(o: Int, n: Int): Long { var v = 0L; for(i in 0 until n) v = v or ((b[o + i].toLong() and 255) shl (8 * i)); return v }
		val byteRate = if(b.size > 44) le(28, 4) else 0L
		return if(byteRate > 0) (b.size - 44) * 1000L / byteRate else 0L
	}

	companion object { private val END = ByteArray(0) }
}

/** plays [Sound]s on the phone, as their pieces come; one at a time */
object PhoneAudio
{
	/** plays [s] to its end (or until [stop] says so); false if it could not */
	fun play(s: Sound, stop: () -> Boolean): Boolean = try
	{
		if(s.mime == "audio/mpeg") playMp3(s, stop) else playWav(s, stop)
		true
	}
	catch(e: Exception) { com.inarabic.Diag.add("dub: phone play failed $e"); false }

	private fun track(rate: Int, channels: Int): AudioTrack
	{
		val ch = if(channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
		val min = AudioTrack.getMinBufferSize(rate, ch, AudioFormat.ENCODING_PCM_16BIT)
		return AudioTrack.Builder()
			.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
			.setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(ch).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
			.setBufferSizeInBytes(maxOf(min, rate / 5 * 2 * channels))
			.setTransferMode(AudioTrack.MODE_STREAM)
			.build()
	}

	/** waits until all [frames] written have been heard, then lets the track go */
	private fun finish(t: AudioTrack, frames: Long, stop: () -> Boolean)
	{
		val until = System.currentTimeMillis() + 10000
		while(!stop() && System.currentTimeMillis() < until && (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < frames) Thread.sleep(15)
		try { t.stop() } catch(e: Exception) { }
		t.release()
	}

	private fun playWav(s: Sound, stop: () -> Boolean)
	{
		while(!s.finished && !stop()) Thread.sleep(10)
		val b = s.bytes()
		if(b.size <= 44) return
		fun le(o: Int, n: Int): Int { var v = 0; for(i in 0 until n) v = v or ((b[o + i].toInt() and 255) shl (8 * i)); return v }
		val channels = le(22, 2).coerceIn(1, 2)
		val rate = le(24, 4)
		// the samples start after the "data" chunk header
		var at = 12
		while(at + 8 <= b.size && String(b, at, 4, Charsets.US_ASCII) != "data") at += 8 + le(at + 4, 4)
		at += 8
		if(at >= b.size) return
		val t = track(rate, channels)
		t.play()
		var o = at
		while(o < b.size && !stop()) { val n = t.write(b, o, minOf(4096, b.size - o)); if(n <= 0) break; o += n }
		finish(t, ((b.size - at) / (2 * channels)).toLong(), stop)
	}

	// MP3 frames (MPEG-1/2/2.5 layer III): the decoder is given whole frames
	private val BR1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
	private val BR2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
	private val SR = arrayOf(intArrayOf(11025, 12000, 8000), intArrayOf(0, 0, 0), intArrayOf(22050, 24000, 16000), intArrayOf(44100, 48000, 32000))

	/** the length of the frame starting at [o], or 0 if there is no frame header there */
	private fun frameLength(b: ByteArray, o: Int): Int
	{
		if(o + 4 > b.size) return 0
		val b1 = b[o + 1].toInt() and 255; val b2 = b[o + 2].toInt() and 255
		if((b[o].toInt() and 255) != 0xFF || (b1 and 0xE0) != 0xE0) return 0
		val version = (b1 shr 3) and 3; val layer = (b1 shr 1) and 3
		val bri = (b2 shr 4) and 15; val sri = (b2 shr 2) and 3; val pad = (b2 shr 1) and 1
		if(version == 1 || layer != 1 || bri == 0 || bri == 15 || sri == 3) return 0
		val br = (if(version == 3) BR1 else BR2)[bri] * 1000
		val sr = SR[version][sri]
		return (if(version == 3) 144 else 72) * br / sr + pad
	}

	private fun playMp3(s: Sound, stop: () -> Boolean)
	{
		val codec = MediaCodec.createDecoderByType("audio/mpeg")
		codec.configure(MediaFormat.createAudioFormat("audio/mpeg", 24000, 1), null, null, 0)
		codec.start()
		var t: AudioTrack? = null
		var channels = 1
		var frames = 0L
		val info = MediaCodec.BufferInfo()
		var buf = ByteArray(0)
		var ended = false
		var eosSent = false
		try
		{
			fun drain(): Boolean
			{
				while(true)
				{
					val i = codec.dequeueOutputBuffer(info, 5000)
					when
					{
						i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
						{
							val f = codec.outputFormat
							channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
							t = track(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels).also { it.play() }
						}
						i >= 0 ->
						{
							val out = codec.getOutputBuffer(i)
							if(out != null && info.size > 0)
							{
								val pcm = ByteArray(info.size)
								out.position(info.offset); out.get(pcm)
								val tr = t ?: track(24000, 1).also { it.play(); t = it }
								var o = 0
								while(o < pcm.size && !stop()) { val n = tr.write(pcm, o, pcm.size - o); if(n <= 0) break; o += n }
								frames += pcm.size / (2 * channels)
							}
							codec.releaseOutputBuffer(i, false)
							if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
						}
						else -> return false
					}
				}
			}
			while(!stop())
			{
				// whole frames from what has come so far
				if(!ended)
				{
					val piece = s.next(50)
					if(piece == null) { if(s.finished) ended = true }
					else buf += piece
				}
				var o = 0
				while(true)
				{
					// find the next frame header
					while(o < buf.size && frameLength(buf, o) == 0) o++
					val len = frameLength(buf, o)
					if(len == 0 || o + len > buf.size) break
					val i = codec.dequeueInputBuffer(10000)
					if(i < 0) { drain(); continue }
					codec.getInputBuffer(i)!!.apply { clear(); put(buf, o, len) }
					codec.queueInputBuffer(i, 0, len, 0, 0)
					o += len
				}
				buf = buf.copyOfRange(minOf(o, buf.size), buf.size)
				if(ended && !eosSent)
				{
					val i = codec.dequeueInputBuffer(10000)
					if(i >= 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosSent = true }
				}
				if(drain()) break
			}
		}
		finally
		{
			try { codec.stop() } catch(e: Exception) { }
			codec.release()
			t?.let { finish(it, frames, stop) }
		}
	}
}
