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
		assertEquals("Kolt & Associates", DubLines.speaker("Kolt & Associates: Ayo, wait!"))
		assertNull(DubLines.speaker("Let's go."))
		assertNull(DubLines.speaker("Objective: Leave the trailer"))
	}

	@Test fun nameNotSpoken()
	{
		val p = DubLines.parts(listOf("Lucia: Let's go."), listOf("لوسيا: هيا بنا."), namedOnly = true)
		assertEquals(1, p.size); assertEquals("Lucia", p[0].speaker); assertEquals("هيا بنا.", p[0].arabic)
	}

	@Test fun twoPeople()
	{
		val p = DubLines.parts(listOf("Jason: Ready?", "Lucia: Always."), listOf("جيسون: جاهزة؟", "لوسيا: دائمًا."), namedOnly = true)
		assertEquals(listOf("Jason", "Lucia"), p.map { it.speaker }); assertEquals(listOf(1, 2), p.map { it.end })
	}

	@Test fun taskLineNotSaid()
	{
		// the game writes who speaks: the line with no name is a task, not speech
		val p = DubLines.parts(listOf("Vicki: C'mon.", "Take the pills from Vicki"), listOf("فيكي: هيا.", "خذي الحبوب من فيكي"), namedOnly = true)
		assertEquals(1, p.size); assertEquals("هيا.", p[0].arabic); assertEquals(1, p[0].end)
		assertEquals(0, DubLines.parts(listOf("Return to Jason"), listOf("العودة إلى جيسون"), namedOnly = true).size)
	}

	@Test fun sameSpeakerTogether()
	{
		val p = DubLines.parts(listOf("Jason: Ready?", "Jason: Let's move."), listOf("جيسون: جاهز؟", "جيسون: لنتحرك."), namedOnly = true)
		assertEquals(1, p.size); assertEquals("جاهز؟ لنتحرك.", p[0].arabic); assertEquals(2, p[0].end)
	}

	@Test fun fromTheGameSound()
	{
		val p = DubLines.parts(listOf("Ready?", "Let's move."), listOf("جاهز؟", "لنتحرك."), namedOnly = false)
		assertEquals(1, p.size); assertEquals("جاهز؟ لنتحرك.", p[0].arabic)
	}

	@Test fun linesNotMatching()
	{
		val p = DubLines.parts(listOf("Lucia: one", "two"), listOf("لوسيا: واحد اثنان"), namedOnly = true)
		assertEquals(1, p.size); assertEquals("واحد اثنان", p[0].arabic); assertEquals(2, p[0].end)
	}
}
