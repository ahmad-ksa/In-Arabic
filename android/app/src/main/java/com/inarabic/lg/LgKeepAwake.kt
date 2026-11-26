// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Keeps an LG TV awake while you play.
//
// You play with the controller, so the TV sees no remote presses: after a while it puts up its screen saver
// or turns the screen off ("press any button to turn on the screen"). An app on the TV cannot stop that
// (webOS only spares full-screen video, and the HDMI picture inside our app does not count).
//
// So the phone acts as a remote, the way LG's own phone remote does: it talks to the TV's network remote
// service (wss://TV:3001, or ws://TV:3000 on older TVs), asks once for permission (the TV shows "allow this
// device?"), and then every minute presses a key nobody uses (BLUE). If the TV still reports
// "Screen Saver" / "Screen Off", it turns the screen straight back on.
//
// The same link also connects the TV app to the phone by itself (linkScreens): the phone finds the LG TVs on the
// network (SSDP, the way LG's own remote app does), and opens «بالعربي» on them with the phone's address, so the
// TV never has to look for the phone (its address may have changed since last time).
//
// The pairing manifest (assets/lg-remote-pairing.json) is the public one from the lgtv2 library (MIT).

package com.inarabic.lg

import android.content.Context
import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.X509TrustManager

object LgKeepAwake
{
	private const val TAG = "InArabicLgAwake"
	private const val PRESS_EVERY_S = 60L
	private const val KEY = "BLUE" // our TV page ignores it; the system leaves colour keys to the app on screen

	// the certificates LG TVs present on port 3001 (same list as lgtv2): only these are accepted
	private val LG_CERTS = setOf(
		"E2BD6464D3F51C1B95B7697D9D6773C33D9412EBA0299C568C34937D3FE68AA0", // LGE SSG Intermediate CA
		"11C5B1C5907750ABB9DA2A6665CCCE2BB288A583F45A3339E71F87BF2F808552"  // LGE TV SSG
	)

	private val timer = Executors.newSingleThreadScheduledExecutor()
	private val tvs = mutableMapOf<String, Tv>()
	private var appContext: Context? = null

	/** an LG screen with our app has connected from this address */
	@Synchronized fun start(context: Context, ip: String)
	{
		appContext = context.applicationContext
		if(ip.isBlank() || ip == "?" || tvs.containsKey(ip)) return
		prefs().edit().putString("last_ip", ip).apply()
		val tv = Tv(ip)
		tvs[ip] = tv
		tv.connect()
	}

	/**
	 * A press right now ("start playing in Arabic" was tapped): wakes a TV in its screen saver at once.
	 * To every LG TV kept awake; none yet: the last one we knew is reached and pressed as soon as it answers.
	 */
	@Synchronized fun pressNow(context: Context)
	{
		appContext = context.applicationContext
		if(tvs.isNotEmpty()) { for(tv in tvs.values) timer.execute { tv.press() }; return }
		prefs().getString("last_ip", null)?.let { start(context, it) } // the pointer socket presses once it opens
	}

	/**
	 * Opens «بالعربي» on the LG TVs with this phone's address (the QR was scanned, "start playing" was tapped, or the
	 * app was opened). [foregroundOnly]: only if our app is already on the TV screen (opening the phone app must not
	 * take over a TV that is showing something else). [hint]: a TV address already known (from its QR).
	 */
	fun linkScreens(context: Context, foregroundOnly: Boolean, hint: String? = null)
	{
		appContext = context.applicationContext
		if(com.inarabic.link.LinkServer.hasLgScreen()) return
		Thread {
			val ips = LinkedHashSet<String>()
			hint?.takeIf { it.isNotBlank() }?.let { ips += it }
			prefs().getString("last_ip", null)?.let { ips += it }
			ips += findTvs(appContext!!)
			Log.i(TAG, "link: TVs $ips")
			synchronized(this) {
				for(ip in ips)
				{
					// a TV that refused earlier (or gave up) is asked again: this time you asked for it
					val tv = tvs[ip]?.takeIf { !it.closed } ?: Tv(ip).also { tvs[ip] = it; it.connect() }
					tv.wantLaunch(foregroundOnly)
				}
			}
		}.start()
	}

