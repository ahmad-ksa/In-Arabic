// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// The diagnostic log goes by itself to one issue («سجل التشخيص») of the project on GitHub, where it can be read
// without you sending it: every 10 minutes while you play, when the stream ends, and from the settings button.
// It needs your own GitHub key (settings → متقدم), limited to this project's issues; without it nothing is sent.

package com.inarabic

import android.content.Context
import android.os.Build
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object DiagUpload
{
	private const val API = "https://api.github.com/repos/ahmad-ksa/In-Arabic"
	private const val TITLE = "سجل التشخيص"
	private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "diag-upload").also { it.isDaemon = true } }
	private var timer: ScheduledFuture<*>? = null
	/** the log lines sent so far (Diag numbers) */
	@Volatile private var sent = 0L

	/** while you play: every 10 minutes */
	@Synchronized fun start(c: Context)
	{
		val ctx = c.applicationContext
		timer?.cancel(false)
		timer = worker.scheduleWithFixedDelay({ send(ctx) }, 10, 10, TimeUnit.MINUTES)
	}

	/** the stream ended: what is left goes now */
	@Synchronized fun stop(c: Context)
	{
		timer?.cancel(false); timer = null
		val ctx = c.applicationContext
		worker.execute { send(ctx) }
	}

	/** the settings button: sent now, [done] gets a line for you (on the upload thread) */
	fun now(c: Context, done: (String) -> Unit)
	{
		val ctx = c.applicationContext
		worker.execute { done(send(ctx)) }
	}

	private fun send(c: Context): String
	{
		val sp = PreferenceManager.getDefaultSharedPreferences(c)
		val token = sp.getString("ia_diag_token", "")?.trim().orEmpty()
		if(token.isEmpty()) return "حط مفتاح قيت هب أول (متقدم ← مفتاح رفع السجل)"
		val (next, text) = Diag.since(sent)
		if(text.isBlank()) return "ما فيه شي جديد في السجل"
		return try
		{
			comment(c, token, body(c, text))
			sent = next
			"✓ ارتفع السجل"
		}
		catch(e: Exception) { Diag.add("diag upload: $e"); "ما ارتفع: ${e.message}" }
	}

	/** what is useful to know with the log: the phone, the app and the settings that change what happens */
	private fun body(c: Context, text: String): String
	{
		val p = InArabicPrefs(c)
		val version = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName } catch(e: Exception) { "?" }
		val sp = PreferenceManager.getDefaultSharedPreferences(c)
		val head = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · app $version\n" +
			"mode=${sp.getString("ia_mode", "menus")} source=${sp.getString("ia_dialog_source", "screen")} voice=${p.voiceOn} out=${p.voiceOut} " +
			"who=${p.voiceWho} dialect=${p.voiceDialect} dlook=${p.dialogLook} mlook=${p.menuLook}"
		// a comment holds 65536 characters: the newest part of a very long log
		return head + "\n```\n" + text.takeLast(60000) + "\n```"
	}

	private fun comment(c: Context, token: String, body: String)
	{
		val sp = PreferenceManager.getDefaultSharedPreferences(c)
		var issue = sp.getInt("ia_diag_issue", 0)
		if(issue == 0) issue = findOrCreate(token).also { sp.edit().putInt("ia_diag_issue", it).apply() }
		val code = call("POST", "$API/issues/$issue/comments", token, JSONObject().put("body", body)).first
		// the issue was deleted: a new one
		if(code == 404 || code == 410)
		{
			sp.edit().putInt("ia_diag_issue", 0).apply()
			val again = findOrCreate(token).also { sp.edit().putInt("ia_diag_issue", it).apply() }
			check(call("POST", "$API/issues/$again/comments", token, JSONObject().put("body", body)).first in 200..299) { "GitHub refused the comment" }
		}
		else check(code in 200..299) { "GitHub answered $code" }
	}

	private fun findOrCreate(token: String): Int
	{
		val (code, list) = call("GET", "$API/issues?state=open&per_page=100", token, null)
		if(code == 401) throw Exception("المفتاح غلط أو انتهى")
		if(code in 200..299) JSONArray(list).let { a -> for(i in 0 until a.length()) { val o = a.getJSONObject(i); if(o.optString("title") == TITLE && !o.has("pull_request")) return o.getInt("number") } }
		val (c2, made) = call("POST", "$API/issues", token, JSONObject().put("title", TITLE).put("body", "سجلات التشخيص من تطبيق الجوال (ترتفع لحالها)."))
		check(c2 in 200..299) { "ما قدرت أسوي ملاحظة السجل ($c2)" }
		return JSONObject(made).getInt("number")
	}

	private fun call(method: String, url: String, token: String, json: JSONObject?): Pair<Int, String>
	{
		val h = URL(url).openConnection() as HttpURLConnection
		try
		{
			h.requestMethod = method
			h.connectTimeout = 15000; h.readTimeout = 30000
			h.setRequestProperty("Authorization", "Bearer $token")
			h.setRequestProperty("Accept", "application/vnd.github+json")
			h.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
			if(json != null)
			{
				h.doOutput = true
				h.setRequestProperty("Content-Type", "application/json; charset=utf-8")
				h.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
			}
			val code = h.responseCode
			val text = (if(code in 200..299) h.inputStream else h.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
			return code to text
		}
		finally { h.disconnect() }
	}
}
