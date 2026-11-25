// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// A side text as OCR reads it, cleaned for translation: button icons next to prompts are read as letters
// ("SHOOT (R2)" comes out as "sh00tr2"), so the button is cut off and shown as it is, and 0 inside a word is an O.
// Pure Kotlin, unit-tested.

package com.inarabic.caption

data class SideText(val text: String, val button: String?)
{
	companion object
	{
		private val BUTTON = Regex("^(.*?)[\\s(\\[]*([RrLl][123])[)\\]]*$")
		private val LEADING_BUTTON = Regex("^[(\\[]*([RrLl][123])[)\\]]*\\s+(.+)$")

		fun clean(raw: String): SideText?
		{
			var t = raw.trim()
			var button: String? = null
			LEADING_BUTTON.find(t)?.let { m -> button = m.groupValues[1].uppercase(); t = m.groupValues[2] }
			if(button == null) BUTTON.find(t)?.let { m ->
				val before = m.groupValues[1]
				// "SHOOTR2": glued, only if what is left is a real word (2+ letters)
				if(before.count { it.isLetter() } >= 2) { button = m.groupValues[2].uppercase(); t = before }
			}
			// digits read inside words: 0 -> o (sh00t -> shoot), 1 -> l when between letters
			t = t.split(Regex("\\s+")).joinToString(" ") { w ->
				if(w.count { it.isLetter() } < 2) w
				else
				{
					val up = w.filter { it.isLetter() }.all { it.isUpperCase() }
					w.replace('0', if(up) 'O' else 'o').replace(Regex("(?<=[A-Za-z])1(?=[A-Za-z])"), if(up) "L" else "l")
				}
			}
			t = t.trim(' ', '(', ')', '[', ']', '|', '-', '.', ':')
			if(t.count { it.isLetter() } < 2) return null
			// ALL CAPS reads badly to the translator: "BACHELOR PARTY" -> "Bachelor party"
			if(t.none { it.isLowerCase() }) t = t.lowercase().replaceFirstChar { it.uppercase() }
			return SideText(t, button)
		}
	}
}
