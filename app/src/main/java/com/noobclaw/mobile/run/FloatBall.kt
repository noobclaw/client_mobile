package com.noobclaw.mobile.run

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.noobclaw.mobile.R
import com.noobclaw.mobile.a11y.NcAccessibilityService
import com.noobclaw.mobile.ui.MainActivity
import com.noobclaw.mobile.ui.Steps
import com.noobclaw.mobile.util.Prefs
import com.noobclaw.mobile.util.RunLog
import com.noobclaw.mobile.util.tr
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * NoobClaw floating ball (2026-11-01). Shown while the app process is alive (needs the overlay permission):
 *   tap → fan menu. Idle: 啟動 / 上次結果 / 回到 NoobClaw. Running: 停止 / 進度.
 *   While running: green ring + a capsule next to the ball (likes · replies · elapsed); tap it for the progress card.
 * Draggable; snaps to the left / right edge; the position is remembered.
 * All window work happens on the main thread.
 */
@SuppressLint("StaticFieldLeak")
object FloatBall {
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var wm: WindowManager? = null

    private var bar: LinearLayout? = null          // [capsule][ball] (right side) or [ball][capsule] (left side)
    private var barLp: WindowManager.LayoutParams? = null
    private var ballBox: FrameLayout? = null
    private var ring: View? = null
    private var ringAnim: ObjectAnimator? = null
    private var pill: TextView? = null
    private var popup: FrameLayout? = null         // menu / card layer (full screen)
    private var popupLp: WindowManager.LayoutParams? = null
    private var runWasDetect = false
    private var lastRunId: String? = null   // 只弹这一次的结果(被拒 / 跳过的运行没有记录,不弹旧的)
    private var liveBox: LinearLayout? = null      // progress card: the part refreshed every second (buttons stay put)
    private var popupKind = ""                     // "menu" | "progress" | "last" | "pick"
    private var cardBody: LinearLayout? = null
    private var passThrough = false

    private var right = false                      // which edge the ball sits on
    private var lastRunning = false
    private var hintUntil = 0L                     // 首次拖動提示顯示到這個時間

    /** Gap between the ball and the screen edge (not flush against the edge). */
    private fun EDGE() = dp(8)      // 离边 8dp
    private const val TAB_W = 50    // 方案 A:深色玻璃圆角方块(像 iOS 小白点)
    private const val TAB_H = 50
    private fun dp(v: Int): Int = (v * (app?.resources?.displayMetrics?.density ?: 2f)).toInt()
    private val screenW get() = app?.resources?.displayMetrics?.widthPixels ?: 1080
    private val screenH get() = app?.resources?.displayMetrics?.heightPixels ?: 1920
    private fun prefs() = app?.getSharedPreferences("nc_mobile", Context.MODE_PRIVATE)

    // ---------------- public ----------------

    /** Shows the ball (no-op without the overlay permission, or when already shown). */
    fun show(ctx: Context) = main.post {
        val c = ctx.applicationContext
        app = c
        if (bar != null || !Settings.canDrawOverlays(c) || Prefs.token.isEmpty()) return@post   // 沒登錄不顯示
        wm = c.getSystemService(WindowManager::class.java)
        right = prefs()?.getBoolean("fb3_right", false) ?: false   // 默認左上角(右側會蓋住 TikTok 頭像 / 點讚列)
        buildBar()
        if (bar != null) { main.removeCallbacks(ticker); main.post(ticker) }   // 只在真的显示出来时跑,且不重复叠
    }

    fun hide() = main.post {
        main.removeCallbacks(ticker)
        closePopup()
        ringAnim?.cancel(); ringAnim = null
        bar?.let { b -> runCatching { wm?.removeView(b) } }
        bar = null; barLp = null; ballBox = null; ring = null; pill = null
    }

