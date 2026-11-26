// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.*
import com.metallic.chiaki.common.ext.putRevealExtra
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.databinding.ActivityMainBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.DiscoveryHost
import com.metallic.chiaki.manualconsole.EditManualConsoleActivity
import com.metallic.chiaki.regist.RegistActivity
import com.metallic.chiaki.settings.SettingsActivity
import com.metallic.chiaki.stream.StreamActivity

class MainActivity : AppCompatActivity()
{
	private lateinit var viewModel: MainViewModel

	private lateinit var binding: ActivityMainBinding
	private var discoveryMenuItem: MenuItem? = null

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		if(savedInstanceState == null && !com.inarabic.SetupActivity.done(this)) startActivity(Intent(this, com.inarabic.SetupActivity::class.java))
		com.inarabic.link.LinkServer.start(this) // TVs can connect as soon as the app is open
		// «بالعربي» already open on an LG TV but not linked (the phone's address changed): give it the address
		com.inarabic.lg.LgKeepAwake.linkScreens(this, foregroundOnly = true)
		binding = ActivityMainBinding.inflate(layoutInflater)
		setContentView(binding.root)

		title = ""
		setSupportActionBar(binding.toolbar)

		binding.floatingActionButton.setOnClickListener {
			expandFloatingActionButton(!binding.floatingActionButton.isExpanded)
		}
		binding.floatingActionButtonDialBackground.setOnClickListener {
			expandFloatingActionButton(false)
		}

		binding.addManualButton.setOnClickListener { addManualConsole() }
		binding.addManualLabelButton.setOnClickListener { addManualConsole() }

		binding.registerButton.setOnClickListener { showRegistration() }
		binding.registerLabelButton.setOnClickListener { showRegistration() }

		setupBackNavigation()

		viewModel = ViewModelProvider(this, viewModelFactory { MainViewModel(getDatabase(this), Preferences(this)) })
			.get(MainViewModel::class.java)

		val recyclerViewAdapter = DisplayHostRecyclerViewAdapter(this::hostTriggered, this::wakeupHost, this::editHost, this::deleteHost)
		binding.hostsRecyclerView.adapter = recyclerViewAdapter
		binding.hostsRecyclerView.layoutManager = LinearLayoutManager(this)
		viewModel.displayHosts.observe(this, Observer {
			val top = binding.hostsRecyclerView.computeVerticalScrollOffset() == 0
			recyclerViewAdapter.hosts = it
			if(top)
				binding.hostsRecyclerView.scrollToPosition(0)
			updateEmptyInfo()
		})

