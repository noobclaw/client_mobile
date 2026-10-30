package com.noobclaw.mobile.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Tiny design kit, built in code: NoobClaw's dark look (near-black background, deep blue-purple cards,
 * neon-green accent — same palette as the desktop app).
 */
object C {
    val BG = Color.parseColor("#09090E")
    val SURFACE = Color.parseColor("#12121F")
    val SURFACE2 = Color.parseColor("#1A1A2B")
    val BORDER = Color.parseColor("#26263D")
    val TEXT = Color.parseColor("#EDEDFF")
    val SUB = Color.parseColor("#8A8AA8")
    val ACCENT = Color.parseColor("#00FF88")
    val ACCENT_SOFT = Color.parseColor("#1400FF88")
    val ON_ACCENT = Color.parseColor("#04140C")
    val DANGER = Color.parseColor("#FF5C7A")
    val WARN = Color.parseColor("#FFB020")
    val PURPLE = Color.parseColor("#7B5CF6")
    val CYAN = Color.parseColor("#00D4FF")
}

class Ui(private val ctx: Context) {
    fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
    fun dpf(v: Int): Float = v * ctx.resources.displayMetrics.density

    fun shape(fill: Int, radius: Int = 16, stroke: Int? = null, strokeW: Int = 1) = GradientDrawable().apply {
        cornerRadius = dpf(radius); setColor(fill)
        if (stroke != null) setStroke(dp(strokeW), stroke)
    }

    private fun pressable(v: View, fill: Int, radius: Int, stroke: Int? = null) {
        v.background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), shape(fill, radius, stroke), null)
        v.isClickable = true
        v.isFocusable = true
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    fun col(pad: Int = 0) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(pad), dp(pad), dp(pad), dp(pad)) }
    fun row() = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun text(t: CharSequence, size: Float = 15f, color: Int = C.TEXT, bold: Boolean = false) = TextView(ctx).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
    }

    fun h1(t: String) = text(t, 26f, C.TEXT, true)
    fun h2(t: String) = text(t, 18f, C.TEXT, true)
    fun sub(t: String) = text(t, 13f, C.SUB)
    fun section(t: String) = text(t, 13f, C.SUB, true).apply { letterSpacing = 0.04f }

    /** Rounded card; selected cards get a green outline. */
    fun card(selected: Boolean = false, onClick: (() -> Unit)? = null) = col(16).apply {
        val stroke = if (selected) C.ACCENT else C.BORDER
        if (onClick != null) { pressable(this, C.SURFACE, 18, stroke); setOnClickListener { onClick() } }
        else background = shape(C.SURFACE, 18, stroke)
    }

    fun primary(t: String, onClick: () -> Unit) = text(t, 16f, C.ON_ACCENT, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(16), dp(14), dp(16), dp(14))
        pressable(this, C.ACCENT, 14); setOnClickListener { onClick() }
    }

    fun secondary(t: String, color: Int = C.TEXT, onClick: () -> Unit) = text(t, 15f, color, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(14), dp(12), dp(14), dp(12))
        pressable(this, C.SURFACE2, 14, C.BORDER); setOnClickListener { onClick() }
    }

    fun link(t: String, color: Int = C.ACCENT, onClick: () -> Unit) = text(t, 14f, color, true).apply {
        setPadding(dp(4), dp(8), dp(4), dp(8)); setOnClickListener { onClick() }
    }

    fun chip(t: String, selected: Boolean, onClick: () -> Unit) = text(t, 14f, if (selected) C.ON_ACCENT else C.TEXT, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(14), dp(10), dp(14), dp(10))
        pressable(this, if (selected) C.ACCENT else C.SURFACE2, 22, if (selected) null else C.BORDER)
        setOnClickListener { onClick() }
    }

    /** Small status pill, e.g. "運行中" / "待命". */
    fun pill(t: String, color: Int) = text(t, 12f, color, true).apply {
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = shape(Color.argb(36, Color.red(color), Color.green(color), Color.blue(color)), 20)
    }

    /** Round badge with letters, used as a platform / script icon. */
    fun badge(t: String, fill: Int, fg: Int = Color.WHITE, size: Int = 44) = text(t, 15f, fg, true).apply {
        gravity = Gravity.CENTER
        background = shape(fill, size / 2)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    fun input(hint: String, value: String = "", multi: Boolean = false, number: Boolean = false, password: Boolean = false) = EditText(ctx).apply {
        this.hint = hint; setText(value)
        setTextColor(C.TEXT); setHintTextColor(Color.parseColor("#5C5C78")); textSize = 15f
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = shape(C.SURFACE2, 12, C.BORDER)
        inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            number -> InputType.TYPE_CLASS_NUMBER
            multi -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (multi) { minLines = 3; isSingleLine = false; gravity = Gravity.TOP or Gravity.START }
    }

    fun spacer(h: Int) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
}
