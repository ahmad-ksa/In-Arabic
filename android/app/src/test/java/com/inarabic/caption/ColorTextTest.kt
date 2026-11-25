package com.inarabic.caption

import org.junit.Assert.*
import org.junit.Test

class ColorTextTest
{
	@Test fun wholePhraseFound()
	{
		val ar = "اذهب إلى المطار الآن"
		assertEquals(listOf(ColorSpan(9, 15, 1)), ColorText.place(ar, listOf("المطار" to 1)))
	}

	@Test fun wordWithPrefixFound()
	{
		// the phrase alone was translated "المطار", the sentence has "بالمطار"
		val ar = "قابلني بالمطار"
		val s = ColorText.place(ar, listOf("المطار" to 2)).single()
		assertEquals("بالمطار", ar.substring(s.start, s.end))
	}

	@Test fun notFoundIsNoColour()
	{
		assertTrue(ColorText.place("مرحبا", listOf("سيارة" to 3)).isEmpty())
	}
}
