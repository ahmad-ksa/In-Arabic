// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// In Arabic: settings for subtitle reading, translation and the TV link.

package com.inarabic

import android.content.Context
import androidx.preference.PreferenceManager
import kotlin.random.Random

/** What the PS5 reported as running when the stream was started. */
object GameContext { @Volatile var running: String? = null }

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

	/** Second reader (PaddleOCR) for lines the first one is unsure about. */
	val secondReader get() = p.getBoolean("ia_paddle", true)

	/** Show the Arabic line on the phone too (over the stream). */
	val showOnPhone get() = p.getBoolean("ia_show_on_phone", true)

	/** Second box: side texts such as objectives or messages, in its own area of the picture (percent). */
	/** subtitles are centred: text off to the side of the subtitle area is treated (and drawn) apart */
	val centerOnly get() = p.getBoolean("ia_center_only", true)
	/** also read the rest of the picture for short prompts (OPEN, ENTER…) and translate them in place */
	val scanScreen get() = p.getBoolean("ia_scan_screen", true)
	/** game sound on the phone (off: the TV plays it) */
	var phoneAudio
		get() = p.getBoolean("ia_phone_audio", true)
		set(v) { p.edit().putBoolean("ia_phone_audio", v).apply() }
	/** reconnect by itself when the stream drops */
	val autoReconnect get() = p.getBoolean("ia_auto_reconnect", true)
	val box2Enabled get() = p.getBoolean("ia_box2", false)
	val box2 get() = floatArrayOf(p.getInt("ia_box2_x", 2) / 100f, p.getInt("ia_box2_y", 3) / 100f,
		p.getInt("ia_box2_w", 40) / 100f, p.getInt("ia_box2_h", 22) / 100f)

	/** Second, better translation by an AI model (OpenAI-compatible API). */
	val aiEnabled get() = p.getBoolean("ia_ai_enabled", false) && aiKey.isNotBlank()
	val aiBaseUrl get() = p.getString("ia_ai_base", null)?.trim()?.trimEnd('/').takeUnless { it.isNullOrEmpty() } ?: "https://api.openai.com/v1"
	val aiModel get() = p.getString("ia_ai_model", null)?.trim().takeUnless { it.isNullOrEmpty() } ?: "gpt-4o-mini"
	val aiKey get() = p.getString("ia_ai_key", "")?.trim() ?: ""

	/** Caption look on the TV. */
	val textScale get() = p.getInt("ia_text_scale", 100) / 100f
	val coverOriginal get() = p.getBoolean("ia_cover", true)
	val boxOpacity get() = p.getInt("ia_box_opacity", 85) / 100f
	val textColor get() = p.getString("ia_color", "#FFFFFF") ?: "#FFFFFF"
	/** auto = over the English line, bottom / top = fixed */
	val position get() = p.getString("ia_position", "auto") ?: "auto"
	/** TV picture fit: the TV may enlarge the HDMI picture (overscan) or move it */
	val tvZoom get() = p.getInt("ia_tv_zoom", 0) / 100f
	val tvShift get() = p.getInt("ia_tv_shift", 0) / 100f
	val bold get() = p.getBoolean("ia_bold", true)

	/** The game being played (filled from the PS5 when it says, or typed): helps the AI keep names right. */
	val gameName: String get() = GameContext.running?.takeIf { it.isNotBlank() } ?: (p.getString("ia_game", "")?.trim() ?: "")

	/** 4-digit code the TV apps must give to receive subtitles (made once). */
	val pairCode: String get()
	{
		val c = p.getString("ia_pair_code", null)
		if(c != null && c.length == 4) return c
		val n = String.format("%04d", Random.nextInt(10000))
		p.edit().putString("ia_pair_code", n).apply()
		return n
	}
}