    /**
     * While the script dispatches a gesture, our windows must not catch it (the ball sits on TikTok's right
     * edge, where the like / comment buttons are): make them untouchable, then restore. Safe from any thread.
     */
    fun setPassThrough(on: Boolean) {
        val r = Runnable {
            if (passThrough == on) return@Runnable
            passThrough = on
            val nt = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            // 菜单 / 卡片那层全屏遮罩也要放行,否则剧本的点击落在遮罩上被吃掉(以为点了赞其实没点)
            val pv = popup; val plp = popupLp
            if (pv != null && plp != null) {
                plp.flags = if (on) plp.flags or nt else plp.flags and nt.inv()
                runCatching { wm?.updateViewLayout(pv, plp) }
            }
            val b = bar ?: return@Runnable
            val lp = barLp ?: return@Runnable
            lp.flags = if (on) lp.flags or nt else lp.flags and nt.inv()
            runCatching { wm?.updateViewLayout(b, lp) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) r.run() else main.post(r)
    }

    // ---------------- ball + capsule ----------------

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 1000)
        }
    }

    private fun running() = RunService.runningRunId != null

    private fun buildBar() {
        val c = app ?: return
        lastRunning = false   // 重新建的球:运行中的绿圈要重新亮
        // 贴边的横条:靠屏幕那一侧是直边,另一侧是半圆(看起来像个可以拉出来的把手)
        val box = FrameLayout(c).apply { elevation = dp(4).toFloat() }
        box.addView(ImageView(c).apply { setImageResource(R.mipmap.ic_launcher_round); scaleType = ImageView.ScaleType.FIT_CENTER },
            FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        val rg = View(c).apply { visibility = View.GONE }   // 运行中的绿色描边(同形状)
        box.addView(rg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val p = TextView(c).apply {
            setTextColor(Color.WHITE); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.parseColor("#E60A0C14")); setStroke(dp(1), Color.parseColor("#7300FF88")) }
            visibility = View.GONE
            setOnClickListener { openProgress() }
        }
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ballBox = box; ring = rg; pill = p; bar = row
        arrangeRow()

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or (if (right) Gravity.END else Gravity.START)
            x = EDGE()
            y = (prefs()?.getInt("fb3_y", -1) ?: -1).takeIf { it >= 0 } ?: (screenH * 0.24).toInt()
        }
        barLp = lp
        attachDrag(box)
        runCatching { wm?.addView(row, lp) }.onFailure { bar = null; barLp = null; RunLog.w("懸浮球顯示失敗：${it.message}") }
        refresh()
        showDragHintOnce()
    }

    /** Tab shape for the current edge: flat on the screen side, half-round on the other. */
    private fun tabShape(fill: Int, stroke: Int?): GradientDrawable {
        // 半透明深色圆角方块 + 细亮边;运行中的绿色描边是同形状的另一层
        return GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(fill)
            if (stroke != null) setStroke(dp(2), stroke) else setStroke(dp(1), Color.parseColor("#24FFFFFF"))
        }
    }

    private fun arrangeRow() {
        val row = bar ?: return
        val box = ballBox ?: return
        val p = pill ?: return
        box.background = tabShape(Color.parseColor("#B8141620"), null)
        ring?.background = tabShape(Color.TRANSPARENT, Color.parseColor("#00FF88"))
        row.removeAllViews()
        val gap = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (right) marginEnd = dp(4) else marginStart = dp(4)
        }
        val boxLp = LinearLayout.LayoutParams(dp(TAB_W), dp(TAB_H))
        if (right) { row.addView(p, gap); row.addView(box, boxLp) } else { row.addView(box, boxLp); row.addView(p, gap) }
    }

    /** First time only: a small bubble next to the button — "hold and drag to move, tap for the menu". */
    private fun showDragHintOnce() {
        val sp = prefs() ?: return
        if (sp.getBoolean("fb_hint_shown", false)) return
        sp.edit().putBoolean("fb_hint_shown", true).apply()
        val p = pill ?: return
        main.postDelayed({
            if (running()) return@postDelayed
            p.text = tr("按住可拖到別處 · 點一下打開菜單", "Hold to drag · tap for the menu")
            p.visibility = View.VISIBLE
            hintUntil = System.currentTimeMillis() + 6000   // refresh() 6 秒後自動收起
        }, 800)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(v: View) {
        val slop = ViewConfiguration.get(v.context).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startY = 0; var dragging = false
        v.setOnTouchListener { _, e ->
            val lp = barLp ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startY = lp.y; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) {
                        dragging = true; closePopup()
                        v.animate().scaleX(1.12f).scaleY(1.12f).alpha(1f).setDuration(120).start()   // 拿起來的感覺
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    }
                    if (dragging) {
                        lp.gravity = Gravity.TOP or Gravity.START
                        lp.x = (e.rawX - dp(TAB_W / 2)).toInt().coerceIn(0, screenW - dp(TAB_W))
                        lp.y = (startY + (e.rawY - downY)).toInt().coerceIn(0, screenH - dp(60))
                        runCatching { wm?.updateViewLayout(bar, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                        right = e.rawX > screenW / 2f
                        lp.gravity = Gravity.TOP or (if (right) Gravity.END else Gravity.START)
                        lp.x = EDGE()
                        arrangeRow()
                        runCatching { wm?.updateViewLayout(bar, lp) }
                        prefs()?.edit()?.putBoolean("fb3_right", right)?.putInt("fb3_y", lp.y)?.apply()
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        // 測試版:按住 2 秒不動 = 抓取當前畫面結構(用來校準抖音 / 小紅書 / TikTok 的按鈕),正式版沒有
                        if (com.noobclaw.mobile.BuildConfig.DEBUG && e.eventTime - e.downTime > 2000) {
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            app?.let { RunService.start(it, RunService.ACTION_CAPTURE_NOW) }
                        } else openMenu()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun refresh() {
        val run = running()
        if (run) { runWasDetect = RunService.runningTaskId == "detect"; lastRunId = RunService.runningRunId }
        val b = ballBox ?: return
        if (run != lastRunning) {
            lastRunning = run
            ring?.visibility = if (run) View.VISIBLE else View.GONE
            ringAnim?.cancel(); ringAnim = null
            if (run) ringAnim = ObjectAnimator.ofFloat(ring, "alpha", 1f, 0.35f).apply {
                duration = 1200; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
            }
            b.alpha = if (run) 1f else 0.8f
            // 运行结束:正式任务自动弹出这次的结果;检测账号不弹(RunService 自己提示并回到 App)
            if (run && (popupKind == "last" || popupKind == "menu")) closePopup()   // 新的运行开始了:上一次的结果卡片 / 旧菜单收起来
            if (!run) { if (runWasDetect) { if (popupKind == "progress") closePopup() } else if (popupKind != "pick" && Prefs.runHistory().firstOrNull()?.optString("run_id") == lastRunId) openLast() else if (popupKind == "progress") closePopup() }   // run ended while the card was open → show its result
        }
        val p = pill ?: return
        if (run && popupKind != "menu") {
            val lp = RunService.liveProgress()
            val c = lp?.optJSONObject("counts") ?: JSONObject()
            // 检测账号不是任务:不显示点赞数 / 计时,只提示正在读账号
            p.text = if (RunService.runningTaskId == "detect") tr("正在讀取帳號…", "Reading the account…")
                else countsLine(c, lp?.optJSONObject("targets"), taskById(RunService.runningTaskId), withCap = false, sep = "  ") + " · ${elapsed()}"
            p.visibility = View.VISIBLE
        } else if (System.currentTimeMillis() > hintUntil || popupKind == "menu") p.visibility = View.GONE
        if (popupKind == "progress") fillProgress()
    }

    private fun elapsed(): String {
        val s = ((System.currentTimeMillis() - RunService.runStartedAt) / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    /** Centre of the ball on screen. */
    private fun ballCenter(): Pair<Int, Int> {
        val lp = barLp
        val y = (lp?.y ?: 0) + dp(TAB_H / 2)
        val x = if (right) screenW - EDGE() - dp(TAB_W / 2) else EDGE() + dp(TAB_W / 2)
        return x to y
    }

    // ---------------- popups ----------------

    private fun closePopup() {
        popup?.let { v -> runCatching { wm?.removeView(v) } }
        popup = null; popupLp = null; popupKind = ""; cardBody = null; liveBox = null
        refresh()
    }

    private fun newPopup(kind: String, dim: Boolean): FrameLayout? {
        val c = app ?: return null
        closePopup()
        val layer = FrameLayout(c).apply {
            setBackgroundColor(if (dim) Color.parseColor("#D9000000") else Color.TRANSPARENT)
            setOnClickListener { closePopup() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        if (passThrough) lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (runCatching { wm?.addView(layer, lp) }.isFailure) return null
        popup = layer; popupLp = lp; popupKind = kind
        // keep the ball above the layer
        bar?.let { b -> barLp?.let { l -> runCatching { wm?.removeView(b); wm?.addView(b, l) } } }
        return layer
    }

    private fun openMenu() {
        if (popupKind == "menu") { closePopup(); return }
        val c = app ?: return
        val layer = newPopup("menu", dim = true) ?: return
        refresh()
        val items: List<Triple<String, String, () -> Unit>> = if (running()) listOf(
            Triple("■", tr("停止", "Stop")) { closePopup(); RunService.start(c, RunService.ACTION_STOP_RUN); toast(tr("正在停止…", "Stopping…")) },
            Triple("📊", tr("進度", "Progress")) { openProgress() },
        ) else listOf(
            Triple("▶", tr("啟動", "Start")) { startFlow() },
            Triple("📊", tr("上次結果", "Last run")) { openLast() },
            Triple("🏠", tr("回到 NoobClaw", "Open NoobClaw")) { closePopup(); openApp() },
        )
        val (cx, cy) = ballCenter()
        val radius = dp(88)
        val angles = if (items.size == 2) listOf(-30.0, 30.0) else listOf(-55.0, 0.0, 55.0)
        items.forEachIndexed { i, (icon, label, act) ->
            val a = Math.toRadians(angles[i])
            val dx = (cos(a) * radius).toInt() * (if (right) -1 else 1)
            val dy = (sin(a) * radius).toInt()
            val red = icon == "■"; val green = icon == "▶"
            val item = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener { act() }
            }
            item.addView(TextView(c).apply {
                text = icon; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(if (red) "#E5484D" else if (green) "#00C46A" else "#3A4260")) }
                elevation = dp(4).toFloat()
            }, LinearLayout.LayoutParams(dp(46), dp(46)))
            item.addView(TextView(c).apply {
                text = label; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                setShadowLayer(4f, 0f, 1f, Color.BLACK); gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
            val w = dp(96)
            layer.addView(item, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = (cx + dx - w / 2).coerceIn(0, screenW - w)
                topMargin = (cy + dy - dp(23)).coerceIn(0, screenH - dp(80))
            })
        }
    }

    /** Card under (or above) the ball; returns the card's content column. */
    private fun card(kind: String): LinearLayout? {
        val c = app ?: return null
        val layer = newPopup(kind, dim = true) ?: return null
        val (_, cy) = ballCenter()
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.parseColor("#F50E0F18")); setStroke(dp(1), Color.parseColor("#6600FF88")) }
            isClickable = true   // taps inside the card don't close it
        }
        val below = cy < screenH / 2
        layer.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (below) Gravity.TOP else Gravity.BOTTOM
            leftMargin = dp(12); rightMargin = dp(12)
            if (below) topMargin = cy + dp(34) else bottomMargin = (screenH - cy + dp(34)).coerceAtLeast(dp(12))
        })
        cardBody = col
        return col
    }

    private fun txt(s: CharSequence, size: Float = 12f, color: Int = Color.parseColor("#ECECF5"), bold: Boolean = false) =
        TextView(app).apply { text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD }

    private fun header(col: LinearLayout, title: String, tag: String, tagColor: Int) {
        val r = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(txt(title, 13f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(txt(tag, 11f, tagColor, true))
        col.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
    }

    private fun kv(col: LinearLayout, k: String, v: CharSequence) {
        val r = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
        r.addView(txt(k, 12f, Color.parseColor("#9A9AB3")), LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(txt(v, 12f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
    }

    private fun steps(col: LinearLayout, runId: String?, n: Int) {
        if (runId.isNullOrEmpty()) return
        val lines = RunLog.recent().filter { it.runId == runId && it.level == "step" }.mapNotNull { l -> Steps.text(l.msg)?.let { l.ts to it } }.takeLast(n)
        if (lines.isEmpty()) return
        col.addView(View(app).apply { setBackgroundColor(Color.parseColor("#2A2A3D")) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(4); bottomMargin = dp(6) })
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        lines.forEach { (ts, s) ->
            col.addView(txt(fmt.format(java.util.Date(ts)), 10f, Color.parseColor("#7C7C95")))
            col.addView(txt(s, 12f, Color.parseColor("#B9B9CC")).apply { maxLines = 2 }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
        }
    }

    private fun buttons(col: LinearLayout, vararg b: Triple<String, Int, () -> Unit>) {
        val r = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
        b.forEachIndexed { i, (label, fill, act) ->
            r.addView(txt(label, 13f, if (fill == Color.parseColor("#00FF88")) Color.parseColor("#04140C") else Color.WHITE, true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(9), 0, dp(9))
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(fill) }
                setOnClickListener { act() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        col.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    /**
     * "👍 12/1000 · 💬 3/300" — only the actions the task does: a switched-off action (reply off, like off) is left out.
     * The run's targets decide first (0 = not doing it); before the script sets them, the task config does.
     */
    private fun countsLine(c: JSONObject, targets: JSONObject?, task: JSONObject?, withCap: Boolean, sep: String = "  ·  "): String {
        val cfg = task?.optJSONObject("config")
        fun on(k: String, cfgKey: String): Boolean =
            if (targets != null && targets.has(k)) targets.optLong(k) > 0 || c.optLong(k) > 0
            else cfg?.optBoolean(cfgKey, true) ?: true
        fun cap(k: String) = if (withCap) targets?.optLong(k)?.takeIf { it in 1 until 100_000_000 }?.let { "/$it" } ?: "" else ""   // 不限数量 = 不显示 /上限
        val parts = mutableListOf<String>()
        // 收藏 / 關注:這次要做(劇本設了目標)就從 0 開始顯示,和點讚、評論一樣
        fun onT(k: String) = (targets?.optLong(k) ?: 0L) > 0 || c.optLong(k) > 0
        if (on("like", "cl_do_like")) parts += "👍 ${c.optLong("like")}${cap("like")}"
        if (onT("favorite")) parts += "⭐ ${c.optLong("favorite")}${cap("favorite")}"
        if (onT("follow")) parts += "➕ ${c.optLong("follow")}${cap("follow")}"
        if (on("comment", "cl_do_reply")) parts += "💬 ${c.optLong("comment")}${cap("comment")}"
        if (c.optLong("lead_new") > 0) parts += "👤 ${c.optLong("lead_new")}"
        return parts.joinToString(sep).ifEmpty { "👍 0" }
    }

    private fun taskTitle(t: JSONObject?): String {
        if (t == null) return "TikTok"
        val p = platformName(Prefs.platformOf(t))
        val n = t.optString("name").trim()
        val name = when {
            n.isEmpty() || n == "評論區點讚任務" || n == "定向獲客任務" || n == "互動漲粉任務" -> t.optString("script_id").let { sid ->
                if (sid.endsWith("_lead_engage")) tr("定向獲客任務", "Lead task") else if (sid.endsWith("_auto_engage")) tr("互動漲粉任務", "Engagement task") else tr("評論區點讚任務", "Comment-like task") }
            n.startsWith("評論區點讚 · ") -> tr("評論區點讚", "Comment likes") + " · " + n.removePrefix("評論區點讚 · ")
            n.startsWith("定向獲客 · ") -> tr("定向獲客", "Lead engage") + " · " + n.removePrefix("定向獲客 · ")
            else -> n
        }
        return "$p · $name"
    }
    private fun platformName(id: String) = when (id) { "douyin" -> tr("抖音", "Douyin"); "xhs" -> tr("小紅書", "RedNote"); else -> "TikTok" }
    private fun taskById(id: String?) = Prefs.tasks().firstOrNull { it.optString("id") == id }

    private fun runTitle(): String =
        if (RunService.runningTaskId == "detect") tr("檢測賬號", "Detecting account") else taskTitle(taskById(RunService.runningTaskId))

    private fun openProgress() {
        if (!running()) { openLast(); return }
        val col = card("progress") ?: return
        val c = app ?: return
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(box)
        liveBox = box
        fillProgress()
        // 按钮只建一次:每秒刷新只重建上面的数字,不然按到一半被重建,点击会丢
        buttons(col,
            Triple("■ " + tr("停止", "Stop"), Color.parseColor("#E5484D")) { closePopup(); app?.let { RunService.start(it, RunService.ACTION_STOP_RUN) }; toast(tr("正在停止…", "Stopping…")) },
            Triple(tr("收起", "Close"), Color.parseColor("#262C40")) { closePopup() },
        )
    }

    private fun fillProgress() {
        val col = liveBox ?: return
        if (!running()) return
        col.removeAllViews()
        val p = RunService.liveProgress() ?: JSONObject()
        val c = p.optJSONObject("counts") ?: JSONObject()
        val t = p.optJSONObject("targets") ?: JSONObject()
        header(col, runTitle(), "● " + tr("運行中 ", "Running ") + elapsed(), Color.parseColor("#00FF88"))
        kv(col, tr("進度", "Progress"), countsLine(c, t, taskById(RunService.runningTaskId), withCap = true))
        kv(col, tr("消耗", "Cost"), "💎 " + String.format(java.util.Locale.US, "%,d", p.optLong("credits")))
        steps(col, RunService.runningRunId, 3)
    }

    private fun openLast() {
        val col = card("last") ?: return
        val r = Prefs.runHistory().firstOrNull()
        if (r == null) {
            header(col, tr("上次結果", "Last run"), "", Color.WHITE)
            col.addView(txt(tr("還沒有運行記錄", "No runs yet"), 12f, Color.parseColor("#9A9AB3")))
            buttons(col, Triple(tr("關閉", "Close"), Color.parseColor("#262C40")) { closePopup() })
            return
        }
        val t = taskById(r.optString("task_id"))
        val st = r.optString("status")
        val (tag, color) = when (st) {
            "done" -> tr("✓ 已完成", "✓ Done") to Color.parseColor("#00FF88")
            "stopped" -> tr("■ 已停止", "■ Stopped") to Color.parseColor("#F5A524")
            else -> tr("✗ 失敗", "✗ Failed") to Color.parseColor("#FF5C7A")
        }
        header(col, tr("上次結果 · ", "Last run · ") + taskTitle(t), tag, color)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val dur = r.optLong("duration_ms") / 1000
        kv(col, tr("時間", "When"), fmt.format(java.util.Date(r.optLong("started_at"))) + " · " +
            tr("用時 ${dur / 60} 分 ${dur % 60} 秒", "${dur / 60}m ${dur % 60}s"))
        val c = r.optJSONObject("counts") ?: JSONObject()
        kv(col, tr("完成", "Done"), countsLine(c, null, t, withCap = false))
        kv(col, tr("消耗", "Cost"), "💎 " + String.format(java.util.Locale.US, "%,d", r.optLong("credits")))
        if (st != "done" && r.optString("reason").isNotEmpty()) kv(col, tr("原因", "Reason"), reasonText(r.optString("reason")))
        if (t != null) {
            val next = Schedule.nextRunAt(t, Prefs.lastStart(t.optString("id")), app?.getSharedPreferences("nc_slots", Context.MODE_PRIVATE)?.getStringSet("done", emptySet()) ?: emptySet())
            kv(col, tr("下次", "Next"), if (next == null) tr("手動運行", "Manual") else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(next)))
        }
        steps(col, r.optString("run_id"), 2)
        val tid = r.optString("task_id")
        buttons(col,
            Triple("▶ " + tr("再跑一次", "Run again"), Color.parseColor("#00FF88")) { closePopup(); startTask(tid) },
            Triple(tr("關閉", "Close"), Color.parseColor("#262C40")) { closePopup() },
        )
    }

    private fun reasonText(r: String): String = when (r) {
        "user_stopped" -> tr("手動停止", "Stopped by you")
        "time", "time_up" -> tr("到達運行時長", "Time limit reached")
        "caps_reached", "daily_cap" -> tr("到達數量上限", "Limit reached")
        "not_logged_in" -> tr("TikTok 沒登錄", "TikTok not logged in")
        "app_not_installed" -> tr("沒裝 TikTok", "TikTok not installed")
        "concurrency_limit" -> tr("同時運行的手機已達會員上限", "Too many phones running at once for your plan")
        else -> r.take(60)
    }

    // ---------------- actions ----------------

    private fun startFlow() {
        val list = Prefs.primaryTasks().filter { Prefs.platformOf(it) !in Prefs.hiddenPlatforms }
        when {
            list.isEmpty() -> { closePopup(); toast(tr("還沒有任務，先在 NoobClaw 裡創建", "No task yet — create one in NoobClaw")); openApp() }
            list.size == 1 -> { closePopup(); startTask(list[0].optString("id")) }
            else -> {
                val col = card("pick") ?: return
                header(col, tr("啟動哪個平台的任務？", "Start which platform's task?"), "", Color.WHITE)
                list.forEach { t ->
                    col.addView(txt(taskTitle(t), 13f, bold = true).apply {
                        setPadding(dp(12), dp(11), dp(12), dp(11))
                        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.TRANSPARENT); setStroke(dp(1), Color.parseColor("#2A2A3D")) }
                        setOnClickListener { closePopup(); startTask(t.optString("id")) }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
                }
                buttons(col, Triple(tr("取消", "Cancel"), Color.parseColor("#262C40")) { closePopup() })
            }
        }
    }

    private fun startTask(id: String) {
        val c = app ?: return
        if (id.isEmpty() || taskById(id) == null) { toast(tr("任務不存在了", "That task no longer exists")); return }
        if (Prefs.overLimit) { toast(tr("超出會員可用手機數，任務暫停", "Over your plan's phone limit")); return }
        if (!NcAccessibilityService.isRunning) { toast(tr("請先在 NoobClaw 裡開啟權限", "Turn on the permissions in NoobClaw first")); openApp(); return }
        RunService.start(c, RunService.ACTION_RUN_NOW, id)
        toast(tr("已開始", "Started"))
    }

    private fun openApp() {
        val c = app ?: return
        runCatching {
            c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    private fun toast(s: String) { app?.let { Toast.makeText(it, s, Toast.LENGTH_SHORT).show() } }
}