	/** LG TVs on the home network: they answer an SSDP search for their second-screen service */
	private fun findTvs(context: Context): List<String>
	{
		val out = LinkedHashSet<String>()
		val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
		val lock = try { wifi.createMulticastLock("inarabic-ssdp").apply { setReferenceCounted(false); acquire() } } catch(e: Exception) { null }
		try
		{
			java.net.DatagramSocket().use { s ->
				s.soTimeout = 600
				val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\n" +
					"ST: urn:lge-com:service:webos-second-screen:1\r\n\r\n").toByteArray()
				val group = java.net.InetAddress.getByName("239.255.255.250")
				repeat(2) { s.send(java.net.DatagramPacket(msg, msg.size, group, 1900)) }
				val end = System.currentTimeMillis() + 2500
				val buf = ByteArray(2048)
				while(System.currentTimeMillis() < end)
				{
					try
					{
						val pk = java.net.DatagramPacket(buf, buf.size)
						s.receive(pk)
						val txt = String(pk.data, 0, pk.length)
						if(txt.contains("webos", true) || txt.contains("lge", true)) pk.address?.hostAddress?.let { out += it }
					}
					catch(e: java.net.SocketTimeoutException) { }
				}
			}
		}
		catch(e: Exception) { Log.w(TAG, "ssdp ${e.message}") }
		finally { try { lock?.release() } catch(e: Exception) { } }
		return out.toList()
	}

	/** this phone's address on the TV's network */
	private fun phoneIpFor(tvIp: String): String?
	{
		val all = com.inarabic.link.LinkServer.addresses()
		val net = tvIp.substringBeforeLast('.')
		return all.firstOrNull { it.substringBeforeLast('.') == net } ?: all.firstOrNull()
	}

	/** no LG screen at this address any more */
	@Synchronized fun stop(ip: String)
	{
		tvs.remove(ip)?.close()
	}

	private fun prefs() = appContext!!.getSharedPreferences("ia_lg_remote", Context.MODE_PRIVATE)

	private class Tv(val ip: String)
	{
		@Volatile var closed = false
		var main: WebSocketClient? = null
		var pointer: WebSocketClient? = null
		var task: ScheduledFuture<*>? = null
		var retry: ScheduledFuture<*>? = null
		var secure = true
		var unsigned = false
		var nextId = 0
		@Volatile var registered = false
		/** open our app on the TV once registered: 0 no, 1 only if it is already on screen, 2 yes */
		@Volatile var launchWanted = 0

		fun wantLaunch(foregroundOnly: Boolean)
		{
			launchWanted = maxOf(launchWanted, if(foregroundOnly) 1 else 2)
			if(registered) main?.let { doLaunch(it) }
		}

		fun doLaunch(c: WebSocketClient)
		{
			val w = launchWanted; launchWanted = 0
			if(w == 0 || com.inarabic.link.LinkServer.hasLgScreen()) return
			if(w == 1) send(c, "request", "fg", "ssap://com.webos.applicationManager/getForegroundAppInfo") else launch(c)
		}

		/** opens «بالعربي» on the TV (or wakes it if it is open) with the phone's address */
		fun launch(c: WebSocketClient)
		{
			val phone = phoneIpFor(ip) ?: return
			Log.i(TAG, "$ip: open the TV app, phone $phone")
			send(c, "request", "launch${nextId++}", "ssap://system.launcher/launch",
				JSONObject().put("id", "com.inarabic.lg").put("params", JSONObject().put("phone", phone)))
		}

		fun connect()
		{
			if(closed) return
			val url = if(secure) "wss://$ip:3001" else "ws://$ip:3000"
			val c = object : WebSocketClient(URI(url))
			{
				override fun onOpen(h: ServerHandshake?) { register(this) }
				override fun onMessage(message: String) { handle(this, message) }
				override fun onClose(code: Int, reason: String?, remote: Boolean)
				{
					Log.i(TAG, "$ip closed $code $reason")
					if(main === this) lost()
				}
				override fun onError(ex: Exception?) { Log.w(TAG, "$ip ${ex?.message}") }
				override fun onSetSSLParameters(p: SSLParameters) { } // the TV's certificate is pinned below, not named after its address
			}
			if(secure) c.setSocketFactory(pinnedSsl().socketFactory)
			c.connectionLostTimeout = 30
			main = c
			try { c.connect() } catch(e: Exception) { lost() }
		}

		fun lost()
		{
			registered = false
			task?.cancel(false); task = null
			try { pointer?.close() } catch(e: Exception) { }
			pointer = null
			if(closed) return
			// newer TVs only answer on 3001, older ones only on 3000: alternate until one answers
			secure = !secure
			retry?.cancel(false)
			retry = timer.schedule(Runnable { connect() }, 10, TimeUnit.SECONDS)
		}

		fun close()
		{
			closed = true
			task?.cancel(false); retry?.cancel(false)
			try { pointer?.close() } catch(e: Exception) { }
			try { main?.close() } catch(e: Exception) { }
		}

		fun register(c: WebSocketClient)
		{
			val pairing = JSONObject(appContext!!.assets.open("lg-remote-pairing.json").bufferedReader().use { it.readText() })
			if(unsigned)
			{
				val m = pairing.getJSONObject("manifest")
				m.remove("signed"); m.remove("signatures"); m.put("appVersion", "1.0")
				m.getJSONArray("permissions").put("CONTROL_INPUT_TEXT").put("CONTROL_MOUSE_AND_KEYBOARD")
			}
			prefs().getString("key_$ip", null)?.let { pairing.put("client-key", it) }
			send(c, "register", "reg", null, pairing)
		}

		fun send(c: WebSocketClient, type: String, id: String, uri: String?, payload: JSONObject? = null)
		{
			val o = JSONObject().put("type", type).put("id", id)
			if(uri != null) o.put("uri", uri)
			if(payload != null) o.put("payload", payload)
			try { c.send(o.toString()) } catch(e: Exception) { }
		}

		fun handle(c: WebSocketClient, message: String)
		{
			val o = try { JSONObject(message) } catch(e: Exception) { return }
			val id = o.optString("id")
			val p = o.optJSONObject("payload") ?: JSONObject()
			when
			{
				id == "reg" && o.optString("type") == "error" ->
				{
					Log.w(TAG, "$ip register: ${o.optString("error")}")
					// some firmware refuses the signed manifest: ask again without it
					if(!unsigned && o.optString("error").contains("certificate", true)) { unsigned = true; register(c) }
					// "allow" was declined (or ignored): do not ask again every few seconds; asked again next time the TV app connects
					else { closed = true; try { c.close() } catch(e: Exception) { } }
				}
				id == "reg" && p.optString("client-key").isNotEmpty() ->
				{
					// "allow" was pressed on the TV (or it already knew us)
					prefs().edit().putString("key_$ip", p.optString("client-key")).apply()
					send(c, "request", "pointer", "ssap://com.webos.service.networkinput/getPointerInputSocket")
					send(c, "subscribe", "power", "ssap://com.webos.service.tvpower/power/getPowerState")
					registered = true
					doLaunch(c)
				}
				id == "fg" -> if(p.optString("appId") == "com.inarabic.lg") launch(c)
				id == "pointer" && p.optString("socketPath").isNotEmpty() -> openPointer(p.optString("socketPath"))
				id == "power" ->
				{
					val state = p.optString("state") + " " + p.optString("processing")
					if(state.contains("Screen Off", true) || state.contains("Screen Saver", true))
					{
						Log.i(TAG, "$ip screen: $state -> on")
						send(c, "request", "on${nextId++}", "ssap://com.webos.service.tvpower/power/turnOnScreen", JSONObject().put("standbyMode", "active"))
						press()
					}
				}
			}
		}

		fun openPointer(path: String)
		{
			val p = object : WebSocketClient(URI(path))
			{
				override fun onOpen(h: ServerHandshake?) { press() }
				override fun onMessage(message: String) { }
				override fun onClose(code: Int, reason: String?, remote: Boolean) { }
				override fun onError(ex: Exception?) { Log.w(TAG, "$ip pointer ${ex?.message}") }
				override fun onSetSSLParameters(p: SSLParameters) { }
			}
			if(path.startsWith("wss")) p.setSocketFactory(pinnedSsl().socketFactory)
			pointer = p
			try { p.connect() } catch(e: Exception) { }
			task?.cancel(false)
			task = timer.scheduleAtFixedRate(Runnable { if(com.inarabic.link.LinkServer.hasLgScreen()) press() }, PRESS_EVERY_S, PRESS_EVERY_S, TimeUnit.SECONDS)
		}

		fun press()
		{
			val p = pointer ?: return
			try { if(p.isOpen) p.send("type:button\nname:$KEY\n\n") } catch(e: Exception) { }
		}
	}

	private fun pinnedSsl(): SSLContext
	{
		val tm = object : X509TrustManager
		{
			override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { }
			override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String)
			{
				// newer firmware may carry a newer LG certificate than the two known ones: also accept one issued by LG
				val ok = chain.any { cert ->
					MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) } in LG_CERTS
				} || chain.any { it.issuerX500Principal.name.contains("LG", false) }
				if(!ok) throw java.security.cert.CertificateException("not an LG TV certificate")
			}
			override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
		}
		return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
	}
}
