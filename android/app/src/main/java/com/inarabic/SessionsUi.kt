// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Past play sessions (the dialogue of each, a normal or a short story summary) and picking the game by name.

package com.inarabic

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.inarabic.link.LinkServer
import com.inarabic.memory.SessionLog
import com.inarabic.translate.Translator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SessionsUi
{
	private fun when_(t: Long): String
	{
		val day = 24 * 3600 * 1000L
		val f = SimpleDateFormat("h:mm a", Locale("ar"))
		val now = System.currentTimeMillis()
		return when
		{
			now - t < day && Date(now).date == Date(t).date -> "اليوم " + f.format(Date(t))
			now - t < 2 * day -> "أمس " + f.format(Date(t))
			else -> SimpleDateFormat("d MMM، h:mm a", Locale("ar")).format(Date(t))
		}
	}

	fun show(ctx: Context, scope: LifecycleCoroutineScope, prefs: InArabicPrefs)
	{
		val list = SessionLog.sessions(ctx)
		if(list.isEmpty()) { Toast.makeText(ctx, "لا توجد جلسات بعد. العب شوي وارجع هنا", Toast.LENGTH_LONG).show(); return }
		val names = list.map { s ->
			val mins = ((s.lastAt - s.started) / 60000).coerceAtLeast(1)
			(if(s.game.isNotBlank()) s.game + " · " else "") + when_(s.started) + " · " + mins + " د · " + s.lines + " جملة"
		}
		AlertDialog.Builder(ctx).setTitle("سجل الجلسات")
			.setItems(names.toTypedArray()) { _, i -> session(ctx, scope, prefs, list[i]) }
			.setNegativeButton("إغلاق", null).show()
	}

	private fun session(ctx: Context, scope: LifecycleCoroutineScope, prefs: InArabicPrefs, s: SessionLog.Session)
	{
		val items = arrayOf("📖 ملخص عادي (أحداث، شخصيات، وين وقفت)", "⚡ ملخص قصير (فقرة وحدة)", "💬 الحوار كامل", "🗑 احذف الجلسة")
		AlertDialog.Builder(ctx).setTitle(if(s.game.isNotBlank()) s.game else when_(s.started))
			.setItems(items) { _, i ->
				when(i)
				{
					0 -> summary(ctx, scope, prefs, s, short = false)
					1 -> summary(ctx, scope, prefs, s, short = true)
					2 -> text(ctx, "الحوار", SessionLog.lines(s.file).joinToString("\n\n") { it.second + "\n" + it.first })
					3 -> AlertDialog.Builder(ctx).setMessage("حذف هذه الجلسة؟").setPositiveButton("احذف") { _, _ -> s.file.delete() }.setNegativeButton("إلغاء", null).show()
				}
			}.show()
	}

	private fun text(ctx: Context, title: String, body: String, onTv: Boolean = false)
	{
		val b = AlertDialog.Builder(ctx).setTitle(title)
			.setView(ScrollView(ctx).apply { addView(TextView(ctx).apply { text = body; textSize = 16f; setPadding(56, 24, 56, 24); setTextIsSelectable(true) }) })
			.setNegativeButton("إغلاق", null)
		if(onTv) b.setPositiveButton("اعرضه على الشاشة") { _, _ -> LinkServer.sendPanel(title, body, 45000) }
		b.show()
	}

	fun summary(ctx: Context, scope: LifecycleCoroutineScope, prefs: InArabicPrefs, s: SessionLog.Session, short: Boolean)
	{
		val lines = SessionLog.lines(s.file)
		if(!prefs.aiEnabled)
		{
			text(ctx, "آخر ما قيل", "الملخص يحتاج تفعيل الذكاء الاصطناعي من الإعدادات. هذي آخر الجمل:\n\n" +
				lines.takeLast(12).joinToString("\n") { "• " + it.second }, onTv = true)
			return
		}
		val wait = AlertDialog.Builder(ctx).setMessage("جارٍ إعداد الملخص…").setCancelable(false).show()
		scope.launch {
			val game = if(s.game.isNotBlank()) "اللعبة: ${s.game}. " else ""
			val system = if(short)
				"أنت تلخّص جلسة لعب للاعب عربي. ${game}اكتب فقرة عربية واحدة قصيرة (3 جمل) عن أهم ما حدث، وانتهِ بأين توقف. بلا عناوين."
			else
				"أنت تلخّص جلسة لعب للاعب عربي. ${game}اكتب بالعربية: فقرتين أو ثلاث عن أحداث القصة اعتمادًا على الحوار فقط، " +
					"ثم سطر يبدأ بـ «الشخصيات:» بأسماء الشخصيات المهمة، ثم سطر يبدأ بـ «توقفت عند:» يبيّن أين توقف اللاعب."
			val r = withContext(Dispatchers.Default) {
				val t = Translator(prefs)
				val x = t.ask(system, lines.takeLast(300).joinToString("\n") { it.first }, 45000)
				t.close(); x
			}
			wait.dismiss()
			if(r == null) Toast.makeText(ctx, "تعذّر إعداد الملخص. تأكد من الإنترنت وحاول مرة أخرى.", Toast.LENGTH_LONG).show()
			else text(ctx, if(short) "الملخص القصير" else "الملخص", r + "\n\nالملخص آلي وقد يحتوي على أخطاء.", onTv = true)
		}
	}

	// ---- the game: search by name (Steam's public store search), or type it ----

	private val popular = listOf("Grand Theft Auto V", "Grand Theft Auto VI", "Red Dead Redemption 2", "The Last of Us Part I", "The Last of Us Part II",
		"God of War", "God of War Ragnarök", "Marvel's Spider-Man 2", "Ghost of Tsushima", "Horizon Forbidden West", "Elden Ring",
		"Resident Evil 4", "Final Fantasy VII Rebirth", "Cyberpunk 2077", "Assassin's Creed Mirage", "Uncharted 4", "Death Stranding",
		"Hogwarts Legacy", "Black Myth: Wukong", "Stellar Blade", "Alan Wake 2", "Baldur's Gate 3", "Call of Duty: Black Ops 6", "Metal Gear Solid Delta")

	private fun search(term: String): List<String> = try
	{
		val c = URL("https://store.steampowered.com/api/storesearch/?l=english&cc=US&term=" + URLEncoder.encode(term, "UTF-8")).openConnection() as HttpURLConnection
		c.connectTimeout = 6000; c.readTimeout = 6000
		val o = JSONObject(c.inputStream.bufferedReader().readText())
		val a = o.optJSONArray("items")
		(0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it)?.optString("name") }.filter { it.isNotBlank() }
	}
	catch(e: Exception) { emptyList() }

	fun pickGame(ctx: Context, scope: LifecycleCoroutineScope, prefs: InArabicPrefs, done: () -> Unit)
	{
		val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 16, 40, 0) }
		val input = EditText(ctx).apply { hint = "اكتب اسم اللعبة بالإنجليزي"; setText(prefs.gameName) }
		val results = ArrayList(popular)
		val adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, results)
		val list = ListView(ctx).apply { this.adapter = adapter; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 900) }
		box.addView(input); box.addView(list)
		val dlg = AlertDialog.Builder(ctx).setTitle("اختر لعبتك").setView(box)
			.setPositiveButton("احفظ المكتوب") { _, _ -> prefs.setGame(input.text.toString()); done() }
			.setNeutralButton("أي لعبة") { _, _ -> prefs.setGame(""); done() }
			.setNegativeButton("إلغاء", null).show()
		list.setOnItemClickListener { _, _, i, _ -> prefs.setGame(results[i]); dlg.dismiss(); done() }
		var job: Job? = null
		input.addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
			override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
			override fun afterTextChanged(e: Editable?)
			{
				val term = e.toString().trim()
				job?.cancel()
				job = scope.launch {
					delay(400)
					val found = if(term.length < 2) popular else withContext(Dispatchers.IO) { search(term) }.ifEmpty { popular.filter { it.contains(term, true) } }
					results.clear(); results.addAll(found); adapter.notifyDataSetChanged()
				}
			}
		})
	}
}
