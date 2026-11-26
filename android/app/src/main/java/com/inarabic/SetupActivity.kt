// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Step-by-step setup: the PlayStation, linking it, the TV (with a test word you confirm you can see),
// and the free translation model. Shown on first start, and from the menu any time.

package com.inarabic

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.inarabic.link.LinkServer
import com.inarabic.translate.Translator
import com.metallic.chiaki.R
import com.metallic.chiaki.common.getDatabase
import com.metallic.chiaki.regist.RegistActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity()
{
	companion object
	{
		fun done(c: Context) = c.getSharedPreferences("ia_setup", MODE_PRIVATE).getBoolean("done", false)
		private fun setDone(c: Context) = c.getSharedPreferences("ia_setup", MODE_PRIVATE).edit().putBoolean("done", true).apply()
	}

	private var step = 0
	private val steps = 5
	private lateinit var body: LinearLayout
	private lateinit var heading: TextView
	private lateinit var counter: TextView
	private lateinit var next: Button
	private lateinit var prev: Button

	private val accent = Color.rgb(159, 211, 180)
	private val text2 = Color.rgb(166, 192, 179)
	private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
	private fun round(c: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat(); if(stroke != 0) setStroke(dp(1), stroke) }

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		title = "خطوات التجهيز"
		LinkServer.start(this)
		val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16)) }
		counter = TextView(this).apply { setTextColor(accent); textSize = 13f }
		heading = TextView(this).apply { setTextColor(Color.WHITE); textSize = 24f; typeface = ResourcesCompat.getFont(this@SetupActivity, R.font.plex_bold); setPadding(0, dp(4), 0, dp(12)) }
		body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
		val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
		next = Button(this).apply { background = round(accent, 999); setTextColor(Color.rgb(15, 36, 27)); textSize = 16f; isAllCaps = false }
		prev = Button(this).apply { background = round(Color.argb(30, 255, 255, 255), 999); setTextColor(Color.WHITE); textSize = 16f; isAllCaps = false; text = "السابق" }
		nav.addView(next, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(10) })
		nav.addView(prev, LinearLayout.LayoutParams(dp(110), dp(52)))
		root.addView(counter); root.addView(heading)
		root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
		root.addView(nav)
		setContentView(root)
		next.setOnClickListener { if(step < steps - 1) { step++; show() } else { setDone(this); finish() } }
		prev.setOnClickListener { if(step > 0) { step--; show() } }
		show()
	}

	override fun onResume() { super.onResume(); if(::body.isInitialized) show() }

	private fun para(t: String, color: Int = Color.WHITE, size: Float = 16f) = TextView(this).apply {
		text = t; setTextColor(color); textSize = size; setLineSpacing(0f, 1.3f); setPadding(0, dp(6), 0, dp(6))
	}

	private fun card(vararg lines: String) = LinearLayout(this).apply {
		orientation = LinearLayout.VERTICAL; background = round(Color.rgb(23, 44, 36), 18, Color.rgb(41, 74, 60)); setPadding(dp(16), dp(12), dp(16), dp(12))
		lines.forEach { addView(para(it, Color.WHITE, 15f)) }
		layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(8) }
	}

	private fun action(t: String, onClick: () -> Unit) = Button(this).apply {
		text = t; isAllCaps = false; textSize = 15f; setTextColor(Color.WHITE); background = round(Color.argb(28, 255, 255, 255), 999, accent)
		setOnClickListener { onClick() }
		layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(8) }
	}

	private fun status(ok: Boolean, t: String) = para((if(ok) "✓  " else "•  ") + t, if(ok) Color.rgb(127, 212, 158) else Color.rgb(232, 194, 122), 15f)

	private fun show()
	{
		counter.text = "الخطوة ${step + 1} من $steps"
		next.text = if(step == steps - 1) "يلا نلعب ▶" else "التالي"
		prev.visibility = if(step == 0) View.INVISIBLE else View.VISIBLE
		body.removeAllViews()
		when(step)
		{
			0 ->
			{
				heading.text = "جهّز البلايستيشن"
				body.addView(para("خطوة وحدة في البلايستيشن، مرة وحدة بس:", text2))
				body.addView(card("1. الإعدادات ← النظام ← التشغيل عن بعد ← فعّل «تمكين التشغيل عن بعد».",
					"2. في نفس القائمة فعّل «إخفاء حالة الاتصال» عشان ما يطلع شريط فوق اللعبة.",
					"3. الإعدادات ← النظام ← توفير الطاقة ← الميزات المتاحة في وضع الراحة: فعّل «البقاء متصلاً بالإنترنت» و«تمكين تشغيل PS5 من الشبكة»."))
				body.addView(para("تبي تلعب بيدك على البلايستيشن؟", Color.WHITE, 17f))
				body.addView(card("سوّ حساب ثانوي: زر PS ← تبديل المستخدم ← إضافة مستخدم، وادخل فيه مرة وفعّل له التشغيل عن بعد.",
					"اربط الجوال بهالحساب الثانوي، وأنت العب بحسابك الأساسي واليد على البلايستيشن.",
					"أو: اربط اليد بالجوال (PS + Create لين يومض الضوء، ثم بلوتوث الجوال)."))
			}
			1 ->
			{
				heading.text = "اربط البلايستيشن"
				val st = para("…", text2)
				body.addView(st)
				lifecycleScope.launch {
					val n = try { getDatabase(this@SetupActivity).registeredHostDao().getAll().first().size } catch(e: Exception) { 0 }
					body.removeView(st)
					body.addView(status(n > 0, if(n > 0) "البلايستيشن مربوط ($n)" else "ما فيه بلايستيشن مربوط"), 0)
				}
				body.addView(card("1. اضغط «اربط» تحت.", "2. سجّل دخول حساب البلايستيشن (الثانوي إذا سويته) عشان ناخذ رقم الحساب.",
					"3. في البلايستيشن: الإعدادات ← النظام ← التشغيل عن بعد ← ربط جهاز، واكتب الرقم اللي يطلع (8 أرقام)."))
				body.addView(action("اربط البلايستيشن") {
					startActivity(Intent(this, RegistActivity::class.java).putExtra(RegistActivity.EXTRA_BROADCAST, true))
				})
			}
			2 ->
			{
				heading.text = "اربط التلفزيون"
				val n = LinkServer.screens.size
				body.addView(status(n > 0, if(n > 0) "متصل بـ $n شاشة" else "ما فيه شاشة متصلة الحين"))
				body.addView(card("1. ثبّت تطبيق «بالعربي» على التلفزيون وافتحه، يطلع لك باركود.", "2. اضغط «امسح الباركود» تحت ووجّه الكاميرا عليه.",
					"3. خلّ الجوال والتلفزيون على نفس الواي فاي."))
				body.addView(action("📷 امسح الباركود") { com.inarabic.link.Pairing.scan(this) { show() } })
				body.addView(action("أرسل «تجربة ✓» للتلفزيون") { testTv() })
			}
			3 ->
			{
				heading.text = "الترجمة"
				body.addView(para("الترجمة من Google Translate: سريعة ودقيقة وتحتاج إنترنت بس، بدون تنزيل ولا مفاتيح.", text2))
				body.addView(action("🎮 اختر لعبتك") { SessionsUi.pickGame(this, lifecycleScope, InArabicPrefs(this)) {} })
			}
			4 ->
			{
				heading.text = "جاهز تلعب بالعربي؟"
				body.addView(para("الخطوات اللي فاتت ما تحتاج تعيدها. كل مرة:", text2))
				body.addView(card("1. شغّل التلفزيون والبلايستيشن، وافتح «بالعربي» في التلفزيون.", "2. في الجوال اضغط على البلايستيشن ← «ابدأ اللعب بالعربي».",
					"3. خل الجوال مفتوح على التطبيق طول اللعب.", "4. أول مرة في لعبة جديدة: من البث اضغط «📐 مكان الترجمة» وحط الإطار على الترجمة الإنجليزية."))
			}
		}
	}

	private fun testTv()
	{
		if(LinkServer.screens.isEmpty()) { AlertDialog.Builder(this).setMessage("التلفزيون مو متصل. افتح «بالعربي» في التلفزيون وتأكد إنكم على نفس الواي فاي.").setPositiveButton("حسناً", null).show(); return }
		LinkServer.sendSub(-1, "تجربة ✓", "Test", true, floatArrayOf(0.4f, 0.45f, 0.2f, 0.08f))
		lifecycleScope.launch { delay(8000); LinkServer.sendClear() }
		AlertDialog.Builder(this).setTitle("تشوف كلمة (تجربة) على التلفزيون؟")
			.setPositiveButton("أيوه ✓") { _, _ -> step = 3; show() }
			.setNegativeButton("لا") { _, _ ->
				AlertDialog.Builder(this).setTitle("جرّب هذي:")
					.setMessage("• في تطبيق التلفزيون اضغط «شغّل الترجمة» وفعّل صلاحية العرض فوق التطبيقات.\n\n" +
						"• إذا الترجمة ما تطلع فوق اللعبة: اختر «طريقة بديلة ♿» (تسهيل الاستخدام).\n\n" +
						"• أو استخدم «العب داخل التطبيق» في التلفزيون.")
					.setPositiveButton("حسناً", null).show()
			}.show()
	}
}
