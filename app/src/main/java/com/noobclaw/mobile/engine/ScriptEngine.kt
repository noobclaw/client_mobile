package com.noobclaw.mobile.engine

import android.content.Context
import android.content.Intent
import android.os.Build
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.noobclaw.mobile.a11y.A11yGuard
import com.noobclaw.mobile.a11y.NcAccessibilityService
import com.noobclaw.mobile.a11y.UiOps
import com.noobclaw.mobile.net.Api
import com.noobclaw.mobile.util.RunLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** ctx.say step codes from the contract, with the argument keys each may carry. */
private val STEP_ARGS: Map<String, List<String>> = mapOf(
    "open_app" to emptyList(), "account_ok" to emptyList(), "search" to listOf("kw"), "open_link" to emptyList(),
    "open_rival" to emptyList(), "open_comments" to emptyList(), "ai_writing" to emptyList(), "replied" to listOf("text"),
    "video_summary" to listOf("likes", "replies"), "next_video" to emptyList(), "reach_old_leads" to emptyList(),
    "collect_leads" to emptyList(), "leads_found" to listOf("n"), "reach_leads" to emptyList(), "reach_lead" to emptyList(),
    "lead_liked" to emptyList(), "lead_commented" to listOf("text"), "lead_followed" to emptyList(), "done" to emptyList(),
    "cap_reached" to emptyList(), "time_up" to emptyList(), "not_logged_in" to emptyList(), "verify_stop" to emptyList(),
    "stopped" to emptyList(), "stopped_other" to emptyList(), "liked" to emptyList(), "commented" to listOf("text"),
)
private const val MAX_STEPS = 300

/** A verified script pack as delivered by GET /api/mobile/scenarios/:id. */
data class ScriptPack(
    val id: String,
    val version: String,
    val code: String,
    val manifest: JSONObject,
)

/**
 * Per-run caps, checked before every charge. Values come from the task (the server clamps them
 * to the script manifest's risk_caps); the ceilings below are the app's own last line of defence.
 * Charge action types map onto three buckets: likes, comments, follows.
 */
data class RunCaps(val maxLikes: Int, val maxComments: Int, val maxFollows: Int, val maxRunMs: Long, val maxFavorites: Int = 0) {
    companion object {
        const val CEIL_LIKES = 1000
        const val CEIL_COMMENTS = 300
        const val CEIL_FOLLOWS = 100
        const val HARD_RUN_MS = 10L * 60 * 60 * 1000

        // 「不限」(config.cl_*_nolimit)= 真的不限,不再用 1000 / 300 封頂(2026-11-01)
        private fun nolimit(task: JSONObject, k: String) = task.optJSONObject("config")?.optBoolean(k, false) == true
        fun fromTask(task: JSONObject) = RunCaps(
            maxLikes = if (nolimit(task, "cl_like_nolimit")) Int.MAX_VALUE / 2 else task.optInt("max_likes", 300).coerceIn(0, CEIL_LIKES),
            maxComments = if (nolimit(task, "cl_reply_nolimit")) Int.MAX_VALUE / 2 else task.optInt("max_comments", 30).coerceIn(0, CEIL_COMMENTS),
            maxFollows = task.optInt("max_follows", 0).coerceIn(0, CEIL_FOLLOWS),
            maxRunMs = (task.optLong("max_run_minutes", 60) * 60_000).coerceIn(60_000, HARD_RUN_MS),
            // 收藏(抖音 / 小紅書互動任務,2026-11-02):config.max_favorites
            maxFavorites = (task.optJSONObject("config")?.optInt("max_favorites", 0) ?: 0).coerceIn(0, CEIL_FOLLOWS),
        )

        /** Which cap bucket a charge action type counts against; null = not allowed on mobile. */
        fun bucketOf(action: String): String? = when (action) {
            "tt_comment_like", "like_lite", "like", "comment_like" -> "likes"
            "comment" -> "comments"
            "follow" -> "follows"
            "favorite" -> "favorites"
            else -> null
        }
    }
}

/**
 * Runs one script pack inside QuickJS with a narrow ctx bridge.
 *
 * Sandbox rules (PRD §劇本安全 #3):
 *  - The only host entry point is `__nc_call(name, argsJson)`; scripts have no file or network access.
 *  - eval / Function / async-function constructors are disabled before the script runs.
 *  - Inputs pass through [A11yGuard] (target-app allowlist) and [Governor] (rate ceiling) in Kotlin.
 *  - The whole evaluation has a hard timeout; cancelling the coroutine interrupts the script.
 */
