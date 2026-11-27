package com.inarabic.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class NewTranslatorTest
{
	/** the screen shows the new system's Arabic without the vowel marks the voice reads */
	@Test fun screenWithoutVowelMarks()
	{
		assertEquals("لوسيا: أهلا يا حبيبتي، كيف حالك؟", NewTranslator.plain("لُوسِيَا: أَهْلًا يَا حَبِيبَتِي، كَيْفَ حَالُكِ؟"))
		assertEquals("مرحبا", NewTranslator.plain("مرحبا"))
	}
}
