// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// What one game needs beyond Google's translation, kept apart for each game (GTA VI first):
//  - its people: man or woman, and a voice each that stays the same the whole game;
//  - corrected lines: Google's mistakes seen in the logs, said right; a line said to someone ("you") has a version
//    for a man and one for a woman, picked by who is spoken to (caption/Talk.kt);
//  - names: people and places written one way (Google spells them several ways, or translates a place's name);
//  - a few English fixes before Google, only where the meaning is always the same ("tip out" is "tip"), and the
//    words the reader glues together ("Getin Vicki'scar"). Every logged line is checked against them in the tests
//    (GamesTest): a fix that would change a line it should not stops the build;
//  - how names are said: vowel marks for the voice, so it reads them right.
// Pure Kotlin, unit-tested.

package com.inarabic.translate

import com.inarabic.caption.SubtitleTracker

object Games
{
	/** a person of the game: a woman or a man, and the voice that says their lines (Voices ids) */
	class Person(val female: Boolean, val voice: String? = null)

	/** a corrected line: [toMan] said to a man, [toWoman] to a woman; [usual] when it is not known to whom */
	class Fix(val toMan: String, val toWoman: String = toMan, val usual: Char = 'm')
	{
		fun to(who: Char?) = when(who ?: usual) { 'f' -> toWoman; else -> toMan }
	}

	/** a word Google says wrong the same way in many lines: where the English has [en] (at the start of what is
	 * said), its Arabic [ar] becomes [to] (the first one) */
	class Word(val en: Regex, val ar: Regex, val to: String)

	class Game(
		val title: String,
		val people: Map<String, Person>,
		/** the passers-by the game names by what they are («Local»), not who: each line is someone else, so none
		 * keeps a voice; a man or a woman by how the voice sounds ('m'/'f' when the name says it, null: heard) */
		val crowd: Map<String, Char?>,
		/** names in Arabic letters (people, places; lowercase English): Google leaves them in English or spells
		 * them its own way; the speaker's name before a corrected line («Vicki:») too */
		val names: Map<String, String>,
		/** whole lines, by their key (SubtitleTracker.key of the words after the speaker's name) */
		val lines: Map<String, Fix>,
		/** what the reader gets wrong in the English (glued words, «l'm» for «I'm»): fixed before anything else */
		val ocr: List<Pair<Regex, String>>,
		/** English before Google: only meanings that never change */
		val english: List<Pair<Regex, String>>,
		/** Arabic after Google: one spelling for each name */
		val arabic: List<Pair<Regex, String>>,
		/** words Google says wrong in many lines, with one meaning there (fixed after Google, by the English) */
		val words: List<Word>,
		/** how the voice says a word (vowel marks) */
		val voice: Map<String, String>)

	private val FUCKING = Regex("^(?!.*\\b(?:silly|ridiculous|lame|absurd)\\b).*\\bfuck(?:ing|in'?)\\b", RegexOption.IGNORE_CASE)
	private val REGISTER = Regex("\\b(?:the|that|this|those|these|a|cash)\\s+registers?\\b", RegexOption.IGNORE_CASE)

	// an Arabic letter (or its vowel mark): «،» and «؟» are in the Arabic block too, but end a word
	private fun w(word: String) = Regex("(?<![\\u0621-\\u064A\\u064B-\\u0652\\u0670\\u0640])" + Regex.escape(word) + "(?![\\u0621-\\u064A\\u064B-\\u0652\\u0670\\u0640])")

