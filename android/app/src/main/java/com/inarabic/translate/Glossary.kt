// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Fixed translations of the words games show on screen (prompts, menus, notices). The on-device translator
// is weak on single words without a sentence ("Unlocked" can come out as "مقفلة"), so these never go to it.
// Pure Kotlin, unit-tested.

package com.inarabic.translate

object Glossary
{
	private val words: Map<String, String> = mapOf(
		// notices
		"unlocked" to "تم الفتح", "locked" to "مقفل", "new" to "جديد", "complete" to "اكتمل", "completed" to "اكتملت",
		"mission complete" to "اكتملت المهمة", "mission passed" to "نجحت المهمة", "mission failed" to "فشلت المهمة",
		"objective" to "الهدف", "new objective" to "هدف جديد", "objective updated" to "تحدّث الهدف", "objective complete" to "اكتمل الهدف",
		"quest" to "مهمة", "new quest" to "مهمة جديدة", "quest updated" to "تحدّثت المهمة", "quest complete" to "اكتملت المهمة",
		"checkpoint" to "نقطة حفظ", "checkpoint reached" to "وصلت نقطة الحفظ", "saving" to "جارٍ الحفظ", "game saved" to "تم الحفظ",
		"autosaving" to "حفظ تلقائي", "loading" to "جارٍ التحميل", "level up" to "ارتفع المستوى", "new skill" to "مهارة جديدة",
		"skill unlocked" to "انفتحت مهارة", "achievement unlocked" to "إنجاز جديد", "trophy unlocked" to "كأس جديد",
		"weapon unlocked" to "انفتح سلاح", "new weapon" to "سلاح جديد", "item acquired" to "حصلت على غرض", "item received" to "استلمت غرضاً",
		"wasted" to "متّ", "busted" to "انقبض عليك", "you died" to "متّ", "game over" to "انتهت اللعبة", "retry" to "أعد المحاولة",
		"continue" to "متابعة", "paused" to "متوقف مؤقتاً", "resume" to "استئناف", "restart" to "إعادة", "quit" to "خروج",
		"new game" to "لعبة جديدة", "load game" to "تحميل", "options" to "الخيارات", "settings" to "الإعدادات", "map" to "الخريطة",
		"inventory" to "الحقيبة", "skip" to "تخطّي", "hold to skip" to "اضغط مطولاً للتخطي", "press any button" to "اضغط أي زر",
		"wanted" to "مطلوب", "lost them" to "أفلتّ منهم", "out of ammo" to "نفدت الذخيرة", "reloading" to "إعادة تعبئة",
		"low health" to "صحتك منخفضة", "you are hidden" to "أنت مختبئ", "detected" to "انكشفت", "danger" to "خطر",
		// prompts
		// vehicle stats
		"speed" to "السرعة", "acceleration" to "التسارع", "braking" to "الكبح", "handling" to "التحكم",
		"asphalt handling" to "التحكم على الأسفلت", "off-road handling" to "التحكم على الطرق الوعرة",
		"offroad handling" to "التحكم على الطرق الوعرة", "ff-road handling" to "التحكم على الطرق الوعرة",
		"off road handling" to "التحكم على الطرق الوعرة", "strength" to "المتانة", "rating" to "التقييم",
		"top speed" to "السرعة القصوى", "traction" to "الثبات", "armor" to "الدروع", "armour" to "الدروع",
		"open" to "افتح", "close" to "أغلق", "enter" to "ادخل", "exit" to "اخرج", "leave" to "اترك", "talk" to "تكلّم",
		"interact" to "تفاعل", "use" to "استخدم", "pick up" to "التقط", "take" to "خذ", "grab" to "امسك", "drop" to "ارمِ",
		"search" to "فتّش", "loot" to "اجمع", "examine" to "افحص", "inspect" to "افحص", "read" to "اقرأ", "climb" to "تسلّق",
		"jump" to "اقفز", "crouch" to "انحنِ", "sprint" to "اركض", "run" to "اركض", "walk" to "امشِ", "aim" to "صوّب",
		"shoot" to "أطلق النار", "fire" to "أطلق النار", "attack" to "اهجم", "heavy attack" to "هجوم قوي", "light attack" to "هجوم خفيف",
		"block" to "صدّ", "dodge" to "تفادَ", "parry" to "صدّ مضاد", "reload" to "أعد التعبئة", "throw" to "ارمِ", "melee" to "اشتباك",
		"swap weapon" to "بدّل السلاح", "switch weapon" to "بدّل السلاح", "weapon wheel" to "عجلة الأسلحة", "cover" to "احتمِ",
		"take cover" to "احتمِ", "drive" to "قُد", "get in" to "اركب", "get out" to "انزل", "steal" to "اسرق", "buy" to "اشترِ",
		"sell" to "بِع", "shop" to "المتجر", "accept" to "قبول", "decline" to "رفض", "confirm" to "تأكيد", "cancel" to "إلغاء",
		"back" to "رجوع", "select" to "اختيار", "equip" to "جهّز", "unequip" to "انزع", "upgrade" to "طوّر", "craft" to "اصنع",
		"heal" to "عالج", "rest" to "استرح", "sleep" to "نَم", "wait" to "انتظر", "follow" to "اتبع", "help" to "ساعد",
		"call" to "اتصل", "answer" to "رد", "hang up" to "أنهِ المكالمة", "listen" to "استمع", "hide" to "اختبئ", "stealth" to "تسلل",
		"hold" to "اضغط مطولاً", "press" to "اضغط", "tap" to "اضغط", "toggle" to "بدّل", "zoom" to "تقريب", "scan" to "امسح",
		// map and menus
		"recenter" to "توسيط", "2d view" to "عرض ثنائي الأبعاد", "3d view" to "عرض ثلاثي الأبعاد", "add marker" to "أضف علامة",
		"remove marker" to "احذف العلامة", "set destination" to "حدد الوجهة", "set waypoint" to "حدد نقطة", "remove waypoint" to "احذف النقطة",
		"waypoint" to "نقطة الطريق", "legend" to "دليل الخريطة", "zoom in" to "تقريب", "zoom out" to "تبعيد", "brief" to "الموجز",
		"briefing" to "الموجز", "progress" to "التقدم", "stats" to "الإحصائيات", "statistics" to "الإحصائيات", "missions" to "المهام",
		"mission" to "المهمة", "contacts" to "جهات الاتصال", "messages" to "الرسائل", "gallery" to "المعرض", "help" to "مساعدة",
		"journal" to "السجل", "collectibles" to "المقتنيات", "challenges" to "التحديات", "skills" to "المهارات", "character" to "الشخصية",
		"customize" to "تخصيص", "online" to "أونلاين", "story" to "القصة", "chapter" to "الفصل", "objectives" to "الأهداف",
		"tutorials" to "الدروس", "controls" to "التحكم", "display" to "العرض", "audio" to "الصوت", "language" to "اللغة",
		"store" to "المتجر", "phone" to "الجوال", "camera" to "الكاميرا", "track" to "تتبّع", "untrack" to "إلغاء التتبع",
		"filter" to "تصفية", "details" to "التفاصيل", "info" to "معلومات", "change" to "تغيير", "view" to "عرض",
		"redraw" to "إعادة السحب", "reroll" to "إعادة الرمي", "perk" to "ميزة", "perks" to "المزايا", "choose perk" to "اختر ميزة",
		"choose a perk" to "اختر ميزة", "gear" to "معدّات", "loadout" to "العتاد", "wild card" to "بطاقة عشوائية", "wildcard" to "بطاقة عشوائية",
		"survival" to "النجاة", "chaos" to "الفوضى", "health gains" to "زيادة الصحة", "health" to "الصحة", "armor" to "الدرع",
		"ammo" to "الذخيرة", "stamina" to "التحمّل", "damage" to "الضرر", "reward" to "مكافأة", "rewards" to "المكافآت", "random" to "عشوائي",
		"bachelor party" to "حفلة توديع العزوبية", "fast travel" to "انتقال سريع", "photo mode" to "وضع التصوير"
	)

