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
		"bachelor party" to "حفلة توديع العزوبية", "fast travel" to "انتقال سريع", "photo mode" to "وضع التصوير"
	)

	private fun norm(s: String) = s.lowercase().trim { !it.isLetterOrDigit() }.replace(Regex("\\s+"), " ")

	/** the fixed translation of a whole short text, or null */
	fun lookup(english: String): String?
	{
		val n = norm(english)
		if(n.isEmpty() || n.split(' ').size > 4) return null
		words[n]?.let { return it }
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
