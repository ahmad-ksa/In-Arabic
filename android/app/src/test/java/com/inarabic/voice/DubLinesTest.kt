package com.inarabic.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DubLinesTest
{
	@Test fun nameInFront()
	{
		assertEquals("Lucia", DubLines.speaker("Lucia: Let's go."))
		assertEquals("Uncle Raul", DubLines.speaker("Uncle Raul: Not now."))
		assertNull(DubLines.speaker("Let's go."))
		assertNull(DubLines.speaker("Objective: Leave the trailer"))
	}

	@Test fun nameNotSpoken()
	{
		val p = DubLines.parts(listOf("Lucia: Let's go."), listOf("لوسيا: هيا بنا."))
		assertEquals(1, p.size); assertEquals("Lucia", p[0].speaker); assertEquals("هيا بنا.", p[0].arabic)
	}

	@Test fun twoPeople()
	{
		val p = DubLines.parts(listOf("Jason: Ready?", "Lucia: Always."), listOf("جيسون: جاهزة؟", "لوسيا: دائمًا."))
		assertEquals(listOf("Jason", "Lucia"), p.map { it.speaker }); assertEquals(listOf(1, 2), p.map { it.end })
	}

	@Test fun goingOn()
	{
		val p = DubLines.parts(listOf("Jason: Ready?", "Let's move."), listOf("جيسون: جاهز؟", "لنتحرك."))
		assertEquals(1, p.size); assertEquals("جاهز؟ لنتحرك.", p[0].arabic); assertEquals(2, p[0].end)
	}

	@Test fun linesNotMatching()
	{
		val p = DubLines.parts(listOf("Lucia: one", "two"), listOf("لوسيا: واحد اثنان"))
		assertEquals(1, p.size); assertEquals("واحد اثنان", p[0].arabic); assertEquals(2, p[0].end)
	}
}