	/**
	 * Names the translator leaves in English (people, places): written in Arabic letters instead.
	 * GTA VI first; more games can be added the same way.
	 */
	private val names: Map<String, String> = linkedMapOf(
		"vice city" to "فايس سيتي", "leonida keys" to "جزر ليونيدا", "leonida" to "ليونيدا", "port gellhorn" to "بورت جيلهورن",
		"grassrivers" to "غراس ريفرز", "ambrosia" to "أمبروسيا", "mount kalaga" to "جبل كالاغا", "key lento" to "كي لينتو",
		"jason" to "جيسون", "lucia" to "لوسيا", "kenton" to "كينتون", "cal" to "كال", "boobie" to "بوبي", "raul" to "راؤول",
		"brian" to "براين", "real dimez" to "ريل دايمز", "dre'quan" to "دريكوان", "drequan" to "دريكوان",
		"ammu-nation" to "أمونيشن", "ammu-nations" to "أمونيشن", "los santos" to "لوس سانتوس", "liberty city" to "ليبرتي سيتي"
	)

	/** English names left in a translation, written in Arabic letters */
	fun arabicNames(ar: String): String
	{
		if(ar.none { it in 'A'..'Z' || it in 'a'..'z' }) return ar
		var out = ar
		for((en, a) in names)
			out = Regex("(?i)(?<![A-Za-z])" + Regex.escape(en) + "(?![A-Za-z])").replace(out, a)
		return out
	}

