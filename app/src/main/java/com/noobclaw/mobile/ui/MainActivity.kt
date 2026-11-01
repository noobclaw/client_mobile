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
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.noobclaw.mobile.BuildConfig
import com.noobclaw.mobile.a11y.A11yGuard
import com.noobclaw.mobile.a11y.NcAccessibilityService
import com.noobclaw.mobile.net.Api
import com.noobclaw.mobile.run.RunService
import com.noobclaw.mobile.run.Schedule
import com.noobclaw.mobile.util.I18n
import com.noobclaw.mobile.util.Prefs
import com.noobclaw.mobile.util.RunLog
import com.noobclaw.mobile.util.Updater
import com.noobclaw.mobile.util.tr
import com.noobclaw.mobile.util.trf
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZoneId

/**
 * Single activity, screens built in code. Two tabs at the bottom: 任務 / 我的.
 * 登錄 → 使用說明 → 權限引導 → 任務（雲手機卡片：平台標籤、帳號、任務要點、刪除 / 查看 / 運行；運行中顯示進度和步驟）
 * · 任務詳情 · 任務設置 · 我的（帳號、會員、權限、語言、記錄、客服、退出）。
 */
class MainActivity : AppCompatActivity() {

    private enum class Screen { HOME, ME, CONFIG, DETAIL, LOGS, PERMS }

    private lateinit var ui: Ui
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var nav: LinearLayout
    private var screen = Screen.HOME
    private var draft: JSONObject? = null           // task being created / edited
    // in-app forced update
    private var updateDialog: Dialog? = null
    private var updateRel: Updater.Release? = null
    private var updateApk: java.io.File? = null
    private var updateBusy = false
    private var updateCheckedAt = 0L
    private var configBack = Screen.HOME             // where 返回 / 保存 goes from the editor
    private var platformTab = "tiktok"
    private var permsThen: (() -> Unit)? = null      // continues after the permission screen
    private var permsAutoShown = false
    private var a11yTipOpen = false
    private val main = Handler(Looper.getMainLooper())
    private var lastSig = ""
    private var syncedAt = 0L
    private var balance: JSONObject? = null          // /api/ai/balance, loaded on the 我的 tab
    private val logListener: (RunLog.Line) -> Unit = { l ->
        runOnUiThread { if (screen == Screen.LOGS || (l.level == "step" && (screen == Screen.HOME || screen == Screen.DETAIL))) refreshSoon() }
    }

    // ---------- scripts / platforms ----------
    private val CL = "tiktok_comment_like"
    private val LEAD = "tiktok_lead_engage"
    private fun scriptTitle(id: String) = if (id == LEAD) tr("定向獲客任務", "Lead task") else tr("評論區點讚任務", "Comment-like task")
    private fun scriptShort(id: String) = if (id == LEAD) tr("定向獲客", "Lead engage") else tr("評論區點讚", "Comment likes")
    private fun scriptDesc(id: String) = if (id == LEAD) tr("找客戶，點讚、評論、關注", "Find leads: like, comment, follow")
        else tr("給評論點讚，AI 回覆", "Like comments, AI replies")

    private data class Platform(val id: String, val live: Boolean)
    private val allPlatforms = listOf(Platform("tiktok", true), Platform("douyin", false), Platform("xhs", false))
    private fun platformName(id: String) = when (id) { "douyin" -> tr("抖音", "Douyin"); "xhs" -> tr("小紅書", "RedNote"); else -> "TikTok" }
    /** Platforms not hidden by the backend compliance switch. */
    private fun visiblePlatforms() = allPlatforms.filter { it.id !in Prefs.hiddenPlatforms }
    private fun currentTab(): String {
        val vis = visiblePlatforms()
        if (vis.none { it.id == platformTab }) platformTab = vis.firstOrNull()?.id ?: "tiktok"
        return platformTab
    }

    private fun platformOf(t: JSONObject): String = Prefs.platformOf(t)
    /** One task per platform; the TikTok task is the first TikTok one in the list (the scheduler uses the same pick). */
    private fun taskFor(platform: String) = Prefs.primaryTasks().firstOrNull { platformOf(it) == platform }
    private fun hm(h: Int, m: Int) = String.format(java.util.Locale.US, "%02d:%02d", h, m)
    private fun taskById(id: String?) = Prefs.tasks().firstOrNull { it.optString("id") == id }

