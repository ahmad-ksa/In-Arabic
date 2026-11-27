// The dubbed lines from the phone (MP3 or WAV), played as they come: the phone sends the next one only when
// this one is over.

package com.inarabic.tv

import android.content.Context
import android.media.MediaPlayer
import android.util.Base64
import java.io.File

class VoicePlayer(private val context: Context)
{
	private var player: MediaPlayer? = null

	/** [speed]: faster or slower to last as long as the English line, the pitch kept */
	@Synchronized fun play(mime: String, data: String, speed: Float, failed: (String) -> Unit)
	{
		try { player?.release() } catch(e: Exception) { }
		player = null
		try
		{
			val f = File(context.cacheDir, if(mime == "audio/wav") "dub.wav" else "dub.mp3")
			f.writeBytes(Base64.decode(data, Base64.DEFAULT))
			player = MediaPlayer().apply {
				setDataSource(f.path)
				setOnCompletionListener { synchronized(this@VoicePlayer) { it.release(); if(player === it) player = null } }
				prepare()
				if(speed != 1f) playbackParams = android.media.PlaybackParams().setSpeed(speed).setPitch(1f)
				start()
			}
		}
		catch(e: Exception) { failed(e.toString()) }
	}
}
