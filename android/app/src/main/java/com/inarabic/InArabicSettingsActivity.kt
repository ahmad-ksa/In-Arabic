// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Settings for the translation and the TV screens. Look changes are sent to the TVs at once, so they can be
// tried with the "test line" button while looking at the TV.

package com.inarabic

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.inarabic.link.LinkServer
import com.inarabic.memory.SessionLog
import com.inarabic.memory.TranslationMemory
import com.inarabic.translate.Translator
import com.metallic.chiaki.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InArabicSettingsActivity : AppCompatActivity()
{
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		title = "إعدادات الترجمة"
		LinkServer.start(this)
		if(savedInstanceState == null)
			supportFragmentManager.beginTransaction().replace(android.R.id.content, Fragment()).commit()
	}

	class Fragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener
	{
		private var applying = false
		private val styleKeys = setOf("ia_text_scale", "ia_cover", "ia_box_opacity", "ia_color", "ia_position", "ia_bold", "ia_tv_zoom", "ia_tv_shift", "ia_outline", "ia_box_color")

		override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?)
		{
			setPreferencesFromResource(R.xml.inarabic_prefs, rootKey)
			val ctx = requireContext()
			val prefs = InArabicPrefs(ctx)
			TranslationMemory.load(ctx)
			findPreference<EditTextPreference>("ia_ai_key")?.apply {
				setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
				summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
					val k = p.text ?: ""
					if(k.isBlank()) "غير مضبوط" else "مضبوط (…" + k.takeLast(4) + ")"
				}
			}
			findPreference<Preference>("ia_download_model")?.setOnPreferenceClickListener {
				Toast.makeText(ctx, "جارٍ تنزيل نموذج الترجمة…", Toast.LENGTH_SHORT).show()
				lifecycleScope.launch {
					val t = Translator(prefs)
					val ok = t.prepare()
					t.close()
					Toast.makeText(ctx, if(ok) "نموذج الترجمة جاهز ✓" else "تعذّر التنزيل، تأكد من الإنترنت", Toast.LENGTH_LONG).show()
				}
				true
			}
			findPreference<Preference>("ia_address")?.summary = addressText(prefs)
			findPreference<Preference>("ia_test")?.setOnPreferenceClickListener {
				LinkServer.sendSub(-1, "هذا سطر تجريبي للترجمة العربية فوق اللعبة", "This is a test line", true, floatArrayOf(0.25f, 0.84f, 0.5f, 0.06f))
				if(prefs.box2Enabled) LinkServer.sendBox2("الهدف: اعثر على المفتاح في القبو", prefs.box2)
				lifecycleScope.launch { delay(6000); LinkServer.sendClear(); LinkServer.sendBox2Clear() }
				Toast.makeText(ctx, "أرسلت سطرًا تجريبيًا إلى الشاشات (${LinkServer.screens.size})", Toast.LENGTH_SHORT).show()
				true
			}
			findPreference<Preference>("ia_summary")?.setOnPreferenceClickListener { SessionsUi.show(ctx, lifecycleScope, prefs); true }
			findPreference<Preference>("ia_game_pick")?.apply {
				summary = prefs.gameName.ifBlank { "أي لعبة (تُعرف تلقائيًا إذا كانت شغالة)" }
				setOnPreferenceClickListener { SessionsUi.pickGame(ctx, lifecycleScope, prefs) { summary = prefs.gameName.ifBlank { "أي لعبة" } }; true }
			}
			findPreference<Preference>("ia_pair_scan")?.apply {
				summary = pairedText()
				setOnPreferenceClickListener { com.inarabic.link.Pairing.scan(requireActivity()) { summary = pairedText() }; true }
			}
			findPreference<Preference>("ia_pair_forget")?.setOnPreferenceClickListener {
				com.inarabic.link.Pairing.forgetAll(ctx)
				findPreference<Preference>("ia_pair_scan")?.summary = pairedText()
				Toast.makeText(ctx, "أُلغي ربط كل الشاشات", Toast.LENGTH_SHORT).show()
				true
			}
			findPreference<Preference>("ia_review")?.setOnPreferenceClickListener { reviewAll(prefs); true }
			findPreference<Preference>("ia_memory")?.apply {
				summary = memoryText()
				setOnPreferenceClickListener {
					AlertDialog.Builder(ctx).setMessage("مسح كل الترجمات المحفوظة (ومنها تصحيحاتك)؟")
						.setPositiveButton("امسح") { _, _ -> TranslationMemory.clear(); summary = memoryText() }
						.setNegativeButton("إلغاء", null).show()
					true
				}
			}
			LinkServer.onScreensChanged = { activity?.runOnUiThread { findPreference<Preference>("ia_address")?.summary = addressText(prefs) } }
		}

		override fun onResume()
		{
			super.onResume()
			PreferenceManager.getDefaultSharedPreferences(requireContext()).registerOnSharedPreferenceChangeListener(this)
		}

		override fun onPause()
		{
			PreferenceManager.getDefaultSharedPreferences(requireContext()).unregisterOnSharedPreferenceChangeListener(this)
			super.onPause()
		}

		override fun onDestroy()
		{
			LinkServer.onScreensChanged = null
			super.onDestroy()
		}

		override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?)
		{
			val prefs = InArabicPrefs(requireContext())
			if(key == "ia_preset" && !applying)
			{
				val name = sp?.getString("ia_preset", "normal") ?: "normal"
				if(name != "custom")
				{
					applying = true
					prefs.applyPreset(name)
					// show the new values in the list
					findPreference<androidx.preference.SeekBarPreference>("ia_text_scale")?.value = sp!!.getInt("ia_text_scale", 100)
					findPreference<androidx.preference.SeekBarPreference>("ia_box_opacity")?.value = sp.getInt("ia_box_opacity", 85)
					findPreference<androidx.preference.ListPreference>("ia_color")?.value = sp.getString("ia_color", "#FFFFFF")
					findPreference<androidx.preference.ListPreference>("ia_box_color")?.value = sp.getString("ia_box_color", "#000000")
					findPreference<androidx.preference.SwitchPreference>("ia_bold")?.isChecked = sp.getBoolean("ia_bold", true)
					findPreference<androidx.preference.SwitchPreference>("ia_outline")?.isChecked = sp.getBoolean("ia_outline", false)
					applying = false
				}
				LinkServer.sendStyle(prefs)
			}
			if(key in styleKeys)
			{
				// a change by hand: the look is now your own
				if(!applying && key != "ia_tv_zoom" && key != "ia_tv_shift") findPreference<androidx.preference.ListPreference>("ia_preset")?.let { if(it.value != "custom") { applying = true; it.value = "custom"; applying = false } }
				LinkServer.sendStyle(prefs)
			}
			if(key in styleKeys || key == "ia_preset")
				findPreference<androidx.preference.Preference>("ia_box_opacity")?.summary =
					if(prefs.lowContrast) "⚠ النص صعب يتقرا بدون خلفية. فعّل «حدّ أسود حول الحروف» أو زِد العتامة" else null
		}

		/** Corrects every line only the free model has translated, in batches, now. */
		private fun reviewAll(prefs: InArabicPrefs)
		{
			val ctx = requireContext()
			if(!prefs.aiEnabled) { Toast.makeText(ctx, "فعّل الترجمة بالذكاء الاصطناعي وضع المفتاح أولًا", Toast.LENGTH_LONG).show(); return }
			val total = TranslationMemory.count(TranslationMemory.Source.QUICK)
			if(total == 0) { Toast.makeText(ctx, "كل الترجمات المحفوظة مراجعة ✓", Toast.LENGTH_LONG).show(); return }
			val wait = AlertDialog.Builder(ctx).setMessage("جارٍ مراجعة $total سطر…").setCancelable(false).show()
			lifecycleScope.launch {
				var done = 0
				val t = Translator(prefs)
				withContext(Dispatchers.Default) {
					// bounded: a line the AI skips is not asked again and again
					repeat(total / 20 + 2)
					{
						val todo = TranslationMemory.unreviewed(20)
						if(todo.isEmpty()) return@withContext
						val fixed = t.review(todo.map { it.english to it.arabic })
						if(fixed.isEmpty()) return@withContext
						for((en, ar) in fixed) TranslationMemory.put(en, ar, TranslationMemory.Source.AI)
						done += fixed.size
						withContext(Dispatchers.Main) { wait.setMessage("جارٍ مراجعة الترجمات… $done من $total") }
					}
				}
				t.close()
				wait.dismiss()
				findPreference<Preference>("ia_memory")?.summary = memoryText()
				Toast.makeText(ctx, "روجع $done سطر ✓", Toast.LENGTH_LONG).show()
			}
		}

		private fun pairedText(): String
		{
			val s = com.inarabic.link.Pairing.screens(requireContext()).values
			return if(s.isEmpty()) "افتح تطبيق الشاشة وامسح الباركود الظاهر عليها" else "مربوطة: " + s.joinToString("، ")
		}

		private fun memoryText() =
			"${TranslationMemory.size()} سطر محفوظ (منها ${TranslationMemory.count(TranslationMemory.Source.USER)} من تصحيحاتك) — اضغط للمسح"

		private fun addressText(prefs: InArabicPrefs): String
		{
			val a = LinkServer.addresses()
			val n = LinkServer.screens.size
			val connected = if(n == 0) "لا توجد شاشة متصلة الآن" else "شاشات متصلة: $n"
			return if(a.isEmpty()) "اتصل بشبكة الواي فاي أولًا"
			else "العنوان: " + a.joinToString(" أو ") + "\nرمز الاقتران: " + prefs.pairCode + "\n" + connected
		}

		/** The story so far, from the last play session: by the AI when there is a key, else the last lines. */
		private fun summary(prefs: InArabicPrefs)
		{
			val ctx = requireContext()
			val lines = SessionLog.lastSession(ctx)
			if(lines.isEmpty()) { Toast.makeText(ctx, "لا توجد أسطر محفوظة من آخر جلسة", Toast.LENGTH_LONG).show(); return }
			val wait = AlertDialog.Builder(ctx).setMessage("جارٍ تلخيص القصة…").show()
			lifecycleScope.launch {
				val text = withContext(Dispatchers.Default) {
					val t = Translator(prefs)
					val r = t.ask(
						"أنت تلخّص قصة لعبة فيديو للاعب عربي. اكتب ملخصًا عربيًا واضحًا في 5 إلى 8 جمل لما حدث في هذه الجلسة " +
							"اعتمادًا على حوارات الشخصيات فقط، وانتهِ بجملة تبيّن أين توقف اللاعب. بلا عناوين ولا نقاط.",
						lines.takeLast(250).joinToString("\n") { it.first })
					t.close()
					r
				} ?: ("آخر ما قيل في الجلسة:\n\n" + lines.takeLast(12).joinToString("\n") { "• " + it.second })
				wait.dismiss()
				AlertDialog.Builder(ctx).setTitle("ملخص آخر جلسة")
					.setView(ScrollView(ctx).apply { addView(TextView(ctx).apply { this.text = text; textSize = 17f; setPadding(48, 32, 48, 32) }) })
					.setPositiveButton("اعرضه على الشاشة") { _, _ -> LinkServer.sendPanel("ملخص القصة", text, 45000) }
					.setNegativeButton("إغلاق", null).show()
			}
		}
	}
}
