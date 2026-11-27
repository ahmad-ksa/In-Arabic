// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// The game's sound from the stream (one channel, 16 kHz, the last 3 s kept natively), for the dubbing (who
// speaks: VoicePitch) and for «صوت اللعبة» (its speech turned into the dialogue: SoundDialogue).

package com.inarabic.voice

object GameSound
{
	class Chunk(val samples: ShortArray, val count: Int, val start: Long, val rate: Int)

	/** from sample number [from] on, at most [max] samples (-1: the newest [max]); null when there is no stream */
	fun read(from: Long, max: Int): Chunk? = try
	{
		val buf = ShortArray(max)
		val r = com.metallic.chiaki.lib.Session.audioFrom(from, buf)
		if(r[2] <= 0L) null else Chunk(buf, r[1].toInt(), r[0], r[2].toInt())
	}
	catch(e: Throwable) { null } // no stream library loaded (the settings screen)

	/** the newest [ms] of sound, or null when there is not that much yet */
	fun recent(ms: Long): Chunk?
	{
		val probe = read(-1, 1) ?: return null
		val c = read(-1, (probe.rate * ms / 1000).toInt().coerceAtLeast(1)) ?: return null
		return c.takeIf { it.count >= it.samples.size * 8 / 10 }
	}
}