class ScriptEngine(
    private val appCtx: Context,
    private val pack: ScriptPack,
    private val task: JSONObject,
    private var binding: JSONObject,
    private val dryRun: Boolean,
    private val runId: String,
) {
    @Volatile var abortRequested = false
    private val ui = UiOps { NcAccessibilityService.instance }
    private val governor = Governor(maxInputsPerMinute = 30)
    private val caps = RunCaps.fromTask(task)
    /** tiktok / douyin / xhs: decides the app, allowed links, bound account and the billing platform. */
    private val platform = task.optString("platform").ifEmpty { "tiktok" }
    private val used = HashMap<String, Int>()
    private val dryState = HashMap<String, Any?>()
    private val counts = JSONObject()
    private var targets = JSONObject()
    /** Credits this run used: `charged` from chargeAction + billable tokens of every AI reply. */
    @Volatile private var credits = 0L
    private val statLock = Any()

    private var lastStep = ""
    private var lastStepAt = 0L
    private var stepCount = 0

    /** Step args are shown to the user and uploaded: never other people's handles or links. */
    private fun cleanStepText(v: String): String = v
        .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\S*(tiktok|douyin|xiaohongshu|xhslink)\\.com/\\S*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("@[A-Za-z0-9._]+"), "")
        .replace(Regex("\\s{2,}"), " ").trim().take(120)

    /** Live numbers for the heartbeat `progress` and the run view (safe to call from any thread). */
    fun progress(): JSONObject = synchronized(statLock) {
        JSONObject().put("counts", JSONObject(counts.toString())).put("targets", JSONObject(targets.toString())).put("credits", credits)
    }

    suspend fun run(): JSONObject {
        val quickJs = QuickJs.create(Dispatchers.Default)
        try {
            quickJs.evaluationTimeoutMillis = caps.maxRunMs + 10 * 60_000 // scripts stop themselves at maxRunMs; this is the backstop
            quickJs.asyncFunction("__nc_call") { args ->
                val name = args.getOrNull(0) as? String ?: ""
                val argJson = args.getOrNull(1) as? String ?: "null"
                dispatch(name, argJson)
            }
            val source = buildString {
                append(prelude())
                append("\n")
                append(pack.code)
                // Top-level await: quickjs-kt returns the completion value, not a Promise wrapper.
                append("\n;JSON.stringify((await main(ctx)) ?? null)")
            }
            // evaluationTimeoutMillis only counts JS execution, not awaited bridge calls: enforce wall time here.
            val raw = withTimeout(caps.maxRunMs + 10 * 60_000) { quickJs.evaluate<String>(source, "${pack.id}.js") }
            val result = runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("value", raw) }
            return synchronized(statLock) { result.put("counts", counts).put("targets", targets).put("credits", credits) }
        } finally {
            quickJs.close()
        }
    }

    private fun prelude(): String {
        val q = { s: String -> JSONObject.quote(s) }
        return """
"use strict";
globalThis.eval = undefined;
(function () {
  const blocked = function () { throw new Error('dynamic code is disabled'); };
  const ctors = [Function, (async function () {}).constructor, (function* () {}).constructor, (async function* () {}).constructor];
  for (const F of ctors) {
    try { Object.defineProperty(F.prototype, 'constructor', { value: blocked, writable: false, configurable: false }); } catch (e) {}
  }
  globalThis.Function = blocked;
})();
const __call = async (n, a) => {
  const r = JSON.parse(await __nc_call(n, JSON.stringify(a === undefined ? null : a)));
  if (r && r.__err) { const e = new Error(r.__err); e.code = r.code || ''; throw e; }
  return r ? r.v : null;
};
const ctx = Object.freeze({
  task: JSON.parse(${q(task.toString())}),
  binding: JSON.parse(${q(binding.toString())}),
  manifest: JSON.parse(${q(pack.manifest.toString())}),
  dryRun: ${if (dryRun) "true" else "false"},
  find: (query) => __call('find', query || {}),
  refresh: (handle) => __call('refresh', { handle }),
  children: (handle, maxDepth, limit) => __call('children', { handle, maxDepth: maxDepth || 6, limit: limit || 60 }),
  tap: (target) => __call('tap', target),
  swipe: (s) => __call('swipe', s),
  input: (handle, text) => __call('input', { handle, text }),
  back: () => __call('back'),
  scroll: (handle, forward) => __call('scroll', { handle, forward: forward !== false }),
  imeEnter: (handle, final) => __call('imeEnter', { handle, final: !!final }),
  openUrl: (url) => __call('openUrl', { url: String(url) }),
  quota: (bucket) => __call('quota', { bucket }),
  state: Object.freeze({
    get: (key) => __call('stateGet', { key }),
    set: (key, value) => __call('stateSet', { key, value }),
  }),
  leads: Object.freeze({
    add: (handles, source, maxLeads) => __call('leadsAdd', { handles: handles || [], source: String(source || ''), maxLeads: maxLeads || 300 }),
    next: (limit) => __call('leadsNext', { limit: limit || 20 }),
    mark: (handle, status, action, note) => __call('leadsMark', { handle, status, action: action || '', note: String(note || '') }),
  }),
  // fresh=true restarts the app on its home screen (clears the back stack left by earlier deep links).
  openApp: (pkg, fresh) => __call('openApp', { pkg, fresh: fresh === true }),
  activePackage: () => __call('activePackage'),
  screen: () => __call('screen'),
  appVersion: (pkg) => __call('appVersion', { pkg }),
  sleep: (ms) => __call('sleep', { ms }),
  aborted: () => __call('aborted'),
  log: (msg) => __call('log', { level: 'info', msg: String(msg) }),
  stepLog: (level, msg) => __call('log', { level, msg: String(msg) }),
  aiCall: (system, user) => __call('aiCall', { system, user }),
  chargeAction: (action, ref) => __call('chargeAction', { action, ref }),
  engageHistory: Object.freeze({
    has: (kind, key) => __call('historyHas', { kind, key }),
    remember: (kind, key) => __call('historyRemember', { kind, key }),
  }),
  addActionCount: (type, n) => __call('count', { type, n: n || 1 }),
  setActionTargets: (t) => __call('targets', t || {}),
  notify: (kind, msg) => __call('notify', { kind, msg: String(msg) }),
  dumpTree: () => __call('dumpTree'),
  // The TikTok account logged in on this phone, read by the script from the profile page. No manual binding.
  setAccount: (handle, nickname) => __call('setAccount', { handle: String(handle || ''), nickname: String(nickname || '') }),
  // Friendly progress line (a code + small args); the clients render it per language.
  say: (code, args, text) => __call('say', { code: String(code || ''), args: (args && typeof args === 'object') ? args : {}, text: String(text || '') }),
});
"""
    }

    private fun err(msg: String, code: String = "") = JSONObject().put("__err", msg).put("code", code).toString()
    private fun ok(v: Any?) = JSONObject().put("v", v ?: JSONObject.NULL).toString()

    /** Every ctx call lands here. Returns a JSON string; failures are encoded, never thrown into JS. */
    private val bridgeLock = kotlinx.coroutines.sync.Mutex()

    /** One bridge call at a time, even if a script fires several without awaiting (state below is not thread-safe). */
    private suspend fun dispatch(name: String, argJson: String): String = bridgeLock.withLock { dispatchLocked(name, argJson) }

    private suspend fun dispatchLocked(name: String, argJson: String): String {
        val a = runCatching { if (argJson == "null") JSONObject() else JSONObject(argJson) }.getOrElse { JSONObject() }
        return try {
            when (name) {
                "find" -> ok(ui.find(UiOps.Query.from(a)))
                "refresh" -> ok(ui.refresh(a.optInt("handle")))
                "children" -> ok(ui.descendants(a.optInt("handle"), a.optInt("maxDepth", 6).coerceIn(1, 12), a.optInt("limit", 60).coerceIn(1, 300)))
                "activePackage" -> ok(ui.activePackage())
                "screen" -> {
                    val m = appCtx.resources.displayMetrics
                    ok(JSONObject().put("w", m.widthPixels).put("h", m.heightPixels).put("sdk", Build.VERSION.SDK_INT))
                }
                "appVersion" -> {
                    val pkg = a.optString("pkg")
                    if (!A11yGuard.isTarget(pkg)) return err("not_allowed", "guard_blocked")
                    val pi = runCatching { appCtx.packageManager.getPackageInfo(pkg, 0) }.getOrNull()
                    ok(if (pi == null) null else JSONObject().put("name", pi.versionName ?: "").put("code", pi.longVersionCode))
                }
                "tap" -> {
                    if (abortRequested) return err("aborted", "aborted")
                    // Taps flagged final (like / send) are skipped in dry-run (FR-14).
                    if (dryRun && a.optBoolean("final", false)) return ok(JSONObject().put("dry", true))
                    governor.acquire()
                    val done = if (a.has("handle")) ui.tapNode(a.optInt("handle")) else ui.tapXY(a.optInt("x"), a.optInt("y"))
                    ok(done)
                }
                "swipe" -> {
                    if (abortRequested) return err("aborted", "aborted")
                    governor.acquire()
                    ok(ui.swipe(a.optInt("x1"), a.optInt("y1"), a.optInt("x2"), a.optInt("y2"), a.optLong("ms", 300)))
                }
                "input" -> {
                    if (abortRequested) return err("aborted", "aborted")
                    governor.acquire()
                    ok(ui.input(a.optInt("handle"), a.optString("text")))
                }
                "back" -> ok(ui.back())
                "scroll" -> {
                    if (abortRequested) return err("aborted", "aborted")
                    governor.acquire()
                    ok(ui.scrollNode(a.optInt("handle"), a.optBoolean("forward", true)))
                }
                "imeEnter" -> {
                    if (abortRequested) return err("aborted", "aborted")
                    // An Enter that sends something (a comment) is a final action: skipped in dry runs.
                    if (dryRun && a.optBoolean("final", false)) return ok(JSONObject().put("dry", true))
                    governor.acquire()
                    ok(ui.imeEnter(a.optInt("handle")))
                }
                "openUrl" -> {
                    // Only the platform's own links, and only into its installed app (never a browser).
                    val url = a.optString("url")
                    val allowed = when (platform) {
                        "douyin" -> Regex("^(https://((www|v|m)\\.)?(douyin|iesdouyin)\\.com/[^\\s]*|snssdk1128://[^\\s]+)$", RegexOption.IGNORE_CASE)
                        "xhs" -> Regex("^(https://((www)\\.)?xiaohongshu\\.com/[^\\s]*|https://xhslink\\.com/[^\\s]*|xhsdiscover://[^\\s]+)$", RegexOption.IGNORE_CASE)
                        else -> Regex("^https://(www\\.|m\\.|vm\\.|vt\\.)?tiktok\\.com/[^\\s]*$", RegexOption.IGNORE_CASE)
                    }
                    if (!allowed.matches(url)) return err("url_not_allowed", "guard_blocked")
                    val pkg = task.optString("target_package")
                    if (!A11yGuard.isTarget(pkg)) return err("not_allowed", "guard_blocked")
                    governor.acquire()
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { appCtx.startActivity(intent) }.fold({ ok(true) }, { err("open_failed", "open_failed") })
                }
                "quota" -> {
                    val b = a.optString("bucket")
                    val limit = when (b) { "likes" -> caps.maxLikes; "comments" -> caps.maxComments; "follows" -> caps.maxFollows; "favorites" -> caps.maxFavorites; else -> 0 }
                    ok((limit - (used[b] ?: 0)).coerceAtLeast(0))
                }
                "stateGet" -> {
                    val uid = binding.optString("platform_uid")
                    if (dryRun && dryState.containsKey(a.optString("key"))) return ok(dryState[a.optString("key")])
                    ok(if (uid.isEmpty()) null else Api.stateGet(uid, pack.id, a.optString("key"), platform))
                }
                "stateSet" -> {
                    val uid = binding.optString("platform_uid")
                    // Dry runs keep state in memory only, so a dry run never changes real counters or lists.
                    if (dryRun) { dryState[a.optString("key")] = a.opt("value"); return ok(true) }
                    if (uid.isNotEmpty()) Api.stateSet(uid, pack.id, a.optString("key"), a.opt("value"), platform)
                    ok(true)
                }
                "leadsAdd" -> {
                    val uid = binding.optString("platform_uid")
                    val handles = a.optJSONArray("handles") ?: org.json.JSONArray()
                    if (dryRun || uid.isEmpty()) return ok(JSONObject().put("added", handles.length()).put("dry", dryRun))
                    ok(Api.leadsAdd(uid, handles, a.optString("source"), a.optInt("maxLeads", 300), platform))
                }
                "leadsNext" -> {
                    val uid = binding.optString("platform_uid")
                    if (uid.isEmpty()) return ok(JSONObject().put("leads", org.json.JSONArray()).put("pending", 0))
                    ok(Api.leadsNext(uid, a.optInt("limit", 20).coerceIn(1, 100), platform))
                }
                "leadsMark" -> {
                    val uid = binding.optString("platform_uid")
                    if (!dryRun && uid.isNotEmpty()) Api.leadsMark(uid, a.optString("handle"), a.optString("status"), a.optString("action"), a.optString("note"), platform)
                    ok(true)
                }
                "openApp" -> {
                    val pkg = a.optString("pkg")
                    if (!A11yGuard.isTarget(pkg)) return err("not_allowed", "guard_blocked")
                    val intent = appCtx.packageManager.getLaunchIntentForPackage(pkg)
                        ?: return err("app_not_installed", "app_not_installed")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (a.optBoolean("fresh", false)) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    runCatching { appCtx.startActivity(intent) }.fold({ ok(true) }, { err("open_failed", "open_failed") })
                }
                "sleep" -> {
                    var left = a.optLong("ms", 0).coerceIn(0, 10 * 60_000)
                    while (left > 0 && !abortRequested) { val s = minOf(left, 500); delay(s); left -= s }
                    ok(!abortRequested)
                }
                "aborted" -> ok(abortRequested)
                "log" -> {
                    val level = a.optString("level", "info")
                    RunLog.add(if (level in setOf("info", "warn", "error")) level else "info", a.optString("msg"), runId)
                    ok(true)
                }
                "aiCall" -> {
                    if (dryRun) return ok("[dry-run] comment placeholder")
                    val r = withTimeout(90_000) { Api.chat(a.optString("system").take(8000), a.optString("user").take(8000)) }
                    synchronized(statLock) { credits += r.billable }
                    ok(r.text)
                }
                "say" -> {
                    val code = a.optString("code").trim()
                    // Only the contract's step codes, and only their argument keys; anything else is dropped.
                    // 兜底文字(2026-11-01):劇本可以同時帶一句話,App / 電腦端不認識這個代號時就顯示它 → 新步驟熱更就能看到
                    val fallback = cleanStepText(a.optString("text")).take(80)
                    val keys = STEP_ARGS[code] ?: (if (fallback.isNotEmpty() && code.matches(Regex("^[a-z0-9_]{1,40}$"))) emptyList() else return ok(false))
                    val src = a.optJSONObject("args") ?: JSONObject()
                    val args = JSONObject()
                    for (k in keys) {
                        when (val v = src.opt(k)) {
                            is Number -> args.put(k, v)
                            is String -> args.put(k, cleanStepText(v))
                            else -> {}
                        }
                    }
                    val line = JSONObject().put("c", code).put("a", args).apply { if (fallback.isNotEmpty()) put("t", fallback) }.toString()
                    if (line == lastStep || stepCount >= MAX_STEPS) return ok(false) // no repeats, at most 300 per run
                    // About one line per second: wait out the rest of the second instead of dropping the line.
                    val wait = 1000L - (System.currentTimeMillis() - lastStepAt)
                    if (wait > 0) delay(wait)
                    lastStep = line; lastStepAt = System.currentTimeMillis(); stepCount++
                    RunLog.add("step", line, runId)
                    ok(true)
                }
                "chargeAction" -> ok(charge(a.optString("action"), a.optString("ref")))
                "historyHas" -> {
                    val uid = binding.optString("platform_uid")
                    ok(uid.isNotEmpty() && Api.historyHas(uid, a.optString("kind"), a.optString("key"), platform))
                }
                "historyRemember" -> {
                    val uid = binding.optString("platform_uid")
                    if (!dryRun && uid.isNotEmpty()) Api.historyRemember(uid, a.optString("kind"), a.optString("key"), platform)
                    ok(true)
                }
                "count" -> {
                    val t = a.optString("type")
                    synchronized(statLock) { counts.put(t, counts.optInt(t, 0) + a.optInt("n", 1)) }
                    ok(true)
                }
                "targets" -> { synchronized(statLock) { targets = a }; ok(true) }
                "notify" -> {
                    if (!dryRun) runCatching { Api.notify(a.optString("kind"), a.optString("msg")) }
                    ok(true)
                }
                "dumpTree" -> ok(ui.dumpTree())
                "setAccount" -> {
                    // TikTok: @handle (lower case); 抖音號 / 小紅書號: as shown (2026-11-02)
                    val raw = a.optString("handle").trim().removePrefix("@")
                    val h = if (platform == "tiktok") raw.lowercase() else raw
                    val okId = if (platform == "tiktok") Regex("^[a-z0-9._]{2,24}$") else Regex("^[A-Za-z0-9._-]{2,40}$")
                    if (!okId.matches(h)) return err("bad_handle", "bad_handle")
                    val stored = runCatching { JSONObject(com.noobclaw.mobile.util.Prefs.bindingFor(platform)) }.getOrElse { JSONObject() }
                    // No nickname read this time: keep the one we already know for the same account.
                    val nick = a.optString("nickname").trim().take(60)
                        .ifEmpty { if (stored.optString("platform_uid") == h) stored.optString("nickname") else "" }
                    val b = JSONObject().put("platform", platform).put("platform_uid", h).put("display_id", if (platform == "tiktok") "@$h" else h).put("nickname", nick)
                    // Send again when the account changed, or when we now know a (different) nickname.
                    val changed = binding.optString("platform_uid") != h || stored.optString("platform_uid") != h ||
                        (nick.isNotEmpty() && stored.optString("nickname") != nick)
                    if (changed) {
                        // One phone = one TikTok account: register it silently (server keeps it to one NoobClaw account).
                        try { Api.bindPlatform(JSONObject(b.toString())) } catch (e: com.noobclaw.mobile.net.ApiException) {
                            if (e.status == 409) return err("platform_account_taken", "platform_account_taken")
                            throw e
                        }
                        com.noobclaw.mobile.util.Prefs.setBindingFor(platform, b.toString())
                    }
                    binding = b
                    ok(true)
                }
                else -> err("unknown_call:$name", "unknown_call")
            }
        } catch (e: A11yGuard.Blocked) {
            RunLog.w(com.noobclaw.mobile.util.tr("已攔截：", "Blocked: ") + e.message, runId)
            err(e.message ?: "guard_blocked", "guard_blocked")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            err(e.message ?: e.javaClass.simpleName, e.javaClass.simpleName)
        }
    }

    /** Applies the per-run caps before charging; charging carries an idempotency key (no double charge on retry). */
    private suspend fun charge(action: String, ref: String): JSONObject {
        val bucket = RunCaps.bucketOf(action) ?: return JSONObject().put("ok", false).put("reason", "action_not_enabled")
        val limit = when (bucket) { "likes" -> caps.maxLikes; "comments" -> caps.maxComments; "favorites" -> caps.maxFavorites; else -> caps.maxFollows }
        val used = used[bucket] ?: 0
        if (used >= limit) return JSONObject().put("ok", false).put("reason", "cap_reached")
        if (dryRun) {
            // Count in dry-run too, so the caps stop a dry run exactly where a real run would stop.
            this.used[bucket] = used + 1
            return JSONObject().put("ok", true).put("dry", true).put("charged", 0)
        }
        // The action already happened on screen: it counts against the cap whatever the billing result.
        this.used[bucket] = used + 1
        val refHash = java.security.MessageDigest.getInstance("SHA-256").digest(ref.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        val key = "$runId:$action:$refHash"
        var last: Exception? = null
        for (attempt in 1..3) {
            try {
                val r = Api.chargeAction(action, platform, ref, key, pack.version)
                val charged = r.optInt("charged", 0)
                synchronized(statLock) { credits += charged.coerceAtLeast(0) }
                return JSONObject().put("ok", r.optBoolean("ok", true)).put("charged", charged)
            } catch (e: com.noobclaw.mobile.net.ApiException) {
                if (e.status == 402) return JSONObject().put("ok", false).put("reason", "insufficient_balance")
                if (e.status in 400..499) return JSONObject().put("ok", false).put("reason", e.code)
                last = e
            } catch (e: Exception) {
                last = e
            }
            delay(2000L * attempt) // same idempotency key on every retry
        }
        return JSONObject().put("ok", false).put("reason", "charge_failed:${last?.message ?: ""}".take(120))
    }
}
