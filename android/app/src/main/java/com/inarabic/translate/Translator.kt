// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Two-step translation: Google's free on-device English->Arabic model answers at once; then, when the
// user has set an API key, an AI model (OpenAI-compatible chat API) gives a better line that replaces it.

package com.inarabic.translate

import android.util.Log
import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.inarabic.InArabicPrefs
import com.inarabic.caption.SubtitleTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

class Translator(private val prefs: InArabicPrefs)
{
	companion object { private const val TAG = "InArabic" }

	private val local = Translation.getClient(
		TranslatorOptions.Builder()
			.setSourceLanguage(TranslateLanguage.ENGLISH)
			.setTargetLanguage(TranslateLanguage.ARABIC)
			.build())

	private val quickCache = LruCache<String, String>(600)
	private val aiCache = LruCache<String, String>(600)

	/** recent lines (English -> Arabic) given to the AI as context, so names and tone stay consistent */
	private val history = ArrayDeque<Pair<String, String>>()

	@Volatile var modelReady = false
		private set

	/** Downloads Google's English->Arabic model (about 30 MB, once). */
	suspend fun prepare(): Boolean = suspendCancellableCoroutine { c ->
		local.downloadModelIfNeeded(DownloadConditions.Builder().build())
			.addOnSuccessListener { modelReady = true; if(c.isActive) c.resume(true) }
			.addOnFailureListener { Log.w(TAG, "model download failed", it); if(c.isActive) c.resume(false) }
	}

	suspend fun quick(english: String): String?
	{
		val k = SubtitleTracker.key(english)
		quickCache.get(k)?.let { return it }
		if(!modelReady && !prepare()) return null
		val ar: String? = suspendCancellableCoroutine { c ->
			local.translate(english)
				.addOnSuccessListener { if(c.isActive) c.resume(it) }
				.addOnFailureListener { Log.w(TAG, "translate failed", it); if(c.isActive) c.resume(null) }
		}
		if(ar != null) quickCache.put(k, ar)
		return ar
	}

	/** Better translation by the AI model, or null (no key, offline, timeout). */
	suspend fun better(english: String): String?
	{
		if(!prefs.aiEnabled) return null
		val k = SubtitleTracker.key(english)
		aiCache.get(k)?.let { return it }
		val ar = withTimeoutOrNull(9000) { withContext(Dispatchers.IO) { askAi(english) } }
		if(ar != null)
		{
			aiCache.put(k, ar)
			synchronized(history) {
				history.addLast(english to ar)
				while(history.size > 6) history.removeFirst()
			}
		}
		return ar
	}

	private fun askAi(english: String): String?
	{
		val context = synchronized(history) { history.toList() }
		val system = "أنت مترجم ترجمات ألعاب فيديو من الإنجليزية إلى العربية. ترجم السطر الأخير فقط إلى عربية فصحى سهلة " +
			"وطبيعية يفهمها اللاعب، قصيرة بقدر الأصل تقريبًا. أبقِ أسماء الشخصيات والأماكن كما تُنطق بالعربية، " +
			"وصحّح أخطاء القراءة الواضحة في النص الإنجليزي. أعد الترجمة العربية فقط بلا أي شرح أو علامات تنصيص."
		val user = buildString {
			if(context.isNotEmpty())
			{
				append("السياق (أسطر سابقة):\n")
				for((en, ar) in context) append(en).append(" => ").append(ar).append('\n')
				append('\n')
			}
			append("السطر المطلوب:\n").append(english)
		}
		val body = JSONObject()
			.put("model", prefs.aiModel)
			.put("temperature", 0.2)
			.put("messages", JSONArray()
				.put(JSONObject().put("role", "system").put("content", system))
				.put(JSONObject().put("role", "user").put("content", user)))
		return try
		{
			val con = URL(prefs.aiBaseUrl + "/chat/completions").openConnection() as HttpURLConnection
			con.requestMethod = "POST"
			con.connectTimeout = 5000
			con.readTimeout = 8000
			con.doOutput = true
			con.setRequestProperty("Content-Type", "application/json")
			con.setRequestProperty("Authorization", "Bearer " + prefs.aiKey)
			con.outputStream.use { it.write(body.toString().toByteArray()) }
			if(con.responseCode !in 200..299)
			{
				Log.w(TAG, "AI ${con.responseCode}: " + (con.errorStream?.bufferedReader()?.readText()?.take(300) ?: ""))
				return null
			}
			val txt = con.inputStream.bufferedReader().readText()
			JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
				.trim().trim('"', '«', '»').ifBlank { null }
		}
		catch(e: Exception)
		{
			Log.w(TAG, "AI request failed", e)
			null
		}
	}

	fun close() = local.close()
}
