package com.noobclaw.mobile.net

import com.noobclaw.mobile.BuildConfig
import com.noobclaw.mobile.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, val code: String, message: String, val body: JSONObject = JSONObject()) : Exception(message)

/**
 * Thin JSON client for the NoobClaw backend. Every request carries
 * `X-Client: mobile/<version>` so the backend can keep mobile and desktop traffic apart.
 */
object Api {
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val clientTag: String get() = "mobile/${BuildConfig.VERSION_NAME}"

    suspend fun get(path: String): JSONObject = call("GET", path, null)
    suspend fun post(path: String, body: JSONObject): JSONObject = call("POST", path, body)
    suspend fun put(path: String, body: JSONObject): JSONObject = call("PUT", path, body)

    private suspend fun call(method: String, path: String, body: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        val b = Request.Builder()
            .url(BuildConfig.API_BASE.trimEnd('/') + path)
            .header("X-Client", clientTag)
            .header("X-Device-Id", Prefs.deviceId)
        val token = Prefs.token
        if (token.isNotEmpty()) b.header("Authorization", "Bearer $token")
        when (method) {
            "GET" -> b.get()
            else -> b.method(method, (body ?: JSONObject()).toString().toRequestBody(JSON))
        }
        http.newCall(b.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("raw", text.take(500)) }
            if (!res.isSuccessful) {
                throw ApiException(
                    res.code,
                    json.optString("error", "http_${res.code}"),
                    json.optString("message", json.optString("error", "HTTP ${res.code}")),
                    json,
                )
            }
            json
        }
    }

    // ---- auth ----
    suspend fun login(account: String, password: String): JSONObject {
        val r = post("/api/auth/login", JSONObject().put("account", account).put("password", password).put("clientInfo", clientTag))
        Prefs.token = r.optString("token")
        Prefs.wallet = r.optString("walletAddress")
        return r
    }

    // ---- mobile ----
    suspend fun registerDevice(info: JSONObject) = post("/api/mobile/devices/register", info.put("device_id", Prefs.deviceId))
    suspend fun heartbeat(health: JSONObject) = post("/api/mobile/heartbeat", health.put("device_id", Prefs.deviceId))
    suspend fun bindPlatform(binding: JSONObject) = post("/api/mobile/bindings", binding.put("device_id", Prefs.deviceId))
    suspend fun fetchTask() = get("/api/mobile/tasks/current?device_id=${Prefs.deviceId}")
    suspend fun saveTask(task: JSONObject) = put("/api/mobile/tasks/current", task.put("device_id", Prefs.deviceId))
    /** The phone's task list (app ≥ 0.2). */
    suspend fun fetchTasks() = get("/api/mobile/tasks?device_id=${Prefs.deviceId}")
    suspend fun saveTasks(tasks: org.json.JSONArray) =
        put("/api/mobile/tasks", JSONObject().put("tasks", tasks).put("device_id", Prefs.deviceId))
    suspend fun fetchScript(id: String, appVer: Long, capVer: Int) =
        get("/api/mobile/scenarios/$id?appVer=$appVer&capVer=$capVer&device_id=${Prefs.deviceId}" +
            // Only debug builds without a pinned key can run unsigned test overrides.
            (if (BuildConfig.DEBUG && BuildConfig.SCRIPT_PUBKEY_HEX.isEmpty()) "&unsigned_ok=1" else ""))
    suspend fun uploadLogs(linesJson: String) =
        post("/api/mobile/logs", JSONObject().put("device_id", Prefs.deviceId).put("lines", org.json.JSONArray(linesJson)))
    suspend fun reportRun(run: JSONObject) = post("/api/mobile/runs", run.put("device_id", Prefs.deviceId))
    suspend fun notify(kind: String, message: String) =
        post("/api/mobile/notify", JSONObject().put("device_id", Prefs.deviceId).put("kind", kind).put("message", message))
    /** Uploads a diagnostics bundle (recent logs, device health, optional redacted tree); returns a support code. */
    suspend fun sendDiagnostics(bundle: JSONObject): String =
        post("/api/mobile/diagnostics", bundle.put("device_id", Prefs.deviceId)).optString("support_code")
    suspend fun historyHas(platformUid: String, kind: String, key: String): Boolean =
        get("/api/mobile/history?platform=tiktok&platform_uid=${enc(platformUid)}&kind=${enc(kind)}&key=${enc(key)}").optBoolean("has", false)
    suspend fun historyRemember(platformUid: String, kind: String, key: String) =
        post("/api/mobile/history", JSONObject().put("platform", "tiktok").put("platform_uid", platformUid).put("kind", kind).put("key", key))

    suspend fun stateGet(platformUid: String, scriptId: String, key: String): Any? =
        get("/api/mobile/state?platform=tiktok&platform_uid=${enc(platformUid)}&script_id=${enc(scriptId)}&key=${enc(key)}").opt("value")
    suspend fun stateSet(platformUid: String, scriptId: String, key: String, value: Any?) =
        put("/api/mobile/state", JSONObject().put("platform", "tiktok").put("platform_uid", platformUid)
            .put("script_id", scriptId).put("key", key).put("value", value ?: JSONObject.NULL))
    suspend fun leadsAdd(platformUid: String, handles: org.json.JSONArray, source: String, maxLeads: Int) =
        post("/api/mobile/leads/add", JSONObject().put("platform", "tiktok").put("platform_uid", platformUid)
            .put("handles", handles).put("source", source).put("max_leads", maxLeads))
    suspend fun leadsNext(platformUid: String, limit: Int) =
        get("/api/mobile/leads/next?platform=tiktok&platform_uid=${enc(platformUid)}&limit=$limit")
    suspend fun leadsMark(platformUid: String, handle: String, status: String, action: String, note: String) =
        post("/api/mobile/leads/mark", JSONObject().put("platform", "tiktok").put("platform_uid", platformUid)
            .put("handle", handle).put("status", status).put("action", action).put("note", note))

    // ---- shared with desktop ----
    suspend fun chargeAction(action: String, platform: String, ref: String, idempotencyKey: String, scriptVersion: String): JSONObject =
        post(
            "/api/charge/action",
            JSONObject().put("action_type", action).put("platform", platform).put("ref_id", ref.take(200))
                .put("idempotency_key", idempotencyKey).put("client", clientTag)
                .put("device_id", Prefs.deviceId).put("script_version", scriptVersion)
        )

    /** OpenAI-compatible chat call, same model id the desktop uses for comments. */
    suspend fun chat(system: String, user: String, maxTokens: Int = 800): String {
        val body = JSONObject()
            .put("model", "noobclawai-chat")
            .put("stream", false)
            .put("max_tokens", maxTokens)
            .put("response_format", JSONObject().put("type", "text"))
            .put("messages", org.json.JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        val r = post("/api/ai/chat/completions", body)
        return r.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty().trim()
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
