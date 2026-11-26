// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.content.Intent
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.AlertDialog
import android.graphics.Matrix
import android.os.*
import android.view.*
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.databinding.ActivityStreamBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.session.*
import com.metallic.chiaki.touchcontrols.DefaultTouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchpadOnlyFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.onEach
import kotlin.math.min

private sealed class DialogContents
private object StreamQuitDialog: DialogContents()
private object CreateErrorDialog: DialogContents()
private object PinRequestDialog: DialogContents()

class StreamActivity : AppCompatActivity()
{
	companion object
	{
		const val EXTRA_CONNECT_INFO = "connect_info"
		private const val HIDE_UI_TIMEOUT_MS = 2000L
	}

	private lateinit var viewModel: StreamViewModel
	private lateinit var binding: ActivityStreamBinding
	private lateinit var insetsController: WindowInsetsControllerCompat

	private val uiVisibilityHandler = Handler(Looper.getMainLooper())

	// In Arabic: subtitle reading + translation while the stream runs
	private var captionEngine: com.inarabic.caption.CaptionEngine? = null

	private fun startCaptions()
	{
		if(captionEngine != null) return
		val iaPrefs = com.inarabic.InArabicPrefs(this)
		val overlay = binding.iaOverlay
		overlay.fitToTv = false
		overlay.visibility = if(iaPrefs.showOnPhone) View.VISIBLE else View.GONE
		// the phone draws what the TVs draw: every message the TVs get goes to its own caption view too
		com.inarabic.link.LinkServer.local = { msg ->
			overlay.post { try { overlay.handle(org.json.JSONObject(msg)) } catch(e: Exception) { } }
		}
		captionEngine = com.inarabic.caption.CaptionEngine(binding.surfaceView, iaPrefs) { text ->
			binding.iaCaption.visibility = if(text.isNullOrEmpty()) View.GONE else View.VISIBLE
		}.also {
			it.onNotice = { msg -> notice(msg, 8000) }
			it.onSystemUi = { on -> if(on) notice("شاشة البلايستيشن ظاهرة، الترجمة متوقفة مؤقتاً", 0) else binding.iaNotice.visibility = View.GONE }
			it.start()
		}
		// tap the Arabic line on the phone to correct it: remembered, and replaced on the TV at once
		binding.iaCaption.setOnClickListener {
			val cur = captionEngine?.current ?: return@setOnClickListener
			val edit = EditText(this).apply { setText(cur.arabic); setSelection(text.length) }
			MaterialAlertDialogBuilder(this)
				.setTitle("تصحيح الترجمة")
				.setMessage(cur.english)
				.setView(edit)
				.setPositiveButton("احفظ") { _, _ -> edit.text.toString().trim().takeIf { it.isNotEmpty() }?.let { captionEngine?.correct(it) } }
				.setNegativeButton("إلغاء", null)
				.show()
		}
	}

	private fun stopCaptions()
	{
		captionEngine?.stop()
		captionEngine = null
		com.inarabic.link.LinkServer.local = null
		binding.iaCaption.visibility = View.GONE
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)

		val connectInfo = IntentCompat.getParcelableExtra(intent, EXTRA_CONNECT_INFO, ConnectInfo::class.java)
		if(connectInfo == null)
		{
			finish()
			return
		}

		viewModel = ViewModelProvider(this, viewModelFactory {
			StreamViewModel(application, connectInfo)
		})[StreamViewModel::class.java]

		viewModel.input.observe(this)

		binding = ActivityStreamBinding.inflate(layoutInflater)
		setContentView(binding.root)