	val GTA6 = Game(
		title = "Grand Theft Auto VI",
		people = mapOf(
			"lucia" to Person(true, "ar-LB-LaylaNeural"), "jason" to Person(false, "ar-SA-HamedNeural"),
			"vicki" to Person(true, "ar-KW-NouraNeural"), "dunk" to Person(false, "ar-KW-FahedNeural"),
			"frankie" to Person(false, "ar-AE-HamdanNeural"), "martin" to Person(false, "ar-QA-MoazNeural"),
			"brian" to Person(false, "ar-JO-TaimNeural"), "cal" to Person(false, "ar-BH-AliNeural"),
			"boobie" to Person(false, "ar-OM-AbdullahNeural"), "raul" to Person(false, "ar-LB-RamiNeural"),
			"layla" to Person(true, "ar-AE-FatimaNeural"), "dancer" to Person(true, "ar-QA-AmalNeural"),
			"bartender" to Person(false, "ar-IQ-BasselNeural"), "moses" to Person(false, "ar-SY-LaithNeural"),
			"billy" to Person(false, "ar-LY-OmarNeural"), "bud" to Person(false, "ar-EG-ShakirNeural"),
			"dre'quan" to Person(false, "ar-TN-HediNeural"), "wyman" to Person(false, "ar-DZ-IsmaelNeural"),
			"linda" to Person(true, "ar-JO-SanaNeural"),
			// a man or a woman, the voice chosen when they first speak
			"chester" to Person(false), "shanese" to Person(true)),
		crowd = mapOf("local" to null, "locals" to null, "stranger" to null, "staff member" to null, "vagrants" to null,
			"cop" to null, "kolt & associates" to null, "driver" to null, "drivers" to null, "tourists" to null,
			"resident" to null, "worker" to null, "workers" to null, "stylist" to null, "addict" to null, "clerk" to null,
			"hostage" to null, "shop owner" to null, "food vendor" to null, "tractor driver" to null, "vagrant" to null, "cops" to null,
			"tourist" to null, "security guard" to null),
		names = linkedMapOf(
			"vice city" to "فايس سيتي", "leonida keys" to "جزر ليونيدا", "leonida" to "ليونيدا", "port gellhorn" to "بورت جيلهورن",
			"gellhorn" to "جيلهورن", "vice-dale county" to "مقاطعة فايس-ديل", "grassrivers" to "غراس ريفرز", "ambrosia" to "أمبروسيا", "mount kalaga" to "جبل كالاغا",
			"key lento" to "كي لينتو", "tanglewood" to "تانغلوود", "delights" to "ديلايتس", "kolts" to "الكولتس",
			"jason" to "جيسون", "lucia" to "لوسيا", "kenton" to "كينتون", "cal" to "كال", "boobie" to "بوبي", "raul" to "راؤول",
			"brian" to "براين", "real dimez" to "ريل دايمز", "dre'quan" to "دريكوان", "drequan" to "دريكوان",
			"vicki" to "فيكي", "dunk" to "دنك", "frankie" to "فرانكي", "martin" to "مارتن", "layla" to "ليلى",
			"jordy" to "جوردي", "tommy" to "تومي", "dancer" to "الراقصة", "bartender" to "النادل", "local" to "أحد السكان", "locals" to "سكان",
			"stranger" to "غريب", "staff member" to "موظف", "vagrants" to "متشردون", "cop" to "شرطي", "kolt & associates" to "كولت وشركاه",
			"driver" to "سائق", "drivers" to "سائقون", "tourists" to "سياح", "resident" to "أحد السكان", "worker" to "عامل", "workers" to "عمال",
			"stylist" to "مصفف الشعر", "zach" to "زاك", "moses" to "موزس", "mo" to "مو", "danny" to "داني",
			"billy" to "بيلي", "bud" to "بد", "linda" to "ليندا", "wyman" to "وايمان", "mitch" to "ميتش", "jenny" to "جيني",
			"ian" to "إيان", "donnie" to "دوني", "duval" to "دوفال", "announcer" to "المذيع", "mechanic" to "الميكانيكي",
			"race organizer" to "منظم السباق", "addict" to "مدمن", "clerk" to "موظف", "hostage" to "رهينة", "shop owner" to "صاحب المتجر",
			"food vendor" to "بائع طعام", "tractor driver" to "سائق جرار", "vagrant" to "متشرد", "cops" to "شرطة", "tourist" to "سائح",
			"goose key" to "غوس كي", "bobcat key" to "بوبكات كي", "gambit bay" to "خليج غامبيت", "gullet marsh" to "مستنقع غوليت",
			"buckskin swamp" to "مستنقع باكسكين", "pelican river" to "نهر بيليكان", "wild hog point" to "وايلد هوغ بوينت",
			"hank's waffles" to "هانكس وافلز", "rooster's rentals" to "روسترز رنتالز", "scooter bros" to "سكوتر بروز",
			"wyatt motors" to "وايت موتورز", "chester" to "تشيستر", "shanese" to "شانيز", "security guard" to "حارس الأمن",
			"starlet" to "ستارليت", "delight's" to "ديلايتس", "borrachero sound" to "بوراتشيرو ساوند", "dominator" to "دومينيتور",
			"ammu-nation" to "أمونيشن", "ammu-nations" to "أمونيشن", "los santos" to "لوس سانتوس", "liberty city" to "ليبرتي سيتي"),
		lines = linkedMapOf(
			// «Hey» is a greeting, not «wait»
			"heyhowyoufeeling" to Fix("مرحبًا. كيف تشعر؟", "مرحبًا. كيف تشعرين؟"),
			"heyneedsomethingfromme" to Fix("مرحبًا، هل تحتاج شيئًا مني؟", "مرحبًا، هل تحتاجين شيئًا مني؟", 'f'),
			"heyfindmelater" to Fix("مرحبًا، تعال إليّ لاحقًا.", "مرحبًا، تعالي إليّ لاحقًا.", 'f'),
			"heysup" to Fix("أهلًا، كيف الحال..."),
			"supmama" to Fix("كيف الحال يا جميلة؟", "كيف الحال يا جميلة؟", 'f'),
			"supgirlallgood" to Fix("أهلًا يا فتاة، هل كل شيء بخير؟"),
			"heygirlyougood" to Fix("أهلًا يا فتاة، هل أنتِ بخير؟"),
			"howreyoudoinggorgeous" to Fix("كيف حالك يا وسيم؟", "كيف حالكِ يا جميلة؟", 'f'),
			"igotyouinasecondb00" to Fix("سأكون معك بعد لحظة يا عزيزي..", "سأكون معكِ بعد لحظة يا عزيزتي..", 'f'),
			"ohhey" to Fix("أوه، أهلًا!"),
			"heyheyheyheyheyheyheyeasywiththateasywiththat" to Fix("على مهلك، على مهلك! بهدوء. بهدوء.", "على مهلكِ، على مهلكِ! بهدوء. بهدوء."),
			"heyheyheyheyheyheyheyeasywiththat" to Fix("على مهلك، على مهلك! بهدوء.", "على مهلكِ، على مهلكِ! بهدوء."),
			"heyheyheyheyrelaxvickilookatthissee" to Fix("مهلًا، مهلًا. اهدئي. فيكي، انظري إلى هذا، أرأيتِ؟"),
			"toughcookiehuh" to Fix("عنيد، أليس كذلك؟", "عنيدة، أليس كذلك؟", 'f'),
			"thatstagefeesstandardhon" to Fix("رسوم المسرح هذه عادية يا عزيزي.", "رسوم المسرح هذه عادية يا عزيزتي.", 'f'),
			"lookjusttiphimoutnowokay" to Fix("اسمع، أعطه إكراميته الآن، اتفقنا؟", "اسمعي، أعطيه إكراميته الآن، اتفقنا؟", 'f'),
			"martinsaidthatyoudidnttiphimoutlastnight" to Fix("قال مارتن إنك لم تعطه إكراميته البارحة.", "قال مارتن إنكِ لم تعطيه إكراميته البارحة.", 'f'),
			"jusjustmakesureyoutipmartinonyourwayoutalright" to Fix("فقط تأكد أن تعطي مارتن إكراميته وأنت خارج، اتفقنا؟", "فقط تأكدي أن تعطي مارتن إكراميته وأنتِ خارجة، اتفقنا؟", 'f'),
			"imgonnatipoutthedjtipoutthedoorman" to Fix("سأعطي الدي جي إكراميته، والبوّاب إكراميته."),
			"imgonnatipoutthebarandthenimgonnatipoutthewaitress" to Fix("سأعطي البار إكراميته، ثم النادلة،"),
			"afterimdonetippingoutmartinsuselessass" to Fix("بعد أن أدفع إكرامية مارتن التافه،"),
			"hereletmeshowyouyoutakethefrickinrabbit" to Fix("دعني أريك. تأخذ الأرنب اللعين،", "دعيني أريكِ. تأخذين الأرنب اللعين،", 'f'),
			"youtakethehand" to Fix("وتأخذ اليد.", "وتأخذين اليد.", 'f'),
			"youputitinthegoshdarnhatbutsexy" to Fix("وتضعها في القبعة اللعينة، لكن بإغراء.", "وتضعينها في القبعة اللعينة، لكن بإغراء.", 'f'),
			"bangbaby" to Fix("وهكذا يا حلوة!"),
			"wellenchantémadame" to Fix("تشرّفت بمعرفتكِ يا سيدتي."),
			"luciabeautiful" to Fix("لوسيا، اسم جميل."),
			"whatareyousouptightabouthuh" to Fix("لماذا أنت متوتر هكذا؟", "لماذا أنتِ متوترة هكذا؟", 'f'),
			"inallthoseswankyspotsontheobstrip" to Fix("في كل تلك الأماكن الفخمة على شارع OB."),
			"igotitintheofficevickcomeon" to Fix("إنه في المكتب يا فيك، هيا."),
			"imeanithoneytonightallyours" to Fix("أنا جاد يا عزيزتي. الليلة. كله لكِ."),
			"heyhowboutyougetmainstagetonightandearnyourselfalittlebonus" to Fix("ما رأيك أن تأخذ المسرح الرئيسي الليلة وتكسب مكافأة صغيرة؟", "ما رأيكِ أن تأخذي المسرح الرئيسي الليلة وتكسبي مكافأة صغيرة؟", 'f'),
			"yeahthatstheleastyoucoulddoforavetlikeme" to Fix("نعم، هذا أقل ما يمكنك فعله لمخضرمة مثلي."),
			"woofbeentheresister" to Fix("أوف. مررت بهذا يا أختي."),
			"jeezgodforbidagirlsqueezeinaquickhustle" to Fix("يا إلهي. وكأنه ممنوع على فتاة أن تكسب مالًا سريعًا."),
			"startintothinkyoumightbehigherthanme" to Fix("بدأت أظن أنك منتشٍ أكثر مني.", "بدأت أظن أنكِ منتشية أكثر مني.", 'f'),
			"theresaprettygoodthriftstoreontheotherendofthestrip" to Fix("يوجد متجر ملابس مستعملة جيد في آخر الشارع."),
			"cmonhoneyearnthatscampipush" to Fix("هيا يا عزيزي، اكسب عشاءك. ادفع."),
			"thoseare80ssostartwithhalfandthenuh" to Fix("هذه حبوب ٨٠ ملغ، فابدأ بالنصف ثم، آه...", "هذه حبوب ٨٠ ملغ، فابدئي بالنصف ثم، آه...", 'f'),
			"letsgogirlvamanos" to Fix("هيا يا فتاة. لنذهب."),
			"gottabepatientintherethough" to Fix("لكن عليك أن تصبر هناك.", "لكن عليكِ أن تصبري هناك.", 'f'),
			"ohyoureoneofthosehuh" to Fix("أوه، أنت واحد من هؤلاء، أليس كذلك؟", "أوه، أنتِ واحدة من هؤلاء، أليس كذلك؟", 'f'),
			"cmonpleasejusthelpmeup" to Fix("هيا، أرجوك؟ فقط ساعدني على النهوض.", "هيا، أرجوكِ؟ فقط ساعديني على النهوض.", 'f'),
			"imsupposedtojustknowthathelpmeup" to Fix("وكيف لي أن أعرف ذلك؟ ساعدني على النهوض.", "وكيف لي أن أعرف ذلك؟ ساعديني على النهوض.", 'f'),
			"howboutigiveyouthemoneyforthepillshmmasapeaceoffering" to Fix("ما رأيك أن أعطيك ثمن الحبوب؟ كعربون صلح؟", "ما رأيكِ أن أعطيكِ ثمن الحبوب؟ كعربون صلح؟", 'f'),
			"sowegottatakealittleridedowntownwhatdoyousay" to Fix("علينا أن نذهب في جولة قصيرة إلى وسط المدينة. ما رأيك؟", "علينا أن نذهب في جولة قصيرة إلى وسط المدينة. ما رأيكِ؟", 'f'),
			"whatsupwegoin" to Fix("ما الأمر؟ هل نذهب؟"),
			"whatwoahplottwistokayihaveamillionquestionsbutholdon" to Fix("ماذا؟ واو، مفاجأة! حسنًا، عندي مليون سؤال، لكن انتظر-", "ماذا؟ واو، مفاجأة! حسنًا، عندي مليون سؤال، لكن انتظري-", 'f'),
			"illgetyoubackforthis" to Fix("سأرد لك هذا الجميل.", "سأرد لكِ هذا الجميل.", 'f'),
			"youbetternotbefuckinwithmeagain" to Fix("من الأفضل ألا تعبث معي مرة أخرى.", "من الأفضل ألا تعبثي معي مرة أخرى.", 'f'),
			"imsureyoudo" to Fix("أنا متأكدة من ذلك."),
			"itrynottoinpublic" to Fix("أحاول ألا أفعل ذلك أمام الناس."),
			"deep" to Fix("كلام عميق."),
			"welltrythatthenletsgo" to Fix("سنجرب ذلك إذن. هيا بنا."),
			"igetitihaveonetootommy" to Fix("فهمت. عندي واحد أيضًا. تومي."),
			"vickitoldmeyoureholdingsomepainmeds" to Fix("أخبرتني فيكي أن لديك مسكنات ألم.", "أخبرتني فيكي أن لديكِ مسكنات ألم."),
			"tostraightenthisshitout" to Fix("لأصلح هذه المصيبة!"),
			"okaydoyouknowwhere" to Fix("حسنًا. هل تعرف أين؟", "حسنًا. هل تعرفين أين؟", 'f'),
			"nolikeithinkshegotthesefroma247look" to Fix("لا، أظنها اشترتها من بقالة. انظر.", "لا، أظنها اشترتها من بقالة. انظري.", 'f'),
			"youwantmetosplitazombix" to Fix("تريدني أن أقسم حبة زومبكس؟", "تريدينني أن أقسم حبة زومبكس؟", 'f'),
			"whereyougoin" to Fix("إلى أين أنت ذاهب؟", "إلى أين أنتِ ذاهبة؟", 'f'),
			"buttheyrefuckinfakesmanforreal" to Fix("لكنها مزيفة يا رجل! بجد."),
			"hereitisparadiseonwheels" to Fix("ها هي! جنة على عجلات!"),
			"koltswillsellyouanythingoxiespercsxans" to Fix("الكولتس يبيعونك أي شيء: أوكسي، بيركوسيت، زاناكس..", "الكولتس يبيعونكِ أي شيء: أوكسي، بيركوسيت، زاناكس..", 'f'),
			"ifyourelookinforpillsthengellhornsfuckincandyland" to Fix("إذا كنت تبحث عن حبوب، فجيلهورن جنة الحلوى اللعينة.", "إذا كنتِ تبحثين عن حبوب، فجيلهورن جنة الحلوى اللعينة.", 'f'),
			"uhheywhawhatwasyournameagain" to Fix("آه، مرحبًا. ما-ما اسمك مرة أخرى؟", "آه، مرحبًا. ما-ما اسمكِ مرة أخرى؟", 'f'),
			"ijustcleaneduptoosoyknowuhluckyyou" to Fix("وقد نظفت للتو أيضًا. لذا، أنت محظوظ.", "وقد نظفت للتو أيضًا. لذا، أنتِ محظوظة.", 'f'),
			"areyoustilltakingmehome" to Fix("هل ما زلت ستوصلني إلى البيت؟", "هل ما زلتِ ستوصلينني إلى البيت؟", 'f'),
			"thankyouforgettingmeouttathere" to Fix("شكرًا لأنك أخرجتني من هناك!", "شكرًا لأنكِ أخرجتِني من هناك!", 'f'),
			"yeahthatscausevickiknowswhatsup" to Fix("نعم، لأن فيكي تفهم في الأمور!"),
			"andimnotgonnaliesomeofitsawesome" to Fix("ولن أكذب عليك، بعضها رائع!", "ولن أكذب عليكِ، بعضها رائع!", 'f'),
			"dunkonlycarriesahundredpercentpharmaceuticals" to Fix("دنك لا يبيع إلا أدوية أصلية مئة بالمئة."),
			"yougotananglefriend" to Fix("هل تخطط لشيء يا صديقي؟", "هل تخططين لشيء يا صديقتي؟", 'f'),
			"yeahwalkawayyouknowyouaintaboutit" to Fix("نعم، امشِ. تعرف أنك لست قدّها!", "نعم، امشي. تعرفين أنكِ لستِ قدّها!", 'f'),
			"freak" to Fix("مجنون..", "مجنونة..", 'f'),
			"anywaybacktoit" to Fix("على أي حال... نعود للعمل.."),
			"wantedacloserlook" to Fix("أردت أن تنظر عن قرب؟", "أردتِ أن تنظري عن قرب؟", 'f'),
			"nobodypanic" to Fix("لا داعي للذعر."),
			"notyetworkingonit" to Fix("ليس بعد، أعمل على ذلك."),
			"neverminddidyougetyourpills" to Fix("لا يهم. هل حصلت على حبوبك؟", "لا يهم. هل حصلتِ على حبوبكِ؟", 'f'),
			"comeonin" to Fix("ادخل!", "ادخلي!", 'f'),
			"urghdidyougetacat" to Fix("أف، هل اقتنيت قطة؟", "أف، هل اقتنيتِ قطة؟"),
			"billwiththepill" to Fix("بيل صاحب الحبوب!"),
			// from the log of 27 Nov (robbing a store, the Kolts, passers-by)
			"finegetyourselfsomedeodorant" to Fix("حسنًا... اشترِ لنفسك مزيل عرق.", "حسنًا... اشتري لنفسكِ مزيل عرق."),
			"getthefuckouthurryup" to Fix("اخرج من هنا، اللعنة! أسرع!", "اخرجي من هنا، اللعنة! أسرعي!"),
			"openitbitch" to Fix("افتحه يا حقير.", "افتحيه يا حقيرة."),
			"nowstayrightthereandshutthefuckup" to Fix("الآن ابقَ مكانك واخرس", "الآن ابقي مكانكِ واخرسي"),
			"startwalkin" to Fix("امشِ!", "امشي!"),
			"heymoneysintheregisterjustgetitselfserviceorwhatever" to Fix("المال في الصندوق، خذه بنفسك، خدمة ذاتية أو أيًا كان...", "المال في الصندوق، خذيه بنفسكِ، خدمة ذاتية أو أيًا كان...", 'f'),
			"heymoneysintheregisterjustgetit" to Fix("المال في الصندوق، خذه بنفسك،", "المال في الصندوق، خذيه بنفسكِ،", 'f'),
			"thatsitregistersbonedrynow" to Fix("هذا كل شيء، الصندوق فارغ تمامًا الآن."),
			"heretakeit" to Fix("تفضل، خذه!", "تفضلي، خذيه!", 'f'),
			"needanyhelp" to Fix("هل تحتاج أي مساعدة؟", "هل تحتاجين أي مساعدة؟", 'f'),
			"yougotwhatyoucameforrightmindgettingoutnow" to Fix("حصلت على ما جئت من أجله، صحيح؟ هلّا خرجت الآن؟", "حصلتِ على ما جئتِ من أجله، صحيح؟ هلّا خرجتِ الآن؟", 'f'),
			"couldigetsomechangemisslady" to Fix("هل لي ببعض الفكّة يا سيدتي؟"),
			"manimcrashingandgottheiaaalloverme" to Fix("يا رجل، مفعول المخدر يزول عني ووكالة IAA تلاحقني من كل مكان."),
			"aywatchit" to Fix("هيه، انتبه!", "هيه، انتبهي!", 'f'),
			"heywaitwaithowboutwecallit" to Fix("مهلًا، انتظر، انتظر، ما رأيك أن ننهي الأمر هنا...", "مهلًا، انتظري، انتظري، ما رأيكِ أن ننهي الأمر هنا...", 'f'),
			"imfuckinggone" to Fix("سأرحل من هنا، اللعنة!"),
			"wellleaveyoubeandsaynomoreaboutithowsthat" to Fix("سنتركك وشأنك ولن نتكلم عن الأمر! ما رأيك؟", "سنترككِ وشأنكِ ولن نتكلم عن الأمر! ما رأيكِ؟", 'f'),
			"aintyoubibiscousin" to Fix("ألست ابن عم بيبي؟", "ألستِ ابنة عم بيبي؟", 'f'),
			"ayowaityoulookfamiliar" to Fix("مهلًا، انتظر، تبدو مألوفًا!", "مهلًا، انتظري، تبدين مألوفة!", 'f'),
			"quitfuckinstallinletsgo" to Fix("توقف عن المماطلة اللعينة! هيا!", "توقفي عن المماطلة اللعينة! هيا!", 'f'),
			"surehopin" to Fix("طبعًا، اركب.", "طبعًا، اركبي.", 'f'),
			"soifyouhappentoyouknowfindaridethatspeakstoyouorwhatever" to Fix("فإن صادفت، كما تعلم، سيارة تعجبك، أو أيًا كان،", "فإن صادفتِ، كما تعلمين، سيارة تعجبكِ، أو أيًا كان،", 'f'),
			"justbringittheretellhimisentyou" to Fix("فقط خذها إلى هناك. قل له إنني أرسلتك.", "فقط خذيها إلى هناك. قولي له إنني أرسلتكِ.", 'f'),
			"leasticandoafterthiscolorfulwasteoftime" to Fix("هذا أقل ما يمكنني فعله بعد كل هذا الوقت الضائع."),
			"whosgotavisualanyone" to Fix("هل يراه أحد؟ أي أحد؟"),
			"buyanewoutfit" to Fix("اشترِ ملابس جديدة"),
			"nipper38pistol" to Fix("مسدس نيبر .38"),
			// from the log of 27 Nov, night («Hey» said as «مهلا»: a greeting or a call, by the whole line)
			"heybabe" to Fix("أهلًا يا حبيبي...", "أهلًا يا حبيبتي...", 'f'),
			"heywatchit" to Fix("هيه! انتبه.", "هيه! انتبهي.", 'f'),
			"heyoverheredude" to Fix("هيه، أنا هنا يا صاح."),
			"supwithyoutwo" to Fix("ما أخباركما أنتما الاثنان؟"),
			"chillyouregood" to Fix("اهدأ، أنت بخير.", "اهدئي، أنتِ بخير."),
			"yougood" to Fix("هل أنت بخير؟", "هل أنتِ بخير؟"),
			"okaygoingout" to Fix("حسنًا، سأخرج.."),
			"ohsorryimluciaimafriendofvickis" to Fix("أوه، آسفة. أنا لوسيا، صديقة فيكي؟"),
			"shesaidyoumightbeabletohelpmeoutwiththeride" to Fix("قالت إنك قد تستطيع مساعدتي بخصوص السيارة.", "قالت إنكِ قد تستطيعين مساعدتي بخصوص السيارة."),
			"didyounothearmesayimtakingthis" to Fix("ألم تسمعني أقول إنني سآخذ هذه؟", "ألم تسمعيني أقول إنني سآخذ هذه؟"),
			"ilikeyourcargirl" to Fix("تعجبني سيارتكِ يا فتاة."),
			"babeimback" to Fix("حبيبي... لقد عدت.", "حبيبتي... لقد عدت."),
			"haveagreatrestofyourday" to Fix("أتمنى لك بقية يوم رائع.", "أتمنى لكِ بقية يوم رائع.", 'f'),
			"wedontneedanymoreclosecalls" to Fix("لا نريد المزيد من المواقف الخطرة."),
			"kaywellgoaroundback" to Fix("حسنًا، اذهب من الخلف.", "حسنًا، اذهبي من الخلف.", 'f'),
			"ehshewouldntletthatstopher" to Fix("إيه. ما كانت لتسمح لذلك بأن يوقفها."),
			"areyoutweakinletgoofme" to Fix("هل أنت مسطول؟ اتركني!", "هل أنتِ مسطولة؟ اتركيني!", 'f'),
			"waitwasfuckofftoocomplicatedforyou" to Fix("مهلًا، هل كانت كلمة \"اغرب عن وجهي\" صعبة عليك؟", "مهلًا، هل كانت كلمة \"اغربي عن وجهي\" صعبة عليكِ؟", 'f'),
			"quietfuckoffalready" to Fix("اصمت! اغرب عن وجهي!", "اصمتي! اغربي عن وجهي!", 'f'),
			"ohfuckoffscenestealer" to Fix("أوه، اغرب عن وجهي يا سارق الأضواء.", "أوه، اغربي عن وجهي يا سارقة الأضواء.", 'f'),
			"pshlikeigiveashit" to Fix("بف، وكأنني أهتم.."),
			"canyougrowuplike" to Fix("ألا يمكنك أن تكبر؟ يعني..", "ألا يمكنكِ أن تكبري؟ يعني..", 'f'),
			"yougotafuckinproblem" to Fix("هل عندك مشكلة لعينة؟", "هل عندكِ مشكلة لعينة؟", 'f'),
			"whatsyourfuckingproblem" to Fix("ما مشكلتك اللعينة؟", "ما مشكلتكِ اللعينة؟", 'f'),
			"thefuckswrongwithyou" to Fix("ما خطبك بحق الجحيم؟", "ما خطبكِ بحق الجحيم؟", 'f'),
			"youalmosttookoutamilliondollarinvestmentfuckhead" to Fix("كدت تدمّر استثمارًا بمليون دولار، أيها الأحمق!", "كدتِ تدمّرين استثمارًا بمليون دولار، أيتها الحمقاء!", 'f'),
			"youfuckinpussy" to Fix("يا جبان!", "يا جبانة!", 'f'),
			"stopactinlikeafuckinass" to Fix("توقف عن التصرف كالأحمق اللعين!", "توقفي عن التصرف كالحمقاء اللعينة!", 'f'),
			"whatbackwardsasscountryyoulearntodriveinbetitwaseurope" to Fix("في أي بلد متخلف تعلّمت القيادة؟ أراهن أنها أوروبا!", "في أي بلد متخلف تعلّمتِ القيادة؟ أراهن أنها أوروبا!", 'f'),
			"suckadick" to Fix("تبًا لك!", "تبًا لكِ!", 'f'),
			// from the logs of 28 Nov (the motel, the mud park, Bud, robbing stores, the cops)
			"breakfastluimnotfallingforthatagain" to Fix("فطور؟ لو، لن أنخدع بهذا مرة أخرى."),
			"ambrosiaslikethatrealuhlawandordertown" to Fix("أمبروسيا هكذا. مدينة، آه، قانون ونظام بحق."),
			"areyoustillworkingforthatguyattheclubrealheavypockets" to Fix("هل ما زلت تعمل لدى ذلك الرجل في النادي، صاحب الجيوب الممتلئة؟", "هل ما زلتِ تعملين لدى ذلك الرجل في النادي، صاحب الجيوب الممتلئة؟"),
			"heyemptythefuckingregistersokayokay" to Fix("هيه! أفرغ الصناديق اللعينة! حسنًا، حسنًا!", "هيه! أفرغي الصناديق اللعينة! حسنًا، حسنًا!"),
			"hellyeahitsgoodyourealwayshotasfuck" to Fix("طبعًا، إنه جميل. أنت دائمًا مثير جدًا.", "طبعًا، إنه جميل. أنتِ دائمًا مثيرة جدًا.", 'f'),
			"ihopeyourenottoobigtimeforusnow" to Fix("أتمنى أنك لم تصبح أكبر من أن تعرفنا الآن؟", "أتمنى أنكِ لم تصبحي أكبر من أن تعرفينا الآن؟"),
			"inchortwohigheriwouldvebeenwasted" to Fix("لو كانت أعلى بإنش أو اثنين، لكنت ميتًا."),
			"screwitletstakethatatvwecanstillcatchem" to Fix("تبًا. لنأخذ تلك الدراجة الرباعية، ما زال بإمكاننا اللحاق بهم!"),
			"stillgotallthatcashfromyesterday" to Fix("ما زال معنا كل ذلك المال من الأمس."),
			"thatwasacleanjob" to Fix("كانت تلك عملية نظيفة."),
			"whatthefuckiswrongwithyou" to Fix("ما خطبك بحق الجحيم؟", "ما خطبكِ بحق الجحيم؟"),
			"yeahsilverlinings" to Fix("أجل. لكل شيء جانب مشرق."),
			"youstaydontmove" to Fix("أنت. ابقَ مكانك. لا تتحرك.", "أنتِ. ابقي مكانكِ. لا تتحركي."),
			"right" to Fix("صحيح."),
			"fuckinpunks" to Fix("أوغاد لعينون.."),
			"stupidmotherfucker" to Fix("وغد غبي!"),
			"everybodythinkstheyrejackfuckinhowitzer" to Fix("الجميع يظن نفسه جاك هاوتزر اللعين."),
			"wannaseewhogetstherefirst" to Fix("تريد أن نرى من يصل أولًا؟", "تريدين أن نرى من يصل أولًا؟", 'f'),
			"youwannagetacouplebikestearupsomemud" to Fix("تريد أن نأخذ دراجتين ونخوض في الطين؟", "تريدين أن نأخذ دراجتين ونخوض في الطين؟", 'f'),
			"yougotabikepreference" to Fix("هل تفضّل دراجة معينة؟", "هل تفضّلين دراجة معينة؟", 'f'),
			"youknowyouwereaproouttherefastcleangotthejobdoneimimpressed" to Fix("أتعلم، كنت محترفًا هناك. سريع ونظيف وأنجزت المهمة. أنا معجب.", "أتعلمين، كنتِ محترفة هناك. سريعة ونظيفة وأنجزتِ المهمة. أنا معجب.", 'f'),
			"nicefuckyeahyouthinkwecancomecrashwithyouacoupleofdays" to Fix("رائع. أجل! تظن أنه يمكننا المبيت عندك يومين؟", "رائع. أجل! تظنين أنه يمكننا المبيت عندكِ يومين؟"),
			"heyyouthinkeveryonehereknowswedidthoserobberies" to Fix("اسمع، هل تظن أن الجميع هنا يعرف أننا قمنا بتلك السرقات؟", "اسمعي، هل تظنين أن الجميع هنا يعرف أننا قمنا بتلك السرقات؟", 'f'),
			"ohshitlookatyou" to Fix("يا إلهي. انظر إلى نفسك.", "يا إلهي. انظري إلى نفسكِ.", 'f'),
			"ohfeelingcockyhuh" to Fix("أوه، تشعر بالغرور، هاه؟", "أوه، تشعرين بالغرور، هاه؟", 'f'),
			"wejustgottaseewhatsonthatdrive" to Fix("علينا فقط أن نرى ما على ذلك القرص."),
			"wellwehitafewspots" to Fix("حسنًا، سرقنا بضعة أماكن!"),
			"anddontgetfuckincute" to Fix("ولا تتذاكَ، اللعنة.", "ولا تتذاكي، اللعنة."),
			"dick" to Fix("يا حقير!", "يا حقيرة!"),
			"fuckoutineedthis" to Fix("ابتعد، أحتاج هذا!", "ابتعدي، أحتاج هذا!"),
			"getyourassoverheredontmove" to Fix("تعال... إلى هنا! لا تتحرك!", "تعالي... إلى هنا! لا تتحركي!"),
			"herenowgimmemynecklace" to Fix("تفضل. الآن أعطني قلادتي.", "تفضلي. الآن أعطيني قلادتي."),
			"igotyoucoveredstaylow" to Fix("أنا أغطيك، ابقَ منخفضًا!", "أنا أغطيكِ، ابقي منخفضة!"),
			"illwatchtheseguys" to Fix("سأراقب هؤلاء!"),
			"junkiefucks" to Fix("مدمنون ملاعين."),
			"luckyokaybulletsponge" to Fix("محظوظ؟ حسنًا يا إسفنجة الرصاص."),
			"ohholyfuck" to Fix("يا إلهي!"),
			"shitalmostempty" to Fix("تبًا، شبه فارغ."),
			"sorrywelosteachother" to Fix("آسفة. أضعنا بعضنا."),
			"thanksbabe" to Fix("شكرًا يا حبيبي.", "شكرًا يا حبيبتي."),
			"timetogetcozy" to Fix("حان وقت الراحة."),
			"welltheysoundlikedicks" to Fix("حسنًا، يبدو أنهم حقيرون."),
			"wellatleasttheycouldntdriveforshit" to Fix("حسنًا، على الأقل كانت قيادتهم سيئة جدًا."),
			"whatareyoudoingupyoubetter" to Fix("لماذا أنت مستيقظ؟ هل تحسنت؟", "لماذا أنتِ مستيقظة؟ هل تحسنتِ؟"),
			"whatrankwereyouagainsergeant" to Fix("ما كانت رتبتك؟ رقيب؟"),
			"whatsgotintothesecops" to Fix("ما بال هؤلاء الشرطة؟"),
			"wherethefuckyougoing" to Fix("إلى أين أنت ذاهب بحق الجحيم؟", "إلى أين أنتِ ذاهبة بحق الجحيم؟"),
			"youtoobeatit" to Fix("وأنت أيضًا، اغرب عن وجهي!", "وأنتِ أيضًا، اغربي عن وجهي!"),
			"okayiwannaseesomemoneyrightfuckinnow" to Fix("حسنًا، أريد أن أرى المال الآن، اللعنة!"),
			"okaydumpthoseregistersletsgo" to Fix("حسنًا، أفرغ تلك الصناديق، هيا!", "حسنًا، أفرغي تلك الصناديق، هيا!"),
			"rightokaybetheresoon" to Fix("حسنًا.. سأكون هناك قريبًا."),
			"imontheseguys" to Fix("أنا أتولى هؤلاء!"),
			"justchilloutokayrentscomingipromise" to Fix("اهدأ فقط، حسنًا؟ الإيجار قادم. أعدك.", "اهدئي فقط، حسنًا؟ الإيجار قادم. أعدكِ.", 'f'),
			"ohwhaddayaknowwerekindagoodatthistogether" to Fix("أوه، انظر إلى هذا... يبدو أننا جيدان في هذا معًا.", "أوه، انظري إلى هذا... يبدو أننا جيدان في هذا معًا."),
			"andtrytogetafewgrandstackedaway" to Fix("وأحاول أن أجمع بضعة آلاف."),
			"takingcareofthings" to Fix("أتدبر الأمور."),
			"soeveryoneisupineverybodysbusiness" to Fix("فالجميع يتدخل في شؤون الجميع."),
			"yougottomakeacallchoosewhosdriving" to Fix("عليك أن تقرر. اختر من يقود.", "عليكِ أن تقرري. اختاري من يقود."),
			"heyibuckedyouyourcutonthosepills" to Fix("أهلًا... حوّلت لك نصيبك من تلك الحبوب.", "أهلًا... حوّلت لكِ نصيبكِ من تلك الحبوب.", 'f'),
			"nowthatsstraightup" to Fix("هذا كلام صريح."),
			"theystraightuplosttheirminds" to Fix("فقدوا عقولهم تمامًا."),
			"dropitbythetrailerandillgetyoupaid" to Fix("اتركه عند المقطورة وسأدفع لك.", "اتركيه عند المقطورة وسأدفع لكِ.", 'f'),
			"okaycoolillhityouupthen" to Fix("حسنًا، رائع. سأتواصل معكما إذن"),
			"niceridingyoutwohookedonthisshitnoworwhat" to Fix("قيادة رائعة. هل أدمنتما هذا الشيء الآن أم ماذا؟"),
			"yeahthatsthemgetemsetup" to Fix("أجل، هؤلاء هم. جهّزهم."),
			"alrightgofastdontdietrynottocomeinlast" to Fix("حسنًا، أسرعوا، لا تموتوا، وحاولوا ألا تكونوا الأخيرين!"),
			"goodforyouhoney" to Fix("هذا جيد يا عزيزي.", "هذا جيد يا عزيزتي.", 'f'),
			"youforgetsomethinorareyoupayingup" to Fix("نسيت شيئًا؟ أم ستدفع ما عليك؟", "نسيتِ شيئًا؟ أم ستدفعين ما عليكِ؟", 'f'),
			"heretopayyourrent" to Fix("جئت لتدفع الإيجار؟", "جئتِ لتدفعي الإيجار؟", 'f'),
			"enjoythepeaceandquiet" to Fix("استمتع بالهدوء والسكينة.", "استمتعي بالهدوء والسكينة.", 'f'),
			"justgetawayfromitall" to Fix("فقط... ابتعد عن كل شيء.", "فقط... ابتعدي عن كل شيء.", 'f'),
			"ahsothiswhatchusettledon" to Fix("آه، إذن هذا ما استقررت عليه.", "آه، إذن هذا ما استقررتِ عليه.", 'f'),
			"lookwhatthelawchasedin" to Fix("انظروا من طاردته الشرطة إلى هنا."),
			"heyyoutwocomeoverhere" to Fix("أنتما الاثنان! تعاليا إلى هنا."),
			"laterhombre" to Fix("أراك لاحقًا يا رجل."),
			"wellillseeyalaterthencauseyouknowicantresistwatchinyalleatshitoutthere" to Fix("حسنًا، أراكم لاحقًا إذن، لأنكم تعرفون أنني لا أقاوم مشاهدتكم تتعثرون هناك."),
			"yoifyouaintdoinshithowboutarace" to Fix("إن لم تكن تفعل شيئًا، ما رأيك بسباق؟", "إن لم تكوني تفعلين شيئًا، ما رأيكِ بسباق؟", 'f'),
			"igotitcalmyourselfdown" to Fix("فهمت، اهدأ.", "فهمت، اهدئي.", 'f'),
			"surething" to Fix("بالتأكيد."),
			"allyours" to Fix("كله لك!", "كله لكِ!", 'f'),
			"yeahnahseethistakeagoodhardlook" to Fix("لا، أبدًا! ترى هذا؟ انظر جيدًا.", "لا، أبدًا! ترين هذا؟ انظري جيدًا.", 'f'),
			"okayiamiamjustohfuck" to Fix("حسنًا! أفعل! أفعل! فقط، أوه... اللعنة!"),
			"fuckisyourproblem" to Fix("ما مشكلتك بحق الجحيم؟", "ما مشكلتكِ بحق الجحيم؟", 'f'),
			"cashisintheregisterknockyourselfout" to Fix("المال في الصندوق، خذه كله..", "المال في الصندوق، خذيه كله..", 'f'),
			"checkit" to Fix("انظر إلى هذا!", "انظري إلى هذا!", 'f'),
			"whatsyourfuckingdeal" to Fix("ما مشكلتك بحق الجحيم؟", "ما مشكلتكِ بحق الجحيم؟", 'f'),
			"youreahoafuckingbasicstandardissueho" to Fix("أنتِ عاهرة! عاهرة عادية ومبتذلة!"),
			"youonsomerealqueentypeshitrightnowifuckwitit" to Fix("أنت تتصرف كملك حقيقي الآن! يعجبني هذا!", "أنتِ تتصرفين كملكة حقيقية الآن! يعجبني هذا!", 'f'),
			"sheprettylitoutthereman" to Fix("إنها منتشية تمامًا هناك يا رجل."),
			"outtathecar" to Fix("اخرج من السيارة!", "اخرجي من السيارة!", 'f'),
			"girlwhatsupwitchu" to Fix("يا بنت، ما بكِ؟"),
			"thefuckiswrongwithyou" to Fix("ما خطبك بحق الجحيم؟", "ما خطبكِ بحق الجحيم؟", 'f'),
			"fuckinlibtard" to Fix("ليبرالي أحمق لعين!", "ليبرالية حمقاء لعينة!", 'f'),
			"quitdrivinlikesomecommiesocialistfuck" to Fix("كف عن القيادة كأنك شيوعي لعين!", "كفي عن القيادة كأنكِ شيوعية لعينة!", 'f'),
			"trytorunmedownillkillyourass" to Fix("حاول أن تدهسني وسأقتلك!", "حاولي أن تدهسيني وسأقتلكِ!", 'f'),
			"hardpass" to Fix("لا، شكرًا."),
			"youreliketheickpersonifieddudewhatareyouevendoing" to Fix("أنت القرف بعينه يا رجل. ماذا تفعل أصلًا؟"),
			"suckmydick" to Fix("تبًا لك!", "تبًا لكِ!", 'f'),
			"holyshit" to Fix("يا إلهي!"),
			"holyshitholdonwaitwait" to Fix("يا إلهي! انتظر، انتظر!", "يا إلهي! انتظري، انتظري!", 'f'),
			"fuckoff" to Fix("اغرب عن وجهي!", "اغربي عن وجهي!", 'f'),
			"cuttheengine" to Fix("أطفئ المحرك!", "أطفئي المحرك!"),
			"heycuttheengine" to Fix("هيه! أطفئ المحرك!", "هيه! أطفئي المحرك!"),
			"igotavisual" to Fix("أراه!"),
			"gungunwegotagun" to Fix("سلاح! سلاح! معه سلاح!"),
			"shotsfiredshotsfired" to Fix("إطلاق نار! إطلاق نار!"),
			"suspectditchedtheirvehicle" to Fix("المشتبه به ترك سيارته!"),
			"awhellnahimnothighenoughforthisshit" to Fix("لا، مستحيل، لست منتشيًا بما يكفي لهذا!"),
			"iaintdonearealshitinfourteenyears" to Fix("لم أقضِ حاجتي كما يجب منذ أربعة عشر عامًا."),
			"isuckedthatdookiestraightbackupandran" to Fix("حبستُ ذلك البراز في الداخل وهربت."),
			"justdroppinadookandsuddenlythereitisinfrontofme" to Fix("كنت أقضي حاجتي، وفجأة ها هو أمامي."),
			"ivebeenconstipatedeversince" to Fix("ومنذ ذلك الحين وأنا مصاب بالإمساك."),
			"incorporealasfuck" to Fix("بلا جسد تمامًا."),
			"hotdamnoneofourridersjustgavephysicsthefinger" to Fix("يا للروعة! أحد متسابقينا تحدى الفيزياء للتو!"),
			"movingupthepack" to Fix("يتقدم بين المتسابقين!"),
			"cmonchurnthattrackup" to Fix("هيا، اسحقوا ذلك المضمار!"),
			"impacttrauma" to Fix("صدمة قوية!"),
			"ohtheypassinemup" to Fix("أوه، إنهم يتجاوزونهم!"),
			"forcetheclerktothecashregister" to Fix("أجبر الموظف على الذهاب إلى الصندوق"),
			"robthethriftstore" to Fix("اسرق متجر المستعمل"),
			"losethecops" to Fix("تخلّص من الشرطة"),
			"stealasuitablevehicle" to Fix("اسرق سيارة مناسبة"),
			"destroytheambulance" to Fix("دمّر سيارة الإسعاف"),
			"stoptheambulance" to Fix("أوقف سيارة الإسعاف"),
			"buyagunorrecoveryourown" to Fix("اشترِ سلاحًا أو استرجع سلاحك"),
			"holdtointeractwithyourpartner" to Fix("اضغط مطولًا للتفاعل مع شريكك"),
			"resprayyournewpersonalvehicle" to Fix("أعد طلاء سيارتك الشخصية الجديدة"),
			"leavemosesautoshop" to Fix("غادر ورشة موزس"),
			"leavethestore" to Fix("غادر المتجر"),
			"returntolucia" to Fix("ارجع إلى لوسيا"),
			"parkupattheentrance" to Fix("اركن عند المدخل"),
			"buyanewoutfitatthemerchstall" to Fix("اشترِ ملابس جديدة من كشك البضائع"),
			"suredunktalktoyoulater" to Fix("حسنًا يا دنك. أكلمك لاحقًا."),
			"andillseeyousoon" to Fix("وأراكما قريبًا."),
			// from the log of 28 Nov, night (the motel party, Delights)
			"whewyeahnowyouretalking" to Fix("أوف، أجل، هذا هو الكلام!"),
			"openthisdoorrightnow" to Fix("افتحوا هذا الباب حالًا!"),
			"andiknowyoucantaffordit" to Fix("وأعرف أنكم لا تستطيعون دفعها!"),
			"iknowwhatyouredoinginthereiveseenyoursheets" to Fix("أعرف ماذا تفعلون هناك! رأيت أغطية أسرّتكم!"),
			"ifyoudontturnthemusicdowniwillfineyouforantisocialbehavior" to Fix("إن لم تخفضوا صوت الموسيقى، سأغرّمكم بسبب الإزعاج!"),
			"thisisyourfinalwarning" to Fix("هذا إنذاركم الأخير!"),
			"ayyouneedmetodriveforyou" to Fix("هيه، تحتاج أن أقود عنك؟", "هيه، تحتاجين أن أقود عنكِ؟", 'f'),
			"firstdaydrivinjesus" to Fix("أول يوم لك في القيادة؟ يا إلهي...", "أول يوم لكِ في القيادة؟ يا إلهي...", 'f'),
			"girlisaygoforitthen" to Fix("يا بنت، إذن أقول لكِ افعليها."),
			"heyyouknowifvickisworkingtonight" to Fix("أهلًا، هل تعرف إن كانت فيكي تعمل الليلة؟", "أهلًا، هل تعرفين إن كانت فيكي تعمل الليلة؟"),
			"yeahithinkshesonthebackbarrightnow" to Fix("أجل، أظنها عند البار الخلفي الآن."),
			"havefun" to Fix("استمتع.", "استمتعي.", 'f'),
			"whatdoyouwant" to Fix("ماذا تريد؟", "ماذا تريدين؟", 'f'),
			"delightscouldbebigforallofusifweworktogether" to Fix("ديلايتس قد يكون فرصة كبيرة لنا جميعًا إذا عملنا معًا."),
			"enoughformetoquitdelightsandgetjordyoutofthatfuckinshithole" to Fix("ما يكفي لأترك ديلايتس وأُخرج جوردي من تلك الحفرة اللعينة."),
			"iknowwhoyouguysareimtellingyou" to Fix("أعرف من أنتما. صدّقاني،"),
			"lookiknowokay" to Fix("اسمع، أعرف. حسنًا؟", "اسمعي، أعرف. حسنًا؟", 'f'),
			"luciayoudoknowiwouldpuntaorphanintotraffic" to Fix("لوسيا، أنتِ تعرفين أنني مستعدة أن أرمي يتيمًا وسط السيارات"),
			"ifitmeantlcouldfuckoverfrankieevenonce" to Fix("لو كان هذا يعني أنني أستطيع أن أخدع فرانكي ولو مرة.."),
			"oryoucanupgradeyourroomifyoulikeittherewhatever" to Fix("أو يمكنكما ترقية غرفتكما إن أعجبكما المكان، أيًا كان!"),
			"uhfivefigureseachatleast" to Fix("آه، عشرات الآلاف لكل واحد... على الأقل!"),
			"youguystoo" to Fix("وأنتما أيضًا!"),
			"butimtellingyouthisisourwayoutofstarlet" to Fix("لكن صدّقاني، هذا طريقنا للخروج من ستارليت."),
			// tasks
			"gotodelights" to Fix("اذهب إلى ديلايتس"),
			"gotodunkstrailer" to Fix("اذهب إلى مقطورة دنك"),
			"drivetotown" to Fix("قُد إلى المدينة"),
			"returntojasonwiththepills" to Fix("عُد إلى جيسون بالحبوب"),
			"returntovickisroom" to Fix("عُد إلى غرفة فيكي"),
			"gototheboatyard" to Fix("اذهب إلى حوض السفن")),
		ocr = listOf(
			// words the reader glued together
			Regex("\\bGetin\\b") to "Get in", Regex("\\bGoto\\b") to "Go to",
			Regex("\\bto(?=[A-Z][a-z])") to "to ",
			Regex("(?<=[A-Za-z])'s(?=(car|room|trailer|office|place|house)\\b)") to "'s ",
			// «I» read as «l» or «|»
			Regex("\\bl'(m|ll|d|ve)\\b") to "I'$1", Regex("(?<=^|\\s)\\|(?=\\s)") to "I",
			Regex("(?<=\\s)l(?=\\s)") to "I", Regex("\\bI'l(?=\\s|$)") to "I'll", Regex("\\bAIl\\b") to "All",
			// the first letter of a name lost: «lan:», «ucia:»
			Regex("^lan(?=:)") to "Ian", Regex("^ucia(?=:)") to "Lucia",
			// a letter lost at the end of a name
			Regex("\\bDr\\. Bil\\b") to "Dr. Bill"),
		english = listOf(
			// «tip out» (pay the staff their share) is «tip»: Google read it as «guide», «inform», «take out»
			Regex("\\b(tip|tipping|tipped|tips)\\s+(him|her|them|me|you|us)\\s+out\\b", RegexOption.IGNORE_CASE) to "$1 $2",
			Regex("\\b(tip|tipping|tipped|tips)\\s+out\\b", RegexOption.IGNORE_CASE) to "$1",
			// «'Kay» is always «okay» (Google: «'كاي»)
			Regex("(?<![A-Za-z])'[Kk]ay\\b") to "Okay",
			// «Sup» is always «what's up» (Google: «سوب», «أتناول العشاء»)
			Regex("(?<![A-Za-z'])\\s*'?[Ss]up\\b(?!')") to " What's up",
			// «your ride», «a ride that…» is a car (Google: «رحلة», 4 lines); «take a little ride», «need a ride»,
			// «give me a ride» are a trip or a lift and stay
			Regex("(?<!\\b(?:give|gave|need|needs|want|get|got|offer|catch|hitch|for|take|took)\\s(?:\\w{1,8}\\s)?)\\b(your|my|his|her|their|the|a|that|this)\\s+ride\\b",
				RegexOption.IGNORE_CASE) to "$1 car"),
		arabic = listOf(
			// one spelling for each name
			w("دونك") to "دنك", w("دانك") to "دنك", w("جايسون") to "جيسون", w("المسرات") to "ديلايتس", w("موسى") to "موزس", w("برعم") to "بد"),
		words = listOf(
			// a lone «Hey» (a greeting or a call) is never «مهلا» (wait): «Hey!» is a call, «Hey.» «Hey,» a greeting;
			// «Hey, hey, hey! Easy» and «Hey, wait» do mean «مهلا» and stay
			Word(Regex("^(?:(?:oh|uh|ah),?\\s+)?hey\\s*!(?!\\s*hey)", RegexOption.IGNORE_CASE), Regex("مهل(?:ًا|اً|ا)|يا(?=!)"), "هيه"),
			Word(Regex("^(?:(?:oh|uh|ah),?\\s+)?hey\\b(?!\\s*[,.!]?\\s*(?:hey|wait|easy|relax|hold|slow|calm|stop|whoa))", RegexOption.IGNORE_CASE),
				Regex("مهل(?:ًا|اً|ا)"), "أهلًا"),
			// «fucking» said as «سخيف» (silly), in 6 lines: «لعين»
			Word(FUCKING, Regex("سخيفة"), "لعينة"), Word(FUCKING, Regex("سخيف(?!ة)"), "لعين"),
			// the cash «register» said as «السجل» (the record), in 5 lines (not «register your vehicle»)
			// «Hey.» «Hey?» said as «يا» (3 lines)
			Word(Regex("\\bhey\\s*[.?]", RegexOption.IGNORE_CASE), Regex("(?<![\\u0621-\\u064A\\u064B-\\u0652\\u0670\\u0640])يا(?=\\s*[.?؟])"), "أهلًا"),
			// «Yo» (a call) said as «يو» (4 lines); not «yo'» (your)
			Word(Regex("(?<![A-Za-z'])a?yo\\b(?!')", RegexOption.IGNORE_CASE), Regex("(?<![\\u0621-\\u064A\\u064B-\\u0652\\u0670\\u0640])أ?يو(?![\\u0621-\\u064A\\u064B-\\u0652\\u0670\\u0640])"), "هيه"),
			// «Shit!» «Oh, shit.» said as «القرف» (4 lines)
			Word(Regex("^(?:(?:oh|ah|aw|yeah),?\\s+)?shit\\b", RegexOption.IGNORE_CASE), Regex("يا للقرف|(?<![\\u0621-\\u064A\\u064B-\\u0652\\u0670\\u0640])القرف(?![\\u0621-\\u064A\\u064B-\\u0652\\u0670\\u0640])"), "تبًا"),
			// «dude» said as «المتأنق» (2 lines), «watch it!» as «شاهده» (3 lines)
			Word(Regex("\\bdude\\b", RegexOption.IGNORE_CASE), Regex("متأنق"), "رجل"),
			Word(Regex("\\bwatch it\\b", RegexOption.IGNORE_CASE), Regex("مشاهدته|شاهده|شاهدها"), "انتبه"),
			// two words said wrong together: «you good?» as «أنت جيد» (3 lines; not «good at»)
			Word(Regex("\\byou(?:'re| are)? good\\b(?!\\s+at)", RegexOption.IGNORE_CASE), Regex("(أنتِ?)\\s+جيدة?(?![\\u0621-\\u064A\\u064B-\\u0652\\u0670\\u0640])"), "$1 بخير"),
			// «Come on!» on its own (urging) as «تعال» (3 lines); not when «come» comes twice («Come on then! Come and get it!»)
			Word(Regex("^(?!.*\\bcome\\b.*\\bcome\\b).*\\b(?:come on|c'mon)\\s*[.!?]", RegexOption.IGNORE_CASE), Regex("(?<![\\u0621-\\u064A\\u064B-\\u0652\\u0670\\u0640])تعال(?![\\u0621-\\u064A\\u064B-\\u0652\\u0670\\u0640])"), "هيا"),
			// «fuck off» as «اخرس», «اللعنة قبالة» (3 lines)
			Word(Regex("\\bfuck off\\b", RegexOption.IGNORE_CASE), Regex("اللعنة قبالة|(?<![\\u0621-\\u064A\\u064B-\\u0652\\u0670\\u0640])اخرس(?![\\u0621-\\u064A\\u064B-\\u0652\\u0670\\u0640])"), "اغرب عن وجهي"),
			// «Bye» as «الوداع» (2 lines): a parting for ever
			Word(Regex("\\bbye\\b", RegexOption.IGNORE_CASE), Regex("(?<![\\u0621-\\u064A\\u064B-\\u0652\\u0670\\u0640])(?:الوداع|وداعًا|وداعا)(?![\\u0621-\\u064A\\u064B-\\u0652\\u0670\\u0640])"), "مع السلامة"),
			Word(REGISTER, Regex("السجلات"), "الصناديق"), Word(REGISTER, Regex("السجل(?![\\u0621-\\u064A\\u064B-\\u0652\\u0670\\u0640])"), "الصندوق")),
		voice = mapOf(
			"دنك" to "دَنْك", "فيكي" to "فِيكِي", "لوسيا" to "لُوسِيَا", "جيسون" to "جَيْسُون", "فرانكي" to "فْرانْكِي",
			"مارتن" to "مارْتِن", "براين" to "بْرايِن", "ديلايتس" to "دِيلايْتْس", "جيلهورن" to "جِيلْهُورْن", "ليلى" to "لَيْلى", "موزس" to "مُوزِس"))

