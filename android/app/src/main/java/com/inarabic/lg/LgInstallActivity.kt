// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Installs the LG app straight from the phone, no computer: the TV's free "Developer Mode" app gives a key
// server (port 9991) and SSH (port 9922). We fetch the key with the passphrase shown on the TV, download the
// latest LG app from GitHub, copy it to the TV and ask the TV to install and open it.

package com.inarabic.lg

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

class LgInstallActivity : AppCompatActivity()
{
	companion object
	{
		const val IPK_URL = "https://github.com/ahmad-ksa/In-Arabic/releases/latest/download/InArabic-lg.ipk"
		const val APP_ID = "com.inarabic.lg"
	}

	private lateinit var log: TextView
	private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
	private val accent = Color.rgb(159, 211, 180)

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		title = "ثبّت على تلفزيون LG"
		val p = getSharedPreferences("ia_lg", MODE_PRIVATE)
		val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16)) }
		fun para(t: String, c: Int = Color.WHITE, s: Float = 15f) = TextView(this).apply { text = t; setTextColor(c); textSize = s; setLineSpacing(0f, 1.3f); setPadding(0, dp(4), 0, dp(4)) }
		col.addView(para("مرة وحدة في التلفزيون:", accent, 17f))
		col.addView(para("1. من متجر LG (LG Content Store) ثبّت تطبيق «Developer Mode».\n" +
			"2. افتحه وسجّل بحساب مطوّر LG (مجاني من webostv.developer.lge.com).\n" +
			"3. شغّل «Dev Mode Status» (التلفزيون يعيد التشغيل)، ثم افتحه مرة ثانية وشغّل «Key Server».\n" +
			"4. اكتب هنا عنوان التلفزيون (IP) وكلمة المرور (Passphrase) اللي تطلع في التطبيق.\n\n" +
			"ملاحظة: وضع المطوّر ينتهي بعد 50 ساعة، اضغط «Extend» في التطبيق نفسه قبلها."))
		val ip = EditText(this).apply { hint = "عنوان التلفزيون، مثل 192.168.0.50"; setText(p.getString("ip", "")); inputType = InputType.TYPE_CLASS_PHONE; setTextColor(Color.WHITE) }
		val pass = EditText(this).apply { hint = "Passphrase (6 حروف)"; setText(p.getString("pass", "")); setTextColor(Color.WHITE); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS }
		col.addView(ip); col.addView(pass)
		val go = Button(this).apply {
			text = "ثبّت التطبيق على التلفزيون"; isAllCaps = false; setTextColor(Color.rgb(15, 36, 27)); textSize = 16f
			background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(99).toFloat() }
		}
		col.addView(go, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
		val find = Button(this).apply { text = "دوّر على التلفزيون في الشبكة"; isAllCaps = false; setTextColor(Color.WHITE)
			background = GradientDrawable().apply { setColor(Color.argb(28, 255, 255, 255)); cornerRadius = dp(99).toFloat(); setStroke(dp(1), accent) } }
		col.addView(find, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
		log = para("", Color.rgb(166, 192, 179), 14f)
		col.addView(log)
		setContentView(ScrollView(this).apply { addView(col) })

		find.setOnClickListener {
			say("أدوّر على تلفزيون فيه «Key Server» شغال…")
			lifecycleScope.launch {
				val found = withContext(Dispatchers.IO) { scan() }
				if(found == null) say("ما لقينا. تأكد إن «Key Server» شغال وإن الجوال على نفس الواي فاي، أو اكتب العنوان بنفسك.")
				else { ip.setText(found); say("لقينا التلفزيون: $found") }
			}
		}
		go.setOnClickListener {
			val host = ip.text.toString().trim(); val pw = pass.text.toString().trim()
			if(host.isEmpty() || pw.isEmpty()) { say("اكتب العنوان وكلمة المرور"); return@setOnClickListener }
			p.edit().putString("ip", host).putString("pass", pw).apply()
			go.isEnabled = false
			lifecycleScope.launch {
				val r = withContext(Dispatchers.IO) { runCatching { install(host, pw) } }
				go.isEnabled = true
				r.exceptionOrNull()?.let { say("ما تم: " + explain(it)) }
			}
		}
	}

	private fun say(t: String) = runOnUiThread { log.append(t + "\n") }

	private fun explain(e: Throwable): String
	{
		val m = (e.message ?: e.toString())
		return when
		{
			m.contains("9991") || m.contains("refused", true) -> "التلفزيون ما رد. تأكد إن «Key Server» شغال في Developer Mode"
			m.contains("passphrase", true) || m.contains("Auth", true) || m.contains("invalid privatekey", true) -> "كلمة المرور غلط. اكتب الـ Passphrase اللي في التطبيق بالضبط"
			m.contains("timeout", true) -> "انتهى الوقت. تأكد إنكم على نفس الشبكة"
			else -> m
		}
	}

	/** the subnet's addresses with the key server open */
	private fun scan(): String?
	{
		val me = com.inarabic.NetUtil.broadcastAddress().takeIf { it != "255.255.255.255" } ?: return null
		val base = me.substringBeforeLast('.')
		val pool = java.util.concurrent.Executors.newFixedThreadPool(32)
		try
		{
			val tasks = (1..254).map { i -> pool.submit<String?> {
				val h = "$base.$i"
				try { Socket().use { s -> s.connect(InetSocketAddress(h, 9991), 400) }; h } catch(e: Exception) { null }
			} }
			return tasks.firstNotNullOfOrNull { it.get() }
		}
		finally { pool.shutdownNow() }
	}

	private fun install(host: String, passphrase: String)
	{
		say("١) آخذ مفتاح التلفزيون…")
		val key = (URL("http://$host:9991/webos_rsa").openConnection() as HttpURLConnection).run {
			connectTimeout = 5000; readTimeout = 5000
			inputStream.readBytes()
		}
		say("٢) أنزّل آخر نسخة من تطبيق LG…")
		val ipk = File(cacheDir, "InArabic-lg.ipk")
		(URL(IPK_URL).openConnection() as HttpURLConnection).run {
			instanceFollowRedirects = true; connectTimeout = 10000; readTimeout = 30000
			inputStream.use { i -> ipk.outputStream().use { o -> i.copyTo(o) } }
		}
		say("٣) أتصل بالتلفزيون…")
		val jsch = JSch()
		jsch.addIdentity("webos", key, null, passphrase.toByteArray())
		val s = jsch.getSession("prisoner", host, 9922)
		s.setConfig("StrictHostKeyChecking", "no")
		s.setConfig("PubkeyAcceptedAlgorithms", "ssh-rsa,rsa-sha2-256,rsa-sha2-512")
		s.setConfig("server_host_key", "ssh-rsa,rsa-sha2-256,rsa-sha2-512,ecdsa-sha2-nistp256,ssh-ed25519")
		s.connect(10000)
		try
		{
			say("٤) أنسخ التطبيق للتلفزيون…")
			val ch = s.openChannel("sftp") as ChannelSftp
			ch.connect(10000)
			try { ch.mkdir("/media/developer/temp") } catch(e: Exception) { }
			ch.put(ipk.absolutePath, "/media/developer/temp/inarabic.ipk")
			ch.disconnect()
			say("٥) أثبّته…")
			val out = exec(s, "luna-send-pub -i -f luna://com.webos.appInstallService/dev/install '{\"id\":\"com.ares.defaultName\",\"ipkUrl\":\"/media/developer/temp/inarabic.ipk\",\"subscribe\":true}'", 60000) { it.contains("\"installed\"") || it.contains("failed", true) }
			if(!out.contains("installed")) throw Exception("التثبيت ما كمل: " + out.takeLast(200))
			exec(s, "rm -f /media/developer/temp/inarabic.ipk", 5000)
			exec(s, "luna-send-pub -n 1 luna://com.webos.applicationManager/launch '{\"id\":\"$APP_ID\"}'", 8000)
			say("✓ ثبتنا «بالعربي» على تلفزيونك وفتحناه. تلقاه بعدين في قائمة التطبيقات.")
		}
		finally { s.disconnect() }
	}

	/** runs a command, returns its output (stops early when [done] says so) */
	private fun exec(s: com.jcraft.jsch.Session, cmd: String, timeoutMs: Long, done: (String) -> Boolean = { false }): String
	{
		val ch = s.openChannel("exec") as ChannelExec
		ch.setCommand(cmd)
		val input = ch.inputStream
		ch.connect(5000)
		val sb = StringBuilder()
		val end = System.currentTimeMillis() + timeoutMs
		val buf = ByteArray(4096)
		while(System.currentTimeMillis() < end)
		{
			while(input.available() > 0) { val n = input.read(buf); if(n < 0) break; sb.append(String(buf, 0, n)) }
			if(ch.isClosed || done(sb.toString())) break
			Thread.sleep(200)
		}
		ch.disconnect()
		return sb.toString()
	}
}
