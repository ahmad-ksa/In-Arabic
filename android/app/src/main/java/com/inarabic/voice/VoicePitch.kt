// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// How high the voice in the game's sound is: a man speaks around 85–155 Hz, a woman around 165–255 Hz.
// Only the parts that are clearly a voice count (a steady pitch); music and explosions mostly fail that test,
// and a sound with too little voice in it decides nothing.

package com.inarabic.voice

import kotlin.math.sqrt

object VoicePitch
{
	/** the middle pitch (Hz) of the voiced parts of [s] (one channel, [rate] samples a second); null: too little voice */
	fun pitch(s: ShortArray, rate: Int, from: Int = 0, to: Int = s.size): Float?
	{
		if(rate <= 0 || to - from < rate / 4) return null
		// low hum away (below ~70 Hz): it is not the voice
		val x = FloatArray(to - from)
		var prev = 0f; var y = 0f
		val k = 1f - 2f * Math.PI.toFloat() * 70f / rate
		for(i in x.indices) { val v = s[from + i].toFloat(); y = k * (y + v - prev); prev = v; x[i] = y }
		val frame = rate * 40 / 1000
		val hop = frame / 2
		val minLag = rate / 320
		val maxLag = rate / 70
		if(x.size < frame + maxLag) return null
		val starts = ArrayList<Int>()
		var st = 0
		while(st + frame + maxLag <= x.size) { starts += st; st += hop }
		val rms = starts.map { a -> var e = 0.0; for(i in a until a + frame) e += x[i] * x[i]; sqrt(e / frame).toFloat() }
		val loud = rms.sorted()[(rms.size * 9) / 10]
		// quiet parts are not the speaker
		val floor = maxOf(150f, loud * 0.3f)
		val found = ArrayList<Float>()
		val r = FloatArray(maxLag + 1)
		for((n, a) in starts.withIndex())
		{
			if(rms[n] < floor) continue
			var best = 0f
			for(lag in minLag..maxLag)
			{
				var xy = 0.0; var xx = 0.0; var yy = 0.0
				for(i in a until a + frame) { val p = x[i]; val q = x[i + lag]; xy += p * q; xx += p * p; yy += q * q }
				r[lag] = if(xx <= 0.0 || yy <= 0.0) 0f else (xy / sqrt(xx * yy)).toFloat()
				if(r[lag] > best) best = r[lag]
			}
			if(best < 0.6f) continue
			// twice the period matches nearly as well as the period itself: the shortest strong one is the pitch
			var lag = minLag
			while(lag <= maxLag && !(r[lag] >= best * 0.88f && (lag == maxLag || r[lag] >= r[lag + 1]) && (lag == minLag || r[lag] >= r[lag - 1]))) lag++
			if(lag > maxLag) continue
			found += rate.toFloat() / lag
		}
		if(found.size < 5) return null
		found.sort()
		return found[found.size / 2]
	}

	/** 'm' a man's voice, 'f' a woman's, null: in between or no voice */
	fun gender(hz: Float?): Char? = when
	{
		hz == null -> null
		hz < 158f -> 'm'
		hz > 172f -> 'f'
		else -> null
	}
}
