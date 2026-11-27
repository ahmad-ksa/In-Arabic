package com.inarabic.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VoicePitchTest
{
	private val rate = 12000

	/** a voice-like sound: a pitch with its overtones, a little noise, and pauses between syllables */
	private fun voice(hz: Double, seconds: Double = 1.5, noise: Double = 300.0): ShortArray
	{
		val r = java.util.Random(7)
		return ShortArray((rate * seconds).toInt()) { i ->
			val t = i.toDouble() / rate
			val on = if(((t * 4).toInt() % 4) == 3) 0.05 else 1.0
			var v = 0.0
			for(h in 1..6) v += sin(2 * PI * hz * h * t) / h
			(on * 5000 * v + noise * r.nextGaussian()).toInt().coerceIn(-32768, 32767).toShort()
		}
	}

	@Test fun man() { val p = VoicePitch.pitch(voice(115.0), rate)!!; assertEquals(115f, p, 6f); assertEquals('m', VoicePitch.gender(p)) }
	@Test fun deepMan() { val p = VoicePitch.pitch(voice(90.0), rate)!!; assertEquals(90f, p, 5f); assertEquals('m', VoicePitch.gender(p)) }
	@Test fun woman() { val p = VoicePitch.pitch(voice(215.0), rate)!!; assertEquals(215f, p, 10f); assertEquals('f', VoicePitch.gender(p)) }
	@Test fun silenceDecidesNothing() = assertNull(VoicePitch.pitch(ShortArray(rate), rate))
	@Test fun noiseDecidesNothing()
	{
		val r = java.util.Random(3)
		assertNull(VoicePitch.gender(VoicePitch.pitch(ShortArray(rate * 2) { (4000 * r.nextGaussian()).toInt().coerceIn(-32768, 32767).toShort() }, rate)))
	}
}
