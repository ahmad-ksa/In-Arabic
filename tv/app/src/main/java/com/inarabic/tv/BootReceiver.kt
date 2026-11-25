// The TV was switched on: if the subtitles were on when it went off, put them back on by themselves.

package com.inarabic.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver()
{
	override fun onReceive(context: Context, intent: Intent)
	{
		if(!Prefs.autoStart(context)) return
		val canDraw = Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(context) || Build.VERSION.SDK_INT <= 24
		if(!canDraw) return
		val i = Intent(context, OverlayService::class.java)
		try { if(Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i) } catch(e: Exception) { }
	}
}
