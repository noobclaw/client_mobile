package com.noobclaw.mobile.util

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import java.util.UUID

/**
 * Small key-value store for session and device state.
 * MVP uses private SharedPreferences; move the token to EncryptedSharedPreferences / Keystore
 * before the public release (tracked in docs/MVP_TODO.md).
 */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("nc_mobile", Context.MODE_PRIVATE)
        // 已有編號的安裝(升級上來的)保持不變,任務都掛在它下面。新安裝用系統的 ANDROID_ID:
        //   同一臺手機 + 同一簽名 → 卸載重裝也不變,電腦端就不會冒出「兩臺一樣的手機」(2026-10-30)。
        if (deviceId.isEmpty()) sp.edit().putString(K_DEVICE, stableId(ctx)).apply()
    }

    private fun stableId(ctx: Context): String {
        val aid = runCatching { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull()
            ?.trim()?.lowercase().orEmpty()
        // 9774d56d682e549c = 老機型上大量重複的已知壞值
        val ok = aid.length >= 8 && aid != "9774d56d682e549c" && aid.any { it != '0' }
        return if (ok) "a-$aid" else UUID.randomUUID().toString()
    }

    private const val K_TOKEN = "token"
    private const val K_WALLET = "wallet"
    private const val K_DEVICE = "device_id"
    private const val K_CONSENT = "consent_version"
    private const val K_DIAG = "diag_upload_opt_in"
    private const val K_TASK = "task_json"
    private const val K_BINDING = "binding_json"
    private const val K_COOLDOWN = "cooldown_until"
    private const val K_TASKS = "tasks_json"
    private const val K_OVER = "over_limit"
    private const val K_LANG = "lang"
    private const val K_ACCOUNT = "account"
    private const val K_PHONE_LIMIT = "phone_limit"

    var token: String
        get() = sp.getString(K_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(K_TOKEN, v).apply()

    var wallet: String
        get() = sp.getString(K_WALLET, "") ?: ""
        set(v) = sp.edit().putString(K_WALLET, v).apply()

    /** Install id: "a-" + ANDROID_ID (stable across reinstalls for our signing key), or a random UUID when unavailable. */
    val deviceId: String
        get() = sp.getString(K_DEVICE, "") ?: ""

    /** Version of the disclosure text the user accepted; 0 = not accepted. */
    var consentVersion: Int
        get() = sp.getInt(K_CONSENT, 0)
        set(v) = sp.edit().putInt(K_CONSENT, v).apply()

    /** One-time opt-in for uploading redacted diagnostics on failure (FR-12). */
    var diagOptIn: Boolean
        get() = sp.getBoolean(K_DIAG, false)
        set(v) = sp.edit().putBoolean(K_DIAG, v).apply()

    /** Last task config received from the backend (source of truth is the server). */
    var taskJson: String
        get() = sp.getString(K_TASK, "") ?: ""
        set(v) = sp.edit().putString(K_TASK, v).apply()

    /** Bound platform account: {"platform":"tiktok","platform_uid":"...","display_id":"@handle","nickname":"..."} */
    var bindingJson: String
        get() = sp.getString(K_BINDING, "") ?: ""
        set(v) = sp.edit().putString(K_BINDING, v).apply()

    /** Bound account per platform (2026-11-02: 抖音 / 小紅書). TikTok keeps using [bindingJson] (older code paths read it). */
    fun bindingFor(platform: String): String =
        if (platform == "tiktok") bindingJson else (sp.getString("binding_$platform", "") ?: "")
    fun setBindingFor(platform: String, v: String) {
        if (platform == "tiktok") bindingJson = v else sp.edit().putString("binding_$platform", v).apply()
    }

    /** Epoch millis until which runs are blocked after a challenge (FR-08). */
    /** The phone's task list as a JSON array (source of truth is the server; this is the offline copy). */
    var tasksJson: String
        get() = sp.getString(K_TASKS, "[]") ?: "[]"
        set(v) = sp.edit().putString(K_TASKS, v).apply()

    fun tasks(): List<org.json.JSONObject> = runCatching {
        val a = org.json.JSONArray(tasksJson); (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }.getOrDefault(emptyList())

    /** Platform of a task: `platform`, or derived from the script id for old tasks. */
    fun platformOf(t: org.json.JSONObject): String = t.optString("platform").ifEmpty {
        val sid = t.optString("script_id")
        if (sid.startsWith("tiktok_") || sid.isEmpty()) "tiktok" else sid.substringBefore('_')
    }

    /** One task per platform: the first one of each platform (what the UI shows and the scheduler runs). */
    fun primaryTasks(): List<org.json.JSONObject> = tasks().distinctBy { platformOf(it) }

    var cooldownUntil: Long
        get() = sp.getLong(K_COOLDOWN, 0L)
        set(v) = sp.edit().putLong(K_COOLDOWN, v).apply()

    /** UI language code (I18n.Lang.code); empty = follow the system. Kept across logout. */
    var lang: String
        get() = sp.getString(K_LANG, "") ?: ""
        set(v) = sp.edit().putString(K_LANG, v).apply()

    /** The user name / email typed at login (shown in 我的). */
    var account: String
        get() = sp.getString(K_ACCOUNT, "") ?: ""
        set(v) = sp.edit().putString(K_ACCOUNT, v).apply()

    /** Server says this phone is beyond the account's membership phone count: it may not run tasks. */
    var overLimit: Boolean
        get() = sp.getBoolean(K_OVER, false)
        set(v) = sp.edit().putBoolean(K_OVER, v).apply()
    var phoneLimit: Int
        get() = sp.getInt(K_PHONE_LIMIT, 0)
        set(v) = sp.edit().putInt(K_PHONE_LIMIT, v).apply()

    // ---------- 2026-10-31 雲手機改版 ----------
    private const val K_HIDDEN = "hidden_platforms"
    private const val K_STATS = "task_stats_json"
    private const val K_RUNS = "run_history_json"
    private const val K_LAST_START = "ls_"

    /** Platforms the backend compliance config hides for this user (GET /api/matrix/config → complianceHiddenPlatforms). */
    var hiddenPlatforms: Set<String>
        get() = runCatching {
            val a = org.json.JSONArray(sp.getString(K_HIDDEN, "[]") ?: "[]"); (0 until a.length()).map { a.optString(it) }.toSet()
        }.getOrDefault(emptySet())
        set(v) = sp.edit().putString(K_HIDDEN, org.json.JSONArray(v.toList()).toString()).apply()

    /** When the task last started (epoch ms), for the "every N hours" schedule; null = never on this install. */
    fun lastStart(taskId: String): Long? = sp.getLong(K_LAST_START + taskId, 0L).takeIf { it > 0 }
        ?: taskStats(taskId)?.optLong("last_started_at")?.takeIf { it > 0 } // seed from the local run history
    fun setLastStart(taskId: String, ms: Long) = sp.edit().putLong(K_LAST_START + taskId, ms).apply()

    /** Seeds / updates last starts from the server's `last_started` {taskId: epoch ms} when it is newer than ours. */
    fun mergeLastStarted(m: org.json.JSONObject?) {
        if (m == null) return
        val e = sp.edit()
        m.keys().forEach { id ->
            val v = m.optLong(id, 0L)
            if (v > 0 && v > sp.getLong(K_LAST_START + id, 0L)) e.putLong(K_LAST_START + id, v)
        }
        e.apply()
    }

    /** Per-task totals kept from the run reports this phone sent: {taskId: {runs, totals{}, credits, last_*}}. */
    fun taskStats(taskId: String): org.json.JSONObject? =
        runCatching { org.json.JSONObject(sp.getString(K_STATS, "{}") ?: "{}").optJSONObject(taskId) }.getOrNull()

    /** Recent run reports (newest first, at most 30): {run_id, task_id, status, reason, dry_run, counts, credits, started_at, duration_ms}. */
    fun runHistory(): List<org.json.JSONObject> = runCatching {
        val a = org.json.JSONArray(sp.getString(K_RUNS, "[]") ?: "[]"); (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }.getOrDefault(emptyList())

    /** Records a finished run (called when its report is sent). Dry runs are listed but not added to the totals. */
    @Synchronized fun recordRun(run: org.json.JSONObject) {
        val list = listOf(run) + runHistory().filter { it.optString("run_id") != run.optString("run_id") }
        sp.edit().putString(K_RUNS, org.json.JSONArray(list.take(30)).toString()).apply()
        val tid = run.optString("task_id")
        if (tid.isEmpty() || run.optBoolean("dry_run")) return
        val all = runCatching { org.json.JSONObject(sp.getString(K_STATS, "{}") ?: "{}") }.getOrElse { org.json.JSONObject() }
        val st = all.optJSONObject(tid) ?: org.json.JSONObject()
        val totals = st.optJSONObject("totals") ?: org.json.JSONObject()
        val counts = run.optJSONObject("counts") ?: org.json.JSONObject()
        counts.keys().forEach { k -> totals.put(k, totals.optLong(k) + counts.optLong(k)) }
        st.put("totals", totals).put("runs", st.optInt("runs") + 1)
            .put("credits", st.optLong("credits") + run.optLong("credits"))
            .put("last_run_at", run.optLong("started_at") + run.optLong("duration_ms"))
            .put("last_started_at", run.optLong("started_at"))
            .put("last_status", run.optString("status")).put("last_reason", run.optString("reason"))
            .put("last_counts", counts).put("last_credits", run.optLong("credits")).put("last_run_id", run.optString("run_id"))
        all.put(tid, st)
        sp.edit().putString(K_STATS, all.toString()).apply()
    }

    /** Feature switches from the server (heartbeat `flags`): dev_bubble, dry_run … Off when missing. */
    var flagsJson: String
        get() = sp.getString("mobile_flags", "{}") ?: "{}"
        set(v) { sp.edit().putString("mobile_flags", v).apply() }
    fun flag(name: String): Boolean = runCatching { org.json.JSONObject(flagsJson).optBoolean(name, false) }.getOrDefault(false)

    fun logout() {
        sp.edit().remove(K_TOKEN).remove(K_WALLET).remove(K_TASK).remove(K_TASKS).remove(K_BINDING).remove(K_OVER).remove(K_PHONE_LIMIT).remove(K_ACCOUNT).remove(K_STATS).remove(K_RUNS).remove(K_HIDDEN).remove("mobile_flags").remove("binding_douyin").remove("binding_xhs").apply()   // 功能开关跟着账号走,换账号不带过去
    }
}
