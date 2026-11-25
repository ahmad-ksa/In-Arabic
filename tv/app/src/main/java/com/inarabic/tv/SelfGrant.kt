// Gives this app the "display over other apps" permission on TVs that hide its settings page: the app talks
// to the TV's own debugging service (ADB on 127.0.0.1:5555, turned on once in Developer options) and runs
// "appops set … SYSTEM_ALERT_WINDOW allow". The first time, the TV asks "Allow debugging?": choose Always allow.

package com.inarabic.tv

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

object SelfGrant
{
	private const val TAG = "InArabicTV"

	sealed class Result
	{
		object Granted : Result()
		/** debugging is off (nothing listens on 5555) */
		object NoAdb : Result()
		/** connected but the TV refused (the "allow debugging" prompt was declined or timed out) */
		data class Failed(val why: String) : Result()
	}

	fun run(context: Context): Result
	{
		val dir = File(context.filesDir, "adb")
		val priv = File(dir, "adbkey")
		val pub = File(dir, "adbkey.pub")
		try { if(!priv.exists()) AdbKeyPair.generate(priv, pub) } catch(e: Throwable) { return Result.Failed("key: ${e.message}") }
		val keys = try { AdbKeyPair.read(priv, pub) } catch(e: Throwable) { return Result.Failed("key: ${e.message}") }
		for(host in listOf("127.0.0.1", "localhost"))
		{
			try
			{
				// long socket timeout: the user has to answer the "allow debugging" prompt on the TV
				Dadb.create(host, 5555, keys, connectTimeout = 3000, socketTimeout = 60000).use { adb ->
					val pkg = context.packageName
					val r = adb.shell("appops set $pkg SYSTEM_ALERT_WINDOW allow; appops get $pkg SYSTEM_ALERT_WINDOW")
					Log.i(TAG, "appops: ${r.output} ${r.errorOutput}")
					// also keep the app alive in the background on TVs that kill services
					adb.shell("cmd deviceidle whitelist +$pkg")
					return if(r.output.contains("allow")) Result.Granted else Result.Failed((r.output + r.errorOutput).take(200))
				}
			}
			catch(e: java.net.ConnectException) { continue }
			catch(e: java.net.SocketTimeoutException) { return Result.Failed("انتهت المهلة — وافق على رسالة السماح بتصحيح الأخطاء") }
			catch(e: Throwable) { Log.w(TAG, "adb", e); return Result.Failed(e.message ?: e.javaClass.simpleName) }
		}
		return Result.NoAdb
	}
}
