package com.inarabic.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class DialectTest
{
	@Test fun saudi()
	{
		assertEquals("وش تبي؟", Dialect.say("ماذا تريد؟", "sa"))
		assertEquals("ليش ما تروح الحين؟", Dialect.say("لماذا لا تذهب الآن؟", "sa"))
		assertEquals("أنت بخير؟", Dialect.say("هل أنت بخير؟", "sa"))
		assertEquals("ووش بعد؟", Dialect.say("وماذا أيضًا؟", "sa"))
	}

	@Test fun have()
	{
		// from the log: «ليس لدي المال الآن، ولكن» was said «مو لدي الفلوس الحين، وبس»
		assertEquals("اسمعي، ما عندي الفلوس الحين، بس..", Dialect.say("اسمعي، ليس لدي المال الآن، ولكن..", "sa"))
		assertEquals("معنديش", Dialect.say("ليس لدي", "eg"))
	}

	@Test fun vowelMarksAndHamza() = assertEquals("طيب، يلا.", Dialect.say("حسنًا، هيّا.", "sa"))

	@Test fun otherDialects()
	{
		assertEquals("عايز إيه؟", Dialect.say("ماذا تريد؟", "eg"))
		assertEquals("شو بدك؟", Dialect.say("ماذا تريد؟", "sham"))
		assertEquals("شلونك؟", Dialect.say("كيف حالك؟", "gulf"))
	}

	@Test fun standardStaysAsItIs() = assertEquals("ماذا تريد؟", Dialect.say("ماذا تريد؟", "fusha"))

	@Test fun otherWordsUntouched() = assertEquals("السيارة في المرآب", Dialect.say("السيارة في المرآب", "sa"))
}