	/** the game being played (GTA VI also when no game is chosen: the only one so far) */
	@Volatile var active: Game? = GTA6
		private set

	fun select(gameName: String)
	{
		val n = gameName.lowercase()
		active = if(n.isBlank() || "grand theft auto" in n || "gta" in n) GTA6 else null
	}

	private val NAME = Regex("^\\s*([A-Za-z][A-Za-z'.\\-]*(?:\\s(?:&\\s)?[A-Za-z][A-Za-z'.\\-]*){0,2})\\s*:\\s*(.+)$")

	/** «Vicki: text» → (vicki, text); no name → (null, line) */
	fun split(line: String): Pair<String?, String>
	{
		val m = NAME.find(line) ?: return null to line
		return speakerName(m.groupValues[1].trim()).lowercase() to m.groupValues[2]
	}

	/** a speaker's name as read: words in capitals read with it from the picture behind it («SIMPSON GELLHOS
	 * Shanese», «LRI Dunk») are not part of it when its last word is one of the game's people */
	fun speakerName(name: String): String
	{
		val g = active ?: return name
		val n = name.lowercase()
		if(n in g.people || n in g.crowd || n in g.names) return name
		// the words before it in capitals (a sign, a plate read with it), not a title («Uncle Raul»)
		val last = name.substringAfterLast(' ')
		val before = name.substringBeforeLast(' ', "")
		return if(before.isNotEmpty() && before == before.uppercase() && last.lowercase() in g.people) last else name
	}

