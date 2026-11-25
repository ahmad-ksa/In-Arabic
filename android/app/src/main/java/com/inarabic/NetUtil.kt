// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Android refuses packets to 255.255.255.255 on many phones ("Invalid argument"), so PlayStation search and
// registration use the home network's own broadcast address instead (e.g. 192.168.1.255).

package com.inarabic

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil
{
	fun broadcastAddress(): String = try
	{
		val ifs = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
			.sortedBy { if(it.name.startsWith("wlan")) 0 else if(it.name.startsWith("eth")) 1 else 2 }
		ifs.flatMap { it.interfaceAddresses }
			.firstOrNull { it.address is Inet4Address && it.broadcast != null && it.address.isSiteLocalAddress }
			?.broadcast?.hostAddress ?: "255.255.255.255"
	}
	catch(e: Exception) { "255.255.255.255" }
}