		viewModel.discoveryActive.observe(this, Observer { active ->
			discoveryMenuItem?.let { updateDiscoveryMenuItem(it, active) }
			updateEmptyInfo()
		})
	}

	private fun updateEmptyInfo()
	{
		if(viewModel.displayHosts.value?.isEmpty() ?: true)
		{
			binding.emptyInfoLayout.visibility = View.VISIBLE
			val discoveryActive = viewModel.discoveryActive.value ?: false
			binding.emptyInfoImageView.setImageResource(if(discoveryActive) R.drawable.ic_discover_on else R.drawable.ic_discover_off)
			binding.emptyInfoTextView.setText(if(discoveryActive) R.string.display_hosts_empty_discovery_on_info else R.string.display_hosts_empty_discovery_off_info)
		}
		else
			binding.emptyInfoLayout.visibility = View.GONE
	}

	private fun expandFloatingActionButton(expand: Boolean)
	{
		binding.floatingActionButton.isExpanded = expand
		binding.floatingActionButton.isActivated = binding.floatingActionButton.isExpanded
	}

	override fun onStart()
	{
		super.onStart()
		viewModel.discoveryManager.resume()
	}

	override fun onStop()
	{
		super.onStop()
		viewModel.discoveryManager.pause()
	}

	private fun setupBackNavigation()
	{
		onBackPressedDispatcher.addCallback(this) {
			if(binding.floatingActionButton.isExpanded)
			{
				expandFloatingActionButton(false)
			}
			else
			{
				isEnabled = false
				onBackPressedDispatcher.onBackPressed()
			}
		}
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean
	{
		menuInflater.inflate(R.menu.main, menu)
		val discoveryItem = menu.findItem(R.id.action_discover)
		discoveryMenuItem = discoveryItem
		val discoveryActive = viewModel.discoveryActive.value ?: false
		updateDiscoveryMenuItem(discoveryItem, discoveryActive)
		return true
	}

	private fun updateDiscoveryMenuItem(item: MenuItem, active: Boolean)
	{
		item.isChecked = active
		item.setIcon(if(active) R.drawable.ic_discover_on else R.drawable.ic_discover_off)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when(item.itemId)
	{
		R.id.action_discover ->
		{
			viewModel.discoveryManager.active = !(viewModel.discoveryActive.value ?: false)
			true
		}

		R.id.action_xbox ->
		{
			MaterialAlertDialogBuilder(this).setTitle("جهّز الإكس بوكس")
				.setMessage("مرة وحدة في الإكس بوكس: زر Xbox ← Profile & system ← Settings ← Devices & connections ← Remote features ← فعّل Enable remote features، واختر Sleep في Power options.\n\nبعدها تنفتح صفحة إكس بوكس الرسمية: سجّل بحساب مايكروسوفت اللي على الإكس بوكس، واختر جهازك. وصّل يد الإكس بوكس بالجوال بالبلوتوث إذا طلبت الصفحة يد.")
				.setPositiveButton("افتح صفحة إكس بوكس") { _, _ -> startActivity(Intent(this, com.inarabic.xbox.XboxActivity::class.java)) }
				.setNegativeButton("إلغاء", null).show()
			true
		}

		R.id.action_camera ->
		{
			startActivity(Intent(this, com.inarabic.camera.CameraActivity::class.java))
			true
		}

		R.id.action_lg ->
		{
			startActivity(Intent(this, com.inarabic.lg.LgInstallActivity::class.java))
			true
		}

		R.id.action_setup ->
		{
			startActivity(Intent(this, com.inarabic.SetupActivity::class.java))
			true
		}

		R.id.action_pair ->
		{
			com.inarabic.link.Pairing.scan(this)
			true
		}

		R.id.action_inarabic ->
		{
			startActivity(Intent(this, com.inarabic.InArabicSettingsActivity::class.java))
			true
		}

		R.id.action_settings ->
		{
			Intent(this, SettingsActivity::class.java).also {
				startActivity(it)
			}
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	private fun addManualConsole()
	{
		Intent(this, EditManualConsoleActivity::class.java).also {
			it.putRevealExtra(binding.addManualButton, binding.rootLayout)
			startActivity(it, ActivityOptions.makeSceneTransitionAnimation(this).toBundle())
		}
	}

	private fun showRegistration()
	{
		Intent(this, RegistActivity::class.java).also {
			it.putRevealExtra(binding.registerButton, binding.rootLayout)
			startActivity(it, ActivityOptions.makeSceneTransitionAnimation(this).toBundle())
		}
	}

	private fun hostTriggered(host: DisplayHost)
	{
		val registeredHost = host.registeredHost
		if(registeredHost != null)
		{
			fun connect() {
				com.inarabic.GameContext.running = (host as? DiscoveredDisplayHost)?.discoveredHost?.runningAppName
				val connectInfo = ConnectInfo(host.isPS5, host.host, registeredHost.rpRegistKey, registeredHost.rpKey, Preferences(this).videoProfile)
				Intent(this, StreamActivity::class.java).let {
					it.putExtra(StreamActivity.EXTRA_CONNECT_INFO, connectInfo)
					startActivity(it)
				}
			}

			if(host is DiscoveredDisplayHost && host.discoveredHost.state == DiscoveryHost.State.STANDBY)
			{
				// In Arabic: wake it up and connect by itself as soon as it is ready
				wakeupHost(host)
				var waiting = true
				val dlg = MaterialAlertDialogBuilder(this)
					.setMessage("البلايستيشن نايم، جاري تشغيله…")
					.setNegativeButton(R.string.action_connect_cancel_connect) { _, _ -> waiting = false }
					.setNeutralButton(R.string.action_connect_immediately) { _, _ -> waiting = false; connect() }
					.setCancelable(false)
					.show()
				val started = System.currentTimeMillis()
				val handler = android.os.Handler(mainLooper)
				fun check()
				{
					if(!waiting) return
					val now = viewModel.displayHosts.value?.firstOrNull { it is DiscoveredDisplayHost && it.host == host.host } as? DiscoveredDisplayHost
					when
					{
						now?.discoveredHost?.state == DiscoveryHost.State.READY -> { waiting = false; dlg.dismiss(); handler.postDelayed({ connect() }, 1500) }
						System.currentTimeMillis() - started > 45000 ->
						{
							waiting = false; dlg.dismiss()
							MaterialAlertDialogBuilder(this).setMessage("البلايستيشن ما صحى. شغّله من زره أو من يد التحكم، وجرّب مرة ثانية.")
								.setPositiveButton("حسناً", null).show()
						}
						else ->
						{
							if((System.currentTimeMillis() - started) % 10000 < 1000) wakeupHost(host)
							handler.postDelayed({ check() }, 1000)
						}
					}
				}
				handler.postDelayed({ check() }, 1000)
			}
			else
				connect()
		}
		else
		{
			Intent(this, RegistActivity::class.java).let {
				it.putExtra(RegistActivity.EXTRA_HOST, host.host)
				it.putExtra(RegistActivity.EXTRA_BROADCAST, false)
				if(host is ManualDisplayHost)
					it.putExtra(RegistActivity.EXTRA_ASSIGN_MANUAL_HOST_ID, host.manualHost.id)
				startActivity(it)
			}
		}
	}

	private fun wakeupHost(host: DisplayHost)
	{
		val registeredHost = host.registeredHost ?: return
		viewModel.discoveryManager.sendWakeup(host.host, registeredHost.rpRegistKey, registeredHost.target.isPS5)
	}

	private fun editHost(host: DisplayHost)
	{
		if(host !is ManualDisplayHost)
			return
		Intent(this, EditManualConsoleActivity::class.java).also {
			it.putExtra(EditManualConsoleActivity.EXTRA_MANUAL_HOST_ID, host.manualHost.id)
			startActivity(it)
		}
	}

	private fun deleteHost(host: DisplayHost)
	{
		if(host !is ManualDisplayHost)
			return
		MaterialAlertDialogBuilder(this)
			.setMessage(getString(R.string.alert_message_delete_manual_host, host.manualHost.host))
			.setPositiveButton(R.string.action_delete) { _, _ ->
				viewModel.deleteManualHost(host.manualHost)
			}
			.setNegativeButton(R.string.action_keep) { _, _ -> }
			.create()
			.show()
	}
}
