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
	private lateinit var startBtn: Ui.Tile
	private var triedPage = false

	private lateinit var statusDot: View

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		val dp = resources.displayMetrics.density
		fun px(v: Int) = (v * dp).toInt()
		val ui = Ui(this)
		// TVs crop the picture edges (overscan): keep everything inside a 5 % safe margin
		val safeX = (resources.displayMetrics.widthPixels * 0.05f).toInt()
		val safeY = (resources.displayMetrics.heightPixels * 0.05f).toInt()

		// header: the name in Kufi, a short line, what it does
		val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
		header.addView(ui.text("بالعربي", 44f, Color.WHITE, kufi = true))
		header.addView(View(this).apply {
			background = ui.round(Ui.ACCENT, px(2).toFloat())
			layoutParams = LinearLayout.LayoutParams(px(56), px(3)).apply { topMargin = px(4); bottomMargin = px(10) }
		})
		header.addView(ui.text("ترجمة ألعاب البلايستيشن إلى العربية — فوق اللعبة مباشرة", 17f, Ui.TEXT2))

		// status card
		val status = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
			background = ui.card(); setPadding(px(18), px(14), px(18), px(14))
		}
		statusDot = View(this).apply { layoutParams = LinearLayout.LayoutParams(px(12), px(12)).apply { marginEnd = px(12) } }
		statusView = ui.text("", 17f, Color.WHITE, bold = true)
		status.addView(statusDot); status.addView(statusView)

		// main actions: big tiles, the focused one lights up in the accent colour
		val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
		val play = ui.tile("🎮", "العب داخل التطبيق", "بدون أي صلاحيات") { pickHdmi(inside = true) }
		startBtn = ui.tile("▶", "", "الترجمة فوق مدخل HDMI") { toggle() }
		tiles.addView(play, LinearLayout.LayoutParams(0, px(132), 1f).apply { marginEnd = px(14) })
		tiles.addView(startBtn, LinearLayout.LayoutParams(0, px(132), 1f))

		// secondary actions
		val more = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
		val sec = listOf(
			ui.pill("سطر تجريبي") { if(OverlayService.running) OverlayService.test(this) else toggle(test = true) },
			ui.pill("مدخل البلايستيشن") { pickHdmi(inside = false) },
			ui.pill("طريقة بديلة ♿") { accessibilityWay() })
		sec.forEachIndexed { i, v -> more.addView(v, LinearLayout.LayoutParams(0, px(52), 1f).apply { if(i < sec.size - 1) marginEnd = px(10) }) }

		// manual address (optional)
		val manual = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL; background = ui.card(); setPadding(px(18), px(12), px(18), px(14))
		}
		manual.addView(ui.text("بدون باركود (اختياري): عنوان الجوال ورمز الاقتران من إعدادات الترجمة في الجوال", 14f, Ui.TEXT2))
		val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(8), 0, 0) }
		val addr = ui.field(Prefs.phoneAddress(this), "العنوان (تلقائي)", InputType.TYPE_CLASS_PHONE)
		val code = ui.field(Prefs.pairCode(this), "الرمز", InputType.TYPE_CLASS_NUMBER)
		row.addView(addr, LinearLayout.LayoutParams(0, px(48), 2f).apply { marginEnd = px(10) })
		row.addView(code, LinearLayout.LayoutParams(0, px(48), 1f).apply { marginEnd = px(10) })
		row.addView(ui.pill("احفظ") {
			Prefs.setPhoneAddress(this, addr.text.toString())
			Prefs.setPairCode(this, code.text.toString())
			if(OverlayService.running) { stopService(Intent(this, OverlayService::class.java)); startOverlay() }
		}, LinearLayout.LayoutParams(px(110), px(48)))
		manual.addView(row)

		val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, px(28), 0) }
		fun gap(v: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, px(v)) }
		col.addView(header); col.addView(gap(18)); col.addView(status); col.addView(gap(16)); col.addView(tiles)
		col.addView(gap(12)); col.addView(more); col.addView(gap(16)); col.addView(manual); col.addView(gap(10))
		col.addView(ui.text("أندرويد ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) • ${Build.MANUFACTURER} ${Build.MODEL}", 12f, Ui.TEXT3))

		// side: the QR code on a white card, and the steps
		val qrCol = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
			background = ui.card(); setPadding(px(22), px(22), px(22), px(22))
		}
		qrCol.addView(ui.text("اربط جوالك", 22f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER })
		qrCol.addView(ImageView(this).apply {
			setImageBitmap(qr(Prefs.pairUri(this@MainActivity), 480))
			background = ui.round(Color.WHITE, px(16).toFloat()); setPadding(px(10), px(10), px(10), px(10))
			layoutParams = LinearLayout.LayoutParams(px(230), px(230)).apply { topMargin = px(14); bottomMargin = px(14) }
		})
		qrCol.addView(ui.text("١  افتح «بالعربي» في الجوال\n٢  إعدادات الترجمة ← اربط شاشة بالباركود\n٣  وجّه الكاميرا إلى الباركود", 15f, Ui.TEXT2).apply { setLineSpacing(0f, 1.35f) })

		val root = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			background = ui.backdrop()
			layoutDirection = View.LAYOUT_DIRECTION_RTL
			setPadding(safeX, safeY, safeX, safeY)
		}
		root.addView(ScrollView(this).apply { addView(col); isFillViewport = true }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.7f))
		root.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; addView(qrCol, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) },
			LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
		setContentView(root)
		play.requestFocus()
		OverlayService.onStatus = { s -> runOnUiThread { showStatus(s) } }
	}

	override fun onResume()
	{
		super.onResume()
		showStatus(OverlayService.status)
	}

	private fun showStatus(s: String)
	{
		statusView.text = s
		val ok = s.contains("متصل") && !s.contains("تعذّر")
		statusDot.background = Ui(this).round(if(ok) Ui.OK else if(OverlayService.running) Ui.WARN else Ui.TEXT3, 99f)
		startBtn.set(if(OverlayService.running) "■" else "▶", if(OverlayService.running) "أوقف الترجمة" else "شغّل الترجمة")
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
		// Android 7.0 and older draw with a toast window, which needs no permission
		if(!canDraw() && Build.VERSION.SDK_INT > 24) { askOverlayPermission(test); return }
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
						.setNeutralButton("١) افتح «حول»") { _, _ -> openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS, Settings.ACTION_SETTINGS) }
						.setNegativeButton("٢) افتح «خيارات المطوّر»") { _, _ -> openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_SETTINGS) }
						.show()
					r is SelfGrant.Result.Failed -> AlertDialog.Builder(this)
						.setTitle("لم تكتمل")
						.setMessage("السبب: ${r.why}\n\nتأكد أنك وافقت على رسالة «السماح بتصحيح الأخطاء» ثم حاول مرة ثانية.")
						.setPositiveButton("حاول الآن") { _, _ -> selfGrant(test) }
						.setNegativeButton("إغلاق", null).show()
				}
			}
		}.start()
	}

	/** Accessibility overlay: no overlay permission and no developer options needed. */
	private fun accessibilityWay()
	{
		if(CaptionAccessibilityService.running)
		{
			AlertDialog.Builder(this).setMessage("الطريقة البديلة مفعّلة ✓ — افتح مدخل البلايستيشن والترجمة تظهر فوقه.").setPositiveButton("حسنًا", null).show()
			return
		}
		AlertDialog.Builder(this)
			.setTitle("التفعيل من «إمكانية الوصول»")
			.setMessage("بفتح لك صفحة «إمكانية الوصول». اختر منها «بالعربي — الترجمة فوق الشاشة» وفعّله ووافق.\n\nبعدها يظهر سطر تجريبي فوق الشاشة، وتشتغل الترجمة فوق أي مدخل بدون أي صلاحية ثانية.")
			.setPositiveButton("افتح إمكانية الوصول") { _, _ -> openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
			.setNegativeButton("إلغاء", null).show()
	}

	private fun openSettings(vararg actions: String)
	{
		for(a in actions) try { startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch(e: Exception) { }
		AlertDialog.Builder(this).setMessage("شاشتك لا تسمح بفتح هذه الصفحة من التطبيقات.").setPositiveButton("حسنًا", null).show()
	}

	/** Opens the HDMI input (the PS5) like the TV's own input menu; our window stays above it. */
	private fun pickHdmi(inside: Boolean)
	{
		val tim = getSystemService(TV_INPUT_SERVICE) as? TvInputManager
		val inputs = try { tim?.tvInputList?.filter { it.type == TvInputInfo.TYPE_HDMI || it.isPassthroughInput } } catch(e: Exception) { null } ?: emptyList()
		if(inputs.isEmpty())
		{
			AlertDialog.Builder(this).setMessage(if(inside) "الشاشة لا تعطي التطبيقات قائمة مداخل HDMI، فلا يمكن العرض داخل التطبيق. استعمل الترجمة فوق المدخل." else "لم أجد مداخل HDMI. اختر مدخل البلايستيشن من زر المداخل في الريموت؛ الترجمة تظهر فوقه.")
				.setPositiveButton("حسنًا", null).show()
			return
		}
		val names = inputs.map { (it.loadLabel(this)?.toString() ?: it.id) }.toTypedArray()
		AlertDialog.Builder(this).setTitle("اختر مدخل البلايستيشن").setItems(names) { _, i ->
			Prefs.setLastInput(this, inputs[i].id)
			if(inside)
			{
				if(OverlayService.running) stopService(Intent(this, OverlayService::class.java))
				startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INPUT, inputs[i].id))
				return@setItems
			}
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
