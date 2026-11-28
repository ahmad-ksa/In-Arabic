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
				// one dialogue frame only: any other one saved earlier is a menus frame now
				var seenDialog = false
				return (0 until a.length()).map { i ->
					val o = a.getJSONObject(i)
					val dialog = o.optBoolean("dialog", true) && !seenDialog
					if(dialog) seenDialog = true
					Zone(android.graphics.RectF(o.getDouble("l").toFloat(), o.getDouble("t").toFloat(), o.getDouble("r").toFloat(), o.getDouble("b").toFloat()), dialog)
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
		// three choices only; the old ones ("zones", "popups") are "menus" now
		get() = when(val m = p.getString("ia_mode", null) ?: when { p.getBoolean("ia_dialog_only", false) -> "dialog"; p.getBoolean("ia_scan_screen", false) -> "all"; else -> "menus" }) { "dialog", "all" -> m; else -> "menus" }
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
	/** «النظام الجديد» (chosen when the app opens): one translator, Haiku or Google's official service; the old
	 * one (Google's free service, the game file's corrected lines and words, the dialect's words) is left alone */
	var newSystem: Boolean
		get() = p.getString("ia_system", "old") == "new"
		set(v) { p.edit().putString("ia_system", if(v) "new" else "old").apply() }
	/** the new system's translator: "haiku", "gemini" or "google" (Google's official, keyed) */
	val newTranslator get() = p.getString("ia_new_translator", "haiku") ?: "haiku"
	val haikuKey get() = p.getString("ia_haiku_key", "")?.trim().orEmpty()
	val googleKey get() = p.getString("ia_google_key", "")?.trim().orEmpty()
	val geminiKey get() = p.getString("ia_gemini_key", "")?.trim().orEmpty()
	/** the new system's swearing: as it is said ("raw") or softened ("soft": «تبًا لك») */
	val rawSwears get() = p.getString("ia_swears", "raw") != "soft"

	/** «صوت الدبلجة»: Microsoft's voices ("edge", free, natural) or the phone's own ("phone", at once, less natural) */
	val voiceEngine get() = p.getString("ia_voice_engine", "edge") ?: "edge"

	/** the old system's translation source: Google Translate's free service */
	val engine: String get() = "google"
	val aiOnly get() = false
	val box2Enabled get() = p.getBoolean("ia_box2", false)
	val box2 get() = floatArrayOf(p.getInt("ia_box2_x", 2) / 100f, p.getInt("ia_box2_y", 3) / 100f,
		p.getInt("ia_box2_w", 40) / 100f, p.getInt("ia_box2_h", 22) / 100f)

	/** (no AI any more) */
	val aiEnabled get() = false

	// ---- the look: one choice for the dialogue, one for the menus; each choice decides everything about it, so
	// nothing stacks on anything else. The letters always keep the game's own colours.
	//   "special" (تنسيق خاص): the game's own letters and background, as in the game
	//   "box": the game's letters on a background of your colour and opacity
	//   "outline": the game's letters with a black edge, no background
	val dialogLook get() = look("ia_dialog_look")
	val dialogBg get() = bg("ia_box_color", "ia_box_opacity")
	val menuLook get() = look("ia_menu_look")
	val menuBg get() = bg("ia_menu_box_color", "ia_menu_box_opacity")
	private fun look(key: String): String = p.getString(key, null)?.takeIf { it in setOf("special", "box", "outline") }
		// before: "a black edge" with no background was its own switch
		?: if(p.getBoolean("ia_outline", false) && p.getInt("ia_box_opacity", 85) < 30) "outline" else "box"
	/** #AARRGGBB: the chosen colour at the chosen opacity */
	private fun bg(colorKey: String, opKey: String): String
	{
		val c = (p.getString(colorKey, "#000000") ?: "#000000").removePrefix("#").takeLast(6)
		val a = (p.getInt(opKey, 85).coerceIn(0, 100) * 255 / 100)
		return "#" + String.format("%02X", a) + c
	}

	val textScale get() = p.getInt("ia_text_scale", 100) / 100f
	/** where the dialogue goes: "auto" = over its English, "bottom" / "top" = fixed (before: «cover» off meant bottom) */
	val position get() = (p.getString("ia_position", "auto") ?: "auto").let { if(it == "auto" && !p.getBoolean("ia_cover", true)) "bottom" else it }
	/** TV picture fit: the TV may enlarge the HDMI picture (overscan) or move it */
	val tvZoom get() = p.getInt("ia_tv_zoom", 0) / 100f
	val tvShift get() = p.getInt("ia_tv_shift", 0) / 100f
	/** the phone's clock shown small at the top left of the TV (off until you turn it on) */
	val showClock get() = p.getBoolean("ia_clock", false)

	// ---------- dubbing (voice/Dubber.kt)
	/** where the dialogue comes from: false the subtitles on screen, true the game's speech (games without subtitles) */
	val dialogFromSound get() = p.getString("ia_dialog_source", "screen") == "sound"
	/** each dialogue line is spoken in Arabic too */
	val voiceOn get() = p.getBoolean("ia_voice", false)
	/** where it is heard: "tv" (the screens; the phone when none is linked), "phone", "both" */
	val voiceOut get() = p.getString("ia_voice_out", "tv") ?: "tv"
	/** how the dubbing talks (voice/Dialect.kt) and whose voices come first: fusha, sa, gulf, eg, sham */
	val voiceDialect get() = p.getString("ia_voice_dialect", "sa") ?: "sa"
	/** how fast the dubbing talks, % of normal (the voices' own speed, not a speeded-up recording) */
	val voiceRate get() = p.getInt("ia_voice_rate", 100).coerceIn(50, 200)
	/** «ضبط السرعة تلقائي»: each line as fast as makes it last as long as the English one ([voiceRate] not used) */
	val voiceAutoRate get() = p.getBoolean("ia_voice_auto_rate", false)
	/** the game's sound on the phone: off while the dubbing comes from the phone («الجوال للدبلجة، التلفزيون لصوت
	 * اللعبة»), else the switch «صوت اللعبة» */
	val gameSoundOnPhone get() = !(voiceOn && voiceOut == "phone") && phoneAudio
	/** "auto": a voice for each character (a man's or a woman's); "male" / "female": one voice for everyone */
	val voiceWho get() = p.getString("ia_voice_who", "auto") ?: "auto"
	/** the menus' own size (1 = the English size) and height, apart from the dialogue's */
	val menuScale get() = p.getInt("ia_menu_scale", 100) / 100f
	val menuShift get() = p.getInt("ia_menu_shift", 0) / 100f
	fun setMenuLook(scalePct: Int, shiftPct: Int) = p.edit().putInt("ia_menu_scale", scalePct).putInt("ia_menu_shift", shiftPct).apply()

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
