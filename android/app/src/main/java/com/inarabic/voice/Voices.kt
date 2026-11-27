// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// The Arabic voices (Microsoft's, one man and one woman for each country), and the characters of the game with
// the voice each one has. A new character gets a voice of its own, a man's or a woman's by how high its voice is
// in the game (VoicePitch); you can change it in the settings, and then it stays as you set it.

package com.inarabic.voice

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONObject

object Voices
{
	class V(val id: String, val male: Boolean, val region: String)
	{
		val label get() = (if(male) "رجل " else "امرأة ") + (REGIONS[region] ?: region) + (if(male) "" else "ة")
	}

	/** the countries, in the order voices are given out after the chosen accent */
	private val REGIONS = linkedMapOf("SA" to "سعودي", "KW" to "كويتي", "AE" to "إماراتي", "QA" to "قطري", "BH" to "بحريني",
		"OM" to "عماني", "YE" to "يمني", "IQ" to "عراقي", "JO" to "أردني", "LB" to "لبناني", "SY" to "سوري", "EG" to "مصري",
		"LY" to "ليبي", "TN" to "تونسي", "DZ" to "جزائري", "MA" to "مغربي")
	private val NAMES = mapOf("SA" to ("Hamed" to "Zariyah"), "KW" to ("Fahed" to "Noura"), "AE" to ("Hamdan" to "Fatima"),
		"QA" to ("Moaz" to "Amal"), "BH" to ("Ali" to "Laila"), "OM" to ("Abdullah" to "Aysha"), "YE" to ("Saleh" to "Maryam"),
		"IQ" to ("Bassel" to "Rana"), "JO" to ("Taim" to "Sana"), "LB" to ("Rami" to "Layla"), "SY" to ("Laith" to "Amany"),
		"EG" to ("Shakir" to "Salma"), "LY" to ("Omar" to "Iman"), "TN" to ("Hedi" to "Reem"), "DZ" to ("Ismael" to "Amina"),
		"MA" to ("Jamal" to "Mouna"))
	/** the setting «لهجة الكلام»: the countries whose voices come first (standard Arabic: the Saudi ones) */
	private val ACCENTS = mapOf("fusha" to listOf("SA"), "sa" to listOf("SA"), "gulf" to listOf("KW", "AE", "QA", "BH", "OM"), "eg" to listOf("EG"),
		"sham" to listOf("JO", "LB", "SY"))

	val all: List<V> = REGIONS.keys.flatMap { r -> NAMES[r]!!.let { (m, f) -> listOf(V("ar-$r-${m}Neural", true, r), V("ar-$r-${f}Neural", false, r)) } }

	fun byId(id: String?) = all.firstOrNull { it.id == id }

	/** the voices of one kind, the chosen accent first */
	fun order(accent: String, male: Boolean): List<V>
	{
		val first = ACCENTS[accent] ?: ACCENTS["sa"]!!
		val regions = first + REGIONS.keys.filter { it !in first }
		return regions.map { r -> all.first { it.region == r && it.male == male } }
	}

	// ---------- the characters

	class Person(val key: String, val name: String, val voice: String, val fixed: Boolean, val m: Int, val f: Int)

	private const val KEY = "ia_cast"
	/** after this many lines heard, a character's man/woman is settled */
	private const val VOTES = 5

	private fun load(c: Context): JSONObject = try { JSONObject(PreferenceManager.getDefaultSharedPreferences(c).getString(KEY, "{}") ?: "{}") } catch(e: Exception) { JSONObject() }
	private fun save(c: Context, o: JSONObject) = PreferenceManager.getDefaultSharedPreferences(c).edit().putString(KEY, o.toString()).apply()

	@Synchronized fun people(c: Context): List<Person>
	{
		val o = load(c)
		return o.keys().asSequence().mapNotNull { k -> o.optJSONObject(k)?.let { Person(k, it.optString("name", k), it.optString("voice"), it.optBoolean("fixed"), it.optInt("m"), it.optInt("f")) } }
			.sortedBy { it.name.lowercase() }.toList()
	}

	/** you chose [voice] for this character: it stays */
	@Synchronized fun set(c: Context, key: String, voice: String)
	{
		val o = load(c)
		val p = o.optJSONObject(key) ?: return
		p.put("voice", voice).put("fixed", true)
		save(c, o)
	}

	/** this character's man/woman is known for good (you chose it, or enough of its lines were heard) */
	@Synchronized fun settled(c: Context, name: String): Boolean
	{
		val p = load(c).optJSONObject(name.lowercase()) ?: return false
		return p.optBoolean("fixed") || p.optInt("m") + p.optInt("f") >= VOTES
	}

	/** you chose this character's voice in the settings */
	@Synchronized fun fixed(c: Context, name: String): Boolean = load(c).optJSONObject(name.lowercase())?.optBoolean("fixed") == true

	@Synchronized fun remove(c: Context, key: String) { val o = load(c); o.remove(key); save(c, o) }

	/**
	 * The voice of the character [name], who just spoke with a voice heard as [heard] ('m', 'f' or null). A new
	 * character gets a voice no one else has; until [VOTES] lines are heard its man/woman can still change.
	 */
	@Synchronized fun voiceOf(c: Context, name: String, heard: Char?, accent: String): V
	{
		val o = load(c)
		val key = name.lowercase()
		val p = o.optJSONObject(key) ?: JSONObject().put("name", name)
		var m = p.optInt("m"); var f = p.optInt("f")
		if(!p.optBoolean("fixed") && m + f < VOTES) { if(heard == 'm') m++ else if(heard == 'f') f++ }
		p.put("m", m).put("f", f)
		val male = if(p.optBoolean("fixed")) byId(p.optString("voice"))?.male ?: true else m >= f
		var v = byId(p.optString("voice"))
		if(v == null || (!p.optBoolean("fixed") && v.male != male))
		{
			// the voices already given to the others
			val used = HashMap<String, Int>()
			for(k in o.keys()) if(k != key) o.optJSONObject(k)?.optString("voice")?.let { used[it] = (used[it] ?: 0) + 1 }
			val list = order(accent, male)
			v = list.firstOrNull { it.id !in used } ?: list.minByOrNull { used[it.id] ?: 0 }!!
			p.put("voice", v.id)
		}
		o.put(key, p)
		save(c, o)
		return v
	}
}
