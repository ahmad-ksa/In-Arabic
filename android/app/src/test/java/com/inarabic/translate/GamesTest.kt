package com.inarabic.translate

import com.inarabic.caption.Talk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GamesTest
{
	init { Games.select("Grand Theft Auto VI") }

	/**
	 * Every line seen in the logs (src/test/resources/gta6_lines.txt) goes through the English fixes: only these
	 * may change, and only like this. A new fix that changes any other line fails the build.
	 */
	@Test fun englishFixesChangeOnlyTheirLines()
	{
		val expected = mapOf(
			"Dunk: We just gotta call Dr. Bil!" to "Dunk: We just gotta call Dr. Bill!",
			"Dunk: Right, right. l'm Dunk." to "Dunk: Right, right. I'm Dunk.",
			"Frankie: Look, just tip him out now, okay?" to "Frankie: Look, just tip him now, okay?",
			"Frankie: Martin said that you didn't tip him out last night." to "Frankie: Martin said that you didn't tip him last night.",
			"Frankie: l'm Frankie Delight." to "Frankie: I'm Frankie Delight.",
			"Getin Vicki'scar" to "Get in Vicki's car",
			"Goto Dunk'strailer" to "Go to Dunk's trailer",
			"Lucia: If I was you, l'd just quit." to "Lucia: If I was you, I'd just quit.",
			"Lucia: | try not to in public." to "Lucia: I try not to in public.",
			"Return toVicki'sroom" to "Return to Vicki's room",
			"Vagrants: Could l get some change, Miss Lady?" to "Vagrants: Could I get some change, Miss Lady?",
			"Vagrants: Man, l'm crashing and got the IAA all over me." to "Vagrants: Man, I'm crashing and got the IAA all over me.",
			"Vicki: After l'm done tipping out Martin's useless ass," to "Vicki: After I'm done tipping Martin's useless ass,",
			"Vicki: But can you drive? 'Cause l'm a little..." to "Vicki: But can you drive? 'Cause I'm a little...",
			"Vicki: I'm gonna tip out the DJ, tip out the doorman." to "Vicki: I'm gonna tip the DJ, tip the doorman.",
			"Vicki: I'm gonna tip out the bar, and then I'm gonna tip out the waitress," to "Vicki: I'm gonna tip the bar, and then I'm gonna tip the waitress,",
			"Vicki: and you know what l'm gonna do with the rest?" to "Vicki: and you know what I'm gonna do with the rest?",
			"etin Vicki'scar" to "etin Vicki's car")
		val lines = javaClass.classLoader!!.getResource("gta6_lines.txt")!!.readText().lines().filter { it.isNotBlank() }
		for(l in lines) assertEquals(l, expected[l] ?: l, Games.english(l))
	}

	@Test fun correctedLineByWhomItIsSaidTo()
	{
		assertEquals("لوسيا: مرحبًا. كيف تشعر؟", Games.fix("Lucia: Hey. How you feeling?", 'm'))
		assertEquals("لوسيا: مرحبًا. كيف تشعرين؟", Games.fix("Lucia: Hey. How you feeling?", 'f'))
		// not known to whom: the usual one
		assertEquals("دنك: هل ما زلتِ ستوصلينني إلى البيت؟", Games.fix("Dunk: Are you still taking me home?", null))
		// glued words read apart first
		assertEquals("اذهب إلى مقطورة دنك", Games.fix("Goto Dunk'strailer", null))
		assertEquals("لوسيا: أحاول ألا أفعل ذلك أمام الناس.", Games.fix("Lucia: | try not to in public.", null))
		assertNull(Games.fix("Lucia: Something new.", 'f'))
	}

	@Test fun whoIsSpokenTo()
	{
		val t = Talk()
		assertEquals(listOf<Char?>(null), t.plan(listOf("Jason: Like I got shot."), 0))
		// Lucia answers Jason: to a man
		assertEquals(listOf<Char?>('m'), t.plan(listOf("Lucia: Well, these should help."), 2000))
		// Vicki, then Lucia in the same text: Lucia to Vicki, a woman; a task line: nobody
		assertEquals(listOf<Char?>('f', 'f', null), t.plan(listOf("Vicki: Hey.", "Lucia: Hey.", "Go to Delights"), 4000))
	}

	@Test fun namesSpeltOneWay()
	{
		assertEquals("دنك: صحيح. أنا دنك.", Glossary.arabicNames("دونك: صحيح. أنا دانك."))
		assertEquals("اذهب إلى ديلايتس", Glossary.arabicNames("اذهب إلى المسرات"))
		assertEquals("قال جيسون", Glossary.arabicNames("قال جايسون"))
	}

	@Test fun speakerNameWrittenNotTranslated()
	{
		assertEquals("أحد السكان" to "Hey, find me later.", Games.label("Local: Hey, find me later."))
		assertEquals("دنك" to "No, why?", Games.label("Dunk: No, why?"))
		// a name the game does not know: the whole line goes to Google
		assertNull(Games.label("LRI Dunk: We just gotta call Dr. Bill!"))
		assertNull(Games.label("Go to Delights"))
		// a gang's name with «&»
		assertEquals("كولت وشركاه" to "Quit fuckin' stallin'! Let's go!", Games.label("Kolt & Associates: Quit fuckin' stallin'! Let's go!"))
	}

	@Test fun passersByHaveNoVoiceOfTheirOwn()
	{
		org.junit.Assert.assertTrue(Games.crowd("Local") && !Games.crowd("Lucia") && !Games.crowd(null))
		assertNull(Games.crowdGender("Local"))
		org.junit.Assert.assertTrue(Games.crowd("Kolt & Associates") && Games.crowd("Staff Member"))
	}

	@Test fun namesSaidWithVowelMarks() = assertEquals("أنا دَنْك", Games.forVoice("أنا دنك"))
}
