// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Xbox: Microsoft's own Remote Play page (xbox.com/play) inside the app. You sign in on Microsoft's page
// (we never see the password), pick your console, and the game picture in the page is read like the PS5 stream.
// The Xbox must have "Remote features" on: Settings ← Devices & connections ← Remote features.

package com.inarabic.xbox

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.inarabic.InArabicPrefs
import com.inarabic.caption.CaptionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class XboxActivity : AppCompatActivity()
{
	companion object
	{
		const val URL = "https://www.xbox.com/play/consoles"
		// the page wants a desktop browser for Remote Play
		const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0"
	}

	private lateinit var web: WebView
	private var engine: CaptionEngine? = null
	private val copyThread = HandlerThread("ia-xbox-copy").apply { start() }

	@SuppressLint("SetJavaScriptEnabled")
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		val dp = resources.displayMetrics.density
		val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
		web = WebView(this).apply {
			settings.javaScriptEnabled = true
			settings.domStorageEnabled = true
			settings.mediaPlaybackRequiresUserGesture = false
			settings.userAgentString = UA
			settings.useWideViewPort = true
			settings.loadWithOverviewMode = true
			settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
			webViewClient = WebViewClient()
			webChromeClient = WebChromeClient()
		}
		android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
		root.addView(web, FrameLayout.LayoutParams(-1, -1))
		val hint = TextView(this).apply {
			text = "سجّل دخولك في صفحة إكس بوكس واختر جهازك. أول ما تبدأ صورة اللعبة اضغط «ابدأ الترجمة»."
			setTextColor(Color.WHITE); textSize = 13f; setBackgroundResource(com.metallic.chiaki.R.drawable.ia_notice_bg)
			setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
		}
		val go = MaterialButton(this).apply { text = "ابدأ الترجمة"; cornerRadius = (99 * dp).toInt() }
		root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (8 * dp).toInt() })
		root.addView(go, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins((12 * dp).toInt(), 0, 0, (12 * dp).toInt()) })
		setContentView(root)
		WindowCompat.getInsetsController(window, window.decorView).apply {
			hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
		}

		go.setOnClickListener {
			if(engine == null)
			{
				engine = CaptionEngine(this, InArabicPrefs(this), { grab() }, { }).also { it.start() }
				go.text = "أوقف"; hint.visibility = View.GONE; go.alpha = 0.5f
			}
			else { engine?.stop(); engine = null; go.text = "ابدأ الترجمة"; go.alpha = 1f }
		}
		web.loadUrl(URL)
	}

	/** the page as it is on screen (the video included), at most 1280 px wide */
	private suspend fun grab(): Bitmap? = withContext(Dispatchers.Main) {
		val w = web.width; val h = web.height
		if(w < 16 || h < 16) return@withContext null
		val loc = IntArray(2); web.getLocationInWindow(loc)
		val scale = minOf(1f, 1280f / w)
		val bmp = Bitmap.createBitmap((w * scale).toInt(), (h * scale).toInt(), Bitmap.Config.ARGB_8888)
		suspendCancellableCoroutine { c ->
			try
			{
				PixelCopy.request(window, android.graphics.Rect(loc[0], loc[1], loc[0] + w, loc[1] + h), bmp, { r ->
					if(c.isActive) c.resume(if(r == PixelCopy.SUCCESS) bmp else { bmp.recycle(); null })
				}, Handler(copyThread.looper))
			}
			catch(e: Exception) { bmp.recycle(); if(c.isActive) c.resume(null) }
		}
	}

	@Deprecated("Deprecated in Java")
	override fun onBackPressed()
	{
		if(web.canGoBack()) web.goBack() else super.onBackPressed()
	}

	override fun onDestroy()
	{
		engine?.stop(); engine = null
		web.destroy()
		copyThread.quitSafely()
		super.onDestroy()
	}
}
