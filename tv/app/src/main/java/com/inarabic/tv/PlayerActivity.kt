// Plays the PlayStation's HDMI input INSIDE this app (TvView) and draws the Arabic subtitles on top.
// Needs no "display over other apps" permission, so it works on older TVs whose settings hide that page.

package com.inarabic.tv

import android.app.Activity
import android.graphics.Color
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class PlayerActivity : Activity()
{
	companion object { const val EXTRA_INPUT = "input" }

	private lateinit var tv: TvView
	private lateinit var caption: CaptionView
	private lateinit var status: TextView
	private var link: Link? = null
	private val main = Handler(Looper.getMainLooper())
	private val fadeStatus = Runnable { status.animate().alpha(0f).setDuration(600).start() }

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
		val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
		tv = TvView(this)
		caption = CaptionView(this)
		status = TextView(this).apply {
			setTextColor(Color.WHITE); textSize = 16f; setBackgroundColor(Color.argb(160, 0, 0, 0)); setPadding(24, 10, 24, 10)
		}
		val full = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
		root.addView(tv, full)
		root.addView(caption, full)
		root.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(30, 30, 30, 30) })
		setContentView(root)
		root.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

		val input = intent.getStringExtra(EXTRA_INPUT) ?: Prefs.lastInput(this)
		if(input == null) { show("لم يُختر مدخل البلايستيشن", true); return }
		tv.setCallback(object : TvView.TvInputCallback() {
			override fun onVideoAvailable(inputId: String) { show("الصورة جاهزة — " + (status.tag ?: ""), false) }
			override fun onVideoUnavailable(inputId: String, reason: Int)
			{
				show(when(reason)
				{
					TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING -> "جارٍ فتح المدخل…"
					TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL -> "لا توجد إشارة — تأكد أن البلايستيشن يعمل وعلى هذا المدخل"
					else -> "الصورة غير متاحة ($reason)"
				}, true)
			}
			override fun onConnectionFailed(inputId: String) { show("تعذّر فتح المدخل — جرّب مدخلًا آخر، أو استعمل طريقة العرض فوق التطبيقات", true) }
			override fun onDisconnected(inputId: String) { show("انقطع المدخل", true) }
		})
		try { tv.tune(input, TvContract.buildChannelUriForPassthroughInput(input)) }
		catch(e: Exception) { show("تعذّر فتح المدخل: ${e.message}", true) }

		link = Link(this, { caption.handle(it) }, { s -> status.tag = s; show(s, !s.contains("✓")) }).also { it.start() }
	}

	private fun show(s: String, keep: Boolean)
	{
		status.text = s
		status.alpha = 1f
		main.removeCallbacks(fadeStatus)
		if(!keep) main.postDelayed(fadeStatus, 4000)
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean
	{
		// OK: show a test line (to check size and place); back: leave
		if(keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) { caption.test(); show(status.tag?.toString() ?: "", false); return true }
		return super.onKeyDown(keyCode, event)
	}

	override fun onDestroy()
	{
		link?.stop()
		try { tv.reset() } catch(e: Exception) { }
		super.onDestroy()
	}
}
