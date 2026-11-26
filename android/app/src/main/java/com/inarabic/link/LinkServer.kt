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
//   {"t":"lens","items":[{"ar":"…","box":[x,y,w,h,lineH],"color":"#RRGGBB","bg":"#RRGGBB","spans":[…]}]} / {"t":"lensclear"}
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
	@Volatile private var lastSide: String? = null
	@Volatile var pairCode: String = ""
	@Volatile private var lastStyle: String? = null

	/** connected screens, for the status line in the app */
	val screens = mutableMapOf<WebSocket, String>()
	/** LG screens and their addresses (kept awake from the phone, see LgKeepAwake) */
	private val lgScreens = mutableMapOf<WebSocket, String>()
	/** an LG TV app is connected now */
	fun hasLgScreen() = synchronized(lgScreens) { lgScreens.isNotEmpty() }
	var onScreensChanged: (() -> Unit)? = null

	private var appContext: Context? = null

	@Synchronized fun start(context: Context)
	{
		appContext = context.applicationContext
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

	fun sendSub(id: Int, arabic: String, english: String, final: Boolean, box: FloatArray?, spans: List<com.inarabic.caption.ColorSpan> = emptyList())
	{
		val o = JSONObject().put("t", "sub").put("id", id).put("ar", arabic).put("en", english).put("final", final)
		if(box != null) o.put("box", boxJson(box))
		if(spans.isNotEmpty()) o.put("spans", spansJson(spans))
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
			.put("box", boxJson(box)).toString()
		lastBox2 = msg
		broadcast(msg)
	}

	fun sendBox2Clear()
	{
		lastBox2 = null
		broadcast("{\"t\":\"box2clear\"}")
	}

	/** texts off to the side of the subtitle (mission titles, button prompts): each drawn over its English one */
	fun sendSide(items: List<Triple<String, FloatArray, List<com.inarabic.caption.ColorSpan>>>)
	{
		val arr = org.json.JSONArray()
		for((ar, box, spans) in items)
			arr.put(JSONObject().put("ar", ar).put("box", boxJson(box)).apply { if(spans.isNotEmpty()) put("spans", spansJson(spans)) })
		val msg = JSONObject().put("t", "side").put("items", arr).toString()
		lastSide = msg
		broadcast(msg)
	}

	fun sendSideClear()
	{
		lastSide = null
		broadcast("{\"t\":\"sideclear\"}")
	}

	/** one text inside the lens: the Arabic, its English box, letter and background colours, coloured words */
	class LensOut(val arabic: String, val box: FloatArray, val color: Int, val bg: Int, val spans: List<com.inarabic.caption.ColorSpan>)
	@Volatile private var lastLens: String? = null

	/** the lens's translations: each drawn over its English, same size and colours */
	fun sendLens(items: List<LensOut>)
	{
		val arr = org.json.JSONArray()
		for(it in items)
			arr.put(JSONObject().put("ar", it.arabic).put("box", boxJson(it.box))
				.put("color", String.format("#%06X", it.color and 0xFFFFFF)).put("bg", String.format("#%06X", it.bg and 0xFFFFFF))
				.apply { if(it.spans.isNotEmpty()) put("spans", spansJson(it.spans)) })
		val msg = JSONObject().put("t", "lens").put("items", arr).toString()
		lastLens = msg
		broadcast(msg)
	}

	fun sendLensClear()
	{
		lastLens = null
		broadcast("{\"t\":\"lensclear\"}")
	}

	/** sends what is on screen again (the TVs hide a line they have not heard about for a while) */
	fun refresh()
	{
		lastSub?.let { broadcast(it) }
		lastSide?.let { broadcast(it) }
		lastLens?.let { broadcast(it) }
	}

	/** a short notice shown on the TVs (top of the screen) */
	fun sendNotice(text: String) = broadcast(JSONObject().put("t", "notice").put("text", text).toString())

	/** all reading frames while you place them on the phone (empty: hide) */
	fun sendZones(list: List<com.inarabic.InArabicPrefs.Zone>)
	{
		val a = org.json.JSONArray()
		for(z in list) a.put(JSONObject().put("box", boxJson(floatArrayOf(z.r.left, z.r.top, z.r.width(), z.r.height()))).put("dialog", z.dialog))
		broadcast(JSONObject().put("t", "zones").put("zones", a).toString())
	}

	/** the reading frame while you drag it on the phone (null: hide) */
	fun sendZone(r: android.graphics.RectF?)
	{
		val o = JSONObject().put("t", "zone")
		if(r != null) o.put("box", boxJson(floatArrayOf(r.left, r.top, r.width(), r.height())))
		broadcast(o.toString())
	}

	fun sendPanel(title: String, text: String, ttlMs: Long = 30000)
	{
		broadcast(JSONObject().put("t", "panel").put("title", title).put("text", text).put("ttl", ttlMs).toString())
	}

	fun sendStyle(p: com.inarabic.InArabicPrefs)
	{
		val msg = JSONObject().put("t", "style").put("scale", p.textScale.toDouble()).put("cover", p.coverOriginal)
			.put("opacity", p.boxOpacity.toDouble()).put("color", p.textColor).put("pos", p.position).put("bold", p.bold)
			.put("outline", p.outline).put("boxColor", p.boxColor)
			.put("zoom", p.tvZoom.toDouble()).put("dy", p.tvShift.toDouble()).toString()
		lastStyle = msg
		broadcast(msg)
	}

	/** x, y, w, h (0..1 of the picture), the height of one English line and, for tilted text, its angle and own size */
	private fun boxJson(b: FloatArray): org.json.JSONArray
	{
		val a = org.json.JSONArray()
		for(i in 0 until minOf(b.size, 8)) a.put(Math.round(b[i] * 10000) / 10000.0) // [5..7]: tilt, own width and height
		return a
	}

	/** coloured stretches of the Arabic text: [start, end, "#RRGGBB"] */
	private fun spansJson(spans: List<com.inarabic.caption.ColorSpan>) = org.json.JSONArray().apply {
		for(sp in spans) put(org.json.JSONArray().put(sp.start).put(sp.end).put(String.format("#%06X", sp.color and 0xFFFFFF)))
	}

	/** the phone's own caption view gets every message too */
	@Volatile var local: ((String) -> Unit)? = null

	private fun broadcast(msg: String)
	{
		local?.invoke(msg)
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
			val q = android.net.Uri.parse("http://x" + (handshake.resourceDescriptor ?: "/"))
			val code = q.getQueryParameter("code")
			val token = q.getQueryParameter("token")
			// a screen paired by QR (its token), or one that typed the 4-digit code
			if(!Pairing.isPaired(appContext ?: return, token) && code != pairCode)
			{
				conn.close(4001, "wrong pairing code")
				return
			}
			synchronized(screens) { screens[conn] = conn.remoteSocketAddress?.address?.hostAddress ?: "?" }
			onScreensChanged?.invoke()
			lastStyle?.let { conn.send(it) }
			lastSub?.let { conn.send(it) }
			lastBox2?.let { conn.send(it) }
			lastSide?.let { conn.send(it) }
			lastLens?.let { conn.send(it) }
		}

		override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean)
		{
			synchronized(screens) { screens.remove(conn) }
			onScreensChanged?.invoke()
			val ip = synchronized(lgScreens) { lgScreens.remove(conn)?.takeIf { it !in lgScreens.values } }
			if(ip != null) com.inarabic.lg.LgKeepAwake.stop(ip)
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
					// an LG screen: the phone keeps it awake (you play with the controller, not its remote)
					val ip = conn.remoteSocketAddress?.address?.hostAddress
					if(kind == "lg" && ip != null) appContext?.let {
						synchronized(lgScreens) { lgScreens[conn] = ip }
						com.inarabic.lg.LgKeepAwake.start(it, ip)
					}
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
