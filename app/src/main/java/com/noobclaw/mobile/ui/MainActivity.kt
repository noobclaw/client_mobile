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
import com.noobclaw.mobile.util.Prefs
import com.noobclaw.mobile.util.RunLog
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/**
 * Single activity, screens built in code:
 * 登錄 → 使用說明 → 我的任務 → 新建任務（選平台 → 選劇本 → 設置）→ 運行。記錄頁單獨一屏。
 */
class MainActivity : AppCompatActivity() {

    private enum class Screen { HOME, PLATFORM, SCRIPT, CONFIG, LOGS }

    private lateinit var ui: Ui
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private var screen = Screen.HOME
    private var draft: JSONObject? = null           // task being created / edited
    private var permDialog: Dialog? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastRunning: String? = null
    private val logListener: (RunLog.Line) -> Unit = { _ -> runOnUiThread { if (screen == Screen.LOGS || screen == Screen.HOME) refreshSoon() } }

    // ---------- scripts offered per platform ----------
    private data class Script(val id: String, val title: String, val desc: String, val tags: List<String>, val icon: String, val color: Int)
    private val tiktokScripts = listOf(
        Script("tiktok_comment_like", "評論區點讚", "給評論點讚，AI 回覆", listOf(), "讚", C.CYAN),
        Script("tiktok_lead_engage", "定向獲客", "找客戶，點讚、評論、關注", listOf(), "客", C.PURPLE),
    )
    private fun scriptOf(id: String) = tiktokScripts.firstOrNull { it.id == id } ?: tiktokScripts[0]

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0 // light icons on the dark bars
        root = ui.col().apply { setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(40)) }
        scroll = ScrollView(this).apply { setBackgroundColor(C.BG); isFillViewport = true; addView(root) }
        setContentView(scroll)
        // The page shrinks above the keyboard (so nothing hides behind it).
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (screen) {
                    Screen.HOME -> { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                    Screen.PLATFORM, Screen.LOGS -> go(Screen.HOME)
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

    /** Re-renders the home screen when a run starts or ends. */
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
        RunLog.e("上次 App 崩潰：" + crash.take(600))
        if (Prefs.token.isEmpty()) return
        lifecycleScope.launch {
            runCatching {
                Api.sendDiagnostics(JSONObject().put("kind", "crash").put("crash", crash)
                    .put("app_ver", BuildConfig.VERSION_NAME).put("android_sdk", Build.VERSION.SDK_INT))
            }.onSuccess { RunLog.i("崩潰信息已上傳，客服碼 $it") }
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
        when {
            Prefs.token.isEmpty() -> renderLogin()
            Prefs.consentVersion < RunService.CONSENT_VERSION -> renderConsent()
            else -> when (screen) {
                Screen.HOME -> renderHome()
                Screen.PLATFORM -> renderPlatform()
                Screen.SCRIPT -> renderScript()
                Screen.CONFIG -> renderConfig()
                Screen.LOGS -> renderLogs()
            }
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
        add(ui.spacer(40))
        add(ui.text("NoobClaw", 34f, C.ACCENT, true))
        add(ui.spacer(28))
        val card = ui.card()
        val acc = ui.input("用戶名或郵箱")
        val pw = ui.input("密碼", password = true)
        card.addView(acc, ui.lp(bottom = 10)); card.addView(pw, ui.lp(bottom = 16))
        card.addView(ui.primary("登錄") {
            lifecycleScope.launch {
                try {
                    Api.login(acc.text.toString().trim(), pw.text.toString())
                    try {
                        Api.registerDevice(JSONObject().put("vendor", Build.MANUFACTURER + "/" + Build.MODEL).put("android_sdk", Build.VERSION.SDK_INT))
                    } catch (e: com.noobclaw.mobile.net.ApiException) {
                        if (e.code != "phone_limit") throw e
                        // 會員可用手機數已滿:不讓這臺手機登錄
                        Prefs.logout()
                        toast("當前會員最多 ${e.body.optInt("limit")} 臺手機，已滿。請升級會員，或在電腦端移除不用的手機")
                        return@launch
                    }
                    syncTasks(); render()
                } catch (e: Exception) { toast("登錄失敗：${e.message}") }
            }
        })
        add(card)
        add(ui.sub("v${BuildConfig.VERSION_NAME}").apply { gravity = Gravity.CENTER }, top = 24)
    }

    // ---------- consent (FR-02) ----------
    private fun renderConsent() {
        add(ui.h1("使用說明"), bottom = 16)
        val card = ui.card()
        listOf(
            "通過無障礙服務操作 TikTok，只讀 TikTok 界面",
            "遇到平台驗證會自動停下",
            "自動化有帳號受限風險，請自行評估",
        ).forEach { card.addView(ui.text("•  $it", 14f, C.TEXT), ui.lp(bottom = 8)) }
        add(card, bottom = 16)
        var diag = false
        val diagChip = ui.chip("出錯時上傳診斷：關", false) {}
        diagChip.setOnClickListener {
            diag = !diag
            diagChip.text = "出錯時上傳診斷：" + if (diag) "開" else "關"
            diagChip.setTextColor(if (diag) C.ACCENT else C.TEXT)
        }
        add(diagChip, bottom = 16)
        add(ui.primary("同意") {
            Prefs.diagOptIn = diag
            Prefs.consentVersion = RunService.CONSENT_VERSION
            RunService.start(this) // stand by
            render()
        }, bottom = 10)
        add(ui.link("退出登錄", C.SUB) { Prefs.logout(); RunService.start(this, RunService.ACTION_SHUTDOWN); render() }.apply { gravity = Gravity.CENTER })
    }

    // ---------- permissions ----------
    private data class Perm(val title: String, val why: String, val open: () -> Unit)

    private fun missingPerms(): List<Perm> {
        val out = mutableListOf<Perm>()
        if (!NcAccessibilityService.isRunning) out += Perm("無障礙服務", "") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        if (!Settings.canDrawOverlays(this)) out += Perm("懸浮窗", "") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) out += Perm("後台運行", "") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        return out
    }

    private var pendingAfterPerms: (() -> Unit)? = null

    /** Runs [action] when every permission is on; otherwise asks for the missing ones first. */
    private fun withPerms(action: () -> Unit) {
        if (missingPerms().isEmpty()) action() else showPermDialog(action)
    }

    /** Run / dry-run from the app: blocked when this phone is beyond the membership phone count. */
    private fun withRunPerms(action: () -> Unit) {
        if (Prefs.overLimit) { toast("超出會員可用手機數（${Prefs.phoneLimit} 臺），任務暫停"); return }
        withPerms(action)
    }

    private fun showPermDialog(after: (() -> Unit)?) {
        pendingAfterPerms = after
        val missing = missingPerms()
        if (missing.isEmpty()) { after?.invoke(); return }
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = ui.col(22).apply { background = ui.shape(C.SURFACE, 22, C.BORDER) }
        box.addView(ui.h2("請先開啟權限"), ui.lp(bottom = 16))
        missing.forEachIndexed { i, p ->
            val r = ui.row()
            r.addView(ui.badge("${i + 1}", C.ACCENT, C.ON_ACCENT, 30))
            val t = ui.col().apply { setPadding(ui.dp(12), 0, ui.dp(8), 0) }
            t.addView(ui.text(p.title, 15f, C.TEXT, true))
            r.addView(t, ui.weight())
            r.addView(ui.secondary("開啟", C.ACCENT) { p.open() })
            box.addView(r, ui.lp(bottom = 14))
        }
        box.addView(ui.link("取消", C.SUB) { pendingAfterPerms = null; d.dismiss() }.apply { gravity = Gravity.CENTER })
        d.setContentView(box)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        permDialog = d
        d.show()
    }

    // ---------- home: my tasks ----------
    private fun renderHome() {
        val head = ui.row()
        val title = ui.col()
        title.addView(ui.text("我的任務", 26f, C.TEXT, true))
        title.addView(ui.sub("v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）"))
        head.addView(title, ui.weight())
        head.addView(ui.secondary("記錄") { go(Screen.LOGS) })
        add(head, bottom = 18)

        // status strip: TikTok account + permissions
        val status = ui.card()
        val r = ui.row()
        r.addView(ui.badge("TT", Color.BLACK, Color.WHITE, 40))
        val info = ui.col().apply { setPadding(ui.dp(12), 0, 0, 0) }
        val handle = runCatching { JSONObject(Prefs.bindingJson).optString("display_id") }.getOrDefault("")
        info.addView(ui.text(handle.ifEmpty { "TikTok" }, 15f, C.TEXT, true))
        r.addView(info, ui.weight())
        val missing = missingPerms()
        if (missing.isEmpty()) r.addView(ui.pill("就緒", C.ACCENT))
        else r.addView(ui.pill("缺權限", C.WARN).apply { setOnClickListener { showPermDialog(null) } })
        status.addView(r)
        add(status, bottom = if (Prefs.overLimit) 12 else 22)
        if (Prefs.overLimit) {
            val warn = ui.card()
            warn.addView(ui.text("超出會員可用手機數（${Prefs.phoneLimit} 臺），任務暫停", 14f, C.WARN, true))
            warn.addView(ui.sub("升級會員，或在電腦端移除不用的手機"))
            add(warn, bottom = 22)
        }

        val tasks = Prefs.tasks()

        if (tasks.isEmpty()) {
            val empty = ui.card().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(ui.dp(20), ui.dp(28), ui.dp(20), ui.dp(24)) }
            empty.addView(ui.text("還沒有任務", 17f, C.SUB, true).apply { gravity = Gravity.CENTER }, ui.lp(bottom = 18))
            empty.addView(ui.primary("＋  新建任務") { newTask() })
            add(empty)
        } else {
            tasks.forEach { add(taskCard(it), bottom = 12) }
            add(ui.primary("＋  新建任務") { newTask() }, top = 6)
        }

        add(ui.spacer(28))
        val foot = ui.row().apply { gravity = Gravity.CENTER }
        foot.addView(ui.link("聯繫客服", C.SUB) { sendDiagnostics() })
        foot.addView(ui.text("  ·  ", 14f, C.SUB))
        foot.addView(ui.link("退出登錄", C.SUB) {
            Prefs.logout(); RunService.start(this, RunService.ACTION_SHUTDOWN); screen = Screen.HOME; render()
        })
        add(foot)
    }

    private fun taskCard(t: JSONObject): View {
        val s = scriptOf(t.optString("script_id"))
        val id = t.optString("id")
        val running = RunService.runningTaskId == id
        val card = ui.card(selected = running)
        val top = ui.row()
        top.addView(ui.badge(s.icon, s.color, Color.WHITE, 42))
        val name = ui.col().apply { setPadding(ui.dp(12), 0, ui.dp(8), 0) }
        name.addView(ui.text(t.optString("name").ifEmpty { s.title }, 16f, C.TEXT, true))
        name.addView(ui.sub(s.title))
        top.addView(name, ui.weight())
        top.addView(when {
            running -> ui.pill("運行中", C.ACCENT)
            t.optBoolean("enabled") -> ui.pill("定時", C.PURPLE)
            else -> ui.pill("手動", C.SUB)
        })
        card.addView(top, ui.lp(bottom = 12))

        val c = t.optJSONObject("config") ?: JSONObject()
        val lvName = mapOf("low" to "少", "mid" to "中", "high" to "多")[levelFromCaps(t)] ?: "中"
        card.addView(ui.text(listOfNotNull(whereOf(c).firstOrNull(), lvName,
            if (t.optBoolean("enabled")) slotsOf(t).joinToString(" / ") else null).joinToString("  ·  "), 14f, C.SUB))

        val btns = ui.row().apply { setPadding(0, ui.dp(10), 0, 0) }
        if (running) btns.addView(ui.secondary("■  停止", C.DANGER) { RunService.start(this, RunService.ACTION_STOP_RUN) }, ui.weight())
        else btns.addView(ui.primary("▶  運行") {
            withRunPerms { RunService.start(this, RunService.ACTION_RUN_NOW, id); toast("已開始") }
        }, ui.weight())
        btns.addView(ui.spacer(1).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(8), 1) })
        btns.addView(ui.secondary("編輯") { editTask(t) })
        btns.addView(ui.spacer(1).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(8), 1) })
        btns.addView(ui.secondary("⋯") { taskMenu(t) })
        card.addView(btns)
        return card
    }

    private fun taskMenu(t: JSONObject) {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = ui.col(18).apply { background = ui.shape(C.SURFACE, 20, C.BORDER) }
        box.addView(ui.h2(t.optString("name").ifEmpty { scriptOf(t.optString("script_id")).title }), ui.lp(bottom = 14))
        box.addView(ui.secondary("試跑（不扣費）") {
            d.dismiss(); withRunPerms { RunService.start(this, RunService.ACTION_DRY_RUN, t.optString("id")); toast("試跑開始") }
        }, ui.lp(bottom = 10))
        box.addView(ui.secondary(if (t.optBoolean("enabled")) "關閉定時" else "開啟定時") {
            d.dismiss(); saveTask(JSONObject(t.toString()).put("enabled", !t.optBoolean("enabled")))
        }, ui.lp(bottom = 10))
        box.addView(ui.secondary("刪除", C.DANGER) {
            d.dismiss()
            val list = Prefs.tasks().filter { it.optString("id") != t.optString("id") }
            pushTasks(list, "已刪除")
        })
        d.setContentView(box)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        d.show()
    }

    // ---------- new task: 1 platform ----------
    private fun newTask() = withPerms {
        draft = JSONObject().put("enabled", true)
        go(Screen.PLATFORM)
    }

    private fun renderPlatform() {
        topBar("新建任務", "1 / 3") { go(Screen.HOME) }
        add(ui.h1("選平台"), bottom = 18)
        val platforms = listOf(
            Triple("TikTok", "TT", true), Triple("Instagram", "IG", false), Triple("小紅書", "紅", false),
            Triple("抖音", "抖", false), Triple("YouTube", "YT", false), Triple("X", "X", false),
        )
        platforms.chunked(2).forEach { pair ->
            val r = ui.row()
            pair.forEachIndexed { i, (name, icon, on) ->
                val card = ui.card(onClick = if (on) ({ go(Screen.SCRIPT) }) else ({ toast("即將推出") }))
                card.gravity = Gravity.CENTER_HORIZONTAL
                card.alpha = if (on) 1f else 0.45f
                card.addView(ui.badge(icon, if (on) Color.BLACK else C.SURFACE2, Color.WHITE, 52).apply {
                    if (on) background = ui.shape(Color.BLACK, 26, C.CYAN, 2)
                }, LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)).apply { bottomMargin = ui.dp(10) })
                card.addView(ui.text(name, 15f, C.TEXT, true).apply { gravity = Gravity.CENTER })
                if (!on) card.addView(ui.sub("即將推出").apply { gravity = Gravity.CENTER })
                r.addView(card, ui.weight().apply { if (i == 0) marginEnd = ui.dp(10) })
            }
            add(r, bottom = 10)
        }
    }

    // ---------- new task: 2 script ----------
    private fun renderScript() {
        topBar("新建任務", "2 / 3") { go(Screen.PLATFORM) }
        add(ui.h1("選劇本"), bottom = 18)
        tiktokScripts.forEach { s ->
            val card = ui.card(onClick = {
                draft = (draft ?: JSONObject()).put("script_id", s.id)
                go(Screen.CONFIG)
            })
            val r = ui.row()
            r.addView(ui.badge(s.icon, s.color, Color.WHITE, 48))
            val t = ui.col().apply { setPadding(ui.dp(14), 0, 0, 0) }
            t.addView(ui.text(s.title, 17f, C.TEXT, true), ui.lp(bottom = 4))
            t.addView(ui.text(s.desc, 14f, C.SUB))
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
    private fun amountText(t: JSONObject) = if (t.optString("script_id") == "tiktok_comment_like")
        "點讚 ${t.optInt("max_likes")} · 回覆 ${t.optInt("max_comments")}"
    else "新客戶 ${t.optJSONObject("config")?.optInt("leads_per_run") ?: 0} · 關注 ${t.optInt("max_follows")} · 點讚 ${t.optInt("max_likes")} · 評論 ${t.optInt("max_comments")}"

    private fun editTask(t: JSONObject) {
        draft = JSONObject(t.toString()).put("_editing", true)
        go(Screen.CONFIG)
    }

    private fun renderConfig() {
        val d = draft ?: JSONObject().also { draft = it }
        val editing = d.optBoolean("_editing")
        val script = d.optString("script_id").ifEmpty { "tiktok_comment_like" }
        val s = scriptOf(script)
        topBar(if (editing) "編輯任務" else "新建任務", if (editing) null else "3 / 3") {
            go(if (editing) Screen.HOME else Screen.SCRIPT)
        }

        val head = ui.row()
        head.addView(ui.badge(s.icon, s.color, Color.WHITE, 44))
        val ht = ui.col().apply { setPadding(ui.dp(12), 0, 0, 0) }
        ht.addView(ui.h2(s.title))
        head.addView(ht, ui.weight())
        add(head, bottom = 20)

        val c = d.optJSONObject("config") ?: JSONObject()

        add(ui.section(if (script == "tiktok_comment_like") "關鍵詞 / 鏈接" else "關鍵詞 / 同行帳號"), bottom = 8)
        val where = ui.input(
            if (script == "tiktok_comment_like") "每行一個，例如 latte art" else "每行一個，例如 latte art 或 @rival",
            whereOf(c).joinToString("\n") { if (it.startsWith("@") && script == "tiktok_comment_like") "https://www.tiktok.com/$it" else it }, multi = true,
        )
        add(where, bottom = 20)

        add(ui.section("數量"), bottom = 8)
        var level = d.optString("_level").ifEmpty { levelFromCaps(d) }
        val amount = ui.sub("")
        val chips = ui.row()
        val chipViews = mutableListOf<View>()
        fun drawChips() {
            chips.removeAllViews(); chipViews.clear()
            listOf("low" to "少", "mid" to "中", "high" to "多").forEach { (k, n) ->
                val ch = ui.chip(n, k == level) { level = k; d.put("_level", k); drawChips() }
                chips.addView(ch, ui.weight().apply { marginEnd = ui.dp(8) })
            }
            val lv = levelOf(script, level)
            amount.text = if (script == "tiktok_comment_like") "點讚 ${lv.likes} · 回覆 ${lv.comments}"
                          else "客戶 ${lv.leads} · 關注 ${lv.follows} · 點讚 ${lv.likes} · 評論 ${lv.comments}"
        }
        drawChips()
        add(chips, bottom = 8)
        add(amount, bottom = 20)

        add(ui.section("人設（選填）"), bottom = 8)
        val persona = ui.input("例如：東京咖啡店", d.optString("persona"))
        add(persona, bottom = 20)

        add(ui.section("定時"), bottom = 8)
        var auto = d.optBoolean("enabled", true)
        val slots = slotsOf(d).toMutableList()
        val schedBox = ui.col()
        fun drawSched() {
            schedBox.removeAllViews()
            val modes = ui.row()
            modes.addView(ui.chip("每天", auto) { auto = true; drawSched() }, ui.weight().apply { marginEnd = ui.dp(8) })
            modes.addView(ui.chip("手動", !auto) { auto = false; drawSched() }, ui.weight())
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
            if (items.isEmpty()) { toast("請填關鍵詞"); return null }
            val links = items.filter { it.startsWith("http", true) || it.startsWith("@") }
            val words = items - links.toSet()
            val lv = levelOf(script, level)
            val cfg = JSONObject().put("level", level)
            if (script == "tiktok_comment_like") {
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
            val name = d.optString("name").ifEmpty { s.title + " · " + items.first().removePrefix("https://www.tiktok.com/").take(20) }
            return JSONObject()
                .put("id", d.optString("id").ifEmpty { "t" + System.currentTimeMillis().toString(36) })
                .put("name", name).put("script_id", script).put("config", cfg)
                .put("max_likes", lv.likes).put("max_comments", lv.comments).put("max_follows", lv.follows)
                .put("enabled", auto).put("slots", JSONArray(slots.sorted()))
                .put("tz", ZoneId.systemDefault().id).put("max_run_minutes", 60)
                .put("persona", persona.text.toString().take(400))
        }

        add(ui.primary(if (editing) "保存" else "保存並運行") {
            val t = build() ?: return@primary
            saveTask(t) { if (!editing) withRunPerms { RunService.start(this, RunService.ACTION_RUN_NOW, t.optString("id")); toast("已開始") } }
        }, bottom = 10)
        if (!editing) add(ui.secondary("只保存") { build()?.let { saveTask(it) } })
    }

    // ---------- save / sync ----------
    private fun saveTask(t: JSONObject, then: (() -> Unit)? = null) {
        val list = Prefs.tasks().toMutableList()
        val i = list.indexOfFirst { it.optString("id") == t.optString("id") }
        if (i >= 0) list[i] = t else list.add(t)
        pushTasks(list, "已保存", then)
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
            } catch (e: Exception) { toast("保存失敗：${e.message}") }
        }
    }

    private suspend fun syncTasks() {
        runCatching { Api.fetchTasks().optJSONArray("tasks") }.getOrNull()?.let { Prefs.tasksJson = it.toString() }
    }

    // ---------- logs ----------
    private fun renderLogs() {
        topBar("記錄") { go(Screen.HOME) }
        val lines = RunLog.recent().takeLast(200).reversed()
        if (lines.isEmpty()) { add(ui.sub("暫無記錄")); return }
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
        add(ui.secondary("聯繫客服") { sendDiagnostics() })
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
                toast("客服碼：$code（請發給客服）")
                RunLog.i("已發送診斷，客服碼 $code")
            } catch (e: Exception) { toast("發送失敗：${e.message}") }
        }
    }
}
