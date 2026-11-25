// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Camera mode: no Remote Play at all. The phone (on a stand) films the TV; you put the four dots on the TV's
// corners once; each camera frame is straightened to a flat 16:9 picture and read like the stream.

package com.inarabic.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.inarabic.InArabicPrefs
import com.inarabic.caption.CaptionEngine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class CameraActivity : AppCompatActivity()
{
	private lateinit var preview: PreviewView
	private lateinit var corners: CornersView
	private lateinit var status: TextView
	private lateinit var go: MaterialButton
	private val latest = AtomicReference<Bitmap?>(null)
	private val analysis = Executors.newSingleThreadExecutor()
	private var engine: CaptionEngine? = null

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		val dp = resources.displayMetrics.density
		val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
		preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
		corners = CornersView(this)
		root.addView(preview, FrameLayout.LayoutParams(-1, -1))
		root.addView(corners, FrameLayout.LayoutParams(-1, -1))
		val bar = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
			setBackgroundResource(com.metallic.chiaki.R.drawable.ia_notice_bg); setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
		}
		status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; text = "حط النقاط الأربع على زوايا شاشة التلفزيون" }
		go = MaterialButton(this).apply { text = "ابدأ الترجمة"; cornerRadius = (99 * dp).toInt() }
		val reset = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply { text = "رجّع الزوايا"; setTextColor(Color.WHITE) }
		bar.addView(status, LinearLayout.LayoutParams(0, -2, 1f)); bar.addView(reset); bar.addView(go)
		root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins((12 * dp).toInt(), 0, (12 * dp).toInt(), (12 * dp).toInt()) })
		setContentView(root)

		val prefs = InArabicPrefs(this)
		corners.load(prefs)
		reset.setOnClickListener { corners.reset(); corners.save(prefs) }
		corners.onChange = { corners.save(prefs) }
		go.setOnClickListener {
			if(engine == null) start(prefs) else stop()
		}

		if(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
		else requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
	}

	override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray)
	{
		super.onRequestPermissionsResult(requestCode, permissions, grantResults)
		if(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera()
		else status.text = "اسمح بالكاميرا من إعدادات الجوال ← التطبيقات ← بالعربي"
	}

	private fun openCamera()
	{
		val future = ProcessCameraProvider.getInstance(this)
		future.addListener({
			val provider = future.get()
			val pv = Preview.Builder().setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9).build().also { it.setSurfaceProvider(preview.surfaceProvider) }
			val an = ImageAnalysis.Builder().setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)
				.setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
				.setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
			an.setAnalyzer(analysis) { img ->
				try
				{
					var b = img.toBitmap()
					val rot = img.imageInfo.rotationDegrees
					if(rot != 0) { val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true); b.recycle(); b = r }
					latest.getAndSet(b)?.recycle()
				}
				catch(e: Exception) { }
				finally { img.close() }
			}
			try { provider.unbindAll(); provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, an) }
			catch(e: Exception) { status.text = "ما قدرنا نفتح الكاميرا" }
		}, ContextCompat.getMainExecutor(this))
	}

	/** the TV picture inside the camera frame, straightened to 1280×720 */
	private fun straight(): Bitmap?
	{
		val cam = latest.getAndSet(null) ?: return null
		try
		{
			// corners are in 0..1 of the camera picture (the preview shows the whole picture, fit-centre)
			val q = corners.points
			val src = FloatArray(8) { i -> if(i % 2 == 0) q[i] * cam.width else q[i] * cam.height }
			val w = 1280f; val h = 720f
			val dst = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
			val m = Matrix(); m.setPolyToPoly(src, 0, dst, 0, 4)
			val out = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
			android.graphics.Canvas(out).drawBitmap(cam, m, Paint(Paint.FILTER_BITMAP_FLAG))
			return out
		}
		finally { cam.recycle() }
	}

	private fun start(prefs: InArabicPrefs)
	{
		corners.locked = true
		go.text = "أوقف"
		status.text = "الترجمة شغالة. خل الجوال ثابت ومتجه للتلفزيون"
		engine = CaptionEngine(this, prefs, { straight() }, { }).also { it.start() }
	}

	private fun stop()
	{
		engine?.stop(); engine = null
		corners.locked = false
		go.text = "ابدأ الترجمة"
		status.text = "حط النقاط الأربع على زوايا شاشة التلفزيون"
	}

	override fun onDestroy()
	{
		stop()
		analysis.shutdown()
		super.onDestroy()
	}
}
