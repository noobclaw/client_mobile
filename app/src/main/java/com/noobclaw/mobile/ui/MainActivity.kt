package com.noobclaw.mobile.ui

import android.Manifest
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.noobclaw.mobile.BuildConfig
import com.noobclaw.mobile.a11y.NcAccessibilityService
import com.noobclaw.mobile.net.Api
import com.noobclaw.mobile.run.RunService
import com.noobclaw.mobile.util.I18n
import com.noobclaw.mobile.util.Prefs
import com.noobclaw.mobile.util.RunLog
import com.noobclaw.mobile.util.tr
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/**
 * Single activity, screens built in code. Two tabs at the bottom: 任務 / 我的.
 * 登錄 → 使用說明 → 任務（新建：選平台 → 選劇本 → 設置）· 我的（帳號、會員、權限、語言、記錄、客服、退出）。
 */
class MainActivity : AppCompatActivity() {

    private enum class Screen { HOME, ME, PLATFORM, SCRIPT, CONFIG, LOGS }

    private lateinit var ui: Ui
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var nav: LinearLayout
    private var screen = Screen.HOME
    private var draft: JSONObject? = null           // task being created / edited
    private var permDialog: Dialog? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastRunning: String? = null
    private var balance: JSONObject? = null          // /api/ai/balance, loaded on the 我的 tab
    private val logListener: (RunLog.Line) -> Unit = { _ -> runOnUiThread { if (screen == Screen.LOGS || screen == Screen.HOME) refreshSoon() } }

