// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Other translation sources you can pick in the settings, to compare and keep the one you like.
// Each takes one English line and returns Arabic, or null (no key, limit reached, no internet).

package com.inarabic.translate

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object OnlineEngines
{
	/** why the last request failed, in Arabic */
	@Volatile var lastError: String? = null

	private fun http(url: String, method: String = "GET", body: String? = null, headers: Map<String, String> = emptyMap(), type: String = "application/json"): String?
	{
		return try
		{
			val c = URL(url).openConnection() as HttpURLConnection
			c.requestMethod = method; c.connectTimeout = 5000; c.readTimeout = 8000
			for((k, v) in headers) c.setRequestProperty(k, v)
			if(body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", type); c.outputStream.use { it.write(body.toByteArray()) } }
			val code = c.responseCode
			if(code !in 200..299)
			{
				lastError = when(code) { 429 -> "خلص الحد المجاني لهالمصدر"; 401, 403 -> "مفتاح المصدر غلط أو ناقص"; 456 -> "خلص الحد الشهري لـ DeepL"; else -> "المصدر ما رد ($code)" }
				return null
			}
			lastError = null
			c.inputStream.bufferedReader().readText()
		}
		catch(e: Exception) { lastError = "ما فيه اتصال بالمصدر"; null }
	}

	private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

	/** Google Translate's free web endpoint (no key; unofficial, may stop working or be limited at any time) */
	fun google(text: String): String?
	{
		val r = http("https://translate.googleapis.com/translate_a/single", "POST", "client=gtx&sl=en&tl=ar&dt=t&q=" + enc(text),
			type = "application/x-www-form-urlencoded") ?: return null
		val parts = JSONArray(r).optJSONArray(0) ?: return null
		return buildString { for(i in 0 until parts.length()) append(parts.optJSONArray(i)?.optString(0) ?: "") }.trim().ifBlank { null }
	}

	/** MyMemory: free, no key (a daily limit; an e-mail in the settings raises it) */
	fun myMemory(text: String, email: String): String?
	{
		val r = http("https://api.mymemory.translated.net/get?langpair=en%7Car&q=" + enc(text) + (if(email.isNotBlank()) "&de=" + enc(email) else "")) ?: return null
		val o = JSONObject(r)
		if(o.optInt("responseStatus", 200) != 200) { lastError = "MyMemory: " + o.optString("responseDetails").take(60); return null }
		return o.optJSONObject("responseData")?.optString("translatedText")?.takeIf { it.isNotBlank() }
	}

	/** DeepL API Free (key from deepl.com, 500k characters a month) */
	fun deepl(text: String, key: String): String?
	{
		if(key.isBlank()) { lastError = "ضع مفتاح DeepL في الإعدادات"; return null }
		val host = if(key.endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"
		val r = http("$host/v2/translate", "POST", "text=" + enc(text) + "&source_lang=EN&target_lang=AR",
			mapOf("Authorization" to "DeepL-Auth-Key $key"), "application/x-www-form-urlencoded") ?: return null
		return JSONObject(r).optJSONArray("translations")?.optJSONObject(0)?.optString("text")
	}

	/** Microsoft Translator (Azure free tier F0) */
	fun microsoft(text: String, key: String, region: String): String?
	{
		if(key.isBlank()) { lastError = "ضع مفتاح Microsoft في الإعدادات"; return null }
		val h = mutableMapOf("Ocp-Apim-Subscription-Key" to key)
		if(region.isNotBlank()) h["Ocp-Apim-Subscription-Region"] = region
		val r = http("https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&from=en&to=ar", "POST",
			JSONArray().put(JSONObject().put("Text", text)).toString(), h) ?: return null
		return JSONArray(r).optJSONObject(0)?.optJSONArray("translations")?.optJSONObject(0)?.optString("text")
	}

	/** LibreTranslate (your own server, or a public one with its key) */
	fun libre(text: String, url: String, key: String): String?
	{
		if(url.isBlank()) { lastError = "ضع عنوان LibreTranslate في الإعدادات"; return null }
		val b = JSONObject().put("q", text).put("source", "en").put("target", "ar").put("format", "text")
		if(key.isNotBlank()) b.put("api_key", key)
		val r = http(url.trimEnd('/') + "/translate", "POST", b.toString()) ?: return null
		return JSONObject(r).optString("translatedText").takeIf { it.isNotBlank() }
	}
}
