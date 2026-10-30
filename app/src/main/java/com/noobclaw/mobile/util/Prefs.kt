package com.noobclaw.mobile.util

import android.content.Context
import android.content.SharedPreferences
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
        if (deviceId.isEmpty()) sp.edit().putString(K_DEVICE, UUID.randomUUID().toString()).apply()
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

    var token: String
        get() = sp.getString(K_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(K_TOKEN, v).apply()

    var wallet: String
        get() = sp.getString(K_WALLET, "") ?: ""
        set(v) = sp.edit().putString(K_WALLET, v).apply()

    /** Random install id. Not a hardware identifier; it resets on reinstall by design. */
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

    fun logout() {
        sp.edit().remove(K_TOKEN).remove(K_WALLET).remove(K_TASK).remove(K_TASKS).remove(K_BINDING).apply()
    }
}
