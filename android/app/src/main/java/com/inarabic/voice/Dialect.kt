// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// The spoken line in a dialect: Google translates into standard Arabic, and the dubbing says it the way people
// talk («ماذا تريد؟» → «وش تبي؟»). Word and phrase lists for each dialect, no internet and no limits. Only the
// common words change; the rest stays standard Arabic.

package com.inarabic.voice

object Dialect
{
	// «لدي» (I have) is «عندي» in all of them; «ليس لدي» is «ما عندي» (Saudi, Gulf, Levant)
	private val HAVE = mapOf("ليس لدي" to "ما عندي", "ليس لديك" to "ما عندك", "ليس لديه" to "ما عنده", "ليس لديها" to "ما عندها",
		"ليس لدينا" to "ما عندنا", "ليس لديهم" to "ما عندهم", "لدي" to "عندي", "لديك" to "عندك", "لديه" to "عنده",
		"لديها" to "عندها", "لدينا" to "عندنا", "لديهم" to "عندهم", "ولكن" to "بس")

	// the same for Saudi and the Gulf except where the Gulf list says otherwise
	private val SA = HAVE + mapOf(
		"ماذا تريد" to "وش تبي", "ماذا تفعل" to "وش تسوي", "ماذا حدث" to "وش صار", "ماذا يحدث" to "وش يصير",
		"ما الذي حدث" to "وش اللي صار", "ما الذي يحدث" to "وش اللي يصير", "ما هذا" to "وش هذا", "ما هذه" to "وش هذي",
		"ما الأمر" to "وش السالفة", "إلى أين" to "وين", "من أين" to "منين", "من أنت" to "مين أنت", 
		"من هو" to "مين هو", "من هي" to "مين هي", "لا أعرف" to "ما أدري", "لا أعلم" to "ما أدري", "لا أستطيع" to "ما أقدر",
		"لا يمكنني" to "ما أقدر", "لا يوجد" to "ما فيه", "لا شيء" to "ولا شي", "أي شيء" to "أي شي", "لا بأس" to "عادي",
		"هل تعلم" to "تدري", "لماذا لا" to "ليش ما", "يا رجل" to "يا رجال", "يا أخي" to "يا أخوي", "على ما يرام" to "تمام",
		"يجب أن" to "لازم", "يجب علينا" to "لازم", "علينا أن" to "لازم", "كيف حالك" to "كيفك",
		"ماذا" to "وش", "لماذا" to "ليش", "أين" to "وين", "الآن" to "الحين", "هيا" to "يلا", "أريد" to "أبي", "تريد" to "تبي",
		"يريد" to "يبي", "نريد" to "نبي", "تريدين" to "تبين", "تريدون" to "تبون", "يريدون" to "يبون", "أستطيع" to "أقدر",
		"تستطيع" to "تقدر", "يستطيع" to "يقدر", "نستطيع" to "نقدر", "هذه" to "هذي", "هؤلاء" to "هذولا", "ذلك" to "ذاك",
		"تلك" to "ذيك", "الذي" to "اللي", "التي" to "اللي", "الذين" to "اللي", "جيد" to "زين", "جيدة" to "زينة",
		"حسنا" to "طيب", "قليلا" to "شوي", "ليس" to "مو", "ليست" to "مو", "لست" to "مو", "لسنا" to "مو", "لن" to "ما راح",
		"نحن" to "إحنا", "تفعل" to "تسوي", "أفعل" to "أسوي", "يفعل" to "يسوي", "نفعل" to "نسوي", "افعل" to "سو",
		"اذهب" to "روح", "أذهب" to "أروح", "تذهب" to "تروح", "يذهب" to "يروح", "نذهب" to "نروح", "لنذهب" to "يلا نروح",
		"انتظر" to "اصبر", "انظر" to "شوف", "لكن" to "بس", "فقط" to "بس", "أيضا" to "بعد", "جدا" to "مرة", "شيء" to "شي",
		"حقا" to "صدق", "هل" to "", "صديقي" to "خويي", "أخي" to "أخوي", "المال" to "الفلوس", "النقود" to "الفلوس",
		"أعتقد" to "أظن", "ربما" to "يمكن", "عندما" to "لمن", "كثيرا" to "كثير", "نعم" to "إيه", "يجب" to "لازم",
		"دعني" to "خلني", "دعنا" to "خلنا", "اتركني" to "خلني", "مرحبا" to "هلا", "أعرف" to "أدري", "تعرف" to "تدري")

	private val GULF = SA + mapOf(
		"ماذا تريد" to "شنو تبي", "ماذا تفعل" to "شنو تسوي", "ماذا حدث" to "شنو صار", "ماذا يحدث" to "شنو يصير",
		"ما الذي حدث" to "شنو اللي صار", "ما الذي يحدث" to "شنو اللي يصير", "ما هذا" to "شنو هذا", "ما هذه" to "شنو هذي",
		"ما الأمر" to "شنو السالفة", "كيف حالك" to "شلونك", "ماذا" to "شنو", "كيف" to "شلون", "كثيرا" to "وايد",
		"جدا" to "وايد", "نعم" to "إي", "صديقي" to "ربيعي")

