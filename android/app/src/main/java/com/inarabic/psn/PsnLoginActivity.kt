// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Sign in with PlayStation to get the account ID that PS5 Remote Play registration needs (the same flow as
// chiaki-ng's desktop app, psnaccountid.cpp, based on grill2010's script): Sony's login page in a WebView ->
// redirect with ?code= -> token -> user_id -> 8 bytes little-endian -> Base64.

package com.inarabic.psn

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class PsnLoginActivity : AppCompatActivity()
{
	companion object
	{
		const val EXTRA_ACCOUNT_ID = "psn_account_id"
		private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
		private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
		private const val REDIRECT = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
		private const val SCOPE = "psn:clientapp referenceDataService:countryConfig.read pushNotification:webSocket.desktop.connect sessionManager:remotePlaySession.system.update"
		private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
		private val LOGIN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize?service_entity=urn:service-entity:psn" +
			"&response_type=code&client_id=$CLIENT_ID&redirect_uri=$REDIRECT&scope=" + URLEncoder.encode(SCOPE, "UTF-8") +
			"&request_locale=en_US&ui=pr&service_logo=ps&layout_type=popup&smcid=remoteplay&prompt=always&PlatformPrivacyWs1=minimal&"
	}

	private var done = false

	@SuppressLint("SetJavaScriptEnabled")
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		title = "تسجيل الدخول إلى PlayStation"
		CookieManager.getInstance().removeAllCookies(null)
		val web = WebView(this)
		setContentView(web)
		web.settings.javaScriptEnabled = true
		web.settings.domStorageEnabled = true
		web.webViewClient = object : WebViewClient() {
			override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = check(request.url)
			override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { check(Uri.parse(url)) }
		}
		web.loadUrl(LOGIN_URL)
	}

	private fun check(uri: Uri): Boolean
	{
		if(!uri.toString().startsWith(REDIRECT)) return false
		if(done) return true
		done = true
		val code = uri.getQueryParameter("code")
		if(code == null)
		{
			fail("لم يكتمل تسجيل الدخول")
			return true
		}
		lifecycleScope.launch {
			val id = withContext(Dispatchers.IO) { runCatching { accountId(code) }.getOrNull() }
			if(id == null) fail("تعذّر جلب رقم الحساب، حاول مرة أخرى")
			else
			{
				setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_ACCOUNT_ID, id))
				finish()
			}
		}
		return true
	}

	private fun fail(msg: String)
	{
		Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
		setResult(Activity.RESULT_CANCELED)
		finish()
	}

	private fun accountId(code: String): String
	{
		val auth = "Basic " + Base64.encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(), Base64.NO_WRAP)
		val body = "grant_type=authorization_code&code=" + URLEncoder.encode(code, "UTF-8") +
			"&scope=" + URLEncoder.encode(SCOPE, "UTF-8") + "&redirect_uri=" + URLEncoder.encode(REDIRECT, "UTF-8")
		val t = URL(TOKEN_URL).openConnection() as HttpURLConnection
		t.requestMethod = "POST"
		t.doOutput = true
		t.setRequestProperty("Authorization", auth)
		t.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
		t.outputStream.use { it.write(body.toByteArray()) }
		val token = JSONObject(t.inputStream.bufferedReader().readText()).getString("access_token")
		val u = URL("$TOKEN_URL/$token").openConnection() as HttpURLConnection
		u.setRequestProperty("Authorization", auth)
		val userId = JSONObject(u.inputStream.bufferedReader().readText()).getString("user_id").toLong()
		val bytes = ByteArray(8) { i -> (userId ushr (8 * i)).toByte() } // little-endian
		return Base64.encodeToString(bytes, Base64.NO_WRAP)
	}
}
