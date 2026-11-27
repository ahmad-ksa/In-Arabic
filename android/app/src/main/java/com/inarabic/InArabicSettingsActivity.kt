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
		private val styleKeys = setOf("ia_text_scale", "ia_position", "ia_tv_zoom", "ia_tv_shift", "ia_menu_scale", "ia_menu_shift",
			"ia_dialog_look", "ia_box_color", "ia_box_opacity", "ia_menu_look", "ia_menu_box_color", "ia_menu_box_opacity")

		/** each look shows only its own settings: a background's colour and opacity only for «خلفية» */
		private fun showLookSettings()
		{
			val p = InArabicPrefs(requireContext())
			val d = p.dialogLook == "box"; val m = p.menuLook == "box"
			findPreference<androidx.preference.Preference>("ia_box_color")?.isVisible = d
			findPreference<androidx.preference.Preference>("ia_box_opacity")?.isVisible = d
			findPreference<androidx.preference.Preference>("ia_menu_box_color")?.isVisible = m
			findPreference<androidx.preference.Preference>("ia_menu_box_opacity")?.isVisible = m
			// the list shows the chosen look even for a setting saved the old way
			findPreference<androidx.preference.ListPreference>("ia_dialog_look")?.let { if(it.value != p.dialogLook) it.value = p.dialogLook }
			findPreference<androidx.preference.ListPreference>("ia_menu_look")?.let { if(it.value != p.menuLook) it.value = p.menuLook }
		}

		/** the voice settings show only when the voice is on; «الشخصيات» only when each character has its own */
		private fun showVoiceSettings()
		{
			val p = InArabicPrefs(requireContext())
			for(k in listOf("ia_voice_out", "ia_voice_auto_rate", "ia_voice_who", "ia_voice_dialect")) findPreference<Preference>(k)?.isVisible = p.voiceOn
			// one speed control at a time: yours, or the automatic one
			findPreference<Preference>("ia_voice_rate")?.isVisible = p.voiceOn && !p.voiceAutoRate
			findPreference<Preference>("ia_voice_people")?.apply {
				isVisible = p.voiceOn && p.voiceWho == "auto"
				val n = com.inarabic.voice.Voices.people(requireContext()).size
				summary = if(n == 0) "تنضاف لحالها أول ما تتكلم (إذا اللعبة تكتب اسم المتكلم)" else "$n شخصية — اضغط لتغيير صوت أي وحدة"
			}
		}

		/** the characters heard so far: tap one to hear and choose its voice, or remove it */
		private fun peopleDialog()
		{
			val ctx = requireContext()
			val people = com.inarabic.voice.Voices.people(ctx)
			if(people.isEmpty()) { Toast.makeText(ctx, "ما فيه شخصيات للحين: العب والصوت شغال وتنضاف لحالها", Toast.LENGTH_LONG).show(); return }
			val rows = people.map { pp -> pp.name + " — " + (com.inarabic.voice.Voices.byId(pp.voice)?.label ?: "؟") + (if(pp.fixed) " ✓" else "") }
			AlertDialog.Builder(ctx).setTitle("الشخصيات وأصواتها")
				.setItems(rows.toTypedArray()) { _, i -> voiceDialog(people[i]) }
				.setNegativeButton("إغلاق", null).show()
		}

		private fun voiceDialog(person: com.inarabic.voice.Voices.Person)
		{
			val ctx = requireContext()
			val all = com.inarabic.voice.Voices.all
			var chosen = all.indexOfFirst { it.id == person.voice }
			AlertDialog.Builder(ctx).setTitle("صوت " + person.name + " (اضغط تسمعه)")
				.setSingleChoiceItems(all.map { it.label }.toTypedArray(), chosen) { _, i -> chosen = i; com.inarabic.voice.Dubber.preview(ctx, all[i]) }
				.setPositiveButton("احفظ") { _, _ -> if(chosen >= 0) com.inarabic.voice.Voices.set(ctx, person.key, person.name, all[chosen].id); showVoiceSettings() }
				.setNeutralButton("احذف الشخصية") { _, _ -> com.inarabic.voice.Voices.remove(ctx, person.key); showVoiceSettings() }
				.setNegativeButton("إلغاء", null).show()
		}

		override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?)
		{
			setPreferencesFromResource(R.xml.inarabic_prefs, rootKey)
			val ctx = requireContext()
			val prefs = InArabicPrefs(ctx)
			showLookSettings()
			showVoiceSettings()
			findPreference<Preference>("ia_voice_people")?.setOnPreferenceClickListener { peopleDialog(); true }
			TranslationMemory.load(ctx)
			findPreference<Preference>("ia_address")?.summary = addressText(prefs)
			findPreference<Preference>("ia_test")?.setOnPreferenceClickListener {
				LinkServer.sendSub(-1, "هذا سطر تجريبي للترجمة العربية فوق اللعبة", "This is a test line", true, floatArrayOf(0.25f, 0.84f, 0.5f, 0.06f))
				if(prefs.box2Enabled) LinkServer.sendBox2("الهدف: اعثر على المفتاح في القبو", prefs.box2)
				lifecycleScope.launch { delay(6000); LinkServer.sendClear(); LinkServer.sendBox2Clear() }
				Toast.makeText(ctx, "أرسلت سطرًا تجريبيًا إلى الشاشات (${LinkServer.screens.size})", Toast.LENGTH_SHORT).show()
				true
			}
			findPreference<Preference>("ia_diag")?.setOnPreferenceClickListener {
				val t = Diag.text().ifBlank { "السجل فاضي: العب شوي والترجمة شغالة، وارجع هنا" }
				startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
					.setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, t), "شارك سجل التشخيص"))
				true
			}
			findPreference<Preference>("ia_diag_upload")?.setOnPreferenceClickListener {
				Toast.makeText(ctx, "أرفع السجل…", Toast.LENGTH_SHORT).show()
				DiagUpload.now(ctx) { msg -> activity?.runOnUiThread { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() } }
				true
			}
			// the key is never shown: only whether one is set
			// the new system's keys: hidden like the log's
			for((k, none) in listOf("ia_haiku_key" to "ما فيه مفتاح: Haiku ما يشتغل", "ia_google_key" to "ما فيه مفتاح: قوقل الرسمي ما يشتغل"))
				findPreference<EditTextPreference>(k)?.apply {
					setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
					summaryProvider = Preference.SummaryProvider<EditTextPreference> { p -> if(p.text.isNullOrBlank()) none else "✓ فيه مفتاح" }
				}
			findPreference<EditTextPreference>("ia_diag_token")?.apply {
				setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
				summaryProvider = Preference.SummaryProvider<EditTextPreference> { p -> if(p.text.isNullOrBlank()) "ما فيه مفتاح: السجل ما يرتفع" else "✓ فيه مفتاح، السجل يرتفع لحاله" }
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
			if(key == "ia_clock") com.inarabic.link.LinkServer.sendClock() // shown or hidden on the TV at once
			if(key in styleKeys) LinkServer.sendStyle(prefs)
			if(key == "ia_dialog_look" || key == "ia_menu_look") showLookSettings()
			if(key == "ia_voice" || key == "ia_voice_who" || key == "ia_voice_auto_rate") showVoiceSettings()
			// «صوت اللعبة»: Android's speech recognition needs the microphone permission (the mic itself stays off)
			if(key == "ia_dialog_source" && prefs.dialogFromSound &&
				requireContext().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
				requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 7)
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
