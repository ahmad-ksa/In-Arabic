// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// The translation services on the web: Google Translate's free service (the old system), and the new system's
// keyed ones: Google's official Cloud Translation, and Claude Haiku.

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
				lastError = when(code) { 429 -> "خلص الحد المجاني لهالمصدر"; 401, 403 -> "مفتاح المصدر غلط أو ناقص"; 400 -> "المصدر رفض الطلب (400): الرصيد خالص أو الطلب غلط"; 529 -> "المصدر مشغول (529)"; else -> "المصدر ما رد ($code)" }
				// the service's own words for it, in the log only (which limit, which setting): no guessing
				com.inarabic.Diag.add("source refused ($code) ${URL(url).host}: " + said(c))
				return null
			}
			lastError = null
			c.inputStream.bufferedReader().readText()
		}
		catch(e: java.net.SocketTimeoutException) { lastError = "المصدر تأخر وما رد (أكثر من ٨ ثواني)"; null }
		catch(e: Exception) { lastError = "ما فيه اتصال بالمصدر"; com.inarabic.Diag.add("source unreachable ${URL(url).host}: ${e.javaClass.simpleName} ${e.message.orEmpty().take(150)}"); null }
	}

	/** what a service said when it refused: its error message (Google's and Anthropic's: error.message), else the start of its answer */
	private fun said(c: HttpURLConnection): String = try
	{
		val t = c.errorStream?.bufferedReader()?.readText().orEmpty()
		(try { JSONObject(t).optJSONObject("error")?.optString("message") } catch(e: Exception) { null })?.ifBlank { null } ?: t.take(300)
	}
	catch(e: Exception) { "" }

	private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

	/** Google Translate's free web endpoint (no key; unofficial, may stop working or be limited at any time) */
	fun google(text: String): String? = try
	{
		// the plain GET form that the web uses; a browser user agent, or Google may refuse it
		val r = http("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ar&dt=t&ie=UTF-8&oe=UTF-8&q=" + enc(text),
			headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36"))
		if(r == null) null
		else
		{
			val parts = JSONArray(r).optJSONArray(0)
			parts?.let { buildString { for(i in 0 until it.length()) append(it.optJSONArray(i)?.optString(0) ?: "") }.trim().ifBlank { null } }
				.also { if(it == null) lastError = "Google ما رجّع ترجمة" }
		}
	}
	catch(e: Exception) { lastError = "Google ما رجّع ترجمة"; null }

	/**
	 * Every Arabic Google offers for a word or a short phrase: its translation, the other translations and the
	 * dictionary's words ("bag" → كيس، حقيبة، شنطة…). Used to find a coloured word inside the sentence's Arabic.
	 */
	fun googleAll(text: String): List<String> = try
	{
		val r = http("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ar&dt=t&dt=bd&dt=at&ie=UTF-8&oe=UTF-8&q=" + enc(text),
			headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36"))
		val out = LinkedHashSet<String>()
		if(r != null)
		{
			val a = JSONArray(r)
			a.optJSONArray(0)?.let { p -> buildString { for(i in 0 until p.length()) append(p.optJSONArray(i)?.optString(0) ?: "") }.trim().takeIf { it.isNotEmpty() }?.let { out += it } }
			// the dictionary: [[part of speech, [words…], …], …]
			a.optJSONArray(1)?.let { d -> for(i in 0 until d.length()) d.optJSONArray(i)?.optJSONArray(1)?.let { w -> for(j in 0 until w.length()) w.optString(j).trim().takeIf { it.isNotEmpty() }?.let { out += it } } }
			// the other translations: [[english, …, [[arabic, …], …], …], …]
			a.optJSONArray(5)?.let { al -> for(i in 0 until al.length()) al.optJSONArray(i)?.optJSONArray(2)?.let { c -> for(j in 0 until c.length()) c.optJSONArray(j)?.optString(0)?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it } } }
		}
		out.toList()
	}
	catch(e: Exception) { emptyList() }

	/** Google's official Cloud Translation (your key): each of [lines] into Arabic, in order */
	fun googleOfficial(lines: List<String>, key: String): List<String>? = try
	{
		val body = lines.joinToString("&") { "q=" + enc(it) } + "&source=en&target=ar&format=text"
		val r = http("https://translation.googleapis.com/language/translate/v2?key=" + enc(key), "POST", body, type = "application/x-www-form-urlencoded")
		r?.let { JSONObject(it).getJSONObject("data").getJSONArray("translations") }?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("translatedText") } }
			?.takeIf { it.size == lines.size }.also { if(it == null && r != null) lastError = "قوقل الرسمي ما رجّع ترجمة" }
	}
	catch(e: Exception) { lastError = "قوقل الرسمي ما رجّع ترجمة"; null }

	/** Gemini 3.8 Flash (your key from Google AI Studio): its answer to [user], told [system], thinking as little as it can (faster) */
	fun gemini(system: String, user: String, key: String): String? = try
	{
		val req = JSONObject()
			.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
			.put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
			// Gemini 3.8: no temperature (removed), and thinking set by level: "low" is its least ("minimal" is refused)
			.put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", "low")))
		val r = http("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent", "POST", req.toString(),
			mapOf("x-goog-api-key" to key))
		r?.let { JSONObject(it).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts") }
			?.let { p -> (0 until p.length()).joinToString("") { p.getJSONObject(it).optString("text") } }
			?.trim()?.ifBlank { null }.also { if(it == null && r != null) lastError = "Gemini ما رجّع ترجمة" }
	}
	catch(e: Exception) { lastError = "Gemini ما رجّع ترجمة"; null }

	/** Claude Haiku (your key): its answer to [user], told [system]; null if it failed ([lastError] says why) */
	fun haiku(system: String, user: String, key: String): String? = try
	{
		val req = JSONObject().put("model", "claude-haiku-5-5").put("max_tokens", 600).put("temperature", 0)
			.put("system", system).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
		val r = http("https://api.anthropic.com/v1/messages", "POST", req.toString(),
			mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"))
		r?.let { JSONObject(it).getJSONArray("content") }?.let { c -> (0 until c.length()).joinToString("") { c.getJSONObject(it).optString("text") } }
			?.trim()?.ifBlank { null }.also { if(it == null && r != null) lastError = "Haiku ما رجّع ترجمة" }
	}
	catch(e: Exception) { lastError = "Haiku ما رجّع ترجمة"; null }
}
