// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// The lens on the phone: a box opened with a quick two-finger tap on the stream. Drag inside it to move it, drag
// a corner to make it bigger or smaller, ✕ to close it. Whatever English is inside is translated at once (by
// CaptionEngine.setLens) and drawn over it, same size and colours, on the phone and the TVs.

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
import kotlin.math.hypot

class LensView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs)
{
	/** the box in 0..1 of this view (= the picture); null when closed */
	var rect: RectF? = null
		private set
	/** called when the box is placed (after a move or resize) or closed (null) */
	var onChange: ((RectF?) -> Unit)? = null

	private val dp = resources.displayMetrics.density
	private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE; strokeWidth = 2.5f * dp; color = Color.rgb(159, 211, 180)
		pathEffect = DashPathEffect(floatArrayOf(10 * dp, 6 * dp), 0f)
	}
	private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(159, 211, 180) }
	private val closeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(183, 28, 28) }
	private val closeX = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2.5f * dp; strokeCap = Paint.Cap.ROUND }

	/** opens the box around a point of this view (pixels) */
	fun open(cx: Float, cy: Float)
	{
		if(width == 0 || height == 0) return
		val hw = 0.2f; val hh = 0.14f
		val x = (cx / width).coerceIn(hw, 1 - hw); val y = (cy / height).coerceIn(hh, 1 - hh)
		rect = RectF(x - hw, y - hh, x + hw, y + hh)
		visibility = VISIBLE
		invalidate()
		onChange?.invoke(rect)
	}

	fun close()
	{
		rect = null
		invalidate()
		onChange?.invoke(null)
	}

	private fun px(r: RectF) = RectF(r.left * width, r.top * height, r.right * width, r.bottom * height)
	private fun closeCenter(p: RectF) = floatArrayOf(p.right, (p.top - 18 * dp).coerceAtLeast(16 * dp))

	override fun onDraw(canvas: Canvas)
	{
		val r = rect ?: return
		val p = px(r)
		canvas.drawRoundRect(p, 8 * dp, 8 * dp, frame)
		for(c in corners(p)) canvas.drawCircle(c[0], c[1], 7 * dp, handle)
		val (cx, cy) = closeCenter(p).let { it[0] to it[1] }
		canvas.drawCircle(cx, cy, 14 * dp, closeBg)
		val k = 5.5f * dp
		canvas.drawLine(cx - k, cy - k, cx + k, cy + k, closeX)
		canvas.drawLine(cx + k, cy - k, cx - k, cy + k, closeX)
	}

	private fun corners(p: RectF) = listOf(floatArrayOf(p.left, p.top), floatArrayOf(p.right, p.top), floatArrayOf(p.left, p.bottom), floatArrayOf(p.right, p.bottom))

	private var mode = 0 // 0 none, 1 move, 2..5 corner
	private var lastX = 0f
	private var lastY = 0f

	override fun onTouchEvent(e: MotionEvent): Boolean
	{
		val r = rect ?: return false
		val p = px(r)
		when(e.actionMasked)
		{
			MotionEvent.ACTION_DOWN ->
			{
				val c = closeCenter(p)
				if(hypot(e.x - c[0], e.y - c[1]) < 26 * dp) { close(); return true }
				val i = corners(p).indexOfFirst { hypot(e.x - it[0], e.y - it[1]) < 30 * dp }
				mode = when
				{
					i >= 0 -> 2 + i
					p.contains(e.x, e.y) -> 1
					else -> return false // outside the box: not ours
				}
				lastX = e.x; lastY = e.y
				return true
			}
			MotionEvent.ACTION_MOVE ->
			{
				if(mode == 0) return false
				val dx = (e.x - lastX) / width; val dy = (e.y - lastY) / height
				lastX = e.x; lastY = e.y
				val min = 0.05f
				when(mode)
				{
					1 -> { val ox = dx.coerceIn(-r.left, 1 - r.right); val oy = dy.coerceIn(-r.top, 1 - r.bottom); r.offset(ox, oy) }
					2 -> { r.left = (r.left + dx).coerceIn(0f, r.right - min); r.top = (r.top + dy).coerceIn(0f, r.bottom - min) }
					3 -> { r.right = (r.right + dx).coerceIn(r.left + min, 1f); r.top = (r.top + dy).coerceIn(0f, r.bottom - min) }
					4 -> { r.left = (r.left + dx).coerceIn(0f, r.right - min); r.bottom = (r.bottom + dy).coerceIn(r.top + min, 1f) }
					5 -> { r.right = (r.right + dx).coerceIn(r.left + min, 1f); r.bottom = (r.bottom + dy).coerceIn(r.top + min, 1f) }
				}
				invalidate()
				return true
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
			{
				if(mode == 0) return false
				mode = 0
				onChange?.invoke(RectF(r)) // placed: read and translate what is inside now
				return true
			}
		}
		return mode != 0
	}
}
