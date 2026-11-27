// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// When the app opens: the old system or the new one (the setting «النظام»; the last choice is marked).

package com.inarabic

import android.app.Activity
import androidx.appcompat.app.AlertDialog

object SystemChoice
{
	fun ask(a: Activity)
	{
		val p = InArabicPrefs(a)
		val names = arrayOf("القديم: قوقل المجاني وملف اللعبة", "الجديد: " + if(p.newTranslator == "google") "قوقل الرسمي" else "Haiku")
		AlertDialog.Builder(a).setTitle("أي نظام ترجمة؟")
			.setSingleChoiceItems(names, if(p.newSystem) 1 else 0) { d, i ->
				p.newSystem = i == 1
				d.dismiss()
				// the new system needs its key
				val key = if(p.newTranslator == "google") p.googleKey else p.haikuKey
				if(p.newSystem && key.isBlank())
					AlertDialog.Builder(a).setMessage("النظام الجديد يحتاج مفتاح المترجم: الإعدادات ← «النظام الجديد»")
						.setPositiveButton("تمام", null).show()
			}
			.show()
	}
}
