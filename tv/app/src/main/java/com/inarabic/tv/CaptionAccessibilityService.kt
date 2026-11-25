// Second way to draw over the HDMI input without the "display over other apps" permission: an accessibility
// service may show an accessibility overlay window. It is switched on in the TV's Accessibility settings, which
// older TVs keep even when they hide the overlay-permission page and developer options.

package com.inarabic.tv

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class CaptionAccessibilityService : AccessibilityService()
{
	companion object { @Volatile var running = false; private set }

	private var view: CaptionView? = null
	private var link: Link? = null

	override fun onServiceConnected()
	{
		super.onServiceConnected()
		running = true
		val wm = getSystemService(WINDOW_SERVICE) as WindowManager
		val v = CaptionView(this)
		val lp = WindowManager.LayoutParams(
			WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
			WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
				WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
			PixelFormat.TRANSLUCENT)
		lp.gravity = Gravity.TOP or Gravity.START
		try { wm.addView(v, lp); view = v } catch(e: Exception) { return }
		v.test()
		link = Link(this, { v.handle(it) }, { }).also { it.start() }
	}

	override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
	override fun onInterrupt() {}

	override fun onDestroy()
	{
		running = false
		link?.stop()
		view?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch(e: Exception) { } }
		super.onDestroy()
	}
}
