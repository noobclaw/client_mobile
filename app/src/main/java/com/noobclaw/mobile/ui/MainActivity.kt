package com.noobclaw.mobile.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
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
 * login → disclosure/consent → permissions, platform binding, task settings, controls, log.
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
        render()
        RunLog.onLine(logListener)
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
    private fun renderMain() {
        title("這台手機")
        renderPermissions()
        renderBinding()
        renderTask()
        renderControls()
        title("運行日誌")
        logView = TextView(this).apply { textSize = 12f }.also { root.addView(it) }
        RunLog.recent().takeLast(60).forEach { appendLog(it) }
    }

    private fun renderPermissions() {
        val a11y = NcAccessibilityService.isRunning
        label((if (a11y) "✅" else "❌") + " 無障礙服務")
        if (!a11y) button("去開啟無障礙服務") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        val overlay = Settings.canDrawOverlays(this)
        label((if (overlay) "✅" else "❌") + " 懸浮窗（運行時的停止按鈕）")
        if (!overlay) button("去開啟懸浮窗") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        val pm = getSystemService(PowerManager::class.java)
        val battery = pm.isIgnoringBatteryOptimizations(packageName)
        label((if (battery) "✅" else "❌") + " 關閉電池優化（避免後台被殺）")
        if (!battery) button("去關閉電池優化") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    private fun renderBinding() {
        title("綁定 TikTok 帳號")
        val b = runCatching { JSONObject(Prefs.bindingJson) }.getOrNull()
        if (b != null) label("已綁定：${b.optString("display_id")}（每次運行前會核對當前登錄的是不是這個號）")
        val handle = field("TikTok 用戶名，例如 @myshop", b?.optString("display_id").orEmpty())
        val lang = field("這個號的內容語言（en / ja / es / id / th / vi / pt / zh ...）", b?.optString("content_lang") ?: "en")
        button(if (b == null) "綁定" else "更新綁定") {
            val h = handle.text.toString().trim().removePrefix("@")
            if (!Regex("^[A-Za-z0-9._]{2,24}$").matches(h)) { toast("用戶名格式不對"); return@button }
            val binding = JSONObject()
                .put("platform", "tiktok")
                .put("platform_uid", h.lowercase())   // MVP: handle; switch to the immutable id once Phase 0 finds it
                .put("display_id", "@$h")
                .put("content_lang", lang.text.toString().trim().ifEmpty { "en" })
            lifecycleScope.launch {
                try {
                    Api.bindPlatform(JSONObject(binding.toString()))
                    Prefs.bindingJson = binding.toString()
                    toast("已綁定 @$h")
                    render()
                } catch (e: Exception) { toast("綁定失敗：${e.message}") }
            }
        }
    }

    private fun multi(hint: String, value: String) = EditText(this).apply {
        this.hint = hint; setText(value); minLines = 3; isSingleLine = false
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    }.also { root.addView(it) }
    private fun lines(e: EditText) = JSONArray().also { a ->
        e.text.toString().split('\n', ',', '，').map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { a.put(it) }
    }
    private fun joinArr(a: JSONArray?) = if (a == null) "" else (0 until a.length()).joinToString("\n") { a.optString(it) }
    private fun check(t: String, v: Boolean) = CheckBox(this).apply { text = t; isChecked = v }.also { root.addView(it) }

    /** Which of the two MVP scripts the task editor shows; defaults to the saved task's script. */
    private var editingScript: String? = null

    private fun renderTask() {
        val t = runCatching { JSONObject(Prefs.taskJson) }.getOrElse { JSONObject() }
        val script = editingScript ?: t.optString("script_id").ifEmpty { "tiktok_comment_like" }
        val same = t.optString("script_id") == script
        val c = if (same) (t.optJSONObject("config") ?: JSONObject()) else JSONObject()
        title("任務")
        button((if (script == "tiktok_comment_like") "● " else "○ ") + "TikTok 評論區點讚評論") { editingScript = "tiktok_comment_like"; render() }
        button((if (script == "tiktok_lead_engage") "● " else "○ ") + "TikTok 定向獲客") { editingScript = "tiktok_lead_engage"; render() }

        var save: () -> JSONObject
        if (script == "tiktok_comment_like") {
            val keywordsMode = c.optString("comment_mode") == "keywords"
            val precise = check("精準模式（粘貼作品或主頁鏈接）；不勾＝關鍵詞模式", !keywordsMode)
            label("作品 / 主頁鏈接（每行一條，最多 20 條）")
            val urls = multi("https://www.tiktok.com/@xxx/video/123…", joinArr(c.optJSONArray("urls")))
            label("關鍵詞（關鍵詞模式用，每行一個）")
            val kws = multi("例如：coffee shop", joinArr(c.optJSONArray("keywords")))
            val like = check("給留言點讚", c.optBoolean("cl_do_like", true))
            val reply = check("用 AI 回覆留言", c.optBoolean("cl_do_reply", true))
            label("每次最多點讚（上限 1000）")
            val maxLikes = field("300", (if (same) t.optInt("max_likes", 300) else 300).toString(), number = true)
            label("每次最多回覆（上限 300）")
            val maxComments = field("30", (if (same) t.optInt("max_comments", 30) else 30).toString(), number = true)
            save = {
                JSONObject().put("max_likes", maxLikes.text.toString().toIntOrNull() ?: 300)
                    .put("max_comments", maxComments.text.toString().toIntOrNull() ?: 30)
                    .put("config", JSONObject().put("comment_mode", if (precise.isChecked) "precise" else "keywords")
                        .put("urls", lines(urls)).put("keywords", lines(kws))
                        .put("cl_do_like", like.isChecked).put("cl_do_reply", reply.isChecked))
            }
        } else {
            val kwMode = c.optString("mode") == "keywords"
            val accounts = check("同行帳號模式（粘貼帳號或作品鏈接）；不勾＝關鍵詞模式", !kwMode)
            label("同行帳號 / 作品鏈接（每行一個，例如 @rival 或鏈接）")
            val seeds = multi("@rival_shop", joinArr(c.optJSONArray("seed_accounts")))
            label("關鍵詞（關鍵詞模式用，每行一個）")
            val kws = multi("例如：latte art", joinArr(c.optJSONArray("keywords")))
            label("每次最多新收錄潛客（上限 300）")
            val perRun = field("100", c.optInt("leads_per_run", 100).toString(), number = true)
            val like = check("點讚他的第一條作品", c.optBoolean("lead_do_like", true))
            val follow = check("關注", c.optBoolean("lead_do_follow", true))
            val comment = check("AI 評論他的第一條作品", c.optBoolean("lead_do_comment", true))
            label("每次最多點讚 / 關注 / 評論（上限 500 / 100 / 300）")
            val maxLikes = field("點讚", (if (same) t.optInt("max_likes", 300) else 300).toString(), number = true)
            val maxFollows = field("關注", (if (same) t.optInt("max_follows", 0) else 0).toString(), number = true)
            val maxComments = field("評論", (if (same) t.optInt("max_comments", 0) else 0).toString(), number = true)
            save = {
                JSONObject().put("max_likes", maxLikes.text.toString().toIntOrNull() ?: 300)
                    .put("max_follows", maxFollows.text.toString().toIntOrNull() ?: 0)
                    .put("max_comments", maxComments.text.toString().toIntOrNull() ?: 0)
                    .put("config", JSONObject().put("mode", if (accounts.isChecked) "accounts" else "keywords")
                        .put("seed_accounts", lines(seeds)).put("keywords", lines(kws))
                        .put("leads_per_run", perRun.text.toString().toIntOrNull() ?: 100)
                        .put("lead_do_like", like.isChecked).put("lead_do_follow", follow.isChecked).put("lead_do_comment", comment.isChecked))
            }
        }

        val enabled = check("啟用定時運行", same && t.optBoolean("enabled", false))
        label("每天運行時間（最多 2 個，逗號分隔，24 小時制）")
        val slots = field("09:30,20:00", if (same) joinArr(t.optJSONArray("slots")).replace("\n", ",") else "")
        label("時區（任務按這個時區運行，不看雲手機自己的時鐘）")
        val tz = field("例如 America/Los_Angeles", t.optString("tz", ZoneId.systemDefault().id))
        label("每次最長運行分鐘")
        val minutes = field("60", (if (same) t.optInt("max_run_minutes", 60) else 60).toString(), number = true)
        label("帳號人設（回覆/評論會按這個語氣寫，可留空）")
        val persona = field("例如：在洛杉磯開咖啡店的店主，語氣友善", if (same) t.optString("persona") else "")
        button("保存任務") {
            val slotArr = JSONArray()
            slots.text.toString().split(',', '，').map { it.trim() }.filter { it.matches(Regex("^\\d{1,2}:\\d{2}$")) }
                .take(2).forEach { slotArr.put(it.padStart(5, '0')) }
            val tzId = tz.text.toString().trim()
            if (runCatching { ZoneId.of(tzId) }.isFailure) { toast("時區不對，例如 Asia/Tokyo"); return@button }
            val task = save()
                .put("script_id", script)
                .put("enabled", enabled.isChecked)
                .put("slots", slotArr)
                .put("tz", tzId)
                .put("max_run_minutes", minutes.text.toString().toIntOrNull() ?: 60)
                .put("persona", persona.text.toString().take(400))
            lifecycleScope.launch {
                try {
                    val saved = Api.saveTask(task).optJSONObject("task") ?: task
                    Prefs.taskJson = saved.toString()
                    editingScript = null
                    toast("已保存（服務器會按上限校正數值）")
                    render()
                } catch (e: Exception) { toast("保存失敗：${e.message}") }
            }
        }
    }

    private fun renderControls() {
        title("控制")
        button("開始自動運行（常駐待命）") {
            getSharedPreferences("nc_mobile", MODE_PRIVATE).edit().putBoolean("autostart", true).apply()
            RunService.start(this)
            toast("已開始待命，到點自動運行")
        }
        button("立即運行一次") { RunService.start(this, RunService.ACTION_RUN_NOW) }
        button("乾跑一次（走流程但不點讚、不發評論、不扣費）") { RunService.start(this, RunService.ACTION_DRY_RUN) }
        button("停止當前運行") { RunService.start(this, RunService.ACTION_STOP_RUN) }
        button("關閉自動運行") {
            getSharedPreferences("nc_mobile", MODE_PRIVATE).edit().putBoolean("autostart", false).apply()
            RunService.start(this, RunService.ACTION_SHUTDOWN)
        }
        button("發送診斷（生成客服碼）") {
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
        label("設備 ID：${Prefs.deviceId.take(8)} · v${BuildConfig.VERSION_NAME}").visibility = View.VISIBLE
    }

    private fun appendLog(l: RunLog.Line) {
        val v = logView ?: return
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(l.ts))
        v.append("$time ${if (l.level == "error") "❌" else if (l.level == "warn") "⚠️" else "·"} ${l.msg}\n")
    }
}
