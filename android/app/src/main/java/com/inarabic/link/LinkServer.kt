// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// The phone is the hub: a small WebSocket server on the home network that the TV apps (Android TV and LG)
// connect to. It is announced on the network (mDNS/NSD) so the Android TV app finds it by itself; the LG app
// is given the phone's address once.
//
// A TV connects to ws://PHONE:8765/?code=1234 (the 4-digit pairing code shown in the phone's settings);
// a wrong code is closed with 4001.
//
// Messages (JSON, phone -> TV):
//   {"t":"sub","id":7,"ar":"…","en":"…","final":false,"box":[x,y,w,h]}   box: original subtitle, 0..1 of the picture
//   {"t":"clear"}
//   {"t":"box2","ar":"…","box":[x,y,w,h]} / {"t":"box2clear"}           side texts (objectives, messages)
//   {"t":"panel","title":"…","text":"…","ttl":30000}                     e.g. the story summary
//   {"t":"style","scale":1.0,"cover":true,"opacity":0.85,"color":"#FFFFFF","pos":"auto|bottom|top","bold":true}
// TV -> phone: {"t":"hello","kind":"androidtv|lg","name":"…"}

package com.inarabic.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface

object LinkServer
{
	const val PORT = 8765
	const val SERVICE_TYPE = "_inarabic._tcp"
	private const val TAG = "InArabicLink"

	private var server: Server? = null
	private var nsd: NsdManager? = null
	private var nsdListener: NsdManager.RegistrationListener? = null

	@Volatile private var lastSub: String? = null
	@Volatile private var lastBox2: String? = null
	@Volatile var pairCode: String = ""
	@Volatile private var lastStyle: String? = null

	/** connected screens, for the status line in the app */
	val screens = mutableMapOf<WebSocket, String>()
	var onScreensChanged: (() -> Unit)? = null

	@Synchronized fun start(context: Context)
	{
		val prefs = com.inarabic.InArabicPrefs(context)
		pairCode = prefs.pairCode
		if(lastStyle == null) sendStyle(prefs)
		if(server != null) return
		val s = Server()
		s.isReuseAddr = true
		s.connectionLostTimeout = 10
		try { s.start() } catch(e: Exception) { Log.e(TAG, "server start", e); return }
		server = s
		register(context.applicationContext)
	}

	@Synchronized fun stop()
	{
		try { server?.stop(500) } catch(e: Exception) { }
		server = null
		try { nsdListener?.let { nsd?.unregisterService(it) } } catch(e: Exception) { }
		nsdListener = null
		synchronized(screens) { screens.clear() }
		onScreensChanged?.invoke()
	}

	private fun register(context: Context)
	{
		val m = context.getSystemService(Context.NSD_SERVICE) as NsdManager
		val info = NsdServiceInfo().apply {
			serviceName = "InArabic-" + (Build.MODEL ?: "phone").replace(Regex("[^A-Za-z0-9-]"), "")
			serviceType = SERVICE_TYPE
			port = PORT
		}
		val l = object : NsdManager.RegistrationListener {
			override fun onServiceRegistered(i: NsdServiceInfo) { Log.i(TAG, "announced as ${i.serviceName}") }
			override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { Log.w(TAG, "announce failed $e") }
			override fun onServiceUnregistered(i: NsdServiceInfo) {}
			override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
		}
		try { m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); nsd = m; nsdListener = l }
		catch(e: Exception) { Log.w(TAG, "nsd", e) }
	}

	fun sendSub(id: Int, arabic: String, english: String, final: Boolean, box: FloatArray?)
	{
		val o = JSONObject().put("t", "sub").put("id", id).put("ar", arabic).put("en", english).put("final", final)
		if(box != null) o.put("box", org.json.JSONArray().put(box[0].toDouble()).put(box[1].toDouble()).put(box[2].toDouble()).put(box[3].toDouble()))
		val msg = o.toString()
		lastSub = msg
		broadcast(msg)
	}

	fun sendClear()
	{
		lastSub = null
		broadcast("{\"t\":\"clear\"}")
	}

	fun sendBox2(arabic: String, box: FloatArray)
	{
		val msg = JSONObject().put("t", "box2").put("ar", arabic)
			.put("box", org.json.JSONArray().put(box[0].toDouble()).put(box[1].toDouble()).put(box[2].toDouble()).put(box[3].toDouble())).toString()
		lastBox2 = msg
		broadcast(msg)
	}

	fun sendBox2Clear()
	{
		lastBox2 = null
		broadcast("{\"t\":\"box2clear\"}")
	}

	fun sendPanel(title: String, text: String, ttlMs: Long = 30000)
	{
		broadcast(JSONObject().put("t", "panel").put("title", title).put("text", text).put("ttl", ttlMs).toString())
	}

	fun sendStyle(p: com.inarabic.InArabicPrefs)
	{
		val msg = JSONObject().put("t", "style").put("scale", p.textScale.toDouble()).put("cover", p.coverOriginal)
			.put("opacity", p.boxOpacity.toDouble()).put("color", p.textColor).put("pos", p.position).put("bold", p.bold).toString()
		lastStyle = msg
		broadcast(msg)
	}

	private fun broadcast(msg: String)
	{
		try { server?.broadcast(msg) } catch(e: Exception) { Log.w(TAG, "broadcast", e) }
	}

	/** This phone's addresses on the home network, for the LG app. */
	fun addresses(): List<String> = try
	{
		NetworkInterface.getNetworkInterfaces().toList()
			.filter { it.isUp && !it.isLoopback }
			.flatMap { it.inetAddresses.toList() }
			.filterIsInstance<Inet4Address>()
			.filter { it.isSiteLocalAddress }
			.map { it.hostAddress ?: "" }
			.filter { it.isNotEmpty() }
	}
	catch(e: Exception) { emptyList() }

	private class Server : WebSocketServer(InetSocketAddress(PORT))
	{
		override fun onOpen(conn: WebSocket, handshake: ClientHandshake)
		{
			val code = android.net.Uri.parse("http://x" + (handshake.resourceDescriptor ?: "/")).getQueryParameter("code")
			if(code != pairCode)
			{
				conn.close(4001, "wrong pairing code")
				return
			}
			synchronized(screens) { screens[conn] = conn.remoteSocketAddress?.address?.hostAddress ?: "?" }
			onScreensChanged?.invoke()
			lastStyle?.let { conn.send(it) }
			lastSub?.let { conn.send(it) }
			lastBox2?.let { conn.send(it) }
		}

		override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean)
		{
			synchronized(screens) { screens.remove(conn) }
			onScreensChanged?.invoke()
		}

		override fun onMessage(conn: WebSocket, message: String)
		{
			try
			{
				val o = JSONObject(message)
				if(o.optString("t") == "hello")
				{
					val kind = o.optString("kind")
					val name = o.optString("name").ifBlank { kind }
					synchronized(screens) { screens[conn] = name }
					onScreensChanged?.invoke()
				}
				else if(o.optString("t") == "ping")
					conn.send("{\"t\":\"pong\",\"at\":${o.optLong("at")}}")
			}
			catch(e: Exception) { }
		}

		override fun onError(conn: WebSocket?, ex: Exception) { Log.w(TAG, "ws", ex) }
		override fun onStart() { Log.i(TAG, "listening on $PORT") }
	}
}
