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

	private fun w(word: String) = Regex("(?<![\\u0600-\\u06FF])" + Regex.escape(word) + "(?![\\u0600-\\u06FF])")

	val GTA6 = Game(
		title = "Grand Theft Auto VI",
		people = mapOf(
			"lucia" to Person(true, "ar-LB-LaylaNeural"), "jason" to Person(false, "ar-SA-HamedNeural"),
			"vicki" to Person(true, "ar-KW-NouraNeural"), "dunk" to Person(false, "ar-KW-FahedNeural"),
			"frankie" to Person(false, "ar-AE-HamdanNeural"), "martin" to Person(false, "ar-QA-MoazNeural"),
			"brian" to Person(false, "ar-JO-TaimNeural"), "cal" to Person(false, "ar-BH-AliNeural"),
			"boobie" to Person(false, "ar-OM-AbdullahNeural"), "raul" to Person(false, "ar-LB-RamiNeural"),
			"layla" to Person(true, "ar-AE-FatimaNeural"), "dancer" to Person(true, "ar-QA-AmalNeural"),
			"bartender" to Person(false, "ar-IQ-BasselNeural"), "moses" to Person(false, "ar-SY-LaithNeural")),
		crowd = mapOf("local" to null, "locals" to null, "stranger" to null, "staff member" to null, "vagrants" to null,
			"cop" to null, "kolt & associates" to null, "driver" to null, "drivers" to null, "tourists" to null,
			"resident" to null, "worker" to null, "workers" to null, "stylist" to null),
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
			Regex("(?<=\\s)l(?=\\s)") to "I",
			// a letter lost at the end of a name
			Regex("\\bDr\\. Bil\\b") to "Dr. Bill"),
		english = listOf(
			// «tip out» (pay the staff their share) is «tip»: Google read it as «guide», «inform», «take out»
			Regex("\\b(tip|tipping|tipped|tips)\\s+(him|her|them|me|you|us)\\s+out\\b", RegexOption.IGNORE_CASE) to "$1 $2",
			Regex("\\b(tip|tipping|tipped|tips)\\s+out\\b", RegexOption.IGNORE_CASE) to "$1",
			// «Sup» is always «what's up» (Google: «سوب», «أتناول العشاء»)
			Regex("(?<![A-Za-z'])\\s*'?[Ss]up\\b(?!')") to " What's up"),
		arabic = listOf(
			// one spelling for each name
			w("دونك") to "دنك", w("دانك") to "دنك", w("جايسون") to "جيسون", w("المسرات") to "ديلايتس"),
		words = listOf(
			// a lone «Hey» (a greeting or a call) is never «مهلا» (wait): «Hey!» is a call, «Hey.» «Hey,» a greeting;
			// «Hey, hey, hey! Easy» and «Hey, wait» do mean «مهلا» and stay
			Word(Regex("^(?:(?:oh|uh|ah),?\\s+)?hey\\s*!(?!\\s*hey)", RegexOption.IGNORE_CASE), Regex("مهل(?:ًا|اً|ا)|يا(?=!)"), "هيه"),
			Word(Regex("^(?:(?:oh|uh|ah),?\\s+)?hey\\b(?!\\s*[,.!]?\\s*(?:hey|wait|easy|relax|hold|slow|calm|stop|whoa))", RegexOption.IGNORE_CASE),
				Regex("مهل(?:ًا|اً|ا)"), "أهلًا")),
		voice = mapOf(
			"دنك" to "دَنْك", "فيكي" to "فِيكِي", "لوسيا" to "لُوسِيَا", "جيسون" to "جَيْسُون", "فرانكي" to "فْرانْكِي",
			"مارتن" to "مارْتِن", "براين" to "بْرايِن", "ديلايتس" to "دِيلايْتْس", "جيلهورن" to "جِيلْهُورْن", "ليلى" to "لَيْلى"))

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
		return m.groupValues[1].trim().lowercase() to m.groupValues[2]
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

	/** Google's Arabic for [english] (a whole line), the words it says wrong in many lines said right */
	fun words(english: String, arabic: String): String
	{
		val said = split(english).second.trim()
		val w = active?.words?.firstOrNull { it.en.containsMatchIn(said) } ?: return arabic
		return w.ar.replaceFirst(arabic, w.to)
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
