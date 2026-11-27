// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// The access token Microsoft's Edge read-aloud voices ask for (Sec-MS-GEC, version 2): a hash of the time
// (rounded to 5 minutes) and Edge's public client token. Ported from the open-source edge-tts (LGPL-3.0,
// github.com/rany2/edge-tts, drm.py); checked against it in EdgeTokenTest. When Microsoft changes it, the
// dubbing falls back to the phone's own voice until this file is updated.

package com.inarabic.voice

object EdgeToken
{
	const val CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
	const val CHROMIUM = "143.0.3650.75"
	const val VERSION = "2-" + CHROMIUM
	private const val WIN_EPOCH = 11644473600L

	private val INITIAL = intArrayOf(
		0x6A09E667.toInt(), 0xBB67AE85.toInt(), 0x3C6EF372.toInt(), 0xA54FF53A.toInt(), 0x510E527F.toInt(), 0x9B05688C.toInt(),
		0x1F83D9AB.toInt(), 0x5BE0CD19.toInt())
	private val STATE = intArrayOf(
		0xFD9FBEA2.toInt(), 0x91243CE7.toInt(), 0xBE499094.toInt(), 0xCD2E1E0A.toInt(), 0x6C006F49.toInt(), 0x04BD35AB.toInt(),
		0xFEB0A7E4.toInt(), 0x03EFFBD2.toInt())
	private val SCHEDULE = intArrayOf(
		0xEB24BF9E.toInt(), 0x1558691F.toInt(), 0xA0C09EE2.toInt(), 0x0CFA32BA.toInt(), 0xA622EE8C.toInt(), 0xD1DD9491.toInt(),
		0xC0DD25A0.toInt(), 0xF9E70AB6.toInt(), 0xF0C31573.toInt(), 0x7058CE4E.toInt(), 0x7188E606.toInt(), 0x00E5AB33.toInt(),
		0xD258B060.toInt(), 0x72766564.toInt())
	private val K = intArrayOf(
		0x428A2F98.toInt(), 0x71374491.toInt(), 0xB5C0FBCF.toInt(), 0xE9B5DBA5.toInt(), 0x3956C25B.toInt(), 0x59F111F1.toInt(),
		0x923F82A4.toInt(), 0xAB1C5ED5.toInt(), 0xD807AA98.toInt(), 0x12835B01.toInt(), 0x243185BE.toInt(), 0x550C7DC3.toInt(),
		0x72BE5D74.toInt(), 0x80DEB1FE.toInt(), 0x9BDC06A7.toInt(), 0xC19BF174.toInt(), 0xE49B69C1.toInt(), 0xEFBE4786.toInt(),
		0x0FC19DC6.toInt(), 0x240CA1CC.toInt(), 0x2DE92C6F.toInt(), 0x4A7484AA.toInt(), 0x5CB0A9DC.toInt(), 0x76F988DA.toInt(),
		0x983E5152.toInt(), 0xA831C66D.toInt(), 0xB00327C8.toInt(), 0xBF597FC7.toInt(), 0xC6E00BF3.toInt(), 0xD5A79147.toInt(),
		0x06CA6351.toInt(), 0x14292967.toInt(), 0x27B70A85.toInt(), 0x2E1B2138.toInt(), 0x4D2C6DFC.toInt(), 0x53380D13.toInt(),
		0x650A7354.toInt(), 0x766A0ABB.toInt(), 0x81C2C92E.toInt(), 0x92722C85.toInt(), 0xA2BFE8A1.toInt(), 0xA81A664B.toInt(),
		0xC24B8B70.toInt(), 0xC76C51A3.toInt(), 0xD192E819.toInt(), 0xD6990624.toInt(), 0xF40E3585.toInt(), 0x106AA070.toInt(),
		0x19A4C116.toInt(), 0x1E376C08.toInt(), 0x2748774C.toInt(), 0x34B0BCB5.toInt(), 0x391C0CB3.toInt(), 0x4ED8AA4A.toInt(),
		0x5B9CCA4F.toInt(), 0x682E6FF3.toInt(), 0x748F82EE.toInt(), 0x78A5636F.toInt(), 0x84C87814.toInt(), 0x8CC70208.toInt(),
		0x90BEFFFA.toInt(), 0xA4506CEB.toInt(), 0xBEF9A3F7.toInt(), 0xC67178F2.toInt())
	private const val ADJ = 0xC9DC06A7.toInt()
	private val K_BLOCK = K.copyOf().also { it[14] = ADJ }
	private const val SIGMA0_ADJ = 0x059C0B80
	private const val SIGMA1_ADJ = 0x000B92C0
	private val CS_COEF = intArrayOf(
		0xE82E91B1.toInt(), 0x44DE24C6.toInt(), 0x3BEB65BC.toInt(), 0xC89C5933.toInt(), 0x8D1EC211.toInt(), 0x314EF5D5.toInt(),
		0xE92491B1.toInt(), 0xF0A5B9D5.toInt(), 0x06064CF3.toInt(), 0xDA79FA60.toInt(), 0xBED20560.toInt(), 0x89F9C9F8.toInt(),
		0x35FA38C6.toInt(), 0xCB49E3C6.toInt())
	private val CS_OFF = intArrayOf(
		0xB32700D0.toInt(), 0x88DFF51D.toInt(), 0x29514038.toInt(), 0xB328CBDF.toInt(), 0xD405B4D5.toInt(), 0x26E55F3C.toInt(),
		0x607801C6.toInt(), 0x82B2C1FB.toInt(), 0xFA51A3DA.toInt(), 0x207CC464.toInt(), 0x3C3C74F3.toInt(), 0xD2D14B61.toInt(),
		0xBB04A2C1.toInt(), 0xC5626167.toInt())
	private const val CS_FACTOR = 0x82893933.toInt()
	private const val CS_ADJ0 = 0xF2F90B1A.toInt()
	private const val CS_ADJ1 = 0x0B1A0000

