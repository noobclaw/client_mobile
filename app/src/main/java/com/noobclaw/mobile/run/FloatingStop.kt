package com.noobclaw.mobile.run

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** Always-visible stop bubble while a run is active (FR-10). No-op if overlay permission is missing. */
class FloatingStop(private val ctx: Context, private val onStop: () -> Unit) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: TextView? = null

    fun show() = main.post {
        if (view != null || !Settings.canDrawOverlays(ctx)) return@post
        val tv = TextView(ctx).apply {
            text = "■ 停止"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(36, 22, 36, 22)
            background = GradientDrawable().apply { cornerRadius = 60f; setColor(Color.parseColor("#E5484D")) }
            setOnClickListener { onStop() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 24; y = 240 }
        runCatching { wm.addView(tv, lp); view = tv }
    }

    fun hide() = main.post {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }
}