    /** Default names are stored in Traditional Chinese (shared with the desktop); show them in the app's language. */
    private fun taskName(t: JSONObject): String {
        val n = t.optString("name").trim()
        val sid = t.optString("script_id")
        return when {
            n.isEmpty() || n == "評論區點讚任務" || n == "定向獲客任務" -> scriptTitle(sid)
            n.startsWith("評論區點讚 · ") -> tr("評論區點讚", "Comment likes") + " · " + n.removePrefix("評論區點讚 · ")
            n.startsWith("定向獲客 · ") -> tr("定向獲客", "Lead engage") + " · " + n.removePrefix("定向獲客 · ")
            else -> n
        }
    }

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.SURFACE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0 // light icons on the dark bars
        root = ui.col().apply { setPadding(ui.dp(16), ui.dp(20), ui.dp(16), ui.dp(32)) }
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
                    Screen.ME, Screen.DETAIL, Screen.PERMS -> { if (screen == Screen.PERMS) permsThen = null; go(Screen.HOME) }
                    Screen.LOGS -> go(Screen.ME)
                    Screen.CONFIG -> go(configBack)
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        reportLastCrash()
        val inApp = Prefs.token.isNotEmpty() && Prefs.consentVersion >= RunService.CONSENT_VERSION
        if (inApp) RunService.start(this) // stand by
        if (inApp) com.noobclaw.mobile.run.FloatBall.show(this)
        // Coming back from the system settings: continue when every permission is on.
        if (screen == Screen.PERMS && missingPerms().isEmpty()) {
            val then = permsThen; permsThen = null
            screen = Screen.HOME
            render()
            then?.invoke()
        } else render()
        RunLog.onLine(logListener)
        main.post(ticker)
        // Back from「允許安裝未知應用」settings with the APK ready → install; otherwise look for a new version.
        val apk = updateApk
        if (apk != null && apk.exists() && Updater.canInstall(this)) runCatching { Updater.install(this, apk) }
        else checkUpdate(force = updateDialog?.isShowing == true)
        if (inApp && System.currentTimeMillis() - syncedAt > 60_000) {
            syncedAt = System.currentTimeMillis()
            lifecycleScope.launch { Api.refreshCompliance(); syncTasks(); if (screen == Screen.HOME || screen == Screen.DETAIL) render() }
        }
    }

    override fun onPause() {
        RunLog.offLine(logListener)
        main.removeCallbacks(ticker)
        super.onPause()
    }

    /** What the task screens show; when it changes (run starts / ends, progress, task list from the server) they re-render. */
    private fun signature(): String = listOf(
        RunService.runningTaskId, RunService.liveProgress()?.let { it.optJSONObject("counts")?.toString() + it.optLong("credits") },
        Prefs.tasksJson.hashCode(), Prefs.bindingJson.hashCode(), Prefs.hiddenPlatforms.hashCode(), Prefs.overLimit,
        NcAccessibilityService.isRunning,
    ).joinToString("|")

    private val ticker = object : Runnable {
        override fun run() {
            val sig = signature()
            if (sig != lastSig && (screen == Screen.HOME || screen == Screen.DETAIL)) render()
            if (System.currentTimeMillis() - updateCheckedAt > 30 * 60_000L) checkUpdate()
            lastSig = sig
            main.postDelayed(this, 2000)
        }
    }
    private var refreshQueued = false
    private fun refreshSoon() {
        if (refreshQueued) return
        refreshQueued = true
        main.postDelayed({ refreshQueued = false; if (screen == Screen.LOGS || screen == Screen.HOME || screen == Screen.DETAIL) render() }, 800)
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
    private fun gap(w: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(w), 1) }

    private fun render() {
        root.removeAllViews()
        val inApp = Prefs.token.isNotEmpty() && Prefs.consentVersion >= RunService.CONSENT_VERSION
        // Missing permissions: the step screen shows once per app start (and whenever a run needs them).
        if (inApp && screen == Screen.HOME && !permsAutoShown && missingPerms().isNotEmpty()) { permsAutoShown = true; screen = Screen.PERMS }
        if (inApp && screen == Screen.HOME) permsAutoShown = true
        lastSig = signature()
        when {
            Prefs.token.isEmpty() -> renderLogin()
            !inApp -> renderConsent()
            else -> when (screen) {
                Screen.HOME -> renderHome()
                Screen.ME -> renderMe()
                Screen.CONFIG -> renderConfig()
                Screen.DETAIL -> renderDetail()
                Screen.LOGS -> renderLogs()
                Screen.PERMS -> renderPerms()
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
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(ui.link("🌐 " + I18n.lang.label, C.SUB) { chooseLanguage() })
        add(top)
        root.addView(ui.spacer(24))   // keep the spacer's own fixed height (add() would make it WRAP_CONTENT = fill)
        add(ui.text("NoobClaw", 34f, C.ACCENT, true))
        root.addView(ui.spacer(28))
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
                    Api.refreshCompliance()
                    syncTasks(); syncedAt = System.currentTimeMillis(); screen = Screen.HOME; render()
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
            permsAutoShown = false // next: the permission steps (if any is missing)
            screen = Screen.HOME
            render()
        }, bottom = 10)
        add(ui.link(tr("退出登錄", "Log out"), C.SUB) { logout() }.apply { gravity = Gravity.CENTER })
    }

    private fun logout() {
        Prefs.logout(); com.noobclaw.mobile.run.FloatBall.hide(); RunService.start(this, RunService.ACTION_SHUTDOWN); balance = null; balanceAt = 0L; balanceFailed = false
        permsAutoShown = false; screen = Screen.HOME; render()
    }

    // ---------- permissions ----------
    private enum class PermKind { A11Y, OVERLAY, BATTERY }

    private fun granted(k: PermKind): Boolean = when (k) {
        PermKind.A11Y -> NcAccessibilityService.isRunning
        PermKind.OVERLAY -> Settings.canDrawOverlays(this)
        PermKind.BATTERY -> getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }

    private fun missingPerms(): List<PermKind> = PermKind.values().filter { !granted(it) }

    /** Opens the system page for a permission; falls back to more general pages on ROMs without the direct one. */
    private fun openPerm(k: PermKind) {
        val pkg = Uri.parse("package:$packageName")
        val tries = when (k) {
            PermKind.A11Y -> listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                // Some Settings apps scroll to / highlight our service with these extras.
                val comp = "$packageName/${NcAccessibilityService::class.java.name}"
                putExtra(":settings:fragment_args_key", comp)
                putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", comp) })
            })
            PermKind.OVERLAY -> listOf(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg), Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            PermKind.BATTERY -> listOf(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg), Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } + Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        for (i in tries) if (runCatching { startActivity(i) }.isSuccess) return
        toast(tr("打不開系統設置，請手動打開", "Couldn't open Settings — please open it yourself"))
    }

    private fun openAppDetails() {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            .onFailure { toast(tr("打不開系統設置，請手動打開", "Couldn't open Settings — please open it yourself")) }
    }

    /** Runs [action] when every permission is on; otherwise shows the permission steps first and continues after. */
    private fun withPerms(action: () -> Unit) {
        if (missingPerms().isEmpty()) action() else { permsThen = action; go(Screen.PERMS) }
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

    /** Confirm dialog: title, text, 取消 / [yes]. */
    private fun confirm(title: String, body: String, yes: String, danger: Boolean = true, onYes: () -> Unit) {
        val (d, box) = dialogBox()
        box.addView(ui.h2(title), ui.lp(bottom = 10))
        box.addView(ui.text(body, 14f, C.SUB), ui.lp(bottom = 18))
        val r = ui.row()
        r.addView(ui.secondary(tr("取消", "Cancel")) { d.dismiss() }, ui.weight())
        r.addView(gap(10))
        r.addView(ui.small(yes, Color.WHITE, if (danger) C.DANGER else C.ACCENT, null) { d.dismiss(); onYes() }.apply { setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)) }, ui.weight())
        box.addView(r)
        showDialog(d, box)
    }

    private fun vendorKey(): String? {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> "xiaomi"
            m.contains("huawei") || m.contains("honor") -> "huawei"
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> "oppo"
            m.contains("vivo") || m.contains("iqoo") -> "vivo"
            else -> null
        }
    }

    /** Step screen: "開始前，需要開 3 個權限" — one card per permission, re-checked on return from Settings. */
    private fun renderPerms() {
        val kinds = PermKind.values().toList()
        val done = kinds.count { granted(it) }
        val head = ui.row()
        head.addView(ui.text(tr("開始前，需要開 3 個權限", "Before you start: turn on 3 permissions"), 22f, C.TEXT, true), ui.weight())
        add(head, bottom = 6)
        add(ui.sub(tr("開好後會自動繼續。每個只需開一次。", "We'll continue by ourselves once they're on. Each one is a one-time switch.")), bottom = 14)

        // progress bar k / 3
        val pr = ui.row()
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = ui.shape(C.SURFACE2, 4) }
        bar.addView(View(this).apply { background = ui.shape(C.ACCENT, 4) }, LinearLayout.LayoutParams(0, ui.dp(6), done.toFloat().coerceAtLeast(0.001f)))
        bar.addView(View(this), LinearLayout.LayoutParams(0, ui.dp(6), (3 - done).toFloat().coerceAtLeast(0.001f)))
        pr.addView(bar, LinearLayout.LayoutParams(0, ui.dp(6), 1f))
        pr.addView(ui.text("  $done / 3", 13f, C.ACCENT, true))
        add(pr, bottom = 18)

        kinds.forEachIndexed { i, k ->
            val ok = granted(k)
            val card = ui.card(selected = false)
            val r = ui.row()
            r.addView(if (ok) ui.badge("✓", C.ACCENT, C.ON_ACCENT, 32) else ui.badge("${i + 1}", C.SURFACE2, C.TEXT, 32).apply { background = ui.shape(C.SURFACE2, 16, C.BORDER) })
            val t = ui.col().apply { setPadding(ui.dp(12), 0, ui.dp(8), 0) }
            t.addView(ui.text(when (k) {
                PermKind.A11Y -> tr("無障礙服務", "Accessibility service")
                PermKind.OVERLAY -> tr("懸浮窗", "Display over other apps")
                PermKind.BATTERY -> tr("後台運行", "Run in background")
            }, 16f, C.TEXT, true))
            t.addView(ui.text(when (k) {
                PermKind.A11Y -> tr("讓 NoobClaw 能替你在 TikTok 裡點讚、回覆。", "Lets NoobClaw tap and type in TikTok for you.")
                PermKind.OVERLAY -> tr("運行時在屏幕上顯示停止按鈕，隨時能停。", "Shows a stop button on screen while a task runs.")
                PermKind.BATTERY -> tr("鎖屏或切到別的 App 時，任務也能按時運行。", "Keeps tasks on schedule when the screen is off or you switch apps.")
            }, 13f, C.SUB), ui.lp(top = 2))
            r.addView(t, ui.weight())
            if (ok) r.addView(ui.text(tr("✓ 已開啟", "✓ On"), 13f, C.ACCENT, true))
            else r.addView(ui.small(tr("去開啟", "Turn on"), C.ON_ACCENT, C.ACCENT, null) { openPerm(k) })
            card.addView(r)

            if (k == PermKind.A11Y && !ok) {
                card.addView(ui.sub(tr("打開後找到「NoobClaw」，把開關打開。", "In the list, find \"NoobClaw\" and switch it on.")), ui.lp(top = 10))
                if (Build.VERSION.SDK_INT >= 33) {
                    val tip = ui.col().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = ui.shape(ui.alpha(C.WARN, 18), 12, ui.alpha(C.WARN, 80)) }
                    tip.addView(ui.text((if (a11yTipOpen) "▾ " else "▸ ") + tr("開關是灰色的，點不動？", "Is the switch grey and won't turn on?"), 13f, C.WARN, true).apply {
                        setOnClickListener { a11yTipOpen = !a11yTipOpen; render() }
                    })
                    if (a11yTipOpen) {
                        tip.addView(ui.text(tr(
                            "Android 13 以上，不是從應用商店裝的 App 要先解鎖：\n1. 點下面的按鈕，打開 NoobClaw 的應用信息\n2. 點右上角 ⋮ →「允許受限設置」\n3. 回到這裡，再點「去開啟」",
                            "On Android 13+, apps not installed from a store must be unlocked first:\n1. Tap the button below to open NoobClaw's App info\n2. Tap ⋮ (top right) → \"Allow restricted settings\"\n3. Come back here and tap \"Turn on\" again"),
                            13f, C.TEXT), ui.lp(top = 8, bottom = 10))
                        tip.addView(ui.small(tr("打開應用信息", "Open App info"), C.WARN, C.SURFACE, ui.alpha(C.WARN, 120)) { openAppDetails() })
                    }
                    card.addView(tip, ui.lp(top = 10))
                }
            }
            add(card, bottom = 12)
        }

        vendorKey()?.let { v ->
            val card = ui.card()
            card.addView(ui.text(tr("建議：允許自啟動", "Tip: allow auto-start"), 15f, C.TEXT, true), ui.lp(bottom = 4))
            card.addView(ui.text(when (v) {
                "xiaomi" -> tr("小米 / 紅米：應用信息裡打開「自啟動」，省電策略選「無限制」。", "Xiaomi / Redmi: in App info turn on \"Autostart\" and set battery saver to \"No restrictions\".")
                "huawei" -> tr("華為 / 榮耀：應用啟動管理裡把 NoobClaw 改成手動管理，三個開關都打開。", "Huawei / Honor: in App launch, set NoobClaw to manual and turn on all three switches.")
                "oppo" -> tr("OPPO / realme / 一加：應用信息裡允許「自啟動」和「後台運行」。", "OPPO / realme / OnePlus: in App info allow \"Auto launch\" and background activity.")
                else -> tr("vivo / iQOO：在 i 管家或應用信息裡允許「自啟動」和「後台高耗電」。", "vivo / iQOO: allow \"Auto start\" and high background power use in App info or i Manager.")
            }, 13f, C.SUB), ui.lp(bottom = 10))
            card.addView(ui.small(tr("打開應用信息", "Open App info")) { openAppDetails() })
            add(card, bottom = 12)
        }

        if (done == 3) add(ui.primary(tr("繼續", "Continue")) { val then = permsThen; permsThen = null; go(Screen.HOME); then?.invoke() }, top = 6)
        add(ui.link(tr("稍後再說", "Later"), C.SUB) { permsThen = null; go(Screen.HOME) }.apply { gravity = Gravity.CENTER }, top = 8)
    }

    // ---------- tab 1: the cloud-phone card ----------
    private fun binding(): JSONObject = runCatching { JSONObject(Prefs.bindingJson) }.getOrElse { JSONObject() }
    private fun tiktokHandle() = binding().optString("display_id")

    private fun tiktokInstalled(): Boolean = A11yGuard.TARGET_PACKAGES.any { pkg ->
        runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    private val AV = listOf("#7b5cf6", "#00a3ff", "#ff6a3d", "#e2468a", "#14b8a6", "#f59e0b", "#6366f1", "#22c55e").map { Color.parseColor(it) }
    private fun avColor(h: String): Int { var n = 0L; for (c in h) n = (n * 31 + c.code) and 0xffffffffL; return AV[(n % AV.size).toInt()] }

    /** Initial-letter avatar + nickname + @handle. */
    private fun accountRow(withModel: Boolean = false): View? {
        val b = binding()
        val handle = b.optString("display_id").ifEmpty { return null }
        val nick = b.optString("nickname").ifEmpty { handle.removePrefix("@") }
        val r = ui.row()
        val first = nick.codePointAt(0).let { String(Character.toChars(it)) }.uppercase()
        r.addView(ui.badge(first, avColor(handle), Color.WHITE, 34))
        val t = ui.col().apply { setPadding(ui.dp(10), 0, 0, 0) }
        t.addView(ui.text(nick, 14f, C.TEXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        t.addView(ui.text(handle + if (withModel) " · " + Build.MANUFACTURER + "/" + Build.MODEL else "", 12f, C.SUB).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        r.addView(t, ui.weight())
        return r
    }

    private fun statusPill(): View {
        val running = RunService.runningTaskId != null
        return when {
            running -> ui.pillSolid(tr("運行中", "Running"), C.ACCENT, C.ON_ACCENT)
            Prefs.overLimit -> ui.pill(tr("超出會員數", "Over limit"), C.WARN)
            !NcAccessibilityService.isRunning -> ui.pill(tr("缺權限", "Permissions"), C.WARN)
            System.currentTimeMillis() < Prefs.cooldownUntil -> ui.pill(tr("冷卻中", "Cooling down"), C.DANGER)
            else -> ui.pill(tr("在線", "Online"), C.ACCENT)
        }
    }

    private fun renderHome() {
        val head = ui.row()
        val title = ui.col()
        // 標題:NoobClaw + 後面小一號的版本號
        val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        nameRow.addView(ui.text("NoobClaw", 20f, C.TEXT, true))
        nameRow.addView(ui.text("（v${BuildConfig.VERSION_NAME}）", 12f, C.SUB).apply { setPadding(ui.dp(4), 0, 0, ui.dp(2)) })
        title.addView(nameRow)
        head.addView(title, ui.weight())
        add(head, bottom = 14)

        val running = RunService.runningTaskId != null
        val card = ui.col(14).apply {
            background = if (running) ui.shape(C.SURFACE, 22, ui.alpha(C.ACCENT, 200), 2) else ui.shape(C.SURFACE, 22, C.BORDER)
        }
        // 設備ID chip
        card.addView(ui.mono(tr("設備ID: ", "Device ID: ") + shortId(Prefs.deviceId), 11f, C.TEXT, true).apply {
            setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3)); background = ui.shape(ui.alpha(C.TEXT, 14), 7, C.BORDER)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.dp(10); gravity = Gravity.CENTER_HORIZONTAL })
        // model + status
        val top = ui.row()
        val mt = ui.col()
        mt.addView(ui.text(Build.MANUFACTURER + "/" + Build.MODEL, 15f, C.TEXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        mt.addView(ui.mono("Android ${Build.VERSION.RELEASE}", 11f, C.SUB))
        top.addView(mt, ui.weight())
        top.addView(statusPill())
        card.addView(top, ui.lp(bottom = 12))

        if (Prefs.overLimit) {
            val warn = ui.col().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = ui.shape(ui.alpha(C.WARN, 18), 12, ui.alpha(C.WARN, 90)) }
            warn.addView(ui.text("🔒  " + overLimitText(), 14f, C.WARN, true))
            warn.addView(ui.sub(tr("升級會員，或在電腦端移除不用的手機", "Upgrade, or remove an unused phone in the desktop app")))
            card.addView(warn, ui.lp(bottom = 12))
        }

        when {
            running -> card.addView(runPanel())
            !NcAccessibilityService.isRunning -> card.addView(permPanel())
            else -> card.addView(platformPanel())
        }
        add(card, bottom = 12)

        // a11y is on but something else is missing: a small reminder under the card
        val missing = missingPerms()
        if (NcAccessibilityService.isRunning && missing.isNotEmpty()) {
            add(ui.link(tr("還有 ${missing.size} 個權限沒開，去開啟 ›", "${missing.size} permission(s) still off — turn on ›"), C.WARN) { go(Screen.PERMS) }.apply { gravity = Gravity.CENTER })
        }
    }

    /** a11y off: no platform UI, just the permission note (same as the desktop card). */
    private fun permPanel(): View {
        val p = ui.panel(ui.alpha(C.WARN, 90), ui.alpha(C.WARN, 14)).apply { setPadding(ui.dp(16), ui.dp(18), ui.dp(16), ui.dp(16)); gravity = Gravity.CENTER_HORIZONTAL }
        p.addView(ui.text(tr("運行需要權限", "Permissions needed to run"), 16f, C.WARN, true).apply { gravity = Gravity.CENTER }, ui.lp(bottom = 6))
        p.addView(ui.text(tr("請為 NoobClaw App 打開無障礙服務權限", "Turn on the accessibility service for the NoobClaw app"), 13f, C.WARN).apply { gravity = Gravity.CENTER }, ui.lp(bottom = 14))
        p.addView(ui.small(tr("去開啟", "Turn on"), C.ON_ACCENT, C.ACCENT, null) { go(Screen.PERMS) }.apply { setPadding(ui.dp(22), ui.dp(10), ui.dp(22), ui.dp(10)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return p
    }

    /** Segmented platform tabs (TikTok / 抖音 / 小紅書, minus the ones hidden by compliance). */
    private fun platformTabs(): View? {
        val vis = visiblePlatforms()
        if (vis.size < 2) return null
        val cur = currentTab()
        val bar = ui.row().apply { setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)); background = ui.shape(Color.argb(90, 0, 0, 0), 10, C.BORDER) }
        vis.forEach { p ->
            val on = p.id == cur
            val tab = ui.text(platformName(p.id), 13f, if (on) C.ON_ACCENT else if (p.live) C.TEXT else C.SUB, true).apply {
                gravity = Gravity.CENTER; setPadding(0, ui.dp(7), 0, ui.dp(7))
                background = if (on) ui.shape(C.ACCENT, 8) else null
                isClickable = true
                setOnClickListener { if (platformTab != p.id) { platformTab = p.id; render() } }
            }
            bar.addView(tab, ui.weight())
        }
        return bar
    }

    /** Short device id for display: the first block of the id (e.g. "c520ac72"), same as the desktop card. */
    private fun shortId(id: String): String = id.substringBefore('-').let { if (it.length >= 6) it else id.take(12) }

    private fun emptyBox(title: String, sub: String, warn: Boolean, btn: View? = null): View {
        val b = ui.col().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(ui.dp(8), ui.dp(18), ui.dp(8), ui.dp(14)) }
        b.addView(ui.text(title, 15f, if (warn) C.WARN else C.TEXT, true).apply { gravity = Gravity.CENTER }, ui.lp(bottom = 6))
        b.addView(ui.text(sub, 13f, C.SUB).apply { gravity = Gravity.CENTER }, ui.lp(bottom = if (btn != null) 14 else 0))
        if (btn != null) b.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return b
    }

    private fun platformPanel(): View {
        val p = ui.panel()
        platformTabs()?.let { p.addView(it, ui.lp(top = 0).apply { setMargins(ui.dp(8), ui.dp(8), ui.dp(8), 0) }) }
        val body = ui.col().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(12)) }
        p.addView(body)
        val vis = visiblePlatforms()
        if (vis.isEmpty()) { body.addView(emptyBox(tr("暫無可用平台", "No platform available"), "", false)); return p }
        val tab = currentTab()
        val name = platformName(tab)
        if (allPlatforms.firstOrNull { it.id == tab }?.live != true) {
            body.addView(emptyBox(tr("即將推出", "Coming soon"), tr("$name 任務正在開發中", "$name tasks are on the way"), false))
            return p
        }
        val createBtn = { enabled: Boolean ->
            ui.small(tr("＋ 創建任務", "＋ Create task"), if (enabled) C.ON_ACCENT else C.TEXT, if (enabled) C.ACCENT else C.SURFACE, if (enabled) null else C.BORDER, enabled) { newTask() }
                .apply { setPadding(ui.dp(18), ui.dp(9), ui.dp(18), ui.dp(9)) }
        }
        if (!tiktokInstalled()) {
            body.addView(emptyBox(tr("未檢測到 TikTok 應用", "TikTok app not found"),
                tr("請先在這臺手機上安裝 TikTok，再創建任務", "Install TikTok on this phone first, then create a task"), true, createBtn(false)))
            return p
        }
        val acct = accountRow()
        if (acct != null) {
            body.addView(acct, ui.lp(bottom = 8)); body.addView(ui.divider(), ui.lp(h = 1, bottom = 10))
        } else {
            val w = ui.col().apply { setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8)); background = ui.shape(ui.alpha(C.WARN, 14), 10, ui.alpha(C.WARN, 80)) }
            w.addView(ui.text(tr("未檢測到 TikTok 帳號", "No TikTok account detected yet"), 13f, C.WARN, true))
            w.addView(ui.text(tr("請在這臺手機上打開 TikTok 並登錄。第一次運行時會自動識別帳號。",
                "Open TikTok on this phone and log in. The first run detects the account by itself."), 12f, C.SUB))
            body.addView(w, ui.lp(bottom = 10))
        }
        val t = taskFor("tiktok")
        if (t == null) {
            body.addView(emptyBox(tr("TikTok 還沒有任務", "No TikTok task yet"), tr("每個平台可以建 1 個任務", "One task per platform"), false, createBtn(true)))
            return p
        }
        taskBlock(t, body)
        return p
    }

    private fun isKeywordMode(t: JSONObject): Boolean {
        val c = t.optJSONObject("config") ?: JSONObject()
        return if (t.optString("script_id") == LEAD) c.optString("mode") != "accounts" else c.optString("comment_mode") == "keywords"
    }
    private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    private fun keywordsOf(t: JSONObject) = strs(t.optJSONObject("config")?.optJSONArray("keywords"))
    private fun targetsOf(t: JSONObject): List<String> {
        val c = t.optJSONObject("config") ?: JSONObject()
        return strs(if (t.optString("script_id") == LEAD) c.optJSONArray("seed_accounts") else c.optJSONArray("urls"))
    }
    private fun targetLabel(t: JSONObject) = when {
        isKeywordMode(t) -> tr("關鍵詞", "Keywords")
        t.optString("script_id") == LEAD -> tr("同行帳號", "Competitors")
        else -> tr("鏈接", "Links")
    }
    private fun targetText(t: JSONObject): String {
        val l = if (isKeywordMode(t)) keywordsOf(t) else targetsOf(t).map { it.removePrefix("https://").removePrefix("www.") }
        return l.joinToString(tr("、", ", ")).ifEmpty { "—" }
    }
    private fun modeText(t: JSONObject) = if (isKeywordMode(t)) tr("關鍵詞模式", "Keyword mode") else tr("精準模式", "Precise mode")

    private fun scheduleText(t: JSONObject): String = when (val k = Schedule.kind(t)) {
        is Schedule.Kind.Every -> when (k.hours) {
            0.5 -> tr("每 30 分鐘", "Every 30 min"); 1.0 -> tr("每 1 小時", "Every hour")
            else -> tr("每 ${k.hours.toInt()} 小時", "Every ${k.hours.toInt()} hours")
        }
        is Schedule.Kind.Daily -> tr("每天 ", "Daily at ") + hm(k.at.hour, k.at.minute)
        is Schedule.Kind.Random -> tr("每天隨機 ", "Daily, random ") + hm(k.from.hour, k.from.minute) + "–" + hm(k.to.hour, k.to.minute)
        Schedule.Kind.Manual -> tr("手動", "Manual")
    }

    private fun fmtNum(n: Long) = String.format(java.util.Locale.US, "%,d", n)
    private fun fmtWhen(ms: Long): String {
        val z = ZoneId.systemDefault()
        val d = java.time.Instant.ofEpochMilli(ms).atZone(z)
        val today = java.time.LocalDate.now(z)
        val clock = hm(d.hour, d.minute)
        return when (d.toLocalDate()) {
            today -> tr("今天 ", "Today ") + clock
            today.plusDays(1) -> tr("明天 ", "Tomorrow ") + clock
            today.minusDays(1) -> tr("昨天 ", "Yesterday ") + clock
            else -> String.format(java.util.Locale.US, "%02d/%02d ", d.monthValue, d.dayOfMonth) + clock
        }
    }
    private fun fmtDur(ms: Long): String {
        val m = ms / 60_000
        return if (m < 1) tr("不到 1 分鐘", "< 1 min") else tr("$m 分鐘", "$m min")
    }

    private fun doneSlots(): Set<String> =
        getSharedPreferences("nc_slots", MODE_PRIVATE).getStringSet("done", emptySet()) ?: emptySet()

    private fun lastRunText(t: JSONObject): String {
        val st = Prefs.taskStats(t.optString("id")) ?: return "—"
        val at = st.optLong("last_started_at").takeIf { it > 0 } ?: return "—"
        return fmtWhen(at) + when (st.optString("last_status")) {
            "stopped" -> tr("（已停止）", " (stopped)")
            "error", "rejected" -> tr("（出錯）", " (error)")
            else -> ""
        }
    }

    private fun nextRunText(t: JSONObject): String {
        if (Prefs.overLimit) return "—"
        val id = t.optString("id")
        val at = Schedule.nextRunAt(t, Prefs.lastStart(id), doneSlots()) ?: return tr("手動運行", "Run manually")
        return if (at - System.currentTimeMillis() < 60_000) tr("馬上", "Now") else fmtWhen(at)
    }

    /** "點讚 1,286 · 回覆 174" / "新客戶 214 · 關注 120 · 評論 86". */
    private fun countsText(sid: String, c: JSONObject?): String {
        val m = c ?: JSONObject()
        return if (sid == LEAD) tr("新客戶 ${fmtNum(m.optLong("lead_new"))} · 關注 ${fmtNum(m.optLong("follow"))} · 評論 ${fmtNum(m.optLong("comment"))}",
            "${fmtNum(m.optLong("lead_new"))} leads · ${fmtNum(m.optLong("follow"))} follows · ${fmtNum(m.optLong("comment"))} comments")
        else tr("點讚 ${fmtNum(m.optLong("like"))} · 回覆 ${fmtNum(m.optLong("comment"))}", "${fmtNum(m.optLong("like"))} likes · ${fmtNum(m.optLong("comment"))} replies")
    }

    private fun taskBlock(t: JSONObject, body: LinearLayout) {
        val sid = t.optString("script_id")
        body.addView(ui.text(taskName(t), 15f, C.TEXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, ui.lp(bottom = 4))
        body.addView(ui.kv(tr("模式", "Mode"), modeText(t)))
        body.addView(ui.kv(targetLabel(t), targetText(t)))
        body.addView(ui.kv(tr("定時", "Schedule"), scheduleText(t)))
        body.addView(ui.kv(tr("最近運行", "Last run"), lastRunText(t)))
        body.addView(ui.kv(tr("下次運行", "Next run"), nextRunText(t)))
        body.addView(ui.divider(), ui.lp(h = 1, top = 6, bottom = 6))
        body.addView(ui.text(tr("累計完成", "Total done"), 12f, C.SUB))
        body.addView(ui.text(countsText(sid, Prefs.taskStats(t.optString("id"))?.optJSONObject("totals")), 13f, C.TEXT), ui.lp(bottom = 12))

        val id = t.optString("id")
        val btns = ui.row()
        btns.addView(ui.small(tr("刪除", "Delete"), C.DANGER, C.SURFACE, ui.alpha(C.DANGER, 100)) { confirmDelete(t) }, ui.weight())
        btns.addView(gap(8))
        btns.addView(ui.small(tr("查看", "View")) { go(Screen.DETAIL) }, ui.weight())
        btns.addView(gap(8))
        btns.addView(ui.small(tr("▶ 運行", "▶ Run"), C.ON_ACCENT, C.ACCENT, null, tiktokInstalled()) { runTask(id) }, ui.weight())
        body.addView(btns)
    }

    private fun runTask(id: String) = withRunPerms { RunService.start(this, RunService.ACTION_RUN_NOW, id); toast(tr("已開始", "Started")) }

    private fun confirmDelete(t: JSONObject) {
        confirm(tr("刪除這個任務？", "Delete this task?"),
            trf("「{}」刪除後不會再自動運行，運行記錄保留。", "\"{}\" will stop running. Run history is kept.", taskName(t)),
            tr("刪除", "Delete")) {
            // removes every task of this platform (left-over duplicates from older versions included)
            val list = Prefs.tasks().filter { platformOf(it) != platformOf(t) }
            pushTasks(list, tr("已刪除", "Deleted"), Screen.HOME)
        }
    }

    // ---------- running state ----------
    private data class Item(val icon: String, val key: String)
    private fun progressItems(sid: String) = if (sid == LEAD)
        listOf(Item("👤", "lead_new"), Item("👍", "like"), Item("💬", "comment"), Item("➕", "follow"))
        else listOf(Item("👍", "like"), Item("💬", "comment"))

    /** "進度 👍 6/100 💬 1/15" and "消耗 💎 4,820". */
    private fun progressBox(sid: String, big: Boolean): View {
        val live = RunService.liveProgress() ?: JSONObject()
        val c = live.optJSONObject("counts") ?: JSONObject()
        val tg = live.optJSONObject("targets") ?: JSONObject()
        val box = ui.col().apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(10)) }
        val items = progressItems(sid).filter { tg.optLong(it.key) > 0 || c.optLong(it.key) > 0 }
            .joinToString("   ") { "${it.icon} ${fmtNum(c.optLong(it.key))}/${fmtNum(tg.optLong(it.key))}" }.ifEmpty { "—" }
        fun line(k: String, v: String) = ui.row().apply {
            setPadding(0, ui.dp(2), 0, ui.dp(2))
            addView(ui.text(k, if (big) 13f else 12f, C.ACCENT, true).apply { setPadding(0, 0, ui.dp(12), 0) })
            addView(ui.mono(v, if (big) 14f else 13f, C.TEXT, true).apply { gravity = Gravity.END }, ui.weight())
        }
        box.addView(line(tr("進度", "Progress"), items))
        box.addView(line(tr("消耗", "Used"), "💎 " + fmtNum(live.optLong("credits"))))
        return box
    }

    /** Friendly step lines of a run (time + sentence), newest last. */
    private fun stepLines(runId: String?): List<Pair<Long, String>> {
        if (runId == null) return emptyList()
        return RunLog.recent().filter { it.runId == runId && it.level == "step" }.mapNotNull { l -> Steps.text(l.msg)?.let { l.ts to it } }
    }

    private fun stepList(runId: String?, max: Int, liveDot: Boolean): View {
        val box = ui.col()
        val lines = stepLines(runId).takeLast(max)
        if (lines.isEmpty()) { box.addView(ui.text(if (liveDot) tr("準備中…", "Getting ready…") else tr("暫無明細", "No details"), 13f, C.SUB)); return box }
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        lines.forEachIndexed { i, (ts, txt) ->
            val last = i == lines.size - 1
            val r = ui.row().apply { gravity = Gravity.TOP; setPadding(0, ui.dp(3), 0, ui.dp(3)) }
            r.addView(ui.mono(fmt.format(java.util.Date(ts)), 12f, C.SUB).apply { setPadding(0, ui.dp(1), ui.dp(10), 0) })
            r.addView(ui.text(txt + if (last && liveDot) "  ●" else "", 13f, if (last) C.TEXT else C.SUB), ui.weight())
            if (!last) r.alpha = 0.55f + 0.45f * (i + 1) / lines.size
            box.addView(r)
        }
        return box
    }

    private fun runPanel(): View {
        val t = taskById(RunService.runningTaskId)
        val sid = t?.optString("script_id") ?: CL
        val name = t?.let { taskName(it) } ?: scriptTitle(sid)
        val p = ui.panel()
        val head = ui.col().apply { background = ui.shape(ui.alpha(C.ACCENT, 18), 10, ui.alpha(C.ACCENT, 90)) }
        val mq = TextView(this).apply {
            text = "●  " + tr("正在運行", "Running") + " TikTok · " + name + "          "
            textSize = 13f; setTextColor(C.ACCENT); typeface = android.graphics.Typeface.DEFAULT_BOLD
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1
            isFocusable = true; isFocusableInTouchMode = true; isSelected = true; isHorizontalFadingEdgeEnabled = true
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
        }
        head.addView(mq)
        head.addView(ui.divider(ui.alpha(C.ACCENT, 50)))
        head.addView(progressBox(sid, false))
        p.addView(head, ui.lp().apply { setMargins(ui.dp(8), ui.dp(8), ui.dp(8), 0) })
        val body = ui.col().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(12)) }
        accountRow()?.let { body.addView(it, ui.lp(bottom = 8)); body.addView(ui.divider(), ui.lp(h = 1, bottom = 8)) }
        body.addView(stepList(RunService.runningRunId, 6, true), ui.lp(bottom = 12))
        val r = ui.row().apply { gravity = Gravity.CENTER }
        r.addView(ui.small(tr("■ 停止", "■ Stop"), Color.WHITE, C.DANGER, null) { RunService.start(this, RunService.ACTION_STOP_RUN) }
            .apply { setPadding(ui.dp(24), ui.dp(7), ui.dp(24), ui.dp(7)); textSize = 13f })
        body.addView(r)
        if (t != null) body.addView(ui.link(tr("查看詳情 ›", "Details ›"), C.SUB) { go(Screen.DETAIL) }.apply { gravity = Gravity.CENTER }, ui.lp(top = 4))
        p.addView(body)
        return p
    }

    // ---------- task detail ----------
    private fun statusName(s: String) = when (s) {
        "done", "ok", "completed" -> tr("完成", "Done")
        "stopped" -> tr("已停止", "Stopped")
        "error" -> tr("出錯", "Error")
        "rejected" -> tr("未運行", "Not run")
        "challenge" -> tr("被驗證打斷", "Verification")
        else -> s
    }
    private fun statusColor(s: String) = when (s) { "done", "ok", "completed" -> C.ACCENT; "stopped" -> C.WARN; else -> C.DANGER }

    private fun renderDetail() {
        val t = taskFor("tiktok") ?: taskById(RunService.runningTaskId) ?: run { go(Screen.HOME); return }
        val id = t.optString("id")
        val sid = t.optString("script_id")
        val running = RunService.runningTaskId == id
        topBar(tr("任務詳情", "Task details")) { go(Screen.HOME) }

        val head = ui.card(selected = running)
        val tr0 = ui.row()
        tr0.addView(ui.badge("TT", Color.BLACK, Color.WHITE, 36).apply { background = ui.shape(Color.BLACK, 18, C.CYAN, 2) })
        val tt = ui.col().apply { setPadding(ui.dp(10), 0, 0, 0) }
        tt.addView(ui.text(taskName(t), 17f, C.TEXT, true))
        tt.addView(ui.mono(tr("設備ID ", "Device ") + shortId(Prefs.deviceId), 11f, C.SUB).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        tr0.addView(tt, ui.weight())
        head.addView(tr0, ui.lp(bottom = 12))
        val b = binding()
        head.addView(ui.kv(tr("帳號", "Account"), (listOf(b.optString("nickname"), b.optString("display_id")).filter { it.isNotEmpty() }.joinToString(" ")
            .ifEmpty { tr("未識別", "Not detected") }) + " · " + Build.MANUFACTURER + "/" + Build.MODEL))
        head.addView(ui.kv(tr("模式", "Mode"), (if (isKeywordMode(t)) "🔎 " else "🎯 ") + modeText(t)))
        head.addView(ui.kv(targetLabel(t), targetText(t)))
        head.addView(ui.kv(tr("每次上限", "Per run"), if (sid == LEAD)
            tr("新客戶 ${t.optJSONObject("config")?.optInt("leads_per_run", 30) ?: 30} · 關注 ${t.optInt("max_follows")} · 評論 ${t.optInt("max_comments")}",
                "${t.optJSONObject("config")?.optInt("leads_per_run", 30) ?: 30} leads · ${t.optInt("max_follows")} follows · ${t.optInt("max_comments")} comments")
            else tr("點讚 ${t.optInt("max_likes")} · 回覆 ${t.optInt("max_comments")}", "${t.optInt("max_likes")} likes · ${t.optInt("max_comments")} replies")))
        head.addView(ui.kv(tr("人設", "Persona"), t.optString("persona").ifEmpty { "—" }))
        head.addView(ui.kv(tr("定時", "Schedule"), scheduleText(t) + if (Schedule.kind(t) !is Schedule.Kind.Manual) tr(" · 自動運行", " · automatic") else ""))
        val acts = ui.row().apply { setPadding(0, ui.dp(12), 0, 0) }
        if (running) {
            acts.addView(ui.text("● " + tr("運行中", "Running"), 14f, C.ACCENT, true), ui.weight())
            acts.addView(ui.small(tr("停止", "Stop"), Color.WHITE, C.DANGER, null) { RunService.start(this, RunService.ACTION_STOP_RUN) }.apply { setPadding(ui.dp(22), ui.dp(9), ui.dp(22), ui.dp(9)) })
        } else {
            acts.addView(ui.small(tr("編輯", "Edit")) { editTask(t, Screen.DETAIL) }, ui.weight())
            acts.addView(gap(8))
            acts.addView(ui.small(tr("刪除", "Delete"), C.DANGER, C.SURFACE, ui.alpha(C.DANGER, 100)) { confirmDelete(t) }, ui.weight())
            acts.addView(gap(8))
            acts.addView(ui.small(tr("立即運行", "Run now"), C.ON_ACCENT, C.ACCENT, null, tiktokInstalled()) { runTask(id) }, ui.weight())
        }
        head.addView(acts)
        add(head, bottom = 12)

        if (running) {
            val g = ui.col().apply { background = ui.shape(ui.alpha(C.ACCENT, 18), 14, ui.alpha(C.ACCENT, 90)) }
            g.addView(ui.text("● " + tr("本次運行", "This run"), 13f, C.ACCENT, true).apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), 0) })
            g.addView(progressBox(sid, true))
            add(g, bottom = 12)
        }

        // stats grid
        val st = Prefs.taskStats(id)
        val cells = listOf(
            tr("累計完成", "Total done") to countsText(sid, st?.optJSONObject("totals")),
            tr("累計消耗", "Total used") to "💎 " + fmtNum(st?.optLong("credits") ?: 0),
            tr("上次完成", "Last run did") to (st?.let { countsText(sid, it.optJSONObject("last_counts")) } ?: "—"),
            tr("上次消耗", "Last run used") to (st?.let { "💎 " + fmtNum(it.optLong("last_credits")) } ?: "—"),
            tr("上次運行", "Last run") to lastRunText(t),
            tr("下次運行", "Next run") to nextRunText(t),
        )
        cells.chunked(2).forEach { pair ->
            val r = ui.row().apply { gravity = Gravity.TOP }
            pair.forEachIndexed { i, (k, v) ->
                val c = ui.col(12).apply { background = ui.shape(C.SURFACE, 14, C.BORDER) }
                c.addView(ui.text(k, 12f, C.SUB))
                c.addView(ui.text(v, 14f, C.TEXT, true), ui.lp(top = 4))
                r.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i == 0) marginEnd = ui.dp(10) })
            }
            add(r, bottom = 10)
        }

        // current run steps (or the last run's)
        val log = ui.card()
        log.addView(ui.text(tr("當前運行明細", "Current run"), 15f, C.TEXT, true), ui.lp(bottom = 10))
        val rid = if (running) RunService.runningRunId else st?.optString("last_run_id")?.takeIf { it.isNotEmpty() }
        log.addView(stepList(rid, 40, running))
        if (!running) log.addView(ui.sub(tr("沒有運行中的任務，上面是最近一次的明細", "Nothing running — above is the latest run")), ui.lp(top = 8))
        add(log, bottom = 12)

        // recent runs
        val runs = Prefs.runHistory().filter { it.optString("task_id") == id }.take(10)
        val rc = ui.card()
        rc.addView(ui.text(tr("最近運行", "Recent runs"), 15f, C.TEXT, true), ui.lp(bottom = 6))
        if (runs.isEmpty()) rc.addView(ui.sub(tr("還沒有運行過", "No runs yet")))
        runs.forEachIndexed { i, r ->
            if (i > 0) rc.addView(ui.divider())
            val row = ui.col().apply { setPadding(0, ui.dp(10), 0, ui.dp(10)); isClickable = true; setOnClickListener { showRunSteps(r) } }
            val l1 = ui.row()
            l1.addView(ui.text(fmtWhen(r.optLong("started_at")) + if (r.optBoolean("dry_run")) tr("（試跑）", " (dry run)") else "", 14f, C.TEXT, true), ui.weight())
            l1.addView(ui.pill(statusName(r.optString("status")), statusColor(r.optString("status"))))
            row.addView(l1)
            row.addView(ui.text(countsText(sid, r.optJSONObject("counts")) + " · 💎 " + fmtNum(r.optLong("credits")) + " · " + fmtDur(r.optLong("duration_ms")), 12f, C.SUB), ui.lp(top = 3))
            rc.addView(row)
        }
        add(rc, bottom = 12)
        // 「試跑」默認不顯示;後台功能開關 dry_run 打開時才有(排查用)
        if (Prefs.flag("dry_run")) {
            val anyRun = RunService.runningTaskId != null
            add(ui.link(tr("試跑（不扣費）", "Dry run (free)"), C.SUB) {
                if (RunService.runningTaskId != null) return@link
                withRunPerms { RunService.start(this, RunService.ACTION_DRY_RUN, id); toast(tr("試跑開始", "Dry run started")) }
            }.apply { gravity = Gravity.CENTER; isEnabled = !anyRun; alpha = if (anyRun) 0.35f else 1f })
        }
    }

    private fun showRunSteps(r: JSONObject) {
        val (d, box) = dialogBox()
        box.addView(ui.h2(fmtWhen(r.optLong("started_at")) + " · " + statusName(r.optString("status"))), ui.lp(bottom = 12))
        box.addView(stepList(r.optString("run_id"), 40, false), ui.lp(bottom = 14))
        box.addView(ui.secondary(tr("關閉", "Close")) { d.dismiss() })
        showDialog(d, box)
    }

    // ---------- tab 2: me ----------
    private fun renderMe() {
        add(ui.text(tr("我的", "Me"), 26f, C.TEXT, true), bottom = 18)

        // profile
        val prof = ui.card()
        val pr = ui.row()
        val who = Prefs.account.ifEmpty { if (Prefs.wallet.length > 10) Prefs.wallet.take(6) + "…" + Prefs.wallet.takeLast(4) else Prefs.wallet.ifEmpty { "NoobClaw" } }
        pr.addView(ui.badge(who.take(1).uppercase(), C.PURPLE, Color.WHITE, 52))
        val pt = ui.col().apply { setPadding(ui.dp(14), 0, 0, 0) }
        pt.addView(ui.text(who, 17f, C.TEXT, true))
        val b = balance
        pt.addView(ui.sub(if (b != null) planLabel(b) else if (balanceFailed) "—" else tr("加載中…", "Loading…")))
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
        stat(b?.let { expiryText(it) } ?: "—", tr("會員到期", "Plan ends"), last = true)
        add(stats, bottom = 18)
        if (System.currentTimeMillis() - balanceAt > 60_000) loadBalance()

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
        // 「可用手機」「TikTok 帳號」去掉(不只跑 TikTok;帳號在首頁各平台卡片上看)
        item(tr("權限", "Permissions"), if (missing.isEmpty()) tr("已開啟", "All on") else tr("缺 ${missing.size} 項", "${missing.size} missing"),
            if (missing.isEmpty()) C.ACCENT else C.WARN) { go(Screen.PERMS) }
        item(tr("語言", "Language"), I18n.lang.label) { chooseLanguage() }
        // 「運行記錄」「聯繫客服」兩行去掉(2026-11-01 用戶要求;運行明細在電腦端看)
        item(tr("檢查更新", "Check for updates"), "v${BuildConfig.VERSION_NAME}", divider = false) { manualUpdateCheck() }
        add(list, bottom = 18)

        add(ui.secondary(tr("退出登錄", "Log out"), C.DANGER) { logout() })
    }

    private fun planName(code: String) = when (code) {
        "basic" -> tr("基礎版", "Basic"); "pro" -> tr("進階版", "Pro"); "max" -> tr("旗艦版", "Max"); "ultra" -> tr("至尊版", "Ultra")
        else -> tr("免費版", "Free")
    }
    private fun str(b: JSONObject, k: String) = b.optString(k).takeIf { it.isNotEmpty() && it != "null" }
    /** Membership state from the subscription period (subStatus + subPeriodEnd), not the monthly credit bucket. */
    private fun planLabel(b: JSONObject): String {
        val end = str(b, "subPeriodEnd")?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull() }
        val active = str(b, "subStatus") == "active" && end != null && end.isAfter(java.time.Instant.now())
        val subCode = str(b, "subPlanCode")
        return when {
            active -> planName(subCode ?: str(b, "planCode") ?: "free")
            subCode != null && subCode != "free" -> planName(subCode) + " · " + tr("已過期", "expired")
            else -> planName("free")
        }
    }
    private fun expiryText(b: JSONObject): String {
        val end = b.optString("subPeriodEnd").takeIf { it.isNotEmpty() && it != "null" } ?: return "—"
        return runCatching { java.time.OffsetDateTime.parse(end).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate().toString() }.getOrElse { end.take(10) }
    }
    private fun fmtCredits(n: Long): String = when {
        n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", n / 1_000.0)
        else -> n.toString()
    }
    private var balanceLoading = false
    private var balanceAt = 0L
    private var balanceFailed = false
    private fun loadBalance() {
        if (balanceLoading) return
        balanceLoading = true
        val token = Prefs.token
        lifecycleScope.launch {
            // peek=1: read-only (doesn't consume the desktop's commission notices)
            val r = runCatching { Api.get("/api/ai/balance?peek=1") }
            balanceLoading = false
            if (Prefs.token != token) return@launch // logged out / switched account meanwhile
            r.onSuccess { balance = it; balanceFailed = false }.onFailure { balanceFailed = true }
            balanceAt = System.currentTimeMillis()
            if (screen == Screen.ME) render()
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

    // ---------- in-app forced update ----------
    /** Asks the server for the latest APK (at most every 10 min unless [force]); a newer one opens the update dialog. */
    private fun checkUpdate(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (updateBusy || (!force && now - updateCheckedAt < 10 * 60_000L)) return
        updateCheckedAt = now
        lifecycleScope.launch {
            val rel = Updater.check() ?: return@launch
            if (isFinishing || isDestroyed) return@launch
            updateRel = rel
            if (updateDialog?.isShowing != true) showUpdateDialog(rel)
        }
    }

    /** 「我的 → 檢查更新」:有新版本就彈更新窗,否則提示已是最新。 */
    private fun manualUpdateCheck() {
        if (updateBusy) { updateDialog?.takeIf { !it.isShowing }?.show(); return }
        toast(tr("正在檢查…", "Checking…"))
        lifecycleScope.launch {
            updateCheckedAt = System.currentTimeMillis()
            val rel = Updater.check()
            if (isFinishing || isDestroyed) return@launch
            if (rel == null) { toast(tr("已是最新版本 v${BuildConfig.VERSION_NAME}", "You're up to date (v${BuildConfig.VERSION_NAME})")); return@launch }
            updateRel = rel
            if (updateDialog?.isShowing != true) showUpdateDialog(rel)
        }
    }

    private fun showUpdateDialog(rel: Updater.Release) {
        val (d, box) = dialogBox()
        d.setCancelable(false)          // forced: no back / outside tap
        d.setCanceledOnTouchOutside(false)
        box.addView(ui.h2(tr("發現新版本 v${rel.version}", "New version v${rel.version}")), ui.lp(bottom = 10))
        box.addView(ui.text(tr("當前 v${BuildConfig.VERSION_NAME}，需要更新後才能繼續使用。", "You have v${BuildConfig.VERSION_NAME}. Update to keep using the app."), 14f, C.SUB), ui.lp(bottom = 6))
        if (rel.notes.isNotBlank()) box.addView(ui.text(rel.notes, 13f, C.SUB), ui.lp(bottom = 6))
        val status = ui.text("", 13f, C.SUB)
        box.addView(status, ui.lp(top = 6, bottom = 14))
        lateinit var btn: TextView
        btn = ui.primary(tr("立即更新", "Update now")) {
            if (updateBusy) return@primary
            val ready = updateApk
            if (ready != null && ready.exists()) { installUpdate(ready, status); return@primary }
            updateBusy = true
            btn.text = tr("下載中…", "Downloading…")
            lifecycleScope.launch {
                try {
                    val f = Updater.download(this@MainActivity, rel) { p ->
                        status.text = if (p >= 0) tr("已下載 $p%", "Downloaded $p%") else tr("下載中…", "Downloading…")
                    }
                    updateApk = f
                    btn.text = tr("安裝", "Install")
                    status.text = tr("下載完成", "Downloaded")
                    installUpdate(f, status)
                } catch (e: Exception) {
                    btn.text = tr("重試", "Retry")
                    status.text = tr("下載失敗：", "Download failed: ") + (e.message ?: "")
                } finally { updateBusy = false }
            }
        }
        box.addView(btn, ui.lp(bottom = 8))
        box.addView(ui.secondary(tr("用瀏覽器下載", "Download in browser")) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.url))) }
        })
        updateDialog = d
        showDialog(d, box)
    }

    private fun installUpdate(apk: java.io.File, status: TextView) {
        if (!Updater.canInstall(this)) {
            status.text = tr("請允許「安裝未知應用」，返回後自動安裝", "Allow \"install unknown apps\", then come back to install")
            Updater.openInstallPermission(this)
            return
        }
        runCatching { Updater.install(this, apk) }.onFailure { status.text = tr("無法打開安裝程序：", "Couldn't open the installer: ") + (it.message ?: "") }
    }

    /** Title on the left, a switch on the right (same interaction as the desktop editor); the whole row toggles. */
    private fun switchRow(title: String, on: Boolean, onToggle: () -> Unit): View {
        val r = ui.row().apply {
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            background = ui.shape(C.SURFACE2, 12, C.BORDER)
            setOnClickListener { onToggle() }
        }
        r.addView(ui.text(title, 15f, C.TEXT, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // track + knob drawn by hand: green when on, grey when off
        val track = android.widget.FrameLayout(this).apply { background = ui.shape(if (on) C.ACCENT else Color.parseColor("#3A4260"), 12) }
        val knob = View(this).apply { background = ui.shape(Color.WHITE, 9) }
        track.addView(knob, android.widget.FrameLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply {
            gravity = Gravity.CENTER_VERTICAL or (if (on) Gravity.END else Gravity.START); marginStart = ui.dp(3); marginEnd = ui.dp(3)
        })
        r.addView(track, LinearLayout.LayoutParams(ui.dp(42), ui.dp(24)))
        return r
    }

    // ---------- task editor ----------
    private data class Level(val likes: Int, val comments: Int, val follows: Int, val leads: Int)
    private fun levelOf(script: String, l: String) = when (script) {
        CL -> when (l) { "low" -> Level(30, 5, 0, 0); "high" -> Level(300, 30, 0, 0); else -> Level(100, 15, 0, 0) }
        else -> when (l) { "low" -> Level(10, 5, 10, 10); "high" -> Level(100, 30, 50, 100); else -> Level(30, 15, 30, 30) }
    }
    private fun levelName(k: String) = when (k) {
        "low" -> tr("少", "Few"); "high" -> tr("多", "Many"); else -> tr("中", "Normal")
    }
    private fun levelFromCaps(t: JSONObject): String {
        val l = t.optJSONObject("config")?.optString("level").orEmpty()
        if (l.isNotEmpty()) return l
        val likes = t.optInt("max_likes", 100)
        return if (likes <= 30) "low" else if (likes >= 200) "high" else "mid"
    }

    private fun newTask() {
        if (taskFor(currentTab()) != null) { toast(tr("每個平台只能建 1 個任務", "One task per platform")); return }
        draft = JSONObject().put("script_id", CL).put("schedule", Schedule.toJson(Schedule.Kind.Daily(LocalTime.of(9, 30)))) // 新建默認每天定時
        configBack = Screen.HOME
        go(Screen.CONFIG)
    }

    private fun editTask(t: JSONObject, back: Screen) {
        draft = JSONObject(t.toString()).put("_editing", true)
        configBack = back
        go(Screen.CONFIG)
    }

    private fun renderConfig() {
        val d = draft ?: JSONObject().put("script_id", CL).also { draft = it }
        val editing = d.optBoolean("_editing")
        topBar(if (editing) tr("編輯任務", "Edit task") else tr("創建任務", "Create task")) { go(configBack) }
        add(ui.sub("TikTok · " + tr("每個平台 1 個任務", "one task per platform")), bottom = 16)

        var script = d.optString("_script").ifEmpty { d.optString("script_id").ifEmpty { CL } }
        // draft keeps both lists, so switching mode / script does not lose what was typed
        if (!d.has("_kw")) d.put("_kw", keywordsOf(d).joinToString("\n"))
        if (!d.has("_tg")) d.put("_tg", targetsOf(d).joinToString("\n"))
        var kwMode = if (d.has("_kwMode")) d.optBoolean("_kwMode") else (d.optJSONObject("config") == null || isKeywordMode(d))

        // script
        add(ui.section(tr("劇本", "Script")), bottom = 8)
        val sRow = ui.row()
        listOf(CL, LEAD).forEachIndexed { i, s ->
            val c = ui.card(selected = s == script, onClick = { d.put("_script", s); render() })
            c.addView(ui.text(scriptShort(s), 15f, if (s == script) C.ACCENT else C.TEXT, true))
            c.addView(ui.text(scriptDesc(s), 12f, C.SUB), ui.lp(top = 2))
            sRow.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i == 0) marginEnd = ui.dp(10) })
        }
        add(sRow, bottom = 18)
        val cl = script == CL

        // mode
        add(ui.section(tr("模式", "Mode")), bottom = 8)
        val mRow = ui.row()
        mRow.addView(ui.chip(tr("關鍵詞模式", "Keyword mode"), kwMode) { d.put("_kwMode", true); render() }, ui.weight().apply { marginEnd = ui.dp(8) })
        mRow.addView(ui.chip(tr("精準模式", "Precise mode"), !kwMode) { d.put("_kwMode", false); render() }, ui.weight())
        add(mRow, bottom = 6)
        add(ui.sub(when {
            kwMode -> tr("按關鍵詞搜視頻，在評論區互動", "Searches videos by keyword and works their comments")
            cl -> tr("只在你給的作品鏈接下互動", "Works only the videos you link")
            else -> tr("從同行帳號的粉絲和評論裡找客戶", "Finds leads among a competitor's commenters")
        }), bottom = 12)

        val label = when { kwMode -> tr("關鍵詞（每行一個）", "Keywords (one per line)"); cl -> tr("作品鏈接（每行一個）", "Video links (one per line)"); else -> tr("同行帳號（每行一個）", "Competitor accounts (one per line)") }
        add(ui.section(label), bottom = 8)
        val where = ui.input(when {
            kwMode -> tr("例如 latte art", "e.g. latte art")
            cl -> "https://www.tiktok.com/@name/video/…"
            else -> tr("例如 @rival", "e.g. @rival")
        }, if (kwMode) d.optString("_kw") else d.optString("_tg"), multi = true)
        where.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { d.put(if (kwMode) "_kw" else "_tg", s?.toString() ?: "") }
        })
        add(where, bottom = 20)

        fun watch(et: android.widget.EditText, key: String) = et.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { d.put(key, s?.toString() ?: "") }
        })
        // 評論區點讚(和電腦端雲機任務一致,2026-11):點讚 / 評論各自開關、最多數量(留空 = 不限)、間隔秒數,
        // 評論時可選 AI 自主評論 + 引流語(改寫、概率)。舊任務沒有這些字段 → 按原來的默認值顯示。
        val oc = if (d.optString("script_id") == CL) d.optJSONObject("config") else null
        val hasCl = oc != null && (oc.has("cl_like_gap_sec") || oc.has("cl_ai_comment") || oc.has("cl_like_nolimit"))
        if (!d.has("_doLike")) {
            d.put("_doLike", oc?.optBoolean("cl_do_like", true) ?: true)
            d.put("_doReply", if (hasCl) oc!!.optBoolean("cl_do_reply", true) else false)
            d.put("_nLike", if (hasCl && !oc!!.optBoolean("cl_like_nolimit") && d.optInt("max_likes") > 0) d.optInt("max_likes").toString() else "")
            d.put("_nReply", if (hasCl && !oc!!.optBoolean("cl_reply_nolimit") && d.optInt("max_comments") > 0) d.optInt("max_comments").toString() else "")
            d.put("_gLike", (oc?.optInt("cl_like_gap_sec", 1) ?: 1).toString())
            d.put("_gReply", (oc?.optInt("cl_reply_gap_sec", 30) ?: 30).toString())
            d.put("_ai", oc?.optBoolean("cl_ai_comment", true) ?: true)
            d.put("_funnel", strs(oc?.optJSONArray("cl_funnel")).joinToString("\n"))
            d.put("_rw", oc?.optBoolean("cl_funnel_rewrite", true) ?: true)
            d.put("_prob", oc?.optInt("cl_funnel_prob", 100) ?: 100)
        }
        var level = d.optString("_level").ifEmpty { levelFromCaps(d) }
        if (cl) {
            add(ui.section(tr("動作", "Actions")), bottom = 8)
            val actBox = ui.col()
            fun drawActs() {
                actBox.removeAllViews()
                fun action(on: Boolean, title: String, onKey: String, nKey: String, gKey: String, gHint: String) {
                    actBox.addView(switchRow(title, on) { d.put(onKey, !on); render() }, ui.lp(bottom = 8))
                    if (!on) return
                    val r = ui.row()
                    val n = ui.input(tr("最多(留空=不限)", "Max (empty = no limit)"), d.optString(nKey), number = true).apply { textSize = 14f }
                    val g = ui.input(gHint, d.optString(gKey), number = true).apply { textSize = 14f }
                    watch(n, nKey); watch(g, gKey)
                    r.addView(n, ui.weight().apply { marginEnd = ui.dp(8) })
                    r.addView(g, ui.weight())
                    actBox.addView(r, ui.lp(bottom = 12))
                }
                action(d.optBoolean("_doLike"), tr("點讚評論", "Like comments"), "_doLike", "_nLike", "_gLike", tr("間隔秒數(默認 1)", "Gap sec (default 1)"))
                action(d.optBoolean("_doReply"), tr("回覆評論", "Reply to comments"), "_doReply", "_nReply", "_gReply", tr("間隔秒數(默認 30)", "Gap sec (default 30)"))
            }
            drawActs()
            add(actBox)
            add(ui.sub(tr("最多留空 = 有多少做多少(每次最多點讚 1000、評論 300);間隔 = 做完一個等幾秒再做下一個",
                "Empty max = no limit (per run at most 1000 likes / 300 replies). Gap = seconds between actions")), bottom = 20)

            if (d.optBoolean("_doReply")) {
                add(ui.section(tr("評論內容", "Reply content")), bottom = 8)
                val ai = d.optBoolean("_ai")
                add(switchRow(tr("AI 根據評論自主回覆", "AI writes replies from the comment"), ai) { d.put("_ai", !ai); render() }, bottom = 12)
                add(ui.sub(tr("引流語(選填,每行一句)", "Funnel lines (optional, one per line)")), bottom = 6)
                val fn = ui.input(tr("例如:主頁有教程", "e.g. Tutorial on my profile"), d.optString("_funnel"), multi = true)
                watch(fn, "_funnel")
                add(fn, bottom = 10)
                val rw = d.optBoolean("_rw")
                add(switchRow(tr("AI 改寫引流語(每次說法不同)", "AI rewords funnel lines"), rw) { d.put("_rw", !rw); render() }, bottom = 10)
                if (ai) {
                    add(ui.sub(tr("帶引流語的概率", "Chance to add a funnel line")), bottom = 6)
                    val pr = ui.row()
                    val cur = d.optInt("_prob", 100)
                    listOf(10, 30, 50, 100).forEachIndexed { i, v ->
                        pr.addView(ui.chip("$v%", v == cur) { d.put("_prob", v); render() }, ui.weight().apply { if (i < 3) marginEnd = ui.dp(8) })
                    }
                    add(pr, bottom = 6)
                    add(ui.sub(tr("沒填引流語時只發 AI 回覆", "Without funnel lines only the AI reply is sent")), bottom = 20)
                } else add(ui.sub(tr("關掉 AI 時,每條回覆就是一句引流語(需要至少填一句)", "With AI off every reply is a funnel line (add at least one)")), bottom = 20)
            }
        } else {
            // amount
            add(ui.section(tr("數量", "Amount")), bottom = 8)
            val amount = ui.sub("")
            val chips = ui.row()
            fun drawChips() {
                chips.removeAllViews()
                listOf("low", "mid", "high").forEach { k ->
                    val ch = ui.chip(levelName(k), k == level) { level = k; d.put("_level", k); drawChips() }
                    chips.addView(ch, ui.weight().apply { marginEnd = ui.dp(8) })
                }
                val lv = levelOf(script, level)
                amount.text = tr("每次：客戶 ${lv.leads} · 關注 ${lv.follows} · 點讚 ${lv.likes} · 評論 ${lv.comments}",
                                  "Per run: ${lv.leads} leads · ${lv.follows} follows · ${lv.likes} likes · ${lv.comments} comments")
            }
            drawChips()
            add(chips, bottom = 8)
            add(amount, bottom = 20)
        }

        // schedule
        add(ui.section(tr("定時", "Schedule")), bottom = 8)
        var kind = Schedule.kind(d)
        val schedBox = ui.col()
        fun drawSched() {
            schedBox.removeAllViews()
            val opts: List<Pair<String, Schedule.Kind>> = listOf(
                tr("每30分鐘", "30 min") to Schedule.Kind.Every(0.5),
                tr("每1小時", "1 hour") to Schedule.Kind.Every(1.0),
                tr("每3小時", "3 hours") to Schedule.Kind.Every(3.0),
                tr("每6小時", "6 hours") to Schedule.Kind.Every(6.0),
                tr("每天定時", "Daily") to Schedule.Kind.Daily((kind as? Schedule.Kind.Daily)?.at ?: LocalTime.of(9, 30)),
                tr("每天隨機", "Daily random") to ((kind as? Schedule.Kind.Random) ?: Schedule.Kind.Random(LocalTime.of(9, 0), LocalTime.of(22, 0))),
                tr("手動", "Manual") to Schedule.Kind.Manual,
            )
            opts.chunked(3).forEach { rowOpts ->
                val r = ui.row()
                rowOpts.forEachIndexed { i, (lab, k) ->
                    val on = when { k is Schedule.Kind.Daily -> kind is Schedule.Kind.Daily; k is Schedule.Kind.Random -> kind is Schedule.Kind.Random; else -> k == kind }
                    r.addView(ui.chip(lab, on) { kind = k; d.put("schedule", Schedule.toJson(k)); drawSched() }.apply { textSize = 13f; setPadding(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(10)) },
                        ui.weight().apply { if (i < 2) marginEnd = ui.dp(8) })
                }
                schedBox.addView(r, ui.lp(bottom = 8))
            }
            val k = kind
            if (k is Schedule.Kind.Daily) {
                val tm = hm(k.at.hour, k.at.minute)
                schedBox.addView(ui.secondary(tr("每天 $tm 運行（點擊修改）", "Runs daily at $tm (tap to change)")) {
                    TimePickerDialog(this, android.R.style.Theme_DeviceDefault_Dialog, { _, hh, mm ->
                        kind = Schedule.Kind.Daily(LocalTime.of(hh, mm)); d.put("schedule", Schedule.toJson(kind)); drawSched()
                    }, k.at.hour, k.at.minute, true).show()
                }, ui.lp(top = 2))
            }
            if (k is Schedule.Kind.Random) {
                // 隨機時間的範圍:開始 / 結束各一個時間選擇
                val rr = ui.row()
                fun pick(isFrom: Boolean) {
                    val cur = if (isFrom) k.from else k.to
                    TimePickerDialog(this, android.R.style.Theme_DeviceDefault_Dialog, { _, hh, mm ->
                        val t = LocalTime.of(hh, mm)
                        kind = if (isFrom) Schedule.Kind.Random(t, k.to) else Schedule.Kind.Random(k.from, t)
                        d.put("schedule", Schedule.toJson(kind)); drawSched()
                    }, cur.hour, cur.minute, true).show()
                }
                rr.addView(ui.secondary(tr("從 ", "From ") + hm(k.from.hour, k.from.minute)) { pick(true) }, ui.weight().apply { marginEnd = ui.dp(8) })
                rr.addView(ui.secondary(tr("到 ", "To ") + hm(k.to.hour, k.to.minute)) { pick(false) }, ui.weight())
                schedBox.addView(rr, ui.lp(top = 2))
            }
            schedBox.addView(ui.sub(when (k) {
                is Schedule.Kind.Every -> tr("從上次開始時間算起，到點自動運行", "Starts again this long after the last start")
                is Schedule.Kind.Daily -> tr("錯過時間 2 小時內會補跑", "Catches up within 2 hours if missed")
                is Schedule.Kind.Random -> tr("每天在這個時間段裡隨機挑一個時間運行一次", "Runs once a day at a random time in this window")
                Schedule.Kind.Manual -> tr("只在你點「運行」或電腦端發起時運行", "Runs only when you tap Run or start it from the desktop")
            }), ui.lp(top = 4))
        }
        drawSched()
        add(schedBox, bottom = 28)

        fun lines(s: String) = s.split('\n', ',', '，').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        fun build(): JSONObject? {
            val words = lines(d.optString("_kw")).take(20)
            val rawTargets = lines(d.optString("_tg"))
            val urlRe = Regex("^https://(www\\.|m\\.|vm\\.|vt\\.)?tiktok\\.com/\\S*$", RegexOption.IGNORE_CASE)
            val targets = if (cl) rawTargets.map { if (it.startsWith("@")) "https://www.tiktok.com/$it" else it }.filter { urlRe.matches(it) }.take(20)
                else rawTargets.map { if (it.startsWith("http", true) || it.startsWith("@")) it else "@$it" }.take(20)
            if (kwMode && words.isEmpty()) { toast(tr("請填至少 1 個關鍵詞", "Add at least one keyword")); return null }
            if (!kwMode && targets.isEmpty()) {
                toast(if (cl) tr("請填 TikTok 作品鏈接（https://www.tiktok.com/…）", "Add a TikTok video link (https://www.tiktok.com/…)")
                      else tr("請填至少 1 個同行帳號", "Add at least one competitor account"))
                return null
            }
            if (!kwMode && cl && targets.size < rawTargets.size) toast(tr("不是 TikTok 鏈接的已忽略", "Lines that aren't TikTok links were skipped"))
            fun posInt(v: String, lo: Int, hi: Int): Int? = v.trim().toIntOrNull()?.takeIf { it > 0 }?.coerceIn(lo, hi)
            val sameScript = editing && d.optString("script_id") == script
            // 同劇本編輯:保留原配置裡本頁沒有的字段(電腦端 / 新版本加的),只覆蓋本頁管的
            val cfg = if (sameScript) JSONObject((d.optJSONObject("config") ?: JSONObject()).toString()) else JSONObject()
            val out = JSONObject()
            if (cl) {
                val doLike = d.optBoolean("_doLike"); val doReply = d.optBoolean("_doReply")
                if (!doLike && !doReply) { toast(tr("請至少開啟一個動作", "Turn on at least one action")); return null }
                val ai = d.optBoolean("_ai")
                val funnel = lines(d.optString("_funnel").replace(',', '\u0000').replace('，', '\u0000')).map { it.replace('\u0000', ',') }.take(20).map { it.take(120) }
                if (doReply && !ai && funnel.isEmpty()) { toast(tr("關掉 AI 回覆時請至少填一句引流語", "With AI replies off, add at least one funnel line")); return null }
                val ml = posInt(d.optString("_nLike"), 1, 1000); val mc = posInt(d.optString("_nReply"), 1, 300)
                cfg.put("comment_mode", if (kwMode) "keywords" else "precise")
                    .put("urls", JSONArray(targets)).put("keywords", JSONArray(words)).put("level", "")
                    .put("cl_do_like", doLike).put("cl_do_reply", doReply)
                    .put("cl_like_nolimit", ml == null).put("cl_reply_nolimit", mc == null)
                    .put("cl_like_gap_sec", posInt(d.optString("_gLike"), 1, 600) ?: 1)
                    .put("cl_reply_gap_sec", posInt(d.optString("_gReply"), 5, 3600) ?: 30)
                    .put("cl_ai_comment", ai).put("cl_funnel", JSONArray(funnel))
                    .put("cl_funnel_rewrite", d.optBoolean("_rw", true))
                    .put("cl_funnel_prob", if (ai) d.optInt("_prob", 100) else 100)
                out.put("max_likes", ml ?: 1000).put("max_comments", mc ?: 300).put("max_follows", 0)
            } else {
                val lv = levelOf(script, level)
                cfg.put("level", level).put("mode", if (kwMode) "keywords" else "accounts")
                    .put("seed_accounts", JSONArray(targets)).put("keywords", JSONArray(words))
                    .put("leads_per_run", lv.leads)
                if (!sameScript) cfg.put("lead_do_like", true).put("lead_do_follow", true).put("lead_do_comment", true)
                out.put("max_likes", lv.likes).put("max_comments", lv.comments).put("max_follows", lv.follows)
            }
            // 默認名用繁體(跟服務器、電腦端一致);換了劇本就換成新劇本的默認名
            val baseName = if (cl) "評論區點讚任務" else "定向獲客任務"
            val keepName = sameScript && d.optString("name").isNotEmpty()
            val sc = Schedule.kind(d)
            return out
                .put("id", d.optString("id").ifEmpty { "t" + System.currentTimeMillis().toString(36) })
                .put("name", if (keepName) d.optString("name") else baseName)
                .put("script_id", script).put("platform", "tiktok").put("config", cfg)
                .put("schedule", Schedule.toJson(sc))
                // legacy fields for apps < 0.6 (the server fills them too)
                .put("enabled", sc !is Schedule.Kind.Manual)
                .put("slots", JSONArray(if (sc is Schedule.Kind.Daily) kotlin.collections.listOf(hm(sc.at.hour, sc.at.minute)) else emptyList<String>()))
                .put("tz", d.optString("tz").ifEmpty { ZoneId.systemDefault().id }) // edits keep the task's zone
                .put("max_run_minutes", d.optInt("max_run_minutes", 60))
                // 人設不再展示:清空,免得看不見的舊人設還在影響 AI 回覆(同電腦端)
                .put("persona", "")
        }

        add(ui.primary(if (editing) tr("保存", "Save") else tr("保存並運行", "Save and run")) {
            val t = build() ?: return@primary
            saveTask(t, if (editing) configBack else Screen.HOME) { if (!editing) runTask(t.optString("id")) }
        }, bottom = 10)
        if (!editing) add(ui.secondary(tr("只保存", "Save only")) {
            val t = build() ?: return@secondary
            // Saved without running: "every N hours" waits a full interval instead of starting right away.
            if (Schedule.kind(t) is Schedule.Kind.Every) Prefs.setLastStart(t.optString("id"), System.currentTimeMillis())
            saveTask(t, Screen.HOME)
        })
    }

    // ---------- save / sync ----------
    /** One task per platform: the saved task replaces any other task of the same platform. */
    private fun saveTask(t: JSONObject, then: Screen, after: (() -> Unit)? = null) {
        val list = Prefs.tasks().filter { platformOf(it) != platformOf(t) && it.optString("id") != t.optString("id") } + t
        pushTasks(list, tr("已保存", "Saved"), then, after)
    }

    private fun pushTasks(list: List<JSONObject>, msg: String, then: Screen, after: (() -> Unit)? = null) {
        lifecycleScope.launch {
            try {
                val arr = JSONArray()
                list.forEach { arr.put(JSONObject(it.toString()).apply { keys().asSequence().toList().filter { k -> k.startsWith("_") }.forEach { k -> remove(k) } }) }
                val saved = Api.saveTasks(arr).optJSONArray("tasks") ?: arr
                Prefs.tasksJson = saved.toString()
                RunService.start(this@MainActivity) // stand by: schedules + runs started from the desktop app
                draft = null
                toast(msg)
                go(if (then == Screen.DETAIL && taskFor("tiktok") == null) Screen.HOME else then)
                after?.invoke()
            } catch (e: com.noobclaw.mobile.net.ApiException) {
                toast(if (e.code == "one_task_per_platform") tr("每個平台只能建 1 個任務", "One task per platform")
                      else tr("保存失敗：", "Save failed: ") + e.message)
            } catch (e: Exception) { toast(tr("保存失敗：", "Save failed: ") + e.message) }
        }
    }

    private var dedupeDone = false

    /**
     * Fetches the task list. Phones upgraded from 0.5 may still hold several tasks of one platform: keep the first of
     * each platform, save the list back once and tell the user.
     */
    private suspend fun syncTasks() {
        runCatching { Api.fetchTasks().optJSONArray("tasks") }.getOrNull()?.let { Prefs.tasksJson = it.toString() }
        val all = Prefs.tasks()
        val keep = Prefs.primaryTasks()
        if (dedupeDone || keep.size == all.size) return
        dedupeDone = true
        val arr = JSONArray(); keep.forEach { arr.put(it) }
        runCatching { Api.saveTasks(arr) }.onSuccess { r ->
            Prefs.tasksJson = (r.optJSONArray("tasks") ?: arr).toString()
            toast(tr("每個平台只保留 1 個任務，多出的 ${all.size - keep.size} 個已刪除", "One task per platform now: ${all.size - keep.size} extra task(s) removed"))
        }.onFailure { dedupeDone = false }
    }

    // ---------- logs ----------
    private fun renderLogs() {
        topBar(tr("運行記錄", "Run log")) { go(Screen.ME) }
        val lines = RunLog.recent().takeLast(200).reversed().mapNotNull { l ->
            if (l.level == "step") Steps.text(l.msg)?.let { l to it } else l to l.msg
        }
        if (lines.isEmpty()) { add(ui.sub(tr("暫無記錄", "Nothing yet"))); return }
        val card = ui.card()
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
        lines.forEach { (l, msg) ->
            val color = when (l.level) { "error" -> C.DANGER; "warn" -> C.WARN; "step" -> C.ACCENT; else -> C.TEXT }
            val r = ui.row().apply { gravity = Gravity.TOP }
            r.addView(ui.text(fmt.format(java.util.Date(l.ts)), 11f, C.SUB).apply { setPadding(0, ui.dp(1), ui.dp(10), 0) })
            r.addView(ui.text(msg, 13f, color), ui.weight())
            card.addView(r, ui.lp(bottom = 8))
        }
        add(card, bottom = 14)
        add(ui.secondary(tr("聯繫客服", "Contact support")) { sendDiagnostics() })
    }

    private fun sendDiagnostics() {
        lifecycleScope.launch {
            try {
                val logs = JSONArray()
                RunLog.recent().takeLast(200).forEach {
                    logs.put(JSONObject().put("ts", it.ts).put("level", it.level).put("msg", if (it.level == "step") it.msg else RunLog.redact(it.msg)))
                }
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
