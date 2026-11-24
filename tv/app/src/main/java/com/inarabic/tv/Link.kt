// Connection to the phone: finds it on the home network (mDNS "_inarabic._tcp") or uses the address typed
// once, keeps a WebSocket open and reconnects by itself.

package com.inarabic.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class Link(private val context: Context, private val onMessage: (JSONObject) -> Unit, private val onStatus: (String) -> Unit)
{
	companion object
	{
		const val PORT = 8765
		const val SERVICE_TYPE = "_inarabic._tcp."
		private const val TAG = "InArabicTV"
	}

	private val main = Handler(Looper.getMainLooper())
	private val http = OkHttpClient.Builder().pingInterval(5, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
	private var ws: WebSocket? = null
	private var running = false
	private var host: String? = null
	private var nsd: NsdManager? = null
	private var discovery: NsdManager.DiscoveryListener? = null
	private var multicast: WifiManager.MulticastLock? = null

	fun start()
	{
		running = true
		val manual = Prefs.phoneAddress(context)
		if(manual.isNotBlank()) { host = manual; connect() } else discover()
	}

	fun stop()
	{
		running = false
		main.removeCallbacksAndMessages(null)
		ws?.close(1000, "bye")
		ws = null
		stopDiscovery()
	}

	private fun connect()
	{
		val h = host ?: return
		if(!running) return
		onStatus("جارٍ الاتصال بالجوال $h…")
		val req = Request.Builder().url("ws://$h:$PORT/").build()
		ws = http.newWebSocket(req, object : WebSocketListener() {
			override fun onOpen(webSocket: WebSocket, response: Response)
			{
				webSocket.send(JSONObject().put("t", "hello").put("kind", "androidtv").put("name", Build.MODEL ?: "TV").toString())
				main.post { onStatus("متصل بالجوال ✓") }
			}

			override fun onMessage(webSocket: WebSocket, text: String)
			{
				val o = try { JSONObject(text) } catch(e: Exception) { return }
				main.post { onMessage(o) }
			}

			override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { retry("تعذّر الاتصال: ${t.message ?: ""}") }
			override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { retry("انقطع الاتصال") }
		})
	}

	private fun retry(why: String)
	{
		main.post {
			ws = null
			if(!running) return@post
			onStatus("$why — إعادة المحاولة…")
			main.removeCallbacksAndMessages(null)
			main.postDelayed({
				if(Prefs.phoneAddress(context).isBlank()) { host = null; discover() } else connect()
			}, 3000)
		}
	}

	private fun discover()
	{
		if(!running || discovery != null) return
		onStatus("أبحث عن الجوال في الشبكة… (افتح البث في تطبيق الجوال)")
		val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
		multicast = wifi.createMulticastLock("inarabic").apply { setReferenceCounted(false); acquire() }
		val m = context.getSystemService(Context.NSD_SERVICE) as NsdManager
		nsd = m
		val l = object : NsdManager.DiscoveryListener {
			override fun onDiscoveryStarted(serviceType: String) {}
			override fun onDiscoveryStopped(serviceType: String) {}
			override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "discovery $errorCode") }
			override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
			override fun onServiceLost(info: NsdServiceInfo) {}
			override fun onServiceFound(info: NsdServiceInfo)
			{
				@Suppress("DEPRECATION")
				m.resolveService(info, object : NsdManager.ResolveListener {
					override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
					override fun onServiceResolved(info: NsdServiceInfo)
					{
						@Suppress("DEPRECATION")
						val addr = info.host?.hostAddress ?: return
						main.post {
							if(!running || ws != null && host == addr) return@post
							host = addr
							stopDiscovery()
							connect()
						}
					}
				})
			}
		}
		discovery = l
		try { m.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) } catch(e: Exception) { Log.w(TAG, "nsd", e) }
	}

	private fun stopDiscovery()
	{
		try { discovery?.let { nsd?.stopServiceDiscovery(it) } } catch(e: Exception) { }
		discovery = null
		try { multicast?.release() } catch(e: Exception) { }
		multicast = null
	}
}

object Prefs
{
	private fun p(c: Context) = c.getSharedPreferences("inarabic", Context.MODE_PRIVATE)
	fun phoneAddress(c: Context) = p(c).getString("phone", "") ?: ""
	fun setPhoneAddress(c: Context, v: String) = p(c).edit().putString("phone", v.trim()).apply()
}