	/** where the names sit in an Arabic text: [start, end) pairs, longest names first, no overlaps */
	fun nameRanges(ar: String): List<IntArray>
	{
		val out = ArrayList<IntArray>()
		for(a in names.values.distinct().sortedByDescending { it.length })
		{
			var i = ar.indexOf(a)
			while(i >= 0)
			{
				val e = i + a.length
				if(out.none { i < it[1] && e > it[0] }) out += intArrayOf(i, e)
				i = ar.indexOf(a, e)
			}
		}
		return out.sortedBy { it[0] }
	}

	/** a line made only of names ("JASON & LUCIA") */
	private fun onlyNames(n: String): String?
	{
		val parts = n.split(Regex("\\s*(&|and|,)\\s*")).filter { it.isNotBlank() }
		if(parts.isEmpty() || parts.any { it !in names }) return null
		return parts.joinToString(" و") { names.getValue(it) }
	}

	private fun norm(s: String): String
	{
		val w = s.lowercase().trim { !it.isLetterOrDigit() }.split(Regex("\\s+")).toMutableList()
		// a lone character at either end is a button icon read as a letter ("back o")
		while(w.size > 1 && w.last().trim { !it.isLetterOrDigit() }.length <= 1) w.removeAt(w.size - 1)
		while(w.size > 1 && w.first().trim { !it.isLetterOrDigit() }.length <= 1) w.removeAt(0)
		return w.joinToString(" ").trim { !it.isLetterOrDigit() }
	}

	/** the fixed translation of a whole short text, or null */
	fun lookup(english: String): String?
	{
		// a count in brackets stays: "REDRAW (3)" -> "إعادة السحب (3)"
		Regex("^(.*?)\\s*\\((\\d+)\\)\\s*$").find(english.trim())?.let { m ->
			if(m.groupValues[1].isNotBlank()) return lookup(m.groupValues[1])?.let { "$it (${m.groupValues[2]})" }
		}
		val n = norm(english)
		if(n.isEmpty() || n.split(' ').size > 4) return null
		words[n]?.let { return it }
		onlyNames(n)?.let { return it }
		// "<something> unlocked" / "new <something>"
		Regex("^(.+) (unlocked|acquired|received|completed|complete)$").find(n)?.let { m ->
			val head = words[m.groupValues[1]] ?: return null
			return when(m.groupValues[2]) { "unlocked" -> "انفتح: $head"; "completed", "complete" -> "اكتمل: $head"; else -> "حصلت على: $head" }
		}
		return null
	}

	/** ALL CAPS reads badly to the on-device translator: "WHERE ARE YOU GOING" -> "Where are you going" */
	fun sentenceCase(s: String): String =
		if(s.count { it.isLetter() } >= 4 && s.none { it.isLowerCase() }) s.lowercase().replaceFirstChar { it.uppercase() } else s
}
