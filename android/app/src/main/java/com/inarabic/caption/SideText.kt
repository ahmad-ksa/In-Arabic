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
			// a list: every line cleaned on its own, kept under each other
			if('\n' in raw)
			{
				val parts = raw.split('\n').mapNotNull { clean(it) }
				if(parts.isEmpty()) return null
				if(parts.size == 1) return parts[0]
				return SideText(parts.joinToString("\n") { it.text + (it.button?.let { b -> "  $b" } ?: "") }, null)
			}
			var t = raw.trim()
			var button: String? = null
			// a button icon in front of a prompt ("△ OPEN") is read as one odd character: drop it
			Regex("^(\\S)\\s+(\\S.*)$").find(t)?.let { m ->
				val c = m.groupValues[1][0]
				val rest = m.groupValues[2]
				if(!c.isLetterOrDigit() || (c in "AOXD" && rest.none { it.isLowerCase() } && !rest.contains(' '))) t = rest
			}
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
			// button icons read as a lone character at either end ("Back O", "X Confirm", "Skip ©"): dropped,
			// so the same prompt is always the same text (and the same translation)
			run {
				val w = t.split(Regex("\\s+")).toMutableList()
				fun glyph(s: String) = s.trim('(', ')', '[', ']').let { it.length <= 1 && it != "I" && it.none(Char::isDigit) }
				while(w.size > 1 && glyph(w.last())) w.removeAt(w.size - 1)
				while(w.size > 1 && glyph(w.first())) w.removeAt(0)
				t = w.joinToString(" ")
			}
			if(t.count { it.isLetter() } < 2) return null
			// at least one real word (scattered single letters are paint on a car, not text)
			if(!Regex("[A-Za-z]{2,}").containsMatchIn(t)) return null
			// ALL CAPS reads badly to the translator: "BACHELOR PARTY" -> "Bachelor party"
			if(t.none { it.isLowerCase() }) t = t.lowercase().replaceFirstChar { it.uppercase() }
			return SideText(t, button)
		}
	}
}
