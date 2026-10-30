package com.noobclaw.mobile.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
 * Single-activity UI built in code (no XML) to keep the MVP small:
 * login → disclosure/consent → permissions, task settings, controls, log.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private var logView: TextView? = null
    private val logListener: (RunLog.Line) -> Unit = { line -> runOnUiThread { appendLog(line) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 60) }
        setContentView(ScrollView(this).apply { addView(root) })
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        reportLastCrash()
        render()
        RunLog.onLine(logListener)
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

    override fun onPause() {
        RunLog.offLine(logListener)
        super.onPause()
    }

    private fun render() {
        root.removeAllViews()
        logView = null
        when {
            Prefs.token.isEmpty() -> renderLogin()
            Prefs.consentVersion < RunService.CONSENT_VERSION -> renderConsent()
            else -> renderMain()
        }
    }

    // ---------- helpers ----------
    private fun title(t: String) = TextView(this).apply { text = t; textSize = 20f; setPadding(0, 24, 0, 12) }.also { root.addView(it) }
    private fun label(t: String) = TextView(this).apply { text = t; textSize = 14f; setPadding(0, 8, 0, 4) }.also { root.addView(it) }
    private fun field(hint: String, value: String = "", password: Boolean = false, number: Boolean = false) =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                number -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT
            }
        }.also { root.addView(it) }
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; setOnClickListener { onClick() } }.also { root.addView(it) }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ---------- login ----------
    private fun renderLogin() {
        title("登錄 NoobClaw")
        label("v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
        val acc = field("用戶名或郵箱")
        val pw = field("密碼", password = true)
        button("登錄") {
            lifecycleScope.launch {
                try {
                    Api.login(acc.text.toString().trim(), pw.text.toString())
                    Api.registerDevice(JSONObject().put("vendor", Build.MANUFACTURER + "/" + Build.MODEL).put("android_sdk", Build.VERSION.SDK_INT))
                    render()
                } catch (e: Exception) {
                    toast("登錄失敗：${e.message}")
                }
            }
        }
    }

    // ---------- consent (FR-02) ----------
    private fun renderConsent() {
        title("使用前請先閱讀")
        label(
            "NoobClaw Mobile 會通過安卓「無障礙服務」在 TikTok 裡執行你設置的任務（瀏覽、點讚、評論）。\n\n" +
                "• 只讀取 TikTok 的界面內容，不讀取其他 App、私信、短信或通訊錄。\n" +
                "• 會發到服務器的內容：作品文案、熱門評論、你的 TikTok 暱稱和帳號，用於生成評論和記錄互動歷史。\n" +
                "• 運行日誌保存 7 天；互動記錄保存 180 天；你可以隨時在「設置」裡刪除數據。\n" +
                "• 每天的動作數有上限，遇到平台驗證會立即停止，不會自動處理驗證。\n" +
                "• 自動化操作可能違反平台規則並導致帳號受限，請自行評估。"
        )
        val diag = CheckBox(this).apply { text = "運行失敗時上傳遮蔽後的診斷信息（可隨時關閉）"; isChecked = false }.also { root.addView(it) }
        button("我已閱讀並同意") {
            Prefs.diagOptIn = diag.isChecked
            Prefs.consentVersion = RunService.CONSENT_VERSION
            render()
        }
        button("退出登錄") { Prefs.logout(); render() }
    }

    // ---------- main ----------
    // One screen, top to bottom: (setup, only while something is missing) → what to do → how much → start / stop → recent log.

    private var pickedScript: String? = null
    private var pickedLevel: String? = null
    private var showAllLog = false

    private fun renderMain() {
        title("NoobClaw")
        val setupDone = renderSetup()
        if (!setupDone) return
        renderTask()
        renderLog()
        renderFooter()
    }

    /** Shows only what is still missing, one button each. Returns true when everything is ready. */
    private fun renderSetup(): Boolean {
        val a11y = NcAccessibilityService.isRunning
        val overlay = Settings.canDrawOverlays(this)
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        if (a11y && overlay && battery) return true
        label("先完成 3 步設置（只需一次），按下面的按鈕逐個打開：")
        if (!a11y) button("① 打開「無障礙服務」→ 找到 NoobClaw 打開") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        if (!overlay) button("② 允許「顯示在其他應用上層」") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        if (!battery) button("③ 允許後台運行（不受電池優化限制）") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        label("全部打開後回到這裡。")
        renderFooter()
        return false
    }

    private fun multi(hint: String, value: String) = EditText(this).apply {
        this.hint = hint; setText(value); minLines = 3; isSingleLine = false
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    }.also { root.addView(it) }
    private fun joinArr(a: JSONArray?) = if (a == null) "" else (0 until a.length()).joinToString("\n") { a.optString(it) }
    private fun check(t: String, v: Boolean) = CheckBox(this).apply { text = t; isChecked = v }.also { root.addView(it) }

    /** Daily amounts per level: likes, comments/replies, follows, new leads. */
    private data class Level(val likes: Int, val comments: Int, val follows: Int, val leads: Int)
    private fun levelOf(script: String, l: String) = when (script) {
        "tiktok_comment_like" -> when (l) { "low" -> Level(30, 5, 0, 0); "high" -> Level(300, 30, 0, 0); else -> Level(100, 15, 0, 0) }
        else -> when (l) { "low" -> Level(10, 5, 10, 10); "high" -> Level(100, 30, 50, 100); else -> Level(30, 15, 30, 30) }
    }

    private fun renderTask() {
        val t = runCatching { JSONObject(Prefs.taskJson) }.getOrElse { JSONObject() }
        val script = pickedScript ?: t.optString("script_id").ifEmpty { "tiktok_comment_like" }
        val same = t.optString("script_id") == script
        val c = if (same) (t.optJSONObject("config") ?: JSONObject()) else JSONObject()
        val level = pickedLevel ?: c.optString("level").ifEmpty { "mid" }

        label("要做什麼")
        button((if (script == "tiktok_comment_like") "● " else "○ ") + "給評論點讚 + AI 回覆") { pickedScript = "tiktok_comment_like"; render() }
        button((if (script == "tiktok_lead_engage") "● " else "○ ") + "找客戶：關注、點讚、評論評論區裡的人") { pickedScript = "tiktok_lead_engage"; render() }

        label("在哪裡做（每行一個）")
        val prev = if (same) listOf(c.optJSONArray("urls"), c.optJSONArray("seed_accounts"), c.optJSONArray("keywords"))
            .joinToString("\n") { joinArr(it) }.trim() else ""
        val where = multi(
            if (script == "tiktok_comment_like") "關鍵詞，或作品 / 主頁鏈接\n例如：latte art" else "關鍵詞，或同行帳號（@開頭）\n例如：latte art 或 @rival_shop",
            prev,
        )

        label("每天做多少")
        val names = listOf("low" to "少", "mid" to "中", "high" to "多")
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            names.forEach { (k, n) ->
                addView(Button(this@MainActivity).apply {
                    text = (if (k == level) "● " else "") + n
                    setOnClickListener { pickedLevel = k; render() }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }.also { root.addView(it) }
        val lv = levelOf(script, level)
        label(if (script == "tiktok_comment_like") "每次最多點讚 ${lv.likes} 條、回覆 ${lv.comments} 條"
              else "每次最多找 ${lv.leads} 個新客戶，關注 ${lv.follows}、點讚 ${lv.likes}、評論 ${lv.comments}")

        label("你的帳號是做什麼的（選填，AI 會按這個語氣寫）")
        val persona = field("例如：東京的咖啡店，語氣友善", if (same) t.optString("persona") else "")
        val auto = check("每天自動做（早上 9:30、晚上 8:00）", same && t.optBoolean("enabled", false))

        fun buildTask(): JSONObject? {
            val items = where.text.toString().split('\n', ',', '，').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (items.isEmpty()) { toast("先填「在哪裡做」"); return null }
            val links = items.filter { it.startsWith("http", true) || it.startsWith("@") }
            val words = items - links.toSet()
            val cfg = JSONObject().put("level", level)
            if (script == "tiktok_comment_like") {
                // "@shop" means that account's profile page.
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
            return JSONObject().put("script_id", script).put("config", cfg)
                .put("max_likes", lv.likes).put("max_comments", lv.comments).put("max_follows", lv.follows)
                .put("enabled", auto.isChecked).put("slots", JSONArray(listOf("09:30", "20:00")))
                .put("tz", ZoneId.systemDefault().id).put("max_run_minutes", 60)
                .put("persona", persona.text.toString().take(400))
        }

        fun saveThen(action: String?) {
            val task = buildTask() ?: return
            lifecycleScope.launch {
                try {
                    val saved = Api.saveTask(task).optJSONObject("task") ?: task
                    Prefs.taskJson = saved.toString()
                    pickedScript = null; pickedLevel = null
                    getSharedPreferences("nc_mobile", MODE_PRIVATE).edit().putBoolean("autostart", auto.isChecked).apply()
                    if (auto.isChecked) RunService.start(this@MainActivity) // stand by for the daily times
                    if (action != null) RunService.start(this@MainActivity, action)
                    toast(if (action == null) "已保存" else "開始了，可以切到後台")
                    render()
                } catch (e: Exception) { toast("保存失敗：${e.message}") }
            }
        }

        button("▶ 開始") { saveThen(RunService.ACTION_RUN_NOW) }
        button("先試一次（只看流程，不點讚、不評論、不扣費）") { saveThen(RunService.ACTION_DRY_RUN) }
        button("■ 停止") {
            RunService.start(this, RunService.ACTION_STOP_RUN)
            if (!auto.isChecked) RunService.start(this, RunService.ACTION_SHUTDOWN)
        }
    }

    private fun renderLog() {
        label("最近記錄")
        logView = TextView(this).apply { textSize = 12f }.also { root.addView(it) }
        RunLog.recent().takeLast(if (showAllLog) 200 else 8).forEach { appendLog(it) }
        if (!showAllLog) button("查看全部記錄") { showAllLog = true; render() }
    }

    private fun renderFooter() {
        button("遇到問題？發給客服") {
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
        button("退出登錄") { Prefs.logout(); RunService.start(this, RunService.ACTION_SHUTDOWN); render() }
        label("v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
    }

    private fun appendLog(l: RunLog.Line) {
        val v = logView ?: return
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(l.ts))
        v.append("$time ${if (l.level == "error") "❌" else if (l.level == "warn") "⚠️" else "·"} ${l.msg}\n")
    }
}