	/** the token for this moment ([unixSeconds]: the phone's clock) */
	fun token(unixSeconds: Long = System.currentTimeMillis() / 1000): String
	{
		var t = unixSeconds + WIN_EPOCH
		t -= t % 300
		val data = (t * 10_000_000L).toString() + CLIENT_TOKEN
		val bytes = data.toByteArray(Charsets.US_ASCII)
		val digest = sha(bytes)
		val cs = cs64(bytes, digest)
		val le = ByteArray(8)
		for(i in 0 until 4) { le[i] = (cs[0] ushr (8 * i)).toByte(); le[4 + i] = (cs[1] ushr (8 * i)).toByte() }
		return hex(le) + "-" + hex(digest)
	}

	private fun hex(b: ByteArray) = b.joinToString("") { String.format("%02X", it.toInt() and 255) }
	private fun s0(x: Int) = Integer.rotateRight(x, 7) xor Integer.rotateRight(x, 18) xor (x ushr 3)
	private fun s1(x: Int) = Integer.rotateRight(x, 17) xor Integer.rotateRight(x, 19) xor (x ushr 10)
	private fun rol16(x: Int) = Integer.rotateLeft(x, 16)

	private fun expand(w: IntArray, from: Int)
	{
		for(i in from until 64) w[i] = s1(w[i - 2]) + w[i - 7] + s0(w[i - 15]) + w[i - 16]
	}

	private fun rounds(state: IntArray, w: IntArray, first: Int): IntArray
	{
		val k = if(first == 14) K_BLOCK else K
		var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
		var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
		for(i in first until 64)
		{
			val t1 = h + (Integer.rotateRight(e, 6) xor Integer.rotateRight(e, 11) xor Integer.rotateRight(e, 25)) + (g xor (e and (f xor g))) + k[i] + w[i]
			val t2 = (Integer.rotateRight(a, 2) xor Integer.rotateRight(a, 13) xor Integer.rotateRight(a, 22)) + ((a and b) or (c and (a or b)))
			h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
		}
		return intArrayOf(a, b, c, d, e, f, g, h)
	}

	/** the state after the first 7 bytes, from Microsoft's own starting point */
	private fun init(d7: ByteArray): IntArray
	{
		val w = IntArray(64)
		w[14] = ((d7[0].toInt() and 255) shl 16) or ((d7[1].toInt() and 255) shl 8) or (d7[2].toInt() and 255)
		w[15] = ((d7[3].toInt() and 255) shl 24) or ((d7[4].toInt() and 255) shl 16) or ((d7[5].toInt() and 255) shl 8) or (d7[6].toInt() and 255)
		for(i in 16 until 30)
		{
			var v = SCHEDULE[i - 16]
			if(i - 2 >= 14) v += if(i == 16) s1(w[i - 2]) xor SIGMA1_ADJ else s1(w[i - 2])
			if(i - 7 >= 14) v += w[i - 7]
			if(i - 15 >= 14) v += if(i == 29) s0(w[i - 15]) xor SIGMA0_ADJ else s0(w[i - 15])
			w[i] = v
		}
		w[30] = s1(w[28]) + w[23] + s0(w[15]) + w[14] + ADJ - K[14]
		expand(w, 31)
		val r = rounds(STATE, w, 14)
		return IntArray(8) { INITIAL[it] + r[it] }
	}