    // ---------- scripts offered per platform ----------
    private data class Script(val id: String, val icon: String, val color: Int)
    private val tiktokScripts = listOf(
        Script("tiktok_comment_like", "讚", C.CYAN),
        Script("tiktok_lead_engage", "客", C.PURPLE),
    )
    private fun scriptOf(id: String) = tiktokScripts.firstOrNull { it.id == id } ?: tiktokScripts[0]
    private fun Script.title() = if (id == "tiktok_comment_like") tr("評論區點讚", "Comment likes") else tr("定向獲客", "Lead engage")
    private fun Script.desc() = if (id == "tiktok_comment_like") tr("給評論點讚，AI 回覆", "Like comments, AI replies")
        else tr("找客戶，點讚、評論、關注", "Find leads: like, comment, follow")
    private fun Script.badgeText() = if (I18n.lang == I18n.Lang.EN) (if (id == "tiktok_comment_like") "♥" else "@") else icon

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.SURFACE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0 // light icons on the dark bars
        root = ui.col().apply { setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(32)) }
        scroll = ScrollView(this).apply { setBackgroundColor(C.BG); isFillViewport = true; addView(root) }
        nav = ui.row().apply { setBackgroundColor(C.SURFACE); setPadding(0, ui.dp(6), 0, ui.dp(8)) }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(View(this@MainActivity).apply { setBackgroundColor(C.BORDER) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(page)
        // The page shrinks above the keyboard (so nothing hides behind it).
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (screen) {
                    Screen.HOME -> { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                    Screen.ME, Screen.PLATFORM -> go(Screen.HOME)
                    Screen.LOGS -> go(Screen.ME)
                    Screen.SCRIPT -> go(if (draft?.has("id") == true) Screen.HOME else Screen.PLATFORM)
                    Screen.CONFIG -> go(if (draft?.optBoolean("_editing") == true) Screen.HOME else Screen.SCRIPT)
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        reportLastCrash()
        if (Prefs.token.isNotEmpty() && Prefs.consentVersion >= RunService.CONSENT_VERSION) RunService.start(this) // stand by
        render()
        RunLog.onLine(logListener)
        main.post(ticker)
        // Coming back from the system settings: refresh the permission dialog or close it when all is granted.
        permDialog?.let { if (it.isShowing) { it.dismiss(); if (missingPerms().isNotEmpty()) showPermDialog(pendingAfterPerms) else pendingAfterPerms?.invoke() } }
    }

    override fun onPause() {
        RunLog.offLine(logListener)
        main.removeCallbacks(ticker)
        super.onPause()
    }

    /** Re-renders the task list when a run starts or ends. */
    private val ticker = object : Runnable {
        override fun run() {
            val now = RunService.runningTaskId
            if (now != lastRunning && screen == Screen.HOME) render()
            lastRunning = now
            main.postDelayed(this, 2000)
        }
    }
    private var refreshQueued = false
    private fun refreshSoon() {
        if (refreshQueued) return
        refreshQueued = true
        main.postDelayed({ refreshQueued = false; if (screen == Screen.LOGS) render() }, 800)
    }

    private fun reportLastCrash() {
        val sp = getSharedPreferences("nc_mobile", MODE_PRIVATE)
        val crash = sp.getString("last_crash", null) ?: return
        sp.edit().remove("last_crash").apply()
        RunLog.e(tr("上次 App 崩潰：", "The app crashed last time: ") + crash.take(600))
        if (Prefs.token.isEmpty()) return
        lifecycleScope.launch {
            runCatching {
                Api.sendDiagnostics(JSONObject().put("kind", "crash").put("crash", crash)
                    .put("app_ver", BuildConfig.VERSION_NAME).put("android_sdk", Build.VERSION.SDK_INT))
            }.onSuccess { RunLog.i(tr("崩潰信息已上傳，客服碼 ", "Crash report uploaded, support code ") + it) }
        }
    }

    private fun go(s: Screen) { hideKeyboard(); screen = s; render(); scroll.scrollTo(0, 0) }

    private fun hideKeyboard() {
        val f = currentFocus ?: return
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(f.windowToken, 0)
        f.clearFocus()
    }

    /** Tapping anywhere outside the text box closes the keyboard (Android does not do this by itself). */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
            val f = currentFocus
            if (f is EditText) {
                val r = android.graphics.Rect(); f.getGlobalVisibleRect(r)
                if (!r.contains(ev.rawX.toInt(), ev.rawY.toInt())) hideKeyboard()
            }
        }
        return super.dispatchTouchEvent(ev)
    }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
    private fun add(v: View, top: Int = 0, bottom: Int = 0) = root.addView(v, ui.lp(top = top, bottom = bottom))

    private fun render() {
        root.removeAllViews()
        val inApp = Prefs.token.isNotEmpty() && Prefs.consentVersion >= RunService.CONSENT_VERSION
        when {
            Prefs.token.isEmpty() -> renderLogin()
            !inApp -> renderConsent()
            else -> when (screen) {
                Screen.HOME -> renderHome()
                Screen.ME -> renderMe()
                Screen.PLATFORM -> renderPlatform()
                Screen.SCRIPT -> renderScript()
                Screen.CONFIG -> renderConfig()
                Screen.LOGS -> renderLogs()
            }
        }
        renderNav(inApp && (screen == Screen.HOME || screen == Screen.ME))
    }

    // ---------- bottom tabs ----------
    private fun renderNav(show: Boolean) {
        nav.removeAllViews()
        nav.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        listOf(
            Triple(Screen.HOME, "▦", tr("任務", "Tasks")),
            Triple(Screen.ME, "◉", tr("我的", "Me")),
        ).forEach { (s, icon, label) ->
            val on = screen == s
            val item = ui.col().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, ui.dp(4), 0, ui.dp(2))
                isClickable = true
                setOnClickListener { if (screen != s) go(s) }
            }
            item.addView(ui.text(icon, 18f, if (on) C.ON_ACCENT else C.SUB, true).apply {
                gravity = Gravity.CENTER
                if (on) background = ui.shape(C.ACCENT, 16)
            }, LinearLayout.LayoutParams(ui.dp(56), ui.dp(30)).apply { bottomMargin = ui.dp(4) })
            item.addView(ui.text(label, 12f, if (on) C.ACCENT else C.SUB, on).apply { gravity = Gravity.CENTER })
            nav.addView(item, ui.weight())
        }
    }

    /** Back arrow + title + step text. */
    private fun topBar(title: String, step: String? = null, onBack: () -> Unit) {
        val r = ui.row()
        r.addView(ui.text("←", 22f, C.TEXT, true).apply {
            setPadding(0, ui.dp(4), ui.dp(16), ui.dp(4)); setOnClickListener { onBack() }
        })
        r.addView(ui.h2(title), ui.weight())
        if (step != null) r.addView(ui.pill(step, C.ACCENT))
        add(r, bottom = 18)
    }

    // ---------- login ----------
    private fun renderLogin() {
        val top = ui.row()
        top.addView(View(this), ui.weight())
        top.addView(ui.link("🌐 " + I18n.lang.label, C.SUB) { chooseLanguage() })
        add(top)
        add(ui.spacer(24))
        add(ui.text("NoobClaw", 34f, C.ACCENT, true))
        add(ui.spacer(28))
        val card = ui.card()
        val acc = ui.input(tr("用戶名或郵箱", "Username or email"))
        val pw = ui.input(tr("密碼", "Password"), password = true)
        card.addView(acc, ui.lp(bottom = 10)); card.addView(pw, ui.lp(bottom = 16))
        card.addView(ui.primary(tr("登錄", "Log in")) {
            lifecycleScope.launch {
                try {
                    val name = acc.text.toString().trim()
                    Api.login(name, pw.text.toString())
                    try {
                        Api.registerDevice(JSONObject().put("vendor", Build.MANUFACTURER + "/" + Build.MODEL).put("android_sdk", Build.VERSION.SDK_INT))
                    } catch (e: com.noobclaw.mobile.net.ApiException) {
                        if (e.code != "phone_limit") throw e
                        // 會員可用手機數已滿:不讓這臺手機登錄
                        Prefs.logout()
                        val n = e.body.optInt("limit")
                        toast(tr("當前會員最多 $n 臺手機，已滿。請升級會員，或在電腦端移除不用的手機",
                            "Your plan allows $n phones and they're all in use. Upgrade, or remove a phone in the desktop app"))
                        return@launch
                    }
                    Prefs.account = name
                    syncTasks(); screen = Screen.HOME; render()
                } catch (e: Exception) { toast(tr("登錄失敗：", "Login failed: ") + e.message) }
            }
        })
        add(card)
        add(ui.sub("v${BuildConfig.VERSION_NAME}").apply { gravity = Gravity.CENTER }, top = 24)
    }

    // ---------- consent (FR-02) ----------
    private fun renderConsent() {
        add(ui.h1(tr("使用說明", "Before you start")), bottom = 16)
        val card = ui.card()
        listOf(
            tr("通過無障礙服務操作 TikTok，只讀 TikTok 界面", "Works TikTok through the accessibility service and reads only TikTok's screen"),
            tr("遇到平台驗證會自動停下", "Stops by itself when TikTok asks for verification"),
            tr("自動化有帳號受限風險，請自行評估", "Automation can get accounts restricted — use at your own judgement"),
        ).forEach { card.addView(ui.text("•  $it", 14f, C.TEXT), ui.lp(bottom = 8)) }
        add(card, bottom = 16)
        var diag = false
        fun diagText() = tr("出錯時上傳診斷：", "Upload diagnostics on errors: ") + if (diag) tr("開", "on") else tr("關", "off")
        val diagChip = ui.chip(diagText(), false) {}
        diagChip.setOnClickListener {
            diag = !diag
            diagChip.text = diagText()
            diagChip.setTextColor(if (diag) C.ACCENT else C.TEXT)
        }
        add(diagChip, bottom = 16)
        add(ui.primary(tr("同意", "I agree")) {
            Prefs.diagOptIn = diag
            Prefs.consentVersion = RunService.CONSENT_VERSION
            RunService.start(this) // stand by
            screen = Screen.HOME
            render()
        }, bottom = 10)
        add(ui.link(tr("退出登錄", "Log out"), C.SUB) { logout() }.apply { gravity = Gravity.CENTER })
    }

    private fun logout() {
        Prefs.logout(); RunService.start(this, RunService.ACTION_SHUTDOWN); balance = null; screen = Screen.HOME; render()
    }

    // ---------- permissions ----------
    private data class Perm(val title: String, val open: () -> Unit)

    private fun missingPerms(): List<Perm> {
        val out = mutableListOf<Perm>()
        if (!NcAccessibilityService.isRunning) out += Perm(tr("無障礙服務", "Accessibility service")) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        if (!Settings.canDrawOverlays(this)) out += Perm(tr("懸浮窗", "Display over other apps")) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) out += Perm(tr("後台運行", "Run in background")) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        return out
    }

    private var pendingAfterPerms: (() -> Unit)? = null

    /** Runs [action] when every permission is on; otherwise asks for the missing ones first. */
    private fun withPerms(action: () -> Unit) {
        if (missingPerms().isEmpty()) action() else showPermDialog(action)
    }

    private fun overLimitText() = tr("超出會員可用手機數（${Prefs.phoneLimit} 臺），任務暫停", "Over your plan's phone limit (${Prefs.phoneLimit}) — tasks paused")

    /** Run / dry-run from the app: blocked when this phone is beyond the membership phone count. */
    private fun withRunPerms(action: () -> Unit) {
        if (Prefs.overLimit) { toast(overLimitText()); return }
        withPerms(action)
    }

    private fun dialogBox(): Pair<Dialog, LinearLayout> {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = ui.col(20).apply { background = ui.shape(C.SURFACE, 22, C.BORDER) }
        return d to box
    }
    private fun showDialog(d: Dialog, box: LinearLayout) {
        d.setContentView(box)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        d.show()
    }

    private fun showPermDialog(after: (() -> Unit)?) {
        pendingAfterPerms = after
        val missing = missingPerms()
        if (missing.isEmpty()) { after?.invoke(); return }
        val (d, box) = dialogBox()
        box.addView(ui.h2(tr("請先開啟權限", "Turn on permissions first")), ui.lp(bottom = 16))
        missing.forEachIndexed { i, p ->
            val r = ui.row()
            r.addView(ui.badge("${i + 1}", C.ACCENT, C.ON_ACCENT, 30))
            val t = ui.col().apply { setPadding(ui.dp(12), 0, ui.dp(8), 0) }
            t.addView(ui.text(p.title, 15f, C.TEXT, true))
            r.addView(t, ui.weight())
            r.addView(ui.secondary(tr("開啟", "Open"), C.ACCENT) { p.open() })
            box.addView(r, ui.lp(bottom = 14))
        }
        box.addView(ui.link(tr("取消", "Cancel"), C.SUB) { pendingAfterPerms = null; d.dismiss() }.apply { gravity = Gravity.CENTER })
        permDialog = d
        showDialog(d, box)
    }

    // ---------- tab 1: tasks ----------
    private fun renderHome() {
        val head = ui.row()
        val title = ui.col()
        title.addView(ui.text(tr("我的任務", "My tasks"), 26f, C.TEXT, true))
        title.addView(ui.sub("v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）"))
        head.addView(title, ui.weight())
        add(head, bottom = 18)

        // status strip: TikTok account + permissions
        val status = ui.card()
        val r = ui.row()
        r.addView(ui.badge("TT", Color.BLACK, Color.WHITE, 40))
        val info = ui.col().apply { setPadding(ui.dp(12), 0, 0, 0) }
        info.addView(ui.text(tiktokHandle().ifEmpty { "TikTok" }, 15f, C.TEXT, true))
        r.addView(info, ui.weight())
        val missing = missingPerms()
        if (missing.isEmpty()) r.addView(ui.pill(tr("就緒", "Ready"), C.ACCENT))
        else r.addView(ui.pill(tr("缺權限", "Permissions"), C.WARN).apply { setOnClickListener { showPermDialog(null) } })
        status.addView(r)
        add(status, bottom = if (Prefs.overLimit) 12 else 22)
        if (Prefs.overLimit) {
            val warn = ui.card()
            warn.addView(ui.text(overLimitText(), 14f, C.WARN, true))
            warn.addView(ui.sub(tr("升級會員，或在電腦端移除不用的手機", "Upgrade, or remove an unused phone in the desktop app")))
            add(warn, bottom = 22)
        }

        val tasks = Prefs.tasks()
        if (tasks.isEmpty()) {
            val empty = ui.card().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(ui.dp(20), ui.dp(28), ui.dp(20), ui.dp(24)) }
            empty.addView(ui.text(tr("還沒有任務", "No tasks yet"), 17f, C.SUB, true).apply { gravity = Gravity.CENTER }, ui.lp(bottom = 18))
            empty.addView(ui.primary(tr("＋  新建任務", "＋  New task")) { newTask() })
            add(empty)
        } else {
            tasks.forEach { add(taskCard(it), bottom = 12) }
            add(ui.primary(tr("＋  新建任務", "＋  New task")) { newTask() }, top = 6)
        }
    }

    private fun tiktokHandle() = runCatching { JSONObject(Prefs.bindingJson).optString("display_id") }.getOrDefault("")

    private fun levelName(k: String) = when (k) {
        "low" -> tr("少", "Few"); "high" -> tr("多", "Many"); else -> tr("中", "Normal")
    }

    private fun taskCard(t: JSONObject): View {
        val s = scriptOf(t.optString("script_id"))
        val id = t.optString("id")
        val running = RunService.runningTaskId == id
        val card = ui.card(selected = running)
        val top = ui.row()
        top.addView(ui.badge(s.badgeText(), s.color, Color.WHITE, 42))
        val name = ui.col().apply { setPadding(ui.dp(12), 0, ui.dp(8), 0) }
        name.addView(ui.text(t.optString("name").ifEmpty { s.title() }, 16f, C.TEXT, true))
        name.addView(ui.sub(s.title()))
        top.addView(name, ui.weight())
        top.addView(when {
            running -> ui.pill(tr("運行中", "Running"), C.ACCENT)
            t.optBoolean("enabled") -> ui.pill(tr("定時", "Scheduled"), C.PURPLE)
            else -> ui.pill(tr("手動", "Manual"), C.SUB)
        })
        card.addView(top, ui.lp(bottom = 12))

        val c = t.optJSONObject("config") ?: JSONObject()
        card.addView(ui.text(listOfNotNull(whereOf(c).firstOrNull(), levelName(levelFromCaps(t)),
            if (t.optBoolean("enabled")) slotsOf(t).joinToString(" / ") else null).joinToString("  ·  "), 14f, C.SUB))

        val btns = ui.row().apply { setPadding(0, ui.dp(10), 0, 0) }
        if (running) btns.addView(ui.secondary(tr("■  停止", "■  Stop"), C.DANGER) { RunService.start(this, RunService.ACTION_STOP_RUN) }, ui.weight())
        else btns.addView(ui.primary(tr("▶  運行", "▶  Run")) {
            withRunPerms { RunService.start(this, RunService.ACTION_RUN_NOW, id); toast(tr("已開始", "Started")) }
        }, ui.weight())
        btns.addView(ui.spacer(1).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(8), 1) })
        btns.addView(ui.secondary(tr("編輯", "Edit")) { editTask(t) })
        btns.addView(ui.spacer(1).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(8), 1) })
        btns.addView(ui.secondary("⋯") { taskMenu(t) })
        card.addView(btns)
        return card
    }

    private fun taskMenu(t: JSONObject) {
        val (d, box) = dialogBox()
        box.addView(ui.h2(t.optString("name").ifEmpty { scriptOf(t.optString("script_id")).title() }), ui.lp(bottom = 14))
        box.addView(ui.secondary(tr("試跑（不扣費）", "Dry run (free)")) {
            d.dismiss(); withRunPerms { RunService.start(this, RunService.ACTION_DRY_RUN, t.optString("id")); toast(tr("試跑開始", "Dry run started")) }
        }, ui.lp(bottom = 10))
        box.addView(ui.secondary(if (t.optBoolean("enabled")) tr("關閉定時", "Turn off schedule") else tr("開啟定時", "Turn on schedule")) {
            d.dismiss(); saveTask(JSONObject(t.toString()).put("enabled", !t.optBoolean("enabled")))
        }, ui.lp(bottom = 10))
        box.addView(ui.secondary(tr("刪除", "Delete"), C.DANGER) {
            d.dismiss()
            val list = Prefs.tasks().filter { it.optString("id") != t.optString("id") }
            pushTasks(list, tr("已刪除", "Deleted"))
        })
        showDialog(d, box)
    }

    // ---------- tab 2: me ----------
    private fun renderMe() {
        add(ui.text(tr("我的", "Me"), 26f, C.TEXT, true), bottom = 18)

        // profile
        val prof = ui.card()
        val pr = ui.row()
        val who = Prefs.account.ifEmpty { Prefs.wallet.take(6) + "…" + Prefs.wallet.takeLast(4) }
        pr.addView(ui.badge(who.take(1).uppercase(), C.PURPLE, Color.WHITE, 52))
        val pt = ui.col().apply { setPadding(ui.dp(14), 0, 0, 0) }
        pt.addView(ui.text(who, 17f, C.TEXT, true))
        val b = balance
        pt.addView(ui.sub(if (b == null) tr("加載中…", "Loading…") else planLabel(b)))
        pr.addView(pt, ui.weight())
        prof.addView(pr)
        add(prof, bottom = 12)

        // membership numbers
        val stats = ui.row()
        fun stat(value: String, label: String, last: Boolean = false) {
            val c = ui.card().apply { gravity = Gravity.CENTER_HORIZONTAL }
            c.addView(ui.text(value, 17f, C.TEXT, true).apply { gravity = Gravity.CENTER })
            c.addView(ui.sub(label).apply { gravity = Gravity.CENTER })
            stats.addView(c, ui.weight().apply { if (!last) marginEnd = ui.dp(10) })
        }
        stat(b?.let { fmtCredits(it.optLong("tokenBalance")) } ?: "—", tr("積分", "Credits"))
        stat(if (Prefs.phoneLimit > 0) "${Prefs.phoneLimit}" else "—", tr("可用手機", "Phones allowed"))
        stat(b?.let { expiryText(it) } ?: "—", tr("會員到期", "Plan ends"), last = true)
        add(stats, bottom = 18)
        if (balance == null) loadBalance()

        // settings list
        val list = ui.card().apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4)) }
        val missing = missingPerms()
        fun item(title: String, value: String, valueColor: Int = C.SUB, divider: Boolean = true, onClick: (() -> Unit)?) {
            val r = ui.row().apply {
                setPadding(0, ui.dp(15), 0, ui.dp(15))
                if (onClick != null) { isClickable = true; setOnClickListener { onClick() } }
            }
            r.addView(ui.text(title, 15f, C.TEXT), ui.weight())
            r.addView(ui.text(value, 14f, valueColor))
            if (onClick != null) r.addView(ui.text("  ›", 16f, C.SUB))
            list.addView(r)
            if (divider) list.addView(View(this).apply { setBackgroundColor(C.BORDER) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        }
        item(tr("TikTok 帳號", "TikTok account"), tiktokHandle().ifEmpty { tr("未識別", "Not detected") }, onClick = null)
        item(tr("權限", "Permissions"), if (missing.isEmpty()) tr("已開啟", "All on") else tr("缺 ${missing.size} 項", "${missing.size} missing"),
            if (missing.isEmpty()) C.ACCENT else C.WARN) { showPermDialog(null) }
        item(tr("語言", "Language"), I18n.lang.label) { chooseLanguage() }
        item(tr("運行記錄", "Run log"), "") { go(Screen.LOGS) }
        item(tr("聯繫客服", "Contact support"), "") { sendDiagnostics() }
        item(tr("版本", "Version"), "v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", divider = false, onClick = null)
        add(list, bottom = 18)

        add(ui.secondary(tr("退出登錄", "Log out"), C.DANGER) { logout() })
    }

    private fun planName(code: String) = when (code) {
        "basic" -> tr("基礎版", "Basic"); "pro" -> tr("進階版", "Pro"); "max" -> tr("旗艦版", "Max"); "ultra" -> tr("至尊版", "Ultra")
        else -> tr("免費版", "Free")
    }
    private fun planLabel(b: JSONObject): String {
        val active = b.optBoolean("subActive")
        val code = if (active) b.optString("planCode", "free") else (b.optString("subPlanCode").takeIf { it.isNotEmpty() && it != "null" } ?: "free")
        return if (!active && code != "free") planName(code) + " · " + tr("已過期", "expired") else planName(if (active) code else "free")
    }
    private fun expiryText(b: JSONObject): String {
        val end = b.optString("subPeriodEnd").takeIf { it.isNotEmpty() && it != "null" } ?: return "—"
        return runCatching { java.time.OffsetDateTime.parse(end).toLocalDate().toString() }.getOrElse { end.take(10) }
    }
    private fun fmtCredits(n: Long): String = when {
        n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", n / 1_000.0)
        else -> n.toString()
    }
    private var balanceLoading = false
    private fun loadBalance() {
        if (balanceLoading) return
        balanceLoading = true
        lifecycleScope.launch {
            runCatching { Api.get("/api/ai/balance") }
                .onSuccess { balance = it; if (screen == Screen.ME) render() }
            balanceLoading = false
        }
    }

    private fun chooseLanguage() {
        val (d, box) = dialogBox()
        box.addView(ui.h2(tr("語言", "Language")), ui.lp(bottom = 14))
        val cur = Prefs.lang
        listOf("" to tr("跟隨系統", "System default")).plus(I18n.Lang.values().map { it.code to it.label }).forEach { (code, label) ->
            box.addView(ui.chip(label, code == cur) {
                d.dismiss(); Prefs.lang = code; render()
            }, ui.lp(bottom = 10))
        }
        showDialog(d, box)
    }

    // ---------- new task: 1 platform ----------
    private fun newTask() = withPerms {
        draft = JSONObject().put("enabled", true)
        go(Screen.PLATFORM)
    }

    private fun renderPlatform() {
        topBar(tr("新建任務", "New task"), "1 / 3") { go(Screen.HOME) }
        add(ui.h1(tr("選平台", "Pick a platform")), bottom = 18)
        val platforms = listOf(
            Triple("TikTok", "TT", true), Triple("Instagram", "IG", false), Triple(tr("小紅書", "RedNote"), "紅", false),
            Triple(tr("抖音", "Douyin"), "抖", false), Triple("YouTube", "YT", false), Triple("X", "X", false),
        )
        platforms.chunked(2).forEach { pair ->
            val r = ui.row()
            pair.forEachIndexed { i, (name, icon, on) ->
                val card = ui.card(onClick = if (on) ({ go(Screen.SCRIPT) }) else ({ toast(tr("即將推出", "Coming soon")) }))
                card.gravity = Gravity.CENTER_HORIZONTAL
                card.alpha = if (on) 1f else 0.45f
                card.addView(ui.badge(icon, if (on) Color.BLACK else C.SURFACE2, Color.WHITE, 52).apply {
                    if (on) background = ui.shape(Color.BLACK, 26, C.CYAN, 2)
                }, LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)).apply { bottomMargin = ui.dp(10) })
                card.addView(ui.text(name, 15f, C.TEXT, true).apply { gravity = Gravity.CENTER })
                if (!on) card.addView(ui.sub(tr("即將推出", "Coming soon")).apply { gravity = Gravity.CENTER })
                r.addView(card, ui.weight().apply { if (i == 0) marginEnd = ui.dp(10) })
            }
            add(r, bottom = 10)
        }
    }

    // ---------- new task: 2 script ----------
    private fun renderScript() {
        topBar(tr("新建任務", "New task"), "2 / 3") { go(Screen.PLATFORM) }
        add(ui.h1(tr("選劇本", "Pick a script")), bottom = 18)
        tiktokScripts.forEach { s ->
            val card = ui.card(onClick = {
                draft = (draft ?: JSONObject()).put("script_id", s.id)
                go(Screen.CONFIG)
            })
            val r = ui.row()
            r.addView(ui.badge(s.badgeText(), s.color, Color.WHITE, 48))
            val t = ui.col().apply { setPadding(ui.dp(14), 0, 0, 0) }
            t.addView(ui.text(s.title(), 17f, C.TEXT, true), ui.lp(bottom = 4))
            t.addView(ui.text(s.desc(), 14f, C.SUB))
            r.addView(t, ui.weight())
            card.addView(r)
            add(card, bottom = 12)
        }
    }

    // ---------- new / edit task: 3 settings ----------
    private data class Level(val likes: Int, val comments: Int, val follows: Int, val leads: Int)
    private fun levelOf(script: String, l: String) = when (script) {
        "tiktok_comment_like" -> when (l) { "low" -> Level(30, 5, 0, 0); "high" -> Level(300, 30, 0, 0); else -> Level(100, 15, 0, 0) }
        else -> when (l) { "low" -> Level(10, 5, 10, 10); "high" -> Level(100, 30, 50, 100); else -> Level(30, 15, 30, 30) }
    }
    private fun levelFromCaps(t: JSONObject): String {
        val l = t.optJSONObject("config")?.optString("level").orEmpty()
        if (l.isNotEmpty()) return l
        val likes = t.optInt("max_likes", 100)
        return if (likes <= 30) "low" else if (likes >= 200) "high" else "mid"
    }
    private fun whereOf(c: JSONObject): List<String> =
        listOf(c.optJSONArray("urls"), c.optJSONArray("seed_accounts"), c.optJSONArray("keywords"))
            .flatMap { a -> if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) } }
            .map { it.removePrefix("https://www.tiktok.com/") }.filter { it.isNotBlank() }
    private fun slotsOf(t: JSONObject): List<String> {
        val a = t.optJSONArray("slots")
        val l = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
        return l.ifEmpty { listOf("09:30", "20:00") }
    }

    private fun editTask(t: JSONObject) {
        draft = JSONObject(t.toString()).put("_editing", true)
        go(Screen.CONFIG)
    }

    private fun renderConfig() {
        val d = draft ?: JSONObject().also { draft = it }
        val editing = d.optBoolean("_editing")
        val script = d.optString("script_id").ifEmpty { "tiktok_comment_like" }
        val s = scriptOf(script)
        topBar(if (editing) tr("編輯任務", "Edit task") else tr("新建任務", "New task"), if (editing) null else "3 / 3") {
            go(if (editing) Screen.HOME else Screen.SCRIPT)
        }

        val head = ui.row()
        head.addView(ui.badge(s.badgeText(), s.color, Color.WHITE, 44))
        val ht = ui.col().apply { setPadding(ui.dp(12), 0, 0, 0) }
        ht.addView(ui.h2(s.title()))
        head.addView(ht, ui.weight())
        add(head, bottom = 20)

        val c = d.optJSONObject("config") ?: JSONObject()
        val cl = script == "tiktok_comment_like"

        add(ui.section(if (cl) tr("關鍵詞 / 鏈接", "Keywords / links") else tr("關鍵詞 / 同行帳號", "Keywords / competitor accounts")), bottom = 8)
        val where = ui.input(
            if (cl) tr("每行一個，例如 latte art", "One per line, e.g. latte art") else tr("每行一個，例如 latte art 或 @rival", "One per line, e.g. latte art or @rival"),
            whereOf(c).joinToString("\n") { if (it.startsWith("@") && cl) "https://www.tiktok.com/$it" else it }, multi = true,
        )
        add(where, bottom = 20)

        add(ui.section(tr("數量", "Amount")), bottom = 8)
        var level = d.optString("_level").ifEmpty { levelFromCaps(d) }
        val amount = ui.sub("")
        val chips = ui.row()
        fun drawChips() {
            chips.removeAllViews()
            listOf("low", "mid", "high").forEach { k ->
                val ch = ui.chip(levelName(k), k == level) { level = k; d.put("_level", k); drawChips() }
                chips.addView(ch, ui.weight().apply { marginEnd = ui.dp(8) })
            }
            val lv = levelOf(script, level)
            amount.text = if (cl) tr("點讚 ${lv.likes} · 回覆 ${lv.comments}", "${lv.likes} likes · ${lv.comments} replies")
                          else tr("客戶 ${lv.leads} · 關注 ${lv.follows} · 點讚 ${lv.likes} · 評論 ${lv.comments}",
                                  "${lv.leads} leads · ${lv.follows} follows · ${lv.likes} likes · ${lv.comments} comments")
        }
        drawChips()
        add(chips, bottom = 8)
        add(amount, bottom = 20)

        add(ui.section(tr("人設（選填）", "Persona (optional)")), bottom = 8)
        val persona = ui.input(tr("例如：東京咖啡店", "e.g. a coffee shop in Tokyo"), d.optString("persona"))
        add(persona, bottom = 20)

        add(ui.section(tr("定時", "Schedule")), bottom = 8)
        var auto = d.optBoolean("enabled", true)
        val slots = slotsOf(d).toMutableList()
        val schedBox = ui.col()
        fun drawSched() {
            schedBox.removeAllViews()
            val modes = ui.row()
            modes.addView(ui.chip(tr("每天", "Daily"), auto) { auto = true; drawSched() }, ui.weight().apply { marginEnd = ui.dp(8) })
            modes.addView(ui.chip(tr("手動", "Manual"), !auto) { auto = false; drawSched() }, ui.weight())
            schedBox.addView(modes, ui.lp(bottom = 10))
            if (auto) {
                val times = ui.row()
                slots.forEachIndexed { i, tm ->
                    times.addView(ui.secondary(tm) {
                        val (h, m) = tm.split(":").map { it.toInt() }
                        TimePickerDialog(this, android.R.style.Theme_DeviceDefault_Dialog, { _, hh, mm ->
                            slots[i] = "%02d:%02d".format(hh, mm); drawSched()
                        }, h, m, true).show()
                    }, ui.weight().apply { marginEnd = ui.dp(8) })
                }
                schedBox.addView(times)
            }
        }
        drawSched()
        add(schedBox, bottom = 28)

        fun build(): JSONObject? {
            val items = where.text.toString().split('\n', ',', '，').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (items.isEmpty()) { toast(tr("請填關鍵詞", "Add at least one keyword")); return null }
            val links = items.filter { it.startsWith("http", true) || it.startsWith("@") }
            val words = items - links.toSet()
            val lv = levelOf(script, level)
            val cfg = JSONObject().put("level", level)
            if (cl) {
                val urls = links.map { if (it.startsWith("@")) "https://www.tiktok.com/$it" else it }
                cfg.put("comment_mode", if (urls.isNotEmpty()) "precise" else "keywords")
                    .put("urls", JSONArray(urls)).put("keywords", JSONArray(words))
                    .put("cl_do_like", true).put("cl_do_reply", true)
            } else {
                cfg.put("mode", if (links.isNotEmpty()) "accounts" else "keywords")
                    .put("seed_accounts", JSONArray(links)).put("keywords", JSONArray(words))
                    .put("leads_per_run", lv.leads)
                    .put("lead_do_like", true).put("lead_do_follow", true).put("lead_do_comment", true)
            }
            val name = d.optString("name").ifEmpty { s.title() + " · " + items.first().removePrefix("https://www.tiktok.com/").take(20) }
            return JSONObject()
                .put("id", d.optString("id").ifEmpty { "t" + System.currentTimeMillis().toString(36) })
                .put("name", name).put("script_id", script).put("config", cfg)
                .put("max_likes", lv.likes).put("max_comments", lv.comments).put("max_follows", lv.follows)
                .put("enabled", auto).put("slots", JSONArray(slots.sorted()))
                .put("tz", ZoneId.systemDefault().id).put("max_run_minutes", 60)
                .put("persona", persona.text.toString().take(400))
        }

        add(ui.primary(if (editing) tr("保存", "Save") else tr("保存並運行", "Save and run")) {
            val t = build() ?: return@primary
            saveTask(t) { if (!editing) withRunPerms { RunService.start(this, RunService.ACTION_RUN_NOW, t.optString("id")); toast(tr("已開始", "Started")) } }
        }, bottom = 10)
        if (!editing) add(ui.secondary(tr("只保存", "Save only")) { build()?.let { saveTask(it) } })
    }

    // ---------- save / sync ----------
    private fun saveTask(t: JSONObject, then: (() -> Unit)? = null) {
        val list = Prefs.tasks().toMutableList()
        val i = list.indexOfFirst { it.optString("id") == t.optString("id") }
        if (i >= 0) list[i] = t else list.add(t)
        pushTasks(list, tr("已保存", "Saved"), then)
    }

    private fun pushTasks(list: List<JSONObject>, msg: String, then: (() -> Unit)? = null) {
        lifecycleScope.launch {
            try {
                val arr = JSONArray(); list.forEach { arr.put(JSONObject(it.toString()).apply { remove("_editing"); remove("_level") }) }
                val saved = Api.saveTasks(arr).optJSONArray("tasks") ?: arr
                Prefs.tasksJson = saved.toString()
                RunService.start(this@MainActivity) // stand by: daily times + runs started from the desktop app
                draft = null
                toast(msg)
                go(Screen.HOME)
                then?.invoke()
            } catch (e: Exception) { toast(tr("保存失敗：", "Save failed: ") + e.message) }
        }
    }

    private suspend fun syncTasks() {
        runCatching { Api.fetchTasks().optJSONArray("tasks") }.getOrNull()?.let { Prefs.tasksJson = it.toString() }
    }

    // ---------- logs ----------
    private fun renderLogs() {
        topBar(tr("運行記錄", "Run log")) { go(Screen.ME) }
        val lines = RunLog.recent().takeLast(200).reversed()
        if (lines.isEmpty()) { add(ui.sub(tr("暫無記錄", "Nothing yet"))); return }
        val card = ui.card()
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
        lines.forEach { l ->
            val color = when (l.level) { "error" -> C.DANGER; "warn" -> C.WARN; else -> C.TEXT }
            val r = ui.row().apply { gravity = Gravity.TOP }
            r.addView(ui.text(fmt.format(java.util.Date(l.ts)), 11f, C.SUB).apply { setPadding(0, ui.dp(1), ui.dp(10), 0) })
            r.addView(ui.text(l.msg, 13f, color), ui.weight())
            card.addView(r, ui.lp(bottom = 8))
        }
        add(card, bottom = 14)
        add(ui.secondary(tr("聯繫客服", "Contact support")) { sendDiagnostics() })
    }

    private fun sendDiagnostics() {
        lifecycleScope.launch {
            try {
                val logs = JSONArray()
                RunLog.recent().takeLast(200).forEach { logs.put(JSONObject().put("ts", it.ts).put("level", it.level).put("msg", it.msg)) }
                val code = Api.sendDiagnostics(
                    JSONObject().put("logs", logs)
                        .put("app_ver", BuildConfig.VERSION_NAME)
                        .put("android_sdk", Build.VERSION.SDK_INT)
                        .put("vendor", Build.MANUFACTURER + "/" + Build.MODEL)
                        .put("a11y", NcAccessibilityService.isRunning)
                )
                toast(tr("客服碼：$code（請發給客服）", "Support code: $code (send it to support)"))
                RunLog.i(tr("已發送診斷，客服碼 ", "Diagnostics sent, support code ") + code)
            } catch (e: Exception) { toast(tr("發送失敗：", "Send failed: ") + e.message) }
        }
    }
}
