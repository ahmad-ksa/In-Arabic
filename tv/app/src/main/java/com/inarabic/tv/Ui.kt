// The TV app's look: dark muted greens with the faint Arabic star pattern, IBM Plex Sans Arabic, a sage accent.
// Everything is focusable with the remote: the focused tile or button fills with the accent colour.

package com.inarabic.tv

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class Ui(private val c: Context)
{
	companion object
	{
		val BG1 = Color.rgb(15, 28, 23)
		val BG2 = Color.rgb(31, 58, 48)
		val CARD = Color.argb(235, 23, 44, 36)
		val STROKE = Color.rgb(41, 74, 60)
		val ACCENT = Color.rgb(159, 211, 180)
		val ON_ACCENT = Color.rgb(15, 36, 27)
		val TEXT2 = Color.rgb(166, 192, 179)
		val TEXT3 = Color.rgb(110, 140, 126)
		val OK = Color.rgb(127, 212, 158)
		val WARN = Color.rgb(232, 194, 122)

		private var regular: Typeface? = null
		private var bold: Typeface? = null
		private var kufi: Typeface? = null
		fun font(c: Context, b: Boolean): Typeface
		{
			if(regular == null) try
			{
				regular = Typeface.createFromAsset(c.assets, "fonts/plex_regular.ttf")
				bold = Typeface.createFromAsset(c.assets, "fonts/plex_bold.ttf")
				kufi = Typeface.createFromAsset(c.assets, "fonts/kufi.ttf")
			}
			catch(e: Exception) { }
			return (if(b) bold else regular) ?: Typeface.DEFAULT
		}
	}

	private val dp = c.resources.displayMetrics.density
	private fun px(v: Int) = (v * dp).toInt()

	fun round(color: Int, r: Float, stroke: Int = 0) = GradientDrawable().apply {
		setColor(color); cornerRadius = r; if(stroke != 0) setStroke(px(1), stroke)
	}

	fun card(): Drawable = round(CARD, px(20).toFloat(), STROKE)

	/** the page background: green gradient + the star pattern tile */
	fun backdrop(): Drawable
	{
		val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(BG2, BG1, BG1))
		val tile = BitmapDrawable(c.resources, android.graphics.BitmapFactory.decodeResource(c.resources, R.drawable.ia_pattern_tile)).apply {
			setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT); alpha = 70
		}
		return LayerDrawable(arrayOf(g, tile))
	}

	fun text(t: String, size: Float, color: Int, bold: Boolean = false, kufi: Boolean = false) = TextView(c).apply {
		text = t; textSize = size; setTextColor(color)
		typeface = if(kufi) { font(c, true); Companion.kufi ?: Typeface.DEFAULT_BOLD } else font(c, bold)
		includeFontPadding = !kufi
	}

	private fun focusBg(r: Float, normal: Int, stroke: Int) = StateListDrawable().apply {
		addState(intArrayOf(android.R.attr.state_focused), round(ACCENT, r))
		addState(intArrayOf(android.R.attr.state_pressed), round(ACCENT, r))
		addState(intArrayOf(), round(normal, r, stroke))
	}

	private fun focusText(normal: Int) = ColorStateList(
		arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
		intArrayOf(ON_ACCENT, ON_ACCENT, normal))

	/** a big action tile: icon, title, one line under it */
	inner class Tile(icon: String, title: String, sub: String, onClick: () -> Unit) : LinearLayout(c)
	{
		private val i = text(icon, 30f, Color.WHITE)
		private val t = text(title, 20f, Color.WHITE, bold = true)
		init
		{
			orientation = VERTICAL; gravity = Gravity.CENTER; isFocusable = true; isClickable = true
			background = focusBg(px(20).toFloat(), CARD, STROKE)
			setPadding(px(16), px(12), px(16), px(12))
			i.setTextColor(focusText(Color.WHITE)); t.setTextColor(focusText(Color.WHITE))
			i.isDuplicateParentStateEnabled = true; t.isDuplicateParentStateEnabled = true
			val s = text(sub, 13f, TEXT2).apply { setTextColor(focusText(TEXT2)); isDuplicateParentStateEnabled = true }
			addView(i); addView(t); addView(s)
			setOnClickListener { onClick() }
			setOnFocusChangeListener { v, f -> v.animate().scaleX(if(f) 1.04f else 1f).scaleY(if(f) 1.04f else 1f).setDuration(120).start() }
		}
		fun set(icon: String, title: String) { i.text = icon; t.text = title }
	}

	fun tile(icon: String, title: String, sub: String, onClick: () -> Unit) = Tile(icon, title, sub, onClick)

	/** a smaller rounded button */
	fun pill(t: String, onClick: () -> Unit) = TextView(c).apply {
		text = t; textSize = 15f; gravity = Gravity.CENTER; typeface = font(c, true)
		isFocusable = true; isClickable = true
		background = focusBg(px(99).toFloat(), Color.argb(30, 255, 255, 255), STROKE)
		setTextColor(focusText(Color.WHITE))
		setPadding(px(14), 0, px(14), 0)
		setOnClickListener { onClick() }
	}

	fun field(v: String, hint: String, type: Int) = EditText(c).apply {
		setText(v); this.hint = hint; textSize = 15f; inputType = type; gravity = Gravity.CENTER
		typeface = font(c, false); setTextColor(Color.WHITE); setHintTextColor(TEXT3)
		background = StateListDrawable().apply {
			addState(intArrayOf(android.R.attr.state_focused), round(Color.argb(40, 255, 255, 255), px(12).toFloat(), ACCENT))
			addState(intArrayOf(), round(Color.argb(22, 255, 255, 255), px(12).toFloat(), STROKE))
		}
		setPadding(px(10), 0, px(10), 0)
	}
}
