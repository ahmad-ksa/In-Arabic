// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// The frame you drag over the English subtitles on the live picture: move it from inside, resize from the
// corners. Coordinates are 0..1 of the picture (the view covers exactly the stream picture).

package com.inarabic.caption

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

class ZoneEditorView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs)
{
	/** left, top, right, bottom in 0..1 */
	var zone = RectF(0.05f, 0.62f, 0.95f, 0.97f)
		set(v) { field = RectF(v); invalidate() }
	var onChange: ((RectF) -> Unit)? = null

	private val dp = resources.displayMetrics.density
	private val accent = Color.rgb(159, 211, 180)
	private val dim = Paint().apply { color = Color.argb(140, 0, 0, 0) }
	private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * dp; color = accent }
	private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE; strokeWidth = 1 * dp; color = Color.argb(160, 255, 255, 255); pathEffect = DashPathEffect(floatArrayOf(8 * dp, 6 * dp), 0f)
	}
	private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
	private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13 * dp; textAlign = Paint.Align.CENTER }

	private fun px() = RectF(zone.left * width, zone.top * height, zone.right * width, zone.bottom * height)

	override fun onDraw(canvas: Canvas)
	{
		val r = px()
		val w = width.toFloat(); val h = height.toFloat()
		canvas.drawRect(0f, 0f, w, r.top, dim)
		canvas.drawRect(0f, r.bottom, w, h, dim)
		canvas.drawRect(0f, r.top, r.left, r.bottom, dim)
		canvas.drawRect(r.right, r.top, w, r.bottom, dim)
		canvas.drawRoundRect(r, 10 * dp, 10 * dp, stroke)
		canvas.drawLine(r.left, r.centerY(), r.right, r.centerY(), dash)
		for(c in corners(r)) canvas.drawCircle(c[0], c[1], 9 * dp, handle)
		canvas.drawText("حط الإطار على مكان الترجمة الإنجليزية", r.centerX(), (r.top - 10 * dp).coerceAtLeast(18 * dp), label)
	}

	private fun corners(r: RectF) = listOf(floatArrayOf(r.left, r.top), floatArrayOf(r.right, r.top), floatArrayOf(r.left, r.bottom), floatArrayOf(r.right, r.bottom))

	private var mode = -1 // 0..3 corner, 4 move
	private var lastX = 0f
	private var lastY = 0f

	override fun onTouchEvent(e: MotionEvent): Boolean
	{
		val r = px()
		when(e.actionMasked)
		{
			MotionEvent.ACTION_DOWN ->
			{
				mode = corners(r).indexOfFirst { hypot(it[0] - e.x, it[1] - e.y) < 32 * dp }
				if(mode < 0 && r.contains(e.x, e.y)) mode = 4
				lastX = e.x; lastY = e.y
				return mode >= 0
			}
			MotionEvent.ACTION_MOVE ->
			{
				val dx = (e.x - lastX) / width; val dy = (e.y - lastY) / height
				lastX = e.x; lastY = e.y
				val z = RectF(zone)
				val min = 0.04f
				when(mode)
				{
					4 ->
					{
						val mx = dx.coerceIn(-z.left, 1f - z.right); val my = dy.coerceIn(-z.top, 1f - z.bottom)
						z.offset(mx, my)
					}
					0 -> { z.left = (z.left + dx).coerceIn(0f, z.right - min); z.top = (z.top + dy).coerceIn(0f, z.bottom - min) }
					1 -> { z.right = (z.right + dx).coerceIn(z.left + min, 1f); z.top = (z.top + dy).coerceIn(0f, z.bottom - min) }
					2 -> { z.left = (z.left + dx).coerceIn(0f, z.right - min); z.bottom = (z.bottom + dy).coerceIn(z.top + min, 1f) }
					3 -> { z.right = (z.right + dx).coerceIn(z.left + min, 1f); z.bottom = (z.bottom + dy).coerceIn(z.top + min, 1f) }
					else -> return false
				}
				if(abs(z.left - zone.left) + abs(z.top - zone.top) + abs(z.right - zone.right) + abs(z.bottom - zone.bottom) > 0f)
				{
					zone = z
					onChange?.invoke(RectF(zone))
				}
				return true
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { mode = -1; return true }
		}
		return false
	}
}
