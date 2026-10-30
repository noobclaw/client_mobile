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

    /** Epoch millis until which runs are blocked after a challenge (FR-08). */
    /** The phone's task list as a JSON array (source of truth is the server; this is the offline copy). */
    var tasksJson: String
        get() = sp.getString(K_TASKS, "[]") ?: "[]"
        set(v) = sp.edit().putString(K_TASKS, v).apply()

    fun tasks(): List<org.json.JSONObject> = runCatching {
        val a = org.json.JSONArray(tasksJson); (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }.getOrDefault(emptyList())

    var cooldownUntil: Long
        get() = sp.getLong(K_COOLDOWN, 0L)
        set(v) = sp.edit().putLong(K_COOLDOWN, v).apply()

    /** Server says this phone is beyond the account's membership phone count: it may not run tasks. */
    var overLimit: Boolean
        get() = sp.getBoolean(K_OVER, false)
        set(v) = sp.edit().putBoolean(K_OVER, v).apply()
    var phoneLimit: Int
        get() = sp.getInt(K_PHONE_LIMIT, 0)
        set(v) = sp.edit().putInt(K_PHONE_LIMIT, v).apply()

    fun logout() {
        sp.edit().remove(K_TOKEN).remove(K_WALLET).remove(K_TASK).remove(K_TASKS).remove(K_BINDING).remove(K_OVER).remove(K_PHONE_LIMIT).apply()
    }
}
