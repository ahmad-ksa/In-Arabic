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
			"Lucia: Did you not hear me say l'm taking this?" to "Lucia: Did you not hear me say I'm taking this?",
			"Local: Hey,'sup..." to "Local: Hey, What's up...",
			"Lucia: 'Sup with you two?" to "Lucia: What's up with you two?",
			"Lucia: Sup, girl, all good?" to "Lucia: What's up, girl, all good?",
			"Lucia: Sup, mama?" to "Lucia: What's up, mama?",
			"KEROSENE Lucia: She said you might be able to help me out with the ride." to "KEROSENE Lucia: She said you might be able to help me out with the car.",
			"Lucia: She said you might be able to help me out with the ride." to "Lucia: She said you might be able to help me out with the car.",
			"Moses: I don't know nothing about you 'cept you got a name, and a ride that ain't yours." to "Moses: I don't know nothing about you 'cept you got a name, and a car that ain't yours.",
			"Moses: Is that your ride?" to "Moses: Is that your car?",
			"Vicki: So if you happen to, you know, find a ride that speaks to you, or whatever," to "Vicki: So if you happen to, you know, find a car that speaks to you, or whatever,",
			"Lucia: Right, well | guess I'l come back later then." to "Lucia: Right, well I guess I'l come back later then.",
			"Bud: Aw, hell nah, l'm not high enough for this shit!" to "Bud: Aw, hell nah, I'm not high enough for this shit!",
			"Bud: l've been constipated ever since." to "Bud: I've been constipated ever since.",
			"Goto" to "Go to",
			"Jason: AIl things considered, think I got pretty lucky." to "Jason: All things considered, think I got pretty lucky.",
			"Jason: Breakfast. Lu, l'm not falling for that again." to "Jason: Breakfast. Lu, I'm not falling for that again.",
			"Jenny: Yo! This your ride?" to "Jenny: Yo! This your car?",
			"Layla: I'l see you later, yeah? Bye!" to "Layla: I'll see you later, yeah? Bye!",
			"Local: Nah uh! That's my ride, motherfucker!" to "Local: Nah uh! That's my car, motherfucker!",
			"Locals: Try to run me down, l'll kill your ass!" to "Locals: Try to run me down, I'll kill your ass!",
			"Lucia: I think l've seen the guy in the visor working on an old Dominator out here," to "Lucia: I think I've seen the guy in the visor working on an old Dominator out here,",
			"Lucia: Right, well | guess I'l come back later then." to "Lucia: Right, well I guess I'll come back later then.",
			"Lucia: When you're all better l'm gonna need your help out here," to "Lucia: When you're all better I'm gonna need your help out here,",
			"Lucia: Yknow, if I were stuck out here, l'd jump off a bridge, too." to "Lucia: Yknow, if I were stuck out here, I'd jump off a bridge, too.",
			"Lucia: l'm ain't gonna stop you." to "Lucia: I'm ain't gonna stop you.",
			"Mechanic: Well, I'l see ya later then, 'cause you know I can't resist watchin y'all eat shit out there." to "Mechanic: Well, I'll see ya later then, 'cause you know I can't resist watchin y'all eat shit out there.",
			"Staff Member: 'Sup?" to "Staff Member: What's up?",
			"Staff Member: AIl yours!" to "Staff Member: All yours!",
			"Staff Member: Okay! | am! I am! Just, oh... fuck!" to "Staff Member: Okay! I am! I am! Just, oh... fuck!",
			"Stranger: I'l look for you." to "Stranger: I'll look for you.",
			"Wyman: l'm glad you guys made it! So, there's a merch stand" to "Wyman: I'm glad you guys made it! So, there's a merch stand",
			"lan: Ah, fuck!" to "Ian: Ah, fuck!",
			"lan: Dude, watch it!" to "Ian: Dude, watch it!",
			"ucia: Bye" to "Lucia: Bye",
			"wee-oh\" and l'm just there like, \"what the fuck!\"" to "wee-oh\" and I'm just there like, \"what the fuck!\"",
			"Jason: 'Kay, all good." to "Jason: Okay, all good.",
			"Stylist: 'Kay, well go around back." to "Stylist: Okay, well go around back.",
			"Vicki: but l'm telling you, this is our way out of Starlet." to "Vicki: but I'm telling you, this is our way out of Starlet.",
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
		// «Hey!» calling out, not «مهلا»; the passer-by's name written
		assertEquals("أحد السكان: هيه! انتبه.", Games.fix("Local: Hey! Watch it!", 'm'))
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

	@Test fun wordSaidWrongInManyLines()
	{
		// «Yo» «Hey.» «Oh, shit.» «dude» «watch it» as they are meant
		assertEquals("هيه. كيف عرفت؟", Games.words("Dunk: Yo. How you know?", "يو. كيف عرفت؟"))
		assertEquals("أهلًا. أنت بخير؟", Games.words("Lucia: Hey. You okay?", "يا. أنت بخير؟"))
		assertEquals("تبًا. أنظر إليك.", Games.words("Jason: Oh, shit. Look at you.", "يا للقرف. أنظر إليك."))
		assertEquals("هذا القرف", Games.words("Bud: I'm not high enough for this shit!", "هذا القرف"))
		assertEquals("الرجل، انتبه!", Games.words("Ian: Dude, watch it!", "المتأنق، مشاهدته!"))
		// two words together: «you good?», «Come on!», «fuck off», «Bye»
		assertEquals("لوسيا: أنت بخير؟", Games.words("Lucia: You good?", "لوسيا: أنت جيد؟"))
		assertEquals("أنت اه... هل تجيد هذا الشيء؟", Games.words("Moses: You, uh... you good at that kinda thing?", "أنت اه... هل تجيد هذا الشيء؟"))
		assertEquals("إذهب! هيا!", Games.words("Lucia: Go, go, go! Come on!", "إذهب! تعال!"))
		assertEquals("هيا إذن! تعال واحصل عليه!", Games.words("Addict: Come on then, bitch! Come and get it!", "هيا إذن! تعال واحصل عليه!"))
		assertEquals("المدمن: اغرب عن وجهي!", Games.words("Addict: Fuck off!", "المدمن: اخرس!"))
		assertEquals("أراك لاحقًا! مع السلامة!", Games.words("Layla: I'll see you later, yeah? Bye!", "أراك لاحقًا! الوداع!"))
		// a remembered translation of lines under each other: each line by its own English
		assertEquals("لوسيا: أهلًا!\nاذهب إلى فيكي", Games.wordsLines("Lucia: Hey.\nGo to Vicki", "لوسيا: مهلا!\nاذهب إلى فيكي"))
		// «yo'» is «your»: not a call
		assertEquals("يو", Games.words("Workers: I'll catch yo' ass", "يو"))
		// «fucking» is not «سخيف», the cash «register» is not «السجل»
		assertEquals("ما هي الصفقة لعين الخاص بك؟", Games.words("Local: What's your fucking deal?", "ما هي الصفقة سخيف الخاص بك؟"))
		assertEquals("هل لديك مشكلة لعينة؟", Games.words("Local: You got a fuckin' problem?", "هل لديك مشكلة سخيفة؟"))
		assertEquals("هذا سخيف", Games.words("Lucia: That's silly as fuck, fucking hell", "هذا سخيف"))
		assertEquals("المال في الصندوق", Games.words("Staff Member: The money is in the register", "المال في السجل"))
		assertEquals("أفرغ الصناديق", Games.words("Lucia: Okay, dump those registers", "أفرغ السجلات"))
		assertEquals("سجل سيارتك", Games.words("Register your new personal vehicle", "سجل سيارتك"))
		assertEquals("أهلًا يا أولاد.", Games.words("Lucia: Hey, boys.", "مهلا يا أولاد."))
		assertEquals("هيه! انتبه.", Games.words("Zach: Hey! Watch it.", "مهلا! انتبه."))
		// «Hey, hey! Easy», «Hey, wait»: «مهلا» is right there
		assertEquals("مهلا، مهلا! بهدوء.", Games.words("Frankie: Hey, hey! Easy with that.", "مهلا، مهلا! بهدوء."))
		assertEquals("مهلا، انتظر", Games.words("Local: Hey, wait, wait", "مهلا، انتظر"))
		// no «Hey» at the start: nothing changes
		assertEquals("مهلا، هل كانت", Games.words("Vagrants: Wait, was it", "مهلا، هل كانت"))
	}

	@Test fun namesSpeltOneWay()
	{
		assertEquals("دنك: صحيح. أنا دنك.", Glossary.arabicNames("دونك: صحيح. أنا دانك."))
		assertEquals("اذهب إلى ديلايتس", Glossary.arabicNames("اذهب إلى المسرات"))
		assertEquals("قال جيسون", Glossary.arabicNames("قال جايسون"))
		assertEquals("موزس: هل هذه سيارتك؟", Glossary.arabicNames("موسى: هل هذه سيارتك؟"))
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