		WindowCompat.setDecorFitsSystemWindows(window, false)
		insetsController = WindowCompat.getInsetsController(window, window.decorView)
		insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

		ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
			val systemBars = insets.isVisible(WindowInsetsCompat.Type.systemBars())
			if(systemBars)
				showOverlay()
			else
				hideOverlay()
			insets
		}

		viewModel.onScreenControlsEnabled.observe(this, Observer {
			if(binding.onScreenControlsSwitch.isChecked != it)
				binding.onScreenControlsSwitch.isChecked = it
			if(binding.onScreenControlsSwitch.isChecked)
				binding.touchpadOnlySwitch.isChecked = false
		})
		binding.onScreenControlsSwitch.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setOnScreenControlsEnabled(isChecked)
			showOverlay()
		}

		viewModel.touchpadOnlyEnabled.observe(this, Observer {
			if(binding.touchpadOnlySwitch.isChecked != it)
				binding.touchpadOnlySwitch.isChecked = it
			if(binding.touchpadOnlySwitch.isChecked)
				binding.onScreenControlsSwitch.isChecked = false
		})
		binding.touchpadOnlySwitch.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setTouchpadOnlyEnabled(isChecked)
			showOverlay()
		}

		// In Arabic: the game fills the whole phone screen, round corners and camera cut-out included;
		// the picture mode you pick last is kept (stretch by default: nothing of the picture is cut off)
		if(android.os.Build.VERSION.SDK_INT >= 28)
			window.attributes = window.attributes.apply {
				layoutInDisplayCutoutMode = if(android.os.Build.VERSION.SDK_INT >= 30) android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
					else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
			}
		val modePrefs = getSharedPreferences("ia_stream", MODE_PRIVATE)
		binding.displayModeToggle.check(when(modePrefs.getString("mode", "stretch"))
		{
			"fit" -> R.id.display_mode_normal_button
			"zoom" -> R.id.display_mode_zoom_button
			else -> R.id.display_mode_stretch_button
		})
		binding.displayModeToggle.addOnButtonCheckedListener { _, id, checked ->
			if(checked) modePrefs.edit().putString("mode", when(id) { R.id.display_mode_normal_button -> "fit"; R.id.display_mode_zoom_button -> "zoom"; else -> "stretch" }).apply()
			adjustStreamViewAspect()
			showOverlay()
		}

		//viewModel.session.attachToTextureView(textureView)
		// In Arabic: game sound on the phone on/off
		val iaPrefs = com.inarabic.InArabicPrefs(this)
		com.metallic.chiaki.lib.Session.setAudioMuted(!iaPrefs.phoneAudio)
		binding.iaAudioSwitch.isChecked = iaPrefs.phoneAudio
		binding.iaAudioSwitch.setOnCheckedChangeListener { _, on ->
			iaPrefs.phoneAudio = on
			com.metallic.chiaki.lib.Session.setAudioMuted(!on)
			showOverlay()
		}
		watchNetwork()
		setupZoneEditor(iaPrefs)
		// In Arabic: no on-screen game buttons (you play with the controller); one clear menu button instead
		viewModel.setOnScreenControlsEnabled(false)
		viewModel.setTouchpadOnlyEnabled(false)
		binding.iaMenu.setOnClickListener { showIaMenu(iaPrefs) }

		viewModel.session.attachToSurfaceView(binding.surfaceView)
		viewModel.session.state.observe(this, Observer { this.stateChanged(it) })
		adjustStreamViewAspect()

		if(Preferences(this).rumbleEnabled)
		{
			val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
			viewModel.session.rumbleState.observe(this, Observer {
				val amplitude = min(255, (it.left.toInt() + it.right.toInt()) / 2)
				vibrator.cancel()
				if(amplitude == 0)
					return@Observer
				if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
					vibrator.vibrate(VibrationEffect.createOneShot(1000, amplitude))
				else
					vibrator.vibrate(1000)
			})
		}
	}

	private var controlsJob: Job? = null

	override fun onAttachFragment(fragment: Fragment)
	{
		super.onAttachFragment(fragment)
		if(fragment is TouchControlsFragment)
		{
			controlsJob?.cancel()
			controlsJob = fragment.controllerState
				.onEach { viewModel.input.touchControllerState = it }
				.launchIn(lifecycleScope)
			fragment.onScreenControlsEnabled = viewModel.onScreenControlsEnabled
			if(fragment is TouchpadOnlyFragment)
				fragment.touchpadOnlyEnabled = viewModel.touchpadOnlyEnabled
		}
	}

	override fun onResume()
	{
		super.onResume()
		hideSystemUI()
		viewModel.session.resume()
	}

	override fun onPause()
	{
		super.onPause()
		viewModel.session.pause()
	}

	override fun onDestroy()
	{
		stopCaptions()
		super.onDestroy()
		controlsJob?.cancel()
	}

	// ---- In Arabic: notices, network watch, reconnecting by itself ----
	private val noticeHide = Runnable { binding.iaNotice.visibility = View.GONE }
	private fun notice(text: String, ms: Long = 4000)
	{
		binding.iaNotice.text = text
		binding.iaNotice.visibility = View.VISIBLE
		uiVisibilityHandler.removeCallbacks(noticeHide)
		if(ms > 0) uiVisibilityHandler.postDelayed(noticeHide, ms)
	}

	private var weakCount = 0
	private var weakShownAt = 0L
	private fun watchNetwork()
	{
		lifecycleScope.launch {
			while(true)
			{
				kotlinx.coroutines.delay(2000)
				val s = viewModel.session.session ?: continue
				if(viewModel.session.state.value != StreamStateConnected) { weakCount = 0; continue }
				val loss = try { s.stats()[1] } catch(e: Throwable) { 0.0 }
				weakCount = if(loss > 0.06) weakCount + 1 else 0
				if(weakCount >= 3 && System.currentTimeMillis() - weakShownAt > 60000)
				{
					weakShownAt = System.currentTimeMillis()
					weakCount = 0
					showWeakNetwork()
				}
			}
		}
	}

	private fun showWeakNetwork()
	{
		val prefs = Preferences(this)
		val lower = Preferences.resolutionAll.toList().let { all -> all.getOrNull(all.indexOf(prefs.resolution) - 1) }
		val snack = com.google.android.material.snackbar.Snackbar.make(binding.root, "الشبكة ضعيفة والصورة تتقطع. قرّب الجوال من الراوتر", 8000)
		if(lower != null)
			snack.setAction("خفّض الجودة") {
				prefs.resolution = lower
				val info = viewModel.session.connectInfo.copy(videoProfile = prefs.videoProfile)
				startActivity(Intent(this, StreamActivity::class.java).putExtra(EXTRA_CONNECT_INFO, info))
				finish()
			}
		snack.show()
	}

	/** In Arabic: the reading frames on the live picture (dialogue / side text); the TVs show them while they move */
	private fun setupZoneEditor(prefs: com.inarabic.InArabicPrefs)
	{
		val z = binding.iaZone
		fun close() { z.visibility = View.GONE; binding.iaZoneBar.visibility = View.GONE; com.inarabic.link.LinkServer.sendZones(emptyList()) }
		binding.iaModeButton.setOnClickListener {
			val values = arrayOf("dialog", "popups", "menus", "all")
			val names = arrayOf("الحوارات فقط", "الحوارات + التعليمات (النص الأبيض)", "الحوارات + الأزرار والقوائم والمهام", "كل شي على الشاشة (حتى اللوحات)")
			MaterialAlertDialogBuilder(this).setTitle("وش أترجم؟")
				.setSingleChoiceItems(names, values.indexOf(prefs.mode).coerceAtLeast(0)) { d, i ->
					prefs.mode = values[i]; d.dismiss(); notice("الترجمة: " + names[i], 2500)
				}.show()
		}
		binding.iaZoneButton.setOnClickListener {
			z.set(prefs.zones)
			z.visibility = View.VISIBLE; binding.iaZoneBar.visibility = View.VISIBLE
			com.inarabic.link.LinkServer.sendZones(z.zones)
			notice("المس مربع عشان تختاره، واسحبه أو كبّره من زواياه", 4000)
			hideOverlay()
		}
		z.onChange = { com.inarabic.link.LinkServer.sendZones(it) }
		binding.iaZoneAddDialog.setOnClickListener { z.add(true) }
		binding.iaZoneAddSide.setOnClickListener { z.add(false); prefs.dialogOnly = false }
		binding.iaZoneDelete.setOnClickListener { z.removeSelected() }
		binding.iaZoneReset.setOnClickListener { z.reset(); prefs.dialogOnly = true; notice("الحوار اللي في النص تحت بس. اضغط «احفظ»", 3000) }
		binding.iaZoneSave.setOnClickListener {
			if(z.zones.none { it.dialog }) { notice("لازم يكون فيه مربع حوار واحد على الأقل", 3000); return@setOnClickListener }
			prefs.zones = z.zones.toList()
			captionEngine?.setZones(z.zones.toList())
			close(); notice("تم حفظ مربعات القراءة ✓", 2500)
		}
		binding.iaZoneCancel.setOnClickListener { close() }
		binding.iaZoneAuto.setOnClickListener {
			val engine = captionEngine
			if(engine == null) { notice("ابدأ البث أول", 2500); return@setOnClickListener }
			notice("أدوّر على الحوار… خلّ مشهد فيه كلام على الشاشة", 0)
			engine.autoZone { found ->
				if(found == null) notice("ما لقينا حوار. شغّل مشهد فيه ترجمة وجرّب مرة ثانية", 4000)
				else { z.setSelectedRect(found); notice("لقيناه ✓ اضغط «احفظ»", 3000) }
			}
		}
	}

	private fun hideGameButtons()
	{
		supportFragmentManager.findFragmentById(R.id.controlsFragment)?.view?.visibility = View.GONE
		supportFragmentManager.findFragmentById(R.id.touchpadOnlyFragment)?.view?.visibility = View.GONE
	}

	override fun onPostResume() { super.onPostResume(); hideGameButtons() }

	/** In Arabic: everything for the translation in one list */
	private fun showIaMenu(prefs: com.inarabic.InArabicPrefs)
	{
		val modeNames = mapOf("dialog" to "الحوارات فقط", "popups" to "الحوارات + التعليمات", "menus" to "الحوارات + الأزرار والقوائم", "all" to "كل شي")
		val items = arrayOf(
			"📐  مربعات القراءة (وين أقرأ الترجمة)",
			"🔤  وش أترجم: " + (modeNames[prefs.mode] ?: ""),
			(if(prefs.phoneAudio) "🔊  صوت اللعبة في الجوال: شغال" else "🔇  صوت اللعبة في الجوال: مطفي"),
			"✎  صحّح الترجمة اللي على الشاشة",
			"⚙  إعدادات الترجمة",
			"⏹  أوقف البث واطلع")
		MaterialAlertDialogBuilder(this).setTitle("بالعربي")
			.setItems(items) { _, i ->
				when(i)
				{
					0 -> binding.iaZoneButton.performClick()
					1 -> binding.iaModeButton.performClick()
					2 -> binding.iaAudioSwitch.isChecked = !binding.iaAudioSwitch.isChecked
					3 -> if(captionEngine?.current == null) notice("ما فيه ترجمة على الشاشة الحين", 2500) else binding.iaCaption.performClick()
					4 -> startActivity(Intent(this, com.inarabic.InArabicSettingsActivity::class.java))
					5 -> finish()
				}
			}.show()
	}

	private var autoTries = 0
	/** quit reasons worth trying again by itself: the network, not a setting */
	private fun reconnectable(r: Int) = r in setOf(2, 3, 7, 8, 9, 10, 11)

	private fun reconnect()
	{
		viewModel.session.shutdown()
		viewModel.session.resume()
	}

	private val hideSystemUIRunnable = Runnable { hideSystemUI() }

	private fun showOverlay()
	{
		binding.overlay.isVisible = true
		binding.overlay.animate()
			.alpha(1.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.alpha = 1.0f
				}
			})
		uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
		uiVisibilityHandler.postDelayed(hideSystemUIRunnable, HIDE_UI_TIMEOUT_MS)
	}

	private fun hideOverlay()
	{
		binding.overlay.animate()
			.alpha(0.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.isGone = true
				}
			})
	}

	override fun onWindowFocusChanged(hasFocus: Boolean)
	{
		super.onWindowFocusChanged(hasFocus)
		if(hasFocus)
			hideSystemUI()
	}

	private fun hideSystemUI()
	{
		insetsController.hide(WindowInsetsCompat.Type.systemBars())
	}

	private var dialogContents: DialogContents? = null
	private var dialog: AlertDialog? = null
		set(value)
		{
			field = value
			if(value == null)
				dialogContents = null
		}

	private fun stateChanged(state: StreamState)
	{
		binding.progressBar.visibility = if(state == StreamStateConnecting) View.VISIBLE else View.GONE
		if(state == StreamStateConnected) startCaptions() else if(state is StreamStateQuit) stopCaptions()
		if(state == StreamStateConnected && autoTries > 0) { autoTries = 0; notice("رجع البث ✓", 2500) }

		when(state)
		{
			is StreamStateQuit ->
			{
				if(state.reason.isError && com.inarabic.InArabicPrefs(this).autoReconnect && reconnectable(state.reason.value) && autoTries < 4)
				{
					autoTries++
					notice("انقطع البث. نرجع نتصل… (محاولة $autoTries)", 0)
					uiVisibilityHandler.postDelayed({ reconnect() }, 2500L * autoTries)
					return
				}
				if(dialogContents != StreamQuitDialog)
				{
					if(state.reason.isError)
					{
						dialog?.dismiss()
						val reasonStr = state.reasonString
						val dialog = MaterialAlertDialogBuilder(this)
							.setMessage(getString(R.string.alert_message_session_quit, state.reason.toString())
									+ (if(reasonStr != null) "\n$reasonStr" else ""))
							.setPositiveButton(R.string.action_reconnect) { _, _ ->
								dialog = null
								reconnect()
							}
							.setOnCancelListener {
								dialog = null
								finish()
							}
							.setNegativeButton(R.string.action_quit_session) { _, _ ->
								dialog = null
								finish()
							}
							.create()
						dialogContents = StreamQuitDialog
						dialog.show()
					}
					else
						finish()
				}
			}

			is StreamStateCreateError ->
			{
				if(dialogContents != CreateErrorDialog)
				{
					dialog?.dismiss()
					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(getString(R.string.alert_message_session_create_error, state.error.errorCode.toString()))
						.setOnDismissListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ -> }
						.create()
					dialogContents = CreateErrorDialog
					dialog.show()
				}
			}

			is StreamStateLoginPinRequest ->
			{
				if(dialogContents != PinRequestDialog)
				{
					dialog?.dismiss()

					val view = layoutInflater.inflate(R.layout.dialog_login_pin, null)
					val pinEditText = view.findViewById<EditText>(R.id.pinEditText)

					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(
							if(state.pinIncorrect)
								R.string.alert_message_login_pin_request_incorrect
							else
								R.string.alert_message_login_pin_request)
						.setView(view)
						.setPositiveButton(R.string.action_login_pin_connect) { _, _ ->
							dialog = null
							viewModel.session.setLoginPin(pinEditText.text.toString())
						}
						.setOnCancelListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ ->
							dialog = null
							finish()
						}
						.create()
					dialogContents = PinRequestDialog
					dialog.show()
				}
			}
			else ->{}
		}
	}

	private fun adjustTextureViewAspect(textureView: TextureView)
	{
		val trans = TextureViewTransform(viewModel.session.connectInfo.videoProfile, textureView)
		val resolution = trans.resolutionFor(TransformMode.fromButton(binding.displayModeToggle.checkedButtonId))
		Matrix().also {
			textureView.getTransform(it)
			it.setScale(resolution.width / trans.viewWidth, resolution.height / trans.viewHeight)
			it.postTranslate((trans.viewWidth - resolution.width) * 0.5f, (trans.viewHeight - resolution.height) * 0.5f)
			textureView.setTransform(it)
		}
	}

	private fun adjustSurfaceViewAspect()
	{
		val videoProfile = viewModel.session.connectInfo.videoProfile
		binding.aspectRatioLayout.aspectRatio = videoProfile.width.toFloat() / videoProfile.height.toFloat()
		binding.aspectRatioLayout.mode = TransformMode.fromButton(binding.displayModeToggle.checkedButtonId)
	}

	private fun adjustStreamViewAspect() = adjustSurfaceViewAspect()

	override fun dispatchKeyEvent(event: KeyEvent) = viewModel.input.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
	override fun onGenericMotionEvent(event: MotionEvent) = viewModel.input.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)
}

