package com.inarabic.caption

import org.junit.Assert.*
import org.junit.Test

class SideTextTest
{
	@Test fun gluedButtonIsCutOff()
	{
		assertEquals(SideText("Shoot", "R2"), SideText.clean("SH00TR2"))
		assertEquals(SideText("aim", "L2"), SideText.clean("aiml2"))
		assertEquals(SideText("sh00t".replace('0', 'o'), "R2"), SideText.clean("sh00tr2"))
	}

	@Test fun buttonWithSpaceOrBrackets()
	{
		assertEquals(SideText("Bachelor party", "R3"), SideText.clean("BACHELOR PARTY (R3)"))
		assertEquals(SideText("Sprint", "L3"), SideText.clean("L3 SPRINT"))
	}

	@Test fun buttonIconInFrontIsDropped()
	{
		assertEquals(SideText("Open", null), SideText.clean("© OPEN"))
		assertEquals(SideText("Open", null), SideText.clean("A OPEN"))
		assertEquals(SideText("Open", null), SideText.clean("OPEN"))
	}

	@Test fun plainTextStays()
	{
		assertEquals(SideText("Bachelor party", null), SideText.clean("BACHELOR PARTY"))
		assertNull(SideText.clean("$8"))
		assertNull(SideText.clean("R2"))
		assertNull(SideText.clean("V A D"))
	}
}
