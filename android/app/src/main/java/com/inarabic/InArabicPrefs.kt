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
	val areaLeft get() = p.getInt("ia_area_left", 0) / 100f
	val areaRight get() = p.getInt("ia_area_right", 100) / 100f
	/** one reading frame: [dialog] = the subtitle (Arabic drawn over it); otherwise side text (tasks, prompts, messages) */
	data class Zone(val r: android.graphics.RectF, val dialog: Boolean)

	/** every reading frame; the app reads only inside them */
	var zones: List<Zone>
		get()
		{
			val s = p.getString("ia_zones", null)
			if(s != null) try
			{
				val a = org.json.JSONArray(s)
				return (0 until a.length()).map { i ->
					val o = a.getJSONObject(i)
					Zone(android.graphics.RectF(o.getDouble("l").toFloat(), o.getDouble("t").toFloat(), o.getDouble("r").toFloat(), o.getDouble("b").toFloat()), o.optBoolean("dialog", true))
				}
			}
			catch(e: Exception) { }
			// before frames existed: the subtitle area, and the second box if it was on
			// and the usual place of help boxes and tasks (top left)
			val list = arrayListOf(Zone(area, true), Zone(android.graphics.RectF(0.02f, 0.03f, 0.42f, 0.32f), false))
			if(box2Enabled) box2.let { b -> list += Zone(android.graphics.RectF(b[0], b[1], b[0] + b[2], b[1] + b[3]), false) }
			return list
		}
		set(v)
		{
			val a = org.json.JSONArray()
			for(z in v) a.put(org.json.JSONObject().put("l", z.r.left.toDouble()).put("t", z.r.top.toDouble()).put("r", z.r.right.toDouble()).put("b", z.r.bottom.toDouble()).put("dialog", z.dialog))
			p.edit().putString("ia_zones", a.toString()).apply()
			v.firstOrNull { it.dialog }?.let { area = it.r }
		}

	/** where the subtitles are read, 0..1 of the picture (set by dragging the frame on the live picture) */
	var area: android.graphics.RectF
		get() = android.graphics.RectF(areaLeft, areaTop, areaRight, areaBottom)
		set(r)
		{
			p.edit().putInt("ia_area_left", (r.left * 100).toInt()).putInt("ia_area_top", (r.top * 100).toInt())
				.putInt("ia_area_right", Math.round(r.right * 100)).putInt("ia_area_bottom", Math.round(r.bottom * 100)).apply()
		}

	/** How often the picture is read, in milliseconds. */
	val readEveryMs get() = p.getInt("ia_read_every", 300).toLong().coerceIn(150, 2000)

	/** Second reader (PaddleOCR) for lines the first one is unsure about. */
	val secondReader get() = p.getBoolean("ia_paddle", true)

	/** Show the Arabic line on the phone too (over the stream). */
	val showOnPhone get() = p.getBoolean("ia_show_on_phone", true)

	/** Second box: side texts such as objectives or messages, in its own area of the picture (percent). */
	/** subtitles are centred: text off to the side of the subtitle area is treated (and drawn) apart */
	val centerOnly get() = p.getBoolean("ia_center_only", true)
	/** what to translate: "dialog" (only the dialogue), "menus" (dialogue + side frames, prompts, tasks), "all" (anything on screen) */
	var mode
		get() = p.getString("ia_mode", null) ?: when { p.getBoolean("ia_dialog_only", false) -> "dialog"; p.getBoolean("ia_scan_screen", false) -> "all"; else -> "menus" }
		set(v) { p.edit().putString("ia_mode", v).apply() }
	/** only the dialogue in the middle: no side texts, no side frames */
	var dialogOnly
		get() = mode == "dialog"
		set(v) { mode = if(v) "dialog" else "menus" }
	/** also read the rest of the picture for short prompts (OPEN, ENTER…) and translate them in place */
	val scanScreen get() = mode != "dialog"
	/** reading the whole picture keeps only game words and button prompts (no signs, no shop boards) */
	val scanOnlyPrompts get() = mode == "menus"
	/** game sound on the phone (off: the TV plays it) */
	var phoneAudio
		get() = p.getBoolean("ia_phone_audio", true)
		set(v) { p.edit().putBoolean("ia_phone_audio", v).apply() }
	/** reconnect by itself when the stream drops */
	val autoReconnect get() = p.getBoolean("ia_auto_reconnect", true)
	/** translations from the AI only: the free on-device one is used only if the AI does not answer */
	/**
	 * Where translations come from: "mlkit" (on the phone), "ai" (Gemini / an AI with a key), "mymemory",
	 * "deepl", "microsoft", "libre". Anything but "mlkit" is used alone (never mixed with the on-device one).
	 */
	val engine: String get() = p.getString("ia_engine", null) ?: if(p.getBoolean("ia_ai_enabled", false)) "ai" else "mlkit"
	val aiOnly get() = engine != "mlkit"
	val groqKey get() = p.getString("ia_groq_key", "")?.trim() ?: ""
	val groqModel get() = p.getString("ia_groq_model", null)?.trim().takeUnless { it.isNullOrEmpty() } ?: "openai/gpt-oss-120b"
	val myMemoryEmail get() = p.getString("ia_mm_email", "")?.trim() ?: ""
	val deeplKey get() = p.getString("ia_deepl_key", "")?.trim() ?: ""
	val msKey get() = p.getString("ia_ms_key", "")?.trim() ?: ""
	val msRegion get() = p.getString("ia_ms_region", "")?.trim() ?: ""
	val libreUrl get() = p.getString("ia_libre_url", "")?.trim() ?: ""
	val libreKey get() = p.getString("ia_libre_key", "")?.trim() ?: ""
	val box2Enabled get() = p.getBoolean("ia_box2", false)
	val box2 get() = floatArrayOf(p.getInt("ia_box2_x", 2) / 100f, p.getInt("ia_box2_y", 3) / 100f,
		p.getInt("ia_box2_w", 40) / 100f, p.getInt("ia_box2_h", 22) / 100f)

	/** Second, better translation by an AI model (OpenAI-compatible API). */
	val aiEnabled get() = engine == "ai" && aiKey.isNotBlank()
	val aiBaseUrl get() = p.getString("ia_ai_base", null)?.trim()?.trimEnd('/').takeUnless { it.isNullOrEmpty() } ?: "https://api.openai.com/v1"
	// "gemini-flash-latest" (set by an earlier version) is now a model with only ~20 free requests a day:
	// the Flash-Lite one has 500 and is fast enough for subtitles
	val aiModel get() = (p.getString("ia_ai_model", null)?.trim().takeUnless { it.isNullOrEmpty() } ?: "gpt-4o-mini")
		.let { if(it == "gemini-flash-latest") "gemini-3.5-flash-lite" else it }
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
	val outline get() = p.getBoolean("ia_outline", false)
	val boxColor get() = p.getString("ia_box_color", "#000000") ?: "#000000"

	/** a ready look: writes the single settings it is made of */
	fun applyPreset(name: String)
	{
		val e = p.edit()
		when(name)
		{
			"normal" -> e.putInt("ia_text_scale", 100).putString("ia_color", "#FFFFFF").putBoolean("ia_bold", true).putBoolean("ia_outline", false).putInt("ia_box_opacity", 85).putString("ia_box_color", "#000000")
			"cinema" -> e.putInt("ia_text_scale", 105).putString("ia_color", "#FFE066").putBoolean("ia_bold", true).putBoolean("ia_outline", true).putInt("ia_box_opacity", 0)
			"lounge" -> e.putInt("ia_text_scale", 135).putString("ia_color", "#FFFFFF").putBoolean("ia_bold", true).putBoolean("ia_outline", false).putInt("ia_box_opacity", 92).putString("ia_box_color", "#0F2A20")
			"clear" -> e.putInt("ia_text_scale", 100).putString("ia_color", "#FFFFFF").putBoolean("ia_bold", true).putBoolean("ia_outline", true).putInt("ia_box_opacity", 0)
			else -> {}
		}
		e.apply()
	}

	/** hard to read: no box and no outline */
	val lowContrast get() = boxOpacity < 0.3f && !outline
	val bold get() = p.getBoolean("ia_bold", true)

	/** The game being played (filled from the PS5 when it says, or typed): helps the AI keep names right. */
	/** camera mode: the TV's corners in the camera picture */
	val cameraCorners get() = p.getString("ia_cam_corners", "") ?: ""
	fun setCameraCorners(v: String) = p.edit().putString("ia_cam_corners", v).apply()
	fun setGame(name: String) = p.edit().putString("ia_game", name.trim()).apply()
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