	private val EG = HAVE + mapOf("ليس لدي" to "معنديش", "ليس لديك" to "معندكش", "ليس لديه" to "معندوش",
		"ليس لدينا" to "معندناش", 
		"ماذا تريد" to "عايز إيه", "ماذا تفعل" to "بتعمل إيه", "ماذا حدث" to "إيه اللي حصل", "ما الذي حدث" to "إيه اللي حصل",
		"ماذا يحدث" to "إيه اللي بيحصل", "ما الذي يحدث" to "إيه اللي بيحصل", "ما هذا" to "إيه ده", "ما هذه" to "إيه دي",
		"ما الأمر" to "فيه إيه", "إلى أين" to "على فين", "من أين" to "منين", "من أنت" to "إنت مين", 
		"من هو" to "مين هو", "من هي" to "مين هي", "لا أعرف" to "مش عارف", "لا أعلم" to "مش عارف", "لا أستطيع" to "مش قادر",
		"لا يمكنني" to "مش قادر", "لا يوجد" to "مفيش", "لا شيء" to "ولا حاجة", "أي شيء" to "أي حاجة",
		"لا بأس" to "مفيش مشكلة", "هل تعلم" to "تعرف", "لماذا لا" to "ليه ما", "يا رجل" to "يا راجل", "يا أخي" to "يا أخويا",
		"على ما يرام" to "تمام", "يجب أن" to "لازم", "يجب علينا" to "لازم", "علينا أن" to "لازم", "كيف حالك" to "عامل إيه",
		"ماذا" to "إيه", "لماذا" to "ليه", "أين" to "فين", "الآن" to "دلوقتي", "هيا" to "يلا", "أريد" to "عايز", "تريد" to "عايز",
		"يريد" to "عايز", "نريد" to "عايزين", "تريدين" to "عايزة", "تريدون" to "عايزين", "يريدون" to "عايزين",
		"أستطيع" to "أقدر", "تستطيع" to "تقدر", "يستطيع" to "يقدر", "هذا" to "ده", "هذه" to "دي", "هؤلاء" to "دول",
		"ذلك" to "ده", "تلك" to "دي", "الذي" to "اللي", "التي" to "اللي", "الذين" to "اللي", "جيد" to "كويس",
		"جيدة" to "كويسة", "حسنا" to "ماشي", "قليلا" to "شوية", "ليس" to "مش", "ليست" to "مش", "لست" to "مش",
		"لسنا" to "مش", "نحن" to "إحنا", "أنت" to "إنت", "تفعل" to "تعمل", "أفعل" to "أعمل", "يفعل" to "يعمل",
		"نفعل" to "نعمل", "افعل" to "اعمل", "اذهب" to "روح", "أذهب" to "أروح", "تذهب" to "تروح", "يذهب" to "يروح",
		"نذهب" to "نروح", "لنذهب" to "يلا بينا", "انتظر" to "استنى", "انظر" to "بص", "لكن" to "بس", "فقط" to "بس",
		"أيضا" to "كمان", "جدا" to "أوي", "شيء" to "حاجة", "حقا" to "بجد", "هل" to "", "صديقي" to "صاحبي",
		"أخي" to "أخويا", "المال" to "الفلوس", "النقود" to "الفلوس", "أعتقد" to "أظن", "ربما" to "يمكن", "عندما" to "لما",
		"كثيرا" to "كتير", "كيف" to "إزاي", "متى" to "إمتى", "نعم" to "أيوه", "يجب" to "لازم", "دعني" to "سيبني",
		"دعنا" to "يلا", "اتركني" to "سيبني", "مرحبا" to "أهلا", "أعرف" to "عارف")

