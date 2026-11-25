// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Four dots you drag onto the TV's corners in the camera picture (top-left, top-right, bottom-right, bottom-left).
// Points are kept in 0..1 of the camera picture, which the preview shows whole (16:9, fit-centre).

package com.inarabic.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.inarabic.InArabicPrefs
import kotlin.math.hypot

class CornersView(context: Context) : View(context)
{
	/** x0,y0 … x3,y3 in 0..1 of the camera picture */
	val points = floatArrayOf(0.15f, 0.15f, 0.85f, 0.15f, 0.85f, 0.85f, 0.15f, 0.85f)
	var locked = false
		set(v) { field = v; invalidate() }
	var onChange: (() -> Unit)? = null

	private val dp = resources.displayMetrics.density
	private val accent = Color.rgb(159, 211, 180)
	private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * dp; color = accent }
	private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
	private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = Color.WHITE }

	/** the 16:9 camera picture inside this view (fit-centre) */
	private fun frame(): FloatArray
	{
		val w = width.toFloat(); val h = height.toFloat()
		return if(w / h > 16f / 9f) { val fw = h * 16f / 9f; floatArrayOf((w - fw) / 2, 0f, fw, h) }
		else { val fh = w * 9f / 16f; floatArrayOf(0f, (h - fh) / 2, w, fh) }
	}

	private fun px(i: Int): FloatArray { val f = frame(); return floatArrayOf(f[0] + points[i * 2] * f[2], f[1] + points[i * 2 + 1] * f[3]) }

	override fun onDraw(canvas: Canvas)
	{
		val path = Path()
		for(i in 0 until 4) { val p = px(i); if(i == 0) path.moveTo(p[0], p[1]) else path.lineTo(p[0], p[1]) }
		path.close()
		canvas.drawPath(path, line)
		if(locked) return
		for(i in 0 until 4) { val p = px(i); canvas.drawCircle(p[0], p[1], 12 * dp, dot); canvas.drawCircle(p[0], p[1], 22 * dp, ring) }
	}

	private var drag = -1
	override fun onTouchEvent(e: MotionEvent): Boolean
	{
		if(locked) return false
		val f = frame()
		when(e.actionMasked)
		{
			MotionEvent.ACTION_DOWN -> { drag = (0 until 4).minByOrNull { val p = px(it); hypot(p[0] - e.x, p[1] - e.y) }?.takeIf { val p = px(it); hypot(p[0] - e.x, p[1] - e.y) < 60 * dp } ?: -1; return drag >= 0 }
			MotionEvent.ACTION_MOVE -> if(drag >= 0)
			{
				points[drag * 2] = ((e.x - f[0]) / f[2]).coerceIn(0f, 1f)
				points[drag * 2 + 1] = ((e.y - f[1]) / f[3]).coerceIn(0f, 1f)
				invalidate(); return true
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if(drag >= 0) onChange?.invoke(); drag = -1; return true }
		}
		return false
	}

	fun reset() { floatArrayOf(0.15f, 0.15f, 0.85f, 0.15f, 0.85f, 0.85f, 0.15f, 0.85f).copyInto(points); invalidate() }
	fun save(p: InArabicPrefs) = p.setCameraCorners(points.joinToString(","))
	fun load(p: InArabicPrefs) { p.cameraCorners.split(",").mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 8 }?.forEachIndexed { i, v -> points[i] = v } }
}