	/** the person [name] (lowercase), if the game knows them */
	fun person(name: String?): Person? = name?.let { active?.people?.get(it.lowercase()) }

	/** [name] is a passer-by of the game («Local»): no voice of its own */
	fun crowd(name: String?): Boolean = name != null && active?.crowd?.containsKey(name.lowercase()) == true

	/** a passer-by's man ('m') or woman ('f') when the name says it; null: heard from the voice */
	fun crowdGender(name: String?): Char? = name?.let { active?.crowd?.get(it.lowercase()) }

	/** «Local: text» → («أحد السكان», text): a speaker's name the game knows is not translated, it is written */
	fun label(line: String): Pair<String, String>?
	{
		val g = active ?: return null
		val (who, text) = split(ocr(line))
		return (g.names[who ?: return null] ?: return null) to text
	}

	/** a corrected line for [line] said to a man ('m') or a woman ('f'), with its speaker's name; or null */
	fun fix(line: String, to: Char?): String?
	{
		val g = active ?: return null
		val (who, text) = split(ocr(line))
		val f = g.lines[SubtitleTracker.key(text)] ?: return null
		val name = who?.let { g.names[it] ?: line.substringBefore(':').trim() }
		return (if(name != null) "$name: " else "") + f.to(to)
	}

	/** the English as it is on screen (what the reader got wrong, fixed) */
	fun ocr(text: String): String
	{
		var t = text
		for((re, to) in active?.ocr ?: return text) t = re.replace(t, to)
		return t
	}