	private val SHAM = HAVE + mapOf(
		"ماذا تريد" to "شو بدك", "ماذا تفعل" to "شو عم تعمل", "ماذا حدث" to "شو صار", "ما الذي حدث" to "شو اللي صار",
		"ماذا يحدث" to "شو عم يصير", "ما الذي يحدث" to "شو عم يصير", "ما هذا" to "شو هاد", "ما هذه" to "شو هاي",
		"ما الأمر" to "شو القصة", "إلى أين" to "لوين", "من أين" to "من وين", "من أنت" to "مين إنت", 
		"من هو" to "مين هو", "من هي" to "مين هي", "لا أعرف" to "ما بعرف", "لا أعلم" to "ما بعرف", "لا أستطيع" to "ما فيني",
		"لا يمكنني" to "ما فيني", "لا يوجد" to "ما في", "لا شيء" to "ولا شي", "أي شيء" to "أي شي", "لا بأس" to "ما في مشكلة",
		"هل تعلم" to "بتعرف", "لماذا لا" to "ليش ما", "يا رجل" to "يا زلمة", "يا أخي" to "يا خيي", "على ما يرام" to "تمام",
		"يجب أن" to "لازم", "يجب علينا" to "لازم", "علينا أن" to "لازم", "كيف حالك" to "كيفك",
		"ماذا" to "شو", "لماذا" to "ليش", "أين" to "وين", "الآن" to "هلق", "هيا" to "يلا", "أريد" to "بدي", "تريد" to "بدك",
		"يريد" to "بدو", "نريد" to "بدنا", "تريدين" to "بدك", "تريدون" to "بدكن", "يريدون" to "بدن", "أستطيع" to "فيني",
		"تستطيع" to "فيك", "يستطيع" to "فيه", "هذا" to "هاد", "هذه" to "هاي", "هؤلاء" to "هدول", "ذلك" to "هداك",
		"تلك" to "هديك", "الذي" to "اللي", "التي" to "اللي", "الذين" to "اللي", "جيد" to "منيح", "جيدة" to "منيحة",
		"حسنا" to "طيب", "قليلا" to "شوي", "ليس" to "مش", "ليست" to "مش", "لست" to "مش", "لسنا" to "مش", "نحن" to "نحنا",
		"أنت" to "إنت", "تفعل" to "تعمل", "أفعل" to "أعمل", "يفعل" to "يعمل", "نفعل" to "نعمل", "افعل" to "اعمل",
		"اذهب" to "روح", "أذهب" to "أروح", "تذهب" to "تروح", "يذهب" to "يروح", "نذهب" to "نروح", "لنذهب" to "يلا نروح",
		"انتظر" to "استنى", "انظر" to "شوف", "لكن" to "بس", "فقط" to "بس", "أيضا" to "كمان", "جدا" to "كتير", "شيء" to "شي",
		"حقا" to "عنجد", "هل" to "", "صديقي" to "رفيقي", "أخي" to "خيي", "المال" to "المصاري", "النقود" to "المصاري",
		"أعتقد" to "بظن", "ربما" to "يمكن", "عندما" to "لما", "كثيرا" to "كتير", "متى" to "إيمتى", "نعم" to "إي",
		"يجب" to "لازم", "دعني" to "خليني", "دعنا" to "خلينا", "أعرف" to "بعرف", "تعرف" to "بتعرف")

	/** the lists, read the way the words are matched (no vowel marks, one alef…) */
	private val LISTS: Map<String, Map<String, String>> = mapOf("sa" to SA, "gulf" to GULF, "eg" to EG, "sham" to SHAM)
		.mapValues { (_, m) -> m.mapKeys { norm(it.key) } }
	/** the longest phrase in the lists, in words */
	private const val LONGEST = 3

	/** one way of writing each word: vowel marks and tatweel away, أ إ آ → ا, ى → ي, ة → ه */
	fun norm(w: String): String = w.replace(Regex("[\\u064B-\\u0652\\u0670\\u0640]"), "")
		.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي').replace('ة', 'ه')

	private val WORD = Regex("[\\u0621-\\u064A\\u064B-\\u0652\\u0670\\u0640\\u0671-\\u06D3]+")

	/** [arabic] (standard Arabic) in [dialect] ("fusha": as it is) */
	fun say(arabic: String, dialect: String): String
	{
		val list = LISTS[dialect] ?: return arabic
		val words = WORD.findAll(arabic).toList()
		if(words.isEmpty()) return arabic
		val out = StringBuilder()
		var at = 0 // where the text not yet copied starts
		var i = 0
		while(i < words.size)
		{
			var done = false
			for(k in minOf(LONGEST, words.size - i) downTo 1)
			{
				// a phrase is words with only spaces between them
				if((i until i + k - 1).any { j -> arabic.substring(words[j].range.last + 1, words[j + 1].range.first).isNotBlank() }) continue
				val first = words[i].value
				val rest = (i + 1 until i + k).joinToString(" ") { norm(words[it].value) }
				fun key(f: String) = if(k == 1) norm(f) else norm(f) + " " + rest
				var prefix = ""
				var to = list[key(first)]
				// «و» or «ف» in front: «وماذا» is «و» + «ماذا»
				if(to == null && first.length > 2 && (first[0] == 'و' || first[0] == 'ف')) { to = list[key(first.substring(1))]; if(to != null) prefix = first.substring(0, 1) }
				if(to == null) continue
				// «هل» dropped, but «وهل» kept (the «و» would hang on nothing)
				if(to.isEmpty() && prefix.isNotEmpty()) continue
				out.append(arabic, at, words[i].range.first)
				if(to.isEmpty())
				{
					// the word and the space after it go
					var end = words[i + k - 1].range.last + 1
					while(end < arabic.length && arabic[end] == ' ') end++
					at = end
				}
				else { out.append(prefix).append(to); at = words[i + k - 1].range.last + 1 }
				i += k
				done = true
				break
			}
			if(!done) i++
		}
		out.append(arabic, at, arabic.length)
		return out.toString().trim()
	}
}
