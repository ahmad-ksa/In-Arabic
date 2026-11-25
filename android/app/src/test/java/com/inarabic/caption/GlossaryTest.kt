package com.inarabic.caption

import com.inarabic.translate.Glossary
import org.junit.Assert.*
import org.junit.Test

class GlossaryTest
{
	@Test fun singleWords()
	{
		assertEquals("تم الفتح", Glossary.lookup("UNLOCKED"))
		assertEquals("افتح", Glossary.lookup("Open"))
		assertEquals("فشلت المهمة", Glossary.lookup("MISSION FAILED!"))
	}

	@Test fun patterns() { assertEquals("انفتح: انتقال سريع", Glossary.lookup("Fast travel unlocked")) }

	@Test fun sentencesGoToTheTranslator() { assertNull(Glossary.lookup("Where are you going, Jason? We need to leave")) }

	@Test fun caps() { assertEquals("Where are you going", Glossary.sentenceCase("WHERE ARE YOU GOING")) }
}