	/** the English given to Google */
	fun english(text: String): String
	{
		var t = ocr(text)
		for((re, to) in active?.english ?: return t) t = re.replace(t, to)
		return t
	}

	/** Google's Arabic, the names spelt one way (the speakers' names too) */
	fun arabic(text: String): String
	{
		val g = active ?: return text
		var t = text
		for((re, to) in g.arabic) t = re.replace(t, to)
		return t
	}

	/** Google's Arabic for [english] (a whole line), the words it says wrong in many lines said right (each that fits) */
	fun words(english: String, arabic: String): String
	{
		val said = split(english).second.trim()
		return active?.words.orEmpty().filter { it.en.containsMatchIn(said) }.fold(arabic) { a, w -> w.ar.replaceFirst(a, w.to) }
	}

	/** [words] for lines under each other ([english] and its [arabic] line by line, when they match one to one) */
	fun wordsLines(english: String, arabic: String): String
	{
		val en = english.split('\n'); val ar = arabic.split('\n')
		return if(en.size == ar.size) en.indices.joinToString("\n") { words(en[it], ar[it]) } else words(english, arabic)
	}

	/** how the voice says it: names with their vowel marks */
	fun forVoice(text: String): String
	{
		val g = active ?: return text
		var t = text
		for((word, said) in g.voice) t = w(word).replace(t, said)
		return t
	}
}