enum class TransformMode
{
	FIT,
	STRETCH,
	ZOOM;

	companion object
	{
		fun fromButton(displayModeButtonId: Int)
			= when (displayModeButtonId)
			{
				R.id.display_mode_stretch_button -> STRETCH
				R.id.display_mode_zoom_button -> ZOOM
				else -> FIT
			}
	}
}

class TextureViewTransform(private val videoProfile: ConnectVideoProfile, private val textureView: TextureView)
{
	private val contentWidth : Float get() = videoProfile.width.toFloat()
	private val contentHeight : Float get() = videoProfile.height.toFloat()
	val viewWidth : Float get() = textureView.width.toFloat()
	val viewHeight : Float get() = textureView.height.toFloat()
	private val contentAspect : Float get() =  contentHeight / contentWidth

	fun resolutionFor(mode: TransformMode): Resolution
		= when(mode)
		{
			TransformMode.STRETCH -> strechedResolution
			TransformMode.ZOOM -> zoomedResolution
			TransformMode.FIT -> normalResolution
		}

	private val strechedResolution get() = Resolution(viewWidth, viewHeight)

	private val zoomedResolution get() =
		if(viewHeight > viewWidth * contentAspect)
		{
			val zoomFactor = viewHeight / contentHeight
			Resolution(contentWidth * zoomFactor, viewHeight)
		}
		else
		{
			val zoomFactor = viewWidth / contentWidth
			Resolution(viewWidth, contentHeight * zoomFactor)
		}

	private val normalResolution get() =
		if(viewHeight > viewWidth * contentAspect)
			Resolution(viewWidth, viewWidth * contentAspect)
		else
			Resolution(viewHeight / contentAspect, viewHeight)
}


data class Resolution(val width: Float, val height: Float)
