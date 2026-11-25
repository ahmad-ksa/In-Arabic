// Keeps a transparent window on top of everything (the HDMI input of the PS5 included) and draws the
// Arabic subtitles the phone sends.

package com.inarabic.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import org.json.JSONObject

class OverlayService : Service()
{
	companion object
	{
		var status: String = "متوقف"
			private set
		var onStatus: ((String) -> Unit)? = null
		var running = false
			private set

		/** a test line, to check position and size without the phone */
		fun test(context: Context) = context.startService(Intent(context, OverlayService::class.java).setAction("test"))
	}

	private var view: CaptionView? = null
	private var link: Link? = null
	private val main = Handler(Looper.getMainLooper())
	private val hide = Runnable { view?.text = null }
	private val hidePanel = Runnable { view?.panelText = null }

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onCreate()
	{
		super.onCreate()
		running = true
		Prefs.setAutoStart(this, true)
		goForeground()
		val wm = getSystemService(WINDOW_SERVICE) as WindowManager
		val v = CaptionView(this)
		val canDraw = Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this)
		@Suppress("DEPRECATION")
		val type = when
		{
			Build.VERSION.SDK_INT >= 26 -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
			canDraw -> WindowManager.LayoutParams.TYPE_PHONE
			else -> WindowManager.LayoutParams.TYPE_TOAST // Android 7.0 and older: allowed without the permission
		}
		val lp = WindowManager.LayoutParams(
			WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
				WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
			PixelFormat.TRANSLUCENT)
		lp.gravity = Gravity.TOP or Gravity.START
		try { wm.addView(v, lp); view = v }
		catch(e: Exception) { setStatus("لا توجد صلاحية العرض فوق التطبيقات"); stopSelf(); return }
		link = Link(this, ::onMessage, ::setStatus).also { it.start() }
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
	{
		if(intent?.action == "test")
		{
			view?.test()
		}
		return START_STICKY
	}

	override fun onDestroy()
	{
		running = false
		link?.stop()
		view?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
		view = null
		setStatus("متوقف")
		super.onDestroy()
	}

	private fun onMessage(o: JSONObject) { view?.handle(o) }

	private var wasConnected = false
	private fun setStatus(s: String)
	{
		status = s; onStatus?.invoke(s)
		// a short notice on the game when the phone goes away (and when it is back)
		val now = s.contains("متصل بالجوال")
		if(wasConnected && !now && !s.startsWith("متوقف")) view?.banner("انقطع الاتصال بالجوال. افتح «بالعربي» في جوالك")
		if(!wasConnected && now && status.isNotEmpty()) view?.banner("الجوال متصل ✓")
		wasConnected = now
	}

	private fun goForeground()
	{
		val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
		if(Build.VERSION.SDK_INT >= 26)
			nm.createNotificationChannel(NotificationChannel("overlay", "الترجمة", NotificationManager.IMPORTANCE_LOW))
		val n = (if(Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "overlay") else @Suppress("DEPRECATION") Notification.Builder(this))
			.setContentTitle("بالعربي").setContentText("الترجمة العربية تعمل").setSmallIcon(android.R.drawable.ic_menu_info_details).build()
		if(Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
		else startForeground(1, n)
	}
}
