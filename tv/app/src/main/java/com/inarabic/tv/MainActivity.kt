// Setup screen on the TV: a QR code the phone scans to pair, the overlay permission (granted by the app
// itself through the TV's debugging service when the settings page is hidden), start/stop, test line, and a
// shortcut to the HDMI input of the PlayStation (the subtitles stay on top of it).

package com.inarabic.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : Activity()
{
	private lateinit var statusView: TextView
	private lateinit var startBtn: Button
	private var triedPage = false

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		val dp = resources.displayMetrics.density
		val pad = (24 * dp).toInt()
		fun label(t: String, size: Float, alpha: Float = 1f) = TextView(this).apply {
			text = t; textSize = size; setTextColor(Color.WHITE); this.alpha = alpha; setPadding(0, pad / 4, 0, pad / 4)
		}
		fun button(t: String, onClick: () -> Unit) = Button(this).apply {
			text = t; textSize = 18f; isAllCaps = false; setOnClickListener { onClick() }
		}

		// right: QR code to pair with the phone
		val qrCol = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(pad, pad, pad, pad)
		}
		qrCol.addView(ImageView(this).apply {
			setImageBitmap(qr(Prefs.pairUri(this@MainActivity), 480))
			layoutParams = LinearLayout.LayoutParams((260 * dp).toInt(), (260 * dp).toInt())
		})
		qrCol.addView(label("امسح الباركود من تطبيق الجوال:\nإعدادات الترجمة ← اربط شاشة بالباركود", 16f).apply { gravity = Gravity.CENTER })

		// left: status and actions
		val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
		col.addView(label("بالعربي — ترجمة الألعاب على الشاشة", 28f))
		col.addView(label("افتح تطبيق «بالعربي» في الجوال وابدأ البث من البلايستيشن، وستظهر الترجمة العربية هنا فوق اللعبة.", 16f, 0.75f))
		statusView = label("", 18f)
		col.addView(statusView)
		startBtn = button("") { toggle() }
		col.addView(startBtn)
		col.addView(button("افتح مدخل البلايستيشن (HDMI)") { pickHdmi() })
		col.addView(button("اعرض سطرًا تجريبيًا") { if(OverlayService.running) OverlayService.test(this) else toggle(test = true) })
		col.addView(label("بدون باركود (اختياري): عنوان الجوال ورمز الاقتران من إعدادات الترجمة بالجوال", 15f, 0.6f))
		val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
		val addr = EditText(this).apply {
			setText(Prefs.phoneAddress(this@MainActivity)); hint = "العنوان (تلقائي)"; textSize = 16f
			setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); inputType = InputType.TYPE_CLASS_PHONE; gravity = Gravity.CENTER
			layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
		}
		val code = EditText(this).apply {
			setText(Prefs.pairCode(this@MainActivity)); hint = "الرمز"; textSize = 16f
			setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); inputType = InputType.TYPE_CLASS_NUMBER; gravity = Gravity.CENTER
			layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
		}
		row.addView(addr); row.addView(code)
		row.addView(button("احفظ") {
			Prefs.setPhoneAddress(this, addr.text.toString())
			Prefs.setPairCode(this, code.text.toString())
			if(OverlayService.running) { stopService(Intent(this, OverlayService::class.java)); startOverlay() }
		})
		col.addView(row)

		val root = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			setBackgroundColor(Color.rgb(10, 25, 41))
			layoutDirection = View.LAYOUT_DIRECTION_RTL
			setPadding(pad, pad, pad, pad)
		}
		root.addView(ScrollView(this).apply { addView(col); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.6f) })
		root.addView(qrCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
		setContentView(root)
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

	private fun qr(text: String, size: Int): Bitmap
	{
		val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
		val px = IntArray(size * size) { i -> if(m[i % size, i / size]) Color.BLACK else Color.WHITE }
		return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
	}

	private fun canDraw() = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)

	private fun toggle(test: Boolean = false)
	{
		if(OverlayService.running) { stopService(Intent(this, OverlayService::class.java)); showStatus("متوقف"); return }
		if(!canDraw()) { askOverlayPermission(test); return }
		startOverlay()
		if(test) OverlayService.test(this)
	}

	private fun startOverlay()
	{
		val i = Intent(this, OverlayService::class.java)
		if(Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
		showStatus("يعمل")
	}

	/** The settings page if the TV has it, otherwise the app grants itself the permission. */
	private fun askOverlayPermission(test: Boolean)
	{
		// second try (the page opened but the permission is still off, e.g. an empty page): grant it ourselves
		if(!triedPage)
		{
			triedPage = true
			try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return }
			catch(e: ActivityNotFoundException) { }
			catch(e: SecurityException) { }
		}
		selfGrant(test)
	}

	private fun selfGrant(test: Boolean)
	{
		val wait = AlertDialog.Builder(this)
			.setTitle("تفعيل صلاحية العرض فوق التطبيقات")
			.setMessage("لحظة… إذا ظهرت رسالة «السماح بتصحيح الأخطاء؟» اختر «السماح دائمًا من هذا الكمبيوتر» ثم «موافق».")
			.setCancelable(false).show()
		Thread {
			val r = SelfGrant.run(this)
			runOnUiThread {
				wait.dismiss()
				when
				{
					r is SelfGrant.Result.Granted || canDraw() ->
					{
						AlertDialog.Builder(this).setMessage("تم تفعيل الصلاحية ✓").setPositiveButton("حسنًا", null).show()
						startOverlay()
						if(test) OverlayService.test(this)
					}
					r is SelfGrant.Result.NoAdb -> AlertDialog.Builder(this)
						.setTitle("خطوة واحدة مرة واحدة فقط")
						.setMessage("شاشتك تخفي صفحة هذه الصلاحية، والتطبيق يقدر يفعّلها بنفسه بعد ما تشغّل «تصحيح الأخطاء»:\n\n" +
							"١) الإعدادات ← تفضيلات الجهاز (أو النظام) ← حول ← اضغط «الإصدار» أو «رقم الإصدار» 7 مرات حتى يظهر «أنت الآن مطوّر».\n" +
							"٢) ارجع ← «خيارات المطوّر» ← فعّل «تصحيح أخطاء USB» و«تصحيح الأخطاء عبر الشبكة» إن وُجد.\n" +
							"٣) ارجع هنا واضغط «حاول الآن»، ووافق على رسالة السماح.")
						.setPositiveButton("حاول الآن") { _, _ -> selfGrant(test) }
						.setNeutralButton("افتح الإعدادات") { _, _ -> try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch(e: Exception) { } }
						.setNegativeButton("لاحقًا", null).show()
					r is SelfGrant.Result.Failed -> AlertDialog.Builder(this)
						.setTitle("لم تكتمل")
						.setMessage("السبب: ${r.why}\n\nتأكد أنك وافقت على رسالة «السماح بتصحيح الأخطاء» ثم حاول مرة ثانية.")
						.setPositiveButton("حاول الآن") { _, _ -> selfGrant(test) }
						.setNegativeButton("إغلاق", null).show()
				}
			}
		}.start()
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
