// Setup screen on the TV: overlay permission, start/stop, test line, phone address, and a shortcut to the
// HDMI input of the PlayStation (the subtitles stay on top of it).

package com.inarabic.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity()
{
	private lateinit var statusView: TextView
	private lateinit var startBtn: Button

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		val pad = (32 * resources.displayMetrics.density).toInt()
		val col = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(pad * 2, pad, pad * 2, pad)
			setBackgroundColor(Color.rgb(10, 25, 41))
			layoutDirection = View.LAYOUT_DIRECTION_RTL
		}
		fun label(t: String, size: Float, alpha: Float = 1f) = TextView(this).apply {
			text = t; textSize = size; setTextColor(Color.WHITE); this.alpha = alpha; setPadding(0, pad / 4, 0, pad / 4)
		}
		fun button(t: String, onClick: () -> Unit) = Button(this).apply {
			text = t; textSize = 20f; isAllCaps = false; setOnClickListener { onClick() }
		}
		col.addView(label("بالعربي — ترجمة الألعاب على الشاشة", 30f))
		col.addView(label("افتح تطبيق «بالعربي» في الجوال وابدأ البث من البلايستيشن، وستظهر الترجمة العربية هنا فوق اللعبة.", 18f, 0.75f))
		statusView = label("", 20f)
		col.addView(statusView)
		startBtn = button("") { toggle() }
		col.addView(startBtn)
		col.addView(button("افتح مدخل البلايستيشن (HDMI)") { pickHdmi() })
		col.addView(button("اعرض سطرًا تجريبيًا") { if(OverlayService.running) OverlayService.test(this) else toggle(test = true) })
		col.addView(label("عنوان الجوال (اتركه فارغًا ليُكتشف تلقائيًا):", 18f, 0.75f))
		val addr = EditText(this).apply {
			setText(Prefs.phoneAddress(this@MainActivity)); hint = "مثال: 192.168.1.20"; textSize = 20f
			setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); inputType = InputType.TYPE_CLASS_PHONE; gravity = Gravity.CENTER
		}
		col.addView(addr)
		col.addView(label("رمز الاقتران (يظهر في إعدادات الترجمة بالجوال):", 18f, 0.75f))
		val code = EditText(this).apply {
			setText(Prefs.pairCode(this@MainActivity)); hint = "4 أرقام"; textSize = 24f
			setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); inputType = InputType.TYPE_CLASS_NUMBER; gravity = Gravity.CENTER
		}
		col.addView(code)
		col.addView(button("احفظ") {
			Prefs.setPhoneAddress(this, addr.text.toString())
			Prefs.setPairCode(this, code.text.toString())
			if(OverlayService.running) { stopService(Intent(this, OverlayService::class.java)); startOverlay() }
		})
		setContentView(ScrollView(this).apply { addView(col) })
		OverlayService.onStatus = { s -> runOnUiThread { showStatus(s) } }
	}

	override fun onResume()
	{
		super.onResume()
		showStatus(OverlayService.status)
	}

	private fun showStatus(s: String)
	{
		statusView.text = "الحالة: $s"
		startBtn.text = if(OverlayService.running) "■ أوقف الترجمة" else "▶ شغّل الترجمة"
	}

	private fun canDraw() = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)

	private fun toggle(test: Boolean = false)
	{
		if(OverlayService.running) { stopService(Intent(this, OverlayService::class.java)); showStatus("متوقف"); return }
		if(!canDraw()) { askOverlayPermission(); return }
		startOverlay()
		if(test) OverlayService.test(this)
	}

	private fun startOverlay()
	{
		val i = Intent(this, OverlayService::class.java)
		if(Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
		showStatus("يعمل")
	}

	private fun askOverlayPermission()
	{
		try
		{
			startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
		}
		catch(e: Exception)
		{
			// many Android TVs hide this screen: it is granted once from a computer instead
			AlertDialog.Builder(this)
				.setTitle("صلاحية العرض فوق التطبيقات")
				.setMessage("شاشتك لا تعرض صفحة هذه الصلاحية. فعّلها مرة واحدة من الكمبيوتر بعد تشغيل «تصحيح أخطاء USB/الشبكة» في خيارات المطوّر:\n\n" +
					"adb connect <عنوان الشاشة>\nadb shell appops set $packageName SYSTEM_ALERT_WINDOW allow")
				.setPositiveButton("حسنًا", null).show()
		}
	}

	/** Opens the HDMI input (the PS5) like the TV's own input menu; our window stays above it. */
	private fun pickHdmi()
	{
		val tim = getSystemService(TV_INPUT_SERVICE) as? TvInputManager
		val inputs = try { tim?.tvInputList?.filter { it.type == TvInputInfo.TYPE_HDMI } } catch(e: Exception) { null } ?: emptyList()
		if(inputs.isEmpty())
		{
			AlertDialog.Builder(this).setMessage("لم أجد مداخل HDMI. اختر مدخل البلايستيشن من زر المداخل في الريموت؛ الترجمة تظهر فوقه.")
				.setPositiveButton("حسنًا", null).show()
			return
		}
		val names = inputs.map { (it.loadLabel(this)?.toString() ?: it.id) }.toTypedArray()
		AlertDialog.Builder(this).setTitle("اختر مدخل البلايستيشن").setItems(names) { _, i ->
			if(!OverlayService.running && canDraw()) startOverlay()
			try
			{
				startActivity(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputs[i].id)))
			}
			catch(e: Exception)
			{
				AlertDialog.Builder(this).setMessage("تعذّر فتح المدخل مباشرة. افتحه من زر المداخل في الريموت.").setPositiveButton("حسنًا", null).show()
			}
		}.show()
	}
}
