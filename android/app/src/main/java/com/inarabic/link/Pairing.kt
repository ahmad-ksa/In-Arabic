// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Pairing like a QR login: the TV app shows a QR code ("inarabic:pair?t=TOKEN&n=NAME"), the phone scans it with
// Google's code scanner (no camera permission needed) and from then on accepts that screen's token.

package com.inarabic.link

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

object Pairing
{
	private const val KEY = "ia_tokens"

	/** token -> screen name */
	fun screens(context: Context): Map<String, String> =
		PreferenceManager.getDefaultSharedPreferences(context).getStringSet(KEY, emptySet())!!
			.mapNotNull { e -> e.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()

	fun isPaired(context: Context, token: String?) = !token.isNullOrBlank() && screens(context).containsKey(token)

	fun forgetAll(context: Context) = PreferenceManager.getDefaultSharedPreferences(context).edit().remove(KEY).apply()

	private fun add(context: Context, token: String, name: String)
	{
		val p = PreferenceManager.getDefaultSharedPreferences(context)
		val set = p.getStringSet(KEY, emptySet())!!.filterNot { it.startsWith("$token\t") }.toMutableSet()
		set.add("$token\t" + name.replace('\t', ' '))
		p.edit().putStringSet(KEY, set).apply()
	}

	/** Opens the scanner; on a valid code the screen is paired. */
	fun scan(activity: Activity, onPaired: (String) -> Unit = {})
	{
		val scanner = GmsBarcodeScanning.getClient(activity,
			GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build())
		scanner.startScan()
			.addOnSuccessListener { code ->
				val uri = Uri.parse(code.rawValue ?: "")
				val token = if(uri.scheme == "inarabic") Uri.parse("http://x/?" + (uri.encodedSchemeSpecificPart.substringAfter('?', ""))).getQueryParameter("t") else null
				if(token.isNullOrBlank())
				{
					Toast.makeText(activity, "هذا ليس باركود تطبيق الشاشة", Toast.LENGTH_LONG).show()
					return@addOnSuccessListener
				}
				val name = Uri.parse("http://x/?" + uri.encodedSchemeSpecificPart.substringAfter('?', "")).getQueryParameter("n") ?: "شاشة"
				add(activity, token, name)
				LinkServer.start(activity)
				Toast.makeText(activity, "تم ربط «$name» ✓ — ستتصل خلال ثوانٍ", Toast.LENGTH_LONG).show()
				onPaired(name)
			}
			.addOnFailureListener { Toast.makeText(activity, "تعذّر فتح الماسح: ${it.message}", Toast.LENGTH_LONG).show() }
	}
}
