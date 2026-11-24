package com.inarabic.caption

import org.junit.Assert.*
import org.junit.Test

class SubtitleTrackerTest
{
	@Test fun commitsOnlyWhenTwoReadsAgree()
	{
		val t = SubtitleTracker()
		assertNull(t.feed(OcrRead("Where are you going, Joel?")))
		val e = t.feed(OcrRead("Where are you going, Joel?"))
		assertTrue(e is TrackerEvent.NewLine)
		assertNull(t.feed(OcrRead("Where are you going, Joel?")))
	}

	@Test fun ocrNoiseIsTheSameLine()
	{
		val t = SubtitleTracker()
		t.feed(OcrRead("We need to leave before dark."))
		t.feed(OcrRead("We need to leave before dark."))
		assertNull(t.feed(OcrRead("We need to Ieave before dark.")))
		assertNull(t.feed(OcrRead("We need to leave before dark")))
	}

	@Test fun typingInGivesUpdate()
	{
		val t = SubtitleTracker()
		t.feed(OcrRead("I told you not to"))
		t.feed(OcrRead("I told you not to"))
		t.feed(OcrRead("I told you not to come back here"))
		val e = t.feed(OcrRead("I told you not to come back here"))
		assertTrue(e is TrackerEvent.Update)
	}

	@Test fun newLineAndClear()
	{
		val t = SubtitleTracker()
		t.feed(OcrRead("First line of dialogue")); t.feed(OcrRead("First line of dialogue"))
		t.feed(OcrRead("Something completely different now")); val e = t.feed(OcrRead("Something completely different now"))
		assertTrue(e is TrackerEvent.NewLine)
		assertNull(t.feed(OcrRead(""))); assertNull(t.feed(OcrRead("")))
		assertEquals(TrackerEvent.Clear, t.feed(OcrRead("")))
	}

	@Test fun hudNoiseIgnored()
	{
		assertFalse(SubtitleTracker.looksLikeSubtitle("x2"))
		assertFalse(SubtitleTracker.looksLikeSubtitle("100%"))
		assertTrue(SubtitleTracker.looksLikeSubtitle("Get down!  Now"))
	}
}
