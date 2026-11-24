// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Settings for the translation and the TV screens.

package com.inarabic

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import android.text.InputType
import android.widget.Toast
import com.inarabic.link.LinkServer
import com.inarabic.translate.Translator
import com.metallic.chiaki.R
import kotlinx.coroutines.launch

class InArabicSettingsActivity : AppCompatActivity()
{
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		title = "إعدادات الترجمة"
		if(savedInstanceState == null)
			supportFragmentManager.beginTransaction().replace(android.R.id.content, Fragment()).commit()
	}

	class Fragment : PreferenceFragmentCompat()
	{
		override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?)
		{
			setPreferencesFromResource(R.xml.inarabic_prefs, rootKey)
			findPreference<EditTextPreference>("ia_ai_key")?.apply {
				setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
				summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
					val k = p.text ?: ""
					if(k.isBlank()) "غير مضبوط" else "مضبوط (…" + k.takeLast(4) + ")"
				}
			}
			findPreference<Preference>("ia_download_model")?.setOnPreferenceClickListener {
				Toast.makeText(requireContext(), "جارٍ تنزيل نموذج الترجمة…", Toast.LENGTH_SHORT).show()
				lifecycleScope.launch {
					val ok = Translator(InArabicPrefs(requireContext())).prepare()
					Toast.makeText(requireContext(), if(ok) "نموذج الترجمة جاهز ✓" else "تعذّر التنزيل، تأكد من الإنترنت", Toast.LENGTH_LONG).show()
				}
				true
			}
			findPreference<Preference>("ia_address")?.summary = addressText()
		}

		private fun addressText(): String
		{
			val a = LinkServer.addresses()
			return if(a.isEmpty()) "اتصل بشبكة الواي فاي أولًا"
			else "اكتب في تطبيق LG: " + a.joinToString(" أو ") + "\nشاشة أندرويد تجد الجوال تلقائيًا."
		}
	}
}
