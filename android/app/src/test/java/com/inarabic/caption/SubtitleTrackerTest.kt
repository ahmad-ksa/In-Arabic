package com.inarabic.caption

import org.junit.Assert.*
import org.junit.Test

class SubtitleTrackerTest
{
	@Test fun newLineFromFirstRead()
	{
		// a fast line is caught on its first reading
		val t = SubtitleTracker()
		assertTrue(t.feed(OcrRead("Where are you going, Joel?")) is TrackerEvent.NewLine)
		assertNull(t.feed(OcrRead("Where are you going, Joel?")))
	}

	@Test fun partOfTheLineChanged()
	{
		// a two-line subtitle: the first half stays, the second half is replaced (same length): an update once seen twice
		val t = SubtitleTracker()
		t.feed(OcrRead("Listen to me carefully. We go at midnight."))
		assertNull(t.feed(OcrRead("Listen to me carefully. We go at sunrise!!")))
		assertTrue(t.feed(OcrRead("Listen to me carefully. We go at sunrise!!")) is TrackerEvent.Update)
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
		val e = t.feed(OcrRead("I told you not to come back here"))
		assertTrue(e is TrackerEvent.Update)
		assertNull(t.feed(OcrRead("I told you not to come back here")))
	}

	@Test fun newLineAndClear()
	{
		val t = SubtitleTracker()
		t.feed(OcrRead("First line of dialogue")); t.feed(OcrRead("First line of dialogue"))
		val e = t.feed(OcrRead("Something completely different now"))
		assertTrue(e is TrackerEvent.NewLine)
		assertNull(t.feed(OcrRead("Something completely different now")))
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
