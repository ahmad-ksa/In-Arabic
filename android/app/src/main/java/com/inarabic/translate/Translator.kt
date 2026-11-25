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

	suspend fun quick(english0: String): String?
	{
		// a list: each line on its own, kept under each other
		if('\n' in english0) return english0.split('\n').filter { it.isNotBlank() }.map { quick(it) ?: it }.joinToString("\n")
		Glossary.lookup(english0)?.let { return it }
		val english = Glossary.sentenceCase(english0)
		val k = SubtitleTracker.key(english)
		quickCache.get(k)?.let { return it }
		if(!modelReady && !prepare()) return null
		val ar: String? = suspendCancellableCoroutine { c ->
			local.translate(english)
				.addOnSuccessListener { if(c.isActive) c.resume(it) }
				.addOnFailureListener { Log.w(TAG, "translate failed", it); if(c.isActive) c.resume(null) }
		}
		val named = ar?.let { Glossary.arabicNames(it) }
		if(named != null) quickCache.put(k, named)
		return named
	}

	/** Better translation by the AI model, or null (no key, offline, timeout). */
	suspend fun better(english: String): String?
	{
		if(!prefs.aiEnabled) return null
		if('\n' in english)
		{
			// a list: each line by the AI; if one line fails the whole list waits (never mixed with the on-device one)
			val parts = english.split('\n').filter { it.isNotBlank() }.map { Glossary.lookup(it) ?: better(it) ?: return null }
			return parts.joinToString("\n")
		}
		val k = SubtitleTracker.key(english)
		aiCache.get(k)?.let { return it }
		val ar0 = withTimeoutOrNull(9000) { withContext(Dispatchers.IO) { askAi(english) } }
		val ar = ar0?.let { Glossary.arabicNames(it) }
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

	/**
	 * Review: the AI re-translates lines that only the free model has done, several at once (cheaper), fixing
	 * reading mistakes and keeping names consistent. Returns English -> better Arabic.
	 */
	suspend fun review(lines: List<Pair<String, String>>): Map<String, String>
	{
		if(!prefs.aiEnabled || lines.isEmpty()) return emptyMap()
		val system = "أنت مراجع ترجمات ألعاب فيديو. ستصلك أسطر إنجليزية مرقمة مع ترجمة آلية أولية. صحّح أخطاء القراءة الواضحة " +
			"في الإنجليزي، ثم أعد ترجمة كل سطر إلى عربية فصحى سهلة وطبيعية بأسلوب ترجمات الألعاب، مع توحيد أسماء الشخصيات والأماكن. " +
			"ترجمة أمينة للمعنى والنبرة: إذا فيه شتائم أو كلام بذيء أو عنف ترجمه كما هو بمقابله العربي بدون تلطيف أو حذف أو رقابة (ترجمة لعبة للكبار، استخدام شخصي). " +
			"أعد JSON فقط بالشكل {\"1\":\"الترجمة\",\"2\":\"…\"} بلا أي نص آخر."
		val user = buildString {
			if(prefs.gameName.isNotBlank()) append("اللعبة: ").append(prefs.gameName).append("\n\n")
			lines.forEachIndexed { i, (en, ar) -> append(i + 1).append(") ").append(en).append("\n   الأولية: ").append(ar).append('\n') }
		}
		val raw = ask(system, user, 40000) ?: return emptyMap()
		return try
		{
			val json = JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
			buildMap {
				lines.forEachIndexed { i, (en, _) ->
					json.optString((i + 1).toString()).trim().takeIf { it.isNotEmpty() }?.let { put(en, it) }
				}
			}
		}
		catch(e: Exception) { Log.w(TAG, "review parse", e); emptyMap() }
	}

	/** One question to the AI model (used for the story summary too). */
	suspend fun ask(system: String, user: String, timeoutMs: Long = 30000): String? =
		if(prefs.aiKey.isBlank()) null
		else withTimeoutOrNull(timeoutMs) { withContext(Dispatchers.IO) { chat(system, user, (timeoutMs - 1000).toInt()) } }

	private fun askAi(english: String): String?
	{
		val context = synchronized(history) { history.toList() }
		val system = "أنت مترجم ترجمات ألعاب فيديو من الإنجليزية إلى العربية. ترجم السطر الأخير فقط إلى عربية فصحى سهلة " +
			"وطبيعية يفهمها اللاعب، قصيرة بقدر الأصل تقريبًا. أبقِ أسماء الشخصيات والأماكن كما تُنطق بالعربية، " +
			"وصحّح أخطاء القراءة الواضحة في النص الإنجليزي. " + "ترجمة أمينة للمعنى والنبرة: إذا فيه شتائم أو كلام بذيء أو عنف ترجمه كما هو بمقابله العربي بدون تلطيف أو حذف أو رقابة (ترجمة لعبة للكبار، استخدام شخصي). " +
			"أعد الترجمة العربية فقط بلا أي شرح أو علامات تنصيص."
		val user = buildString {
			if(prefs.gameName.isNotBlank()) append("اللعبة: ").append(prefs.gameName).append("\n\n")
			if(context.isNotEmpty())
			{
				append("السياق (أسطر سابقة):\n")
				for((en, ar) in context) append(en).append(" => ").append(ar).append('\n')
				append('\n')
			}
			append("السطر المطلوب:\n").append(english)
		}
		return chat(system, user, 8000)
	}

	/** why the last AI request failed, in Arabic (null: it worked) */
	@Volatile var lastAiError: String? = null
		private set
	@Volatile private var noReasoningParam = false

	private fun chat(system: String, user: String, readTimeoutMs: Int): String?
	{
		val body = JSONObject()
			.put("model", prefs.aiModel)
			.put("temperature", 0.2)
		// Gemini "thinks" before answering unless told not to: that is most of the wait for a short line
		val gemini = prefs.aiBaseUrl.contains("generativelanguage")
		if(gemini && !noReasoningParam) body.put("reasoning_effort", "none")
			.put("messages", JSONArray()
				.put(JSONObject().put("role", "system").put("content", system))
				.put(JSONObject().put("role", "user").put("content", user)))
		return try
		{
			val con = URL(prefs.aiBaseUrl + "/chat/completions").openConnection() as HttpURLConnection
			con.requestMethod = "POST"
			con.connectTimeout = 5000
			con.readTimeout = readTimeoutMs
			con.doOutput = true
			con.setRequestProperty("Content-Type", "application/json")
			con.setRequestProperty("Authorization", "Bearer " + prefs.aiKey)
			con.outputStream.use { it.write(body.toString().toByteArray()) }
			if(con.responseCode !in 200..299)
			{
				val err = con.errorStream?.bufferedReader()?.readText()?.take(300) ?: ""
				Log.w(TAG, "AI ${con.responseCode}: $err")
				// a model that cannot switch thinking off: ask again without that setting, and stop sending it
				if(con.responseCode == 400 && gemini && !noReasoningParam && err.contains("reasoning", true))
				{
					noReasoningParam = true
					return chat(system, user, readTimeoutMs)
				}
				lastAiError = when(con.responseCode)
				{
					429 -> "خلص حد الذكاء الاصطناعي لليوم"
					401, 403 -> "مفتاح الذكاء الاصطناعي غلط أو موقوف"
					404 -> "اسم النموذج غلط في إعدادات الذكاء الاصطناعي"
					else -> "الذكاء الاصطناعي ما رد (${con.responseCode})"
				}
				return null
			}
			val txt = con.inputStream.bufferedReader().readText()
			lastAiError = null
			JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
				.trim().trim('"', '«', '»').ifBlank { null }
		}
		catch(e: Exception)
		{
			Log.w(TAG, "AI request failed", e)
			lastAiError = "الذكاء الاصطناعي ما رد. تأكد من الإنترنت"
			null
		}
	}

	fun close() = local.close()
}
