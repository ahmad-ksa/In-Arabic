// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Google Translate's free web service: the one translation source of the app.

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
}