	private fun sha(data: ByteArray): ByteArray
	{
		val length = 57 + data.size
		val pad = Math.floorMod(55 - length, 64)
		val rest = data.copyOfRange(7, data.size)
		val padded = ByteArray(rest.size + 1 + pad + 8)
		System.arraycopy(rest, 0, padded, 0, rest.size)
		padded[rest.size] = 0x80.toByte()
		val bits = length.toLong() * 8
		for(i in 0 until 8) padded[padded.size - 1 - i] = (bits ushr (8 * i)).toByte()
		var state = init(data.copyOfRange(0, 7))
		var off = 0
		while(off < padded.size)
		{
			val w = IntArray(64)
			for(i in 0 until 16) w[i] = ((padded[off + 4 * i].toInt() and 255) shl 24) or ((padded[off + 4 * i + 1].toInt() and 255) shl 16) or
				((padded[off + 4 * i + 2].toInt() and 255) shl 8) or (padded[off + 4 * i + 3].toInt() and 255)
			expand(w, 16)
			val r = rounds(state, w, 0)
			state = IntArray(8) { state[it] + r[it] }
			off += 64
		}
		val out = ByteArray(32)
		for(i in 0 until 8) for(j in 0 until 4) out[4 * i + j] = (state[i] ushr (24 - 8 * j)).toByte()
		return out
	}

	private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8) or ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 3].toInt() and 255) shl 24)

	private fun cs64(data: ByteArray, digest: ByteArray): IntArray
	{
		val ka = le32(digest, 0) or 1
		val kb = le32(digest, 4) or 1
		val factors = intArrayOf(ka * CS_FACTOR, kb * CS_FACTOR)
		val terms = ArrayList<Int>(); val offsets = ArrayList<Int>()
		for(i in CS_COEF.indices) { terms += CS_COEF[i] * factors[i % 2]; offsets += CS_OFF[i] }
		// the data after one zero byte, as little-endian words, zero-padded to an even count
		val src = ByteArray(1 + data.size).also { System.arraycopy(data, 0, it, 1, data.size) }
		var n = (src.size + 3) / 4
		if(n % 2 == 1) n++
		val buf = src.copyOf(n * 4)
		for(i in 0 until n)
		{
			val word = le32(buf, 4 * i)
			var term = word * (if(i % 2 == 0) ka else kb)
			var offset = rol16(word) * (if(i % 2 == 0) 0xEF0569FB.toInt() else 0xC31713DB.toInt())
			if(i == 0) { term += CS_ADJ0 * factors[0]; offset += CS_ADJ1 }
			terms += term; offsets += offset
		}
		var swapAcc = 0; var swapLast = 0; var state = 0; var acc = 0
		var i = 0
		while(i < terms.size)
		{
			val x = terms[i]; val y = terms[i + 1]
			var t = rol16(offsets[i] + x)
			var q = t * 0x76F50F34
			swapAcc += rol16(q) * 0xC3EFEA97.toInt() + q
			t = rol16(offsets[i + 1] + y)
			q = t * 0x1881CE3C
			swapLast = rol16(q) * 0x35BD1EC9 + q
			swapAcc += swapLast

			state = state * ka + x
			state = rol16(state) * 0xCF98B111.toInt()
			state = rol16(state) * 0x87085B9F.toInt()
			state = rol16(state) * 0x12CEB96D
			state = rol16(state) * 0x257E1D83
			acc += state
			var u = state * kb + y
			u = rol16(u) * 0xA27416F5.toInt()
			u = rol16(u) * 0xD38396FF.toInt()
			state = rol16(rol16(u) * 0x7C932B89) * 0xBFA49F69.toInt()
			acc += state
			i += 2
		}
		return intArrayOf(swapLast xor state, swapAcc xor acc)
	}
}
