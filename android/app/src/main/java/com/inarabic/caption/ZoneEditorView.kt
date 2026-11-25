// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// The reading frames you place on the live picture: green = dialogue (the Arabic is drawn over it),
// amber = side text (tasks, prompts, messages). Tap a frame to pick it, drag it from inside, resize it from the
// corners. Coordinates are 0..1 of the picture (the view covers exactly the stream picture).

package com.inarabic.caption

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.inarabic.InArabicPrefs
import kotlin.math.hypot

class ZoneEditorView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs)
{
	val zones = ArrayList<InArabicPrefs.Zone>()
	var selected = 0
		private set
	var onChange: ((List<InArabicPrefs.Zone>) -> Unit)? = null

	fun set(list: List<InArabicPrefs.Zone>)
	{
		zones.clear(); list.forEach { zones += InArabicPrefs.Zone(RectF(it.r), it.dialog) }
		selected = 0; invalidate()
	}

	fun add(dialog: Boolean)
	{
		zones += InArabicPrefs.Zone(if(dialog) RectF(0.15f, 0.7f, 0.85f, 0.92f) else RectF(0.03f, 0.05f, 0.38f, 0.25f), dialog)
		selected = zones.size - 1; changed()
	}

	fun removeSelected()
	{
		if(zones.isEmpty()) return
		zones.removeAt(selected); selected = (selected - 1).coerceAtLeast(0); changed()
	}

	/** replaces the selected frame (or the first dialogue frame) */
	fun setSelectedRect(r: RectF)
	{
		val i = if(zones.getOrNull(selected)?.dialog == true) selected else zones.indexOfFirst { it.dialog }
		if(i < 0) { zones += InArabicPrefs.Zone(RectF(r), true); selected = zones.size - 1 } else { zones[i] = InArabicPrefs.Zone(RectF(r), true); selected = i }
		changed()
	}

	private fun changed() { invalidate(); onChange?.invoke(zones.toList()) }

	private val dp = resources.displayMetrics.density
	private val green = Color.rgb(159, 211, 180)
	private val amber = Color.rgb(232, 194, 122)
	private val dim = Paint().apply { color = Color.argb(110, 0, 0, 0) }
	private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
	private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
	private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * dp; textAlign = Paint.Align.CENTER; isFakeBoldText = true }

	private fun px(r: RectF) = RectF(r.left * width, r.top * height, r.right * width, r.bottom * height)

	override fun onDraw(canvas: Canvas)
	{
		canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
		zones.forEachIndexed { i, z ->
			val r = px(z.r)
			val c = if(z.dialog) green else amber
			val sel = i == selected
			fill.color = Color.argb(if(sel) 60 else 30, Color.red(c), Color.green(c), Color.blue(c))
			canvas.drawRoundRect(r, 8 * dp, 8 * dp, fill)
			stroke.color = c; stroke.strokeWidth = (if(sel) 3 else 1.5f) * dp
			canvas.drawRoundRect(r, 8 * dp, 8 * dp, stroke)
			label.color = c
			canvas.drawText((if(z.dialog) "حوار" else "نص جانبي") + " ${i + 1}", r.centerX(), r.top + 16 * dp, label)
			if(sel) for(p in corners(r)) { fill.color = c; canvas.drawCircle(p[0], p[1], 9 * dp, fill) }
		}
	}

	private fun corners(r: RectF) = listOf(floatArrayOf(r.left, r.top), floatArrayOf(r.right, r.top), floatArrayOf(r.left, r.bottom), floatArrayOf(r.right, r.bottom))

	private var mode = -1 // 0..3 corner, 4 move
	private var lastX = 0f
	private var lastY = 0f

	override fun onTouchEvent(e: MotionEvent): Boolean
	{
		when(e.actionMasked)
		{
			MotionEvent.ACTION_DOWN ->
			{
				lastX = e.x; lastY = e.y
				mode = -1
				zones.getOrNull(selected)?.let { z -> mode = corners(px(z.r)).indexOfFirst { hypot(it[0] - e.x, it[1] - e.y) < 32 * dp } }
				if(mode < 0)
				{
					// pick the frame under the finger (the smallest one when they overlap)
					val hit = zones.indices.filter { px(zones[it].r).contains(e.x, e.y) }.minByOrNull { zones[it].r.width() * zones[it].r.height() }
					if(hit != null) { selected = hit; mode = 4; invalidate() }
				}
				return mode >= 0
			}
			MotionEvent.ACTION_MOVE ->
			{
				val z0 = zones.getOrNull(selected) ?: return false
				val dx = (e.x - lastX) / width; val dy = (e.y - lastY) / height
				lastX = e.x; lastY = e.y
				val z = RectF(z0.r)
				val min = 0.04f
				when(mode)
				{
					4 -> z.offset(dx.coerceIn(-z.left, 1f - z.right), dy.coerceIn(-z.top, 1f - z.bottom))
					0 -> { z.left = (z.left + dx).coerceIn(0f, z.right - min); z.top = (z.top + dy).coerceIn(0f, z.bottom - min) }
					1 -> { z.right = (z.right + dx).coerceIn(z.left + min, 1f); z.top = (z.top + dy).coerceIn(0f, z.bottom - min) }
					2 -> { z.left = (z.left + dx).coerceIn(0f, z.right - min); z.bottom = (z.bottom + dy).coerceIn(z.top + min, 1f) }
					3 -> { z.right = (z.right + dx).coerceIn(z.left + min, 1f); z.bottom = (z.bottom + dy).coerceIn(z.top + min, 1f) }
					else -> return false
				}
				zones[selected] = InArabicPrefs.Zone(z, z0.dialog)
				changed()
				return true
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { mode = -1; return true }
		}
		return false
	}
}
