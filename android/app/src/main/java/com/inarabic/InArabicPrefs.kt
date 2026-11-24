// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// In Arabic: settings for subtitle reading, translation and the TV link.

package com.inarabic

import android.content.Context
import androidx.preference.PreferenceManager

class InArabicPrefs(context: Context)
{
	private val p = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

	/** Translate subtitles at all. */
	val enabled get() = p.getBoolean("ia_enabled", true)

	/** Subtitle area, as a fraction of the picture height (top and bottom edges). */
	val areaTop get() = p.getInt("ia_area_top", 62) / 100f
	val areaBottom get() = p.getInt("ia_area_bottom", 97) / 100f

	/** How often the picture is read, in milliseconds. */
	val readEveryMs get() = p.getInt("ia_read_every", 300).toLong().coerceIn(150, 2000)

	/** Show the Arabic line on the phone too (over the stream). */
	val showOnPhone get() = p.getBoolean("ia_show_on_phone", true)

	/** Second, better translation by an AI model (OpenAI-compatible API). */
	val aiEnabled get() = p.getBoolean("ia_ai_enabled", false) && aiKey.isNotBlank()
	val aiBaseUrl get() = p.getString("ia_ai_base", null)?.trim()?.trimEnd('/').takeUnless { it.isNullOrEmpty() } ?: "https://api.openai.com/v1"
	val aiModel get() = p.getString("ia_ai_model", null)?.trim().takeUnless { it.isNullOrEmpty() } ?: "gpt-4o-mini"
	val aiKey get() = p.getString("ia_ai_key", "")?.trim() ?: ""

	/** Caption look on the TV. */
	val textScale get() = p.getInt("ia_text_scale", 100) / 100f
	val coverOriginal get() = p.getBoolean("ia_cover", true)
	val boxOpacity get() = p.getInt("ia_box_opacity", 85) / 100f
}
