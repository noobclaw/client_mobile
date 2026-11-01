package com.noobclaw.mobile.run

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.noobclaw.mobile.App
import com.noobclaw.mobile.R
import com.noobclaw.mobile.a11y.A11yGuard
import com.noobclaw.mobile.a11y.NcAccessibilityService
import com.noobclaw.mobile.engine.ScriptEngine
import com.noobclaw.mobile.net.Api
import com.noobclaw.mobile.util.Prefs
import com.noobclaw.mobile.util.tr
import com.noobclaw.mobile.util.RunLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Long-lived foreground service: heartbeat every 15 s (desktop commands arrive within ~15 s), task list refresh every 60 s (FR-09),
 * and the scheduled runs themselves. One run at a time.
 */
class RunService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    @Volatile private var currentRun: Job? = null
    @Volatile private var captureJob: Job? = null
    @Volatile private var engine: ScriptEngine? = null
    @Volatile private var notifJob: Job? = null
    @Volatile private var lastTasksAt = 0L          // 服務器任務列表的最後修改時間(心跳帶回)
    private val lastTry = HashMap<String, Long>() // every-N tasks: last time the scheduler tried to start them

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(getString(R.string.app_name) + " · " + tr("待命中", "Standing by"))
        FloatBall.show(this)   // 懸浮球(App 進程在就一直在;沒有懸浮窗權限時不顯示)
        when (intent?.action) {
            ACTION_STOP_RUN -> {
                if (currentRun?.isActive == true) stopCurrentRun("user_stopped")
                else if (!autostart() && captureJob?.isActive != true) { stopSelf(); return START_NOT_STICKY }
            }
            ACTION_RUN_NOW -> startRun("manual-" + System.currentTimeMillis(), dryRun = false, taskId = intent.getStringExtra(EXTRA_TASK_ID))
            ACTION_DRY_RUN -> startRun("dry-" + System.currentTimeMillis(), dryRun = true, taskId = intent.getStringExtra(EXTRA_TASK_ID))
            // 檢測帳號:只打開 TikTok 讀「我的」頁的帳號,不點讚不評論(沒帳號就不能建任務)
            ACTION_DETECT_ACCOUNT -> startRun("detect-" + System.currentTimeMillis(), dryRun = true, taskId = null, synthetic = JSONObject()
                .put("id", "detect").put("script_id", "tiktok_comment_like").put("platform", "tiktok").put("detect_only", true)
                .put("max_run_minutes", 5).put("config", JSONObject().put("comment_mode", "keywords").put("keywords", org.json.JSONArray().put("tiktok"))))
            ACTION_CAPTURE, ACTION_CAPTURE_NOW -> if (captureJob?.isActive != true) {
                val wait = intent?.action == ACTION_CAPTURE
                captureJob = scope.launch {
                    try { captureScreen(wait) } finally { runCatching { uploadLogs() }; captureJob = null; stopIfIdle() }
                }
            }
            ACTION_SHUTDOWN -> { stopCurrentRun("service_shutdown"); stopSelf(); return START_NOT_STICKY }
        }
        // The scheduler only runs when the user switched automation on; a manual run / stop does not turn it on.
        if (loop == null && autostart()) loop = scope.launch { mainLoop() }
        return if (autostart()) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(text: String) {
        val n = notification(text)
        ServiceCompat.startForeground(
            this, NOTIF_ID, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, App.CHANNEL_RUN)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .addAction(0, tr("停止", "Stop"), android.app.PendingIntent.getService(
                this, 1, Intent(this, RunService::class.java).setAction(ACTION_STOP_RUN),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
            .build()

    private suspend fun mainLoop() {
        var tick = 0
        while (scope.isActive) {
            runCatching { heartbeat() }.onFailure { RunLog.w(tr("心跳失敗：", "Heartbeat failed: ") + it.message) }
            // Task list from the server every 4th tick (60 s); schedule check every tick on the cached list.
            val fetch = tick % TASK_REFRESH_TICKS == 0
            if (tick % COMPLIANCE_TICKS == 0) runCatching { Api.refreshCompliance() } // compliance switch every 30 min
            tick++
            runCatching { refreshTaskAndMaybeRun(fetch) }.onFailure { RunLog.w(tr("排程檢查失敗：", "Schedule check failed: ") + it.message) }
            runCatching { uploadLogs() }
            delay(HEARTBEAT_MS)
        }
    }

    private suspend fun heartbeat() {
        if (Prefs.token.isEmpty()) return
        val tk = targetAppInfo()
        try { sendHeartbeat(tk) } catch (e: com.noobclaw.mobile.net.ApiException) {
            // Registration at login can fail after the token was saved: register again and retry once.
            if (e.code != "device_not_registered") throw e
            try {
                Api.registerDevice(JSONObject().put("vendor", Build.MANUFACTURER + "/" + Build.MODEL).put("android_sdk", Build.VERSION.SDK_INT))
            } catch (e2: com.noobclaw.mobile.net.ApiException) {
                if (e2.code != "phone_limit") throw e2
                // 被移除後名額已被別的手機佔了:標記超額(首頁提示),不再每 15 秒報錯
                if (!Prefs.overLimit) RunLog.w(tr("超出會員可用手機數，這臺手機暫停", "Over the plan's phone limit — this phone is paused"))
                Prefs.overLimit = true
                Prefs.phoneLimit = e2.body.optInt("limit", Prefs.phoneLimit)
                return
            }
            sendHeartbeat(tk)
        }
    }

    private suspend fun sendHeartbeat(tk: Pair<String, Long>?) {
        val r = Api.heartbeat(
            JSONObject()
                .put("app_ver", com.noobclaw.mobile.BuildConfig.VERSION_NAME)
                .put("android_sdk", Build.VERSION.SDK_INT)
                .put("vendor", Build.MANUFACTURER + "/" + Build.MODEL)
                .put("target_app", tk?.first ?: JSONObject.NULL)
                .put("target_ver", tk?.second ?: JSONObject.NULL)
                .put("a11y", NcAccessibilityService.isRunning)
                .put("running", currentRun?.isActive == true)
                .put("cooldown_until", Prefs.cooldownUntil)
                .put("progress", if (currentRun?.isActive == true) liveProgress() ?: JSONObject.NULL else JSONObject.NULL)
        )
        // 會員可用手機數:超出的手機不跑任務(服務器也不下發劇本)。
        Prefs.overLimit = r.optBoolean("over_limit", false)
        Prefs.phoneLimit = r.optInt("phone_limit", 0)
        // 電腦端改 / 刪了任務(服務器的任務列表時間變了)→ 馬上重新拉,不等每分鐘那次
        // 功能開關:「抓」校準按鈕等,後台改了下次心跳生效
        r.optJSONObject("flags")?.let { f ->
            Prefs.flagsJson = f.toString()
            NcAccessibilityService.instance?.applyDevBubble(f.optBoolean("dev_bubble", false))
        }
        val tasksAt = r.optLong("tasks_at", 0L)
        if (tasksAt > 0 && tasksAt != lastTasksAt) { lastTasksAt = tasksAt; refreshTasks() }
        // Run / stop requests sent from the desktop app ("我的手機").
        val cmds = r.optJSONArray("commands") ?: return
        for (i in 0 until cmds.length()) {
            val c = cmds.optJSONObject(i) ?: continue
            val taskId = c.optString("task_id").takeIf { it.isNotEmpty() && it != "null" }
            val action = c.optString("action")
            if (action != "stop" && Prefs.overLimit) { RunLog.w(tr("超出會員可用手機數，不運行", "Over the plan's phone limit — not running")); continue }
            when (action) {
                "stop" -> if (currentRun?.isActive == true) stopCurrentRun("user_stopped")
                "run" -> { RunLog.i(tr("電腦端要求運行", "Run requested from the desktop")); refreshTasks(); startRun("remote-" + System.currentTimeMillis(), dryRun = false, taskId = taskId) }
                "dry_run" -> { RunLog.i(tr("電腦端要求試跑", "Dry run requested from the desktop")); refreshTasks(); startRun("remote-dry-" + System.currentTimeMillis(), dryRun = true, taskId = taskId) }
            }
        }
    }

    private suspend fun refreshTasks() {
        runCatching { Api.fetchTasks().optJSONArray("tasks") }.getOrNull()?.let { Prefs.tasksJson = it.toString() }
    }

    private suspend fun uploadLogs() {
        if (Prefs.token.isEmpty()) return
        val (batch, json) = RunLog.drainPending()
        if (batch.isEmpty()) return
        runCatching { Api.uploadLogs(json) }.onFailure { RunLog.restore(batch) }
    }

    private suspend fun refreshTaskAndMaybeRun(fetch: Boolean = true) {
        if (Prefs.token.isEmpty()) return
        // Offline: keep using the last list we got, so a network blip does not skip a slot.
        if (fetch) runCatching { Api.fetchTasks().optJSONArray("tasks") }.onSuccess { if (it != null) Prefs.tasksJson = it.toString() }
            .onFailure { RunLog.w(tr("拉取任務失敗，用本地緩存：", "Couldn't fetch tasks, using the local copy: ") + it.message) }
        if (currentRun?.isActive == true) return
        if (Prefs.overLimit) return // 超出會員可用手機數:不排程
        // One run at a time: the first due task runs now, the next one on a later heartbeat (catch-up window 2 h).
        // Only the one task per platform the UI shows (left-over duplicates from older versions never run).
        for (task in Prefs.primaryTasks()) {
            val id = task.optString("id")
            val due = Schedule.due(task, ZonedDateTime.now(), doneSlots(), "#$id", Prefs.lastStart(id)) ?: continue
            val key = due.slotKey
            if (Schedule.kind(task).let { it is Schedule.Kind.Daily || it is Schedule.Kind.Random }) markSlotDone(key) // mark first: a crash mid-run never re-runs the same slot
            else {
                // every N h: the start time is written when the run really starts; a skipped run retries after 15 min
                val tried = lastTry[id] ?: 0L
                if (System.currentTimeMillis() - tried < RETRY_MS) continue
                lastTry[id] = System.currentTimeMillis()
            }
            startRun(key, dryRun = false, taskId = task.optString("id"))
            return
        }
    }

    private fun startRun(slotKey: String, dryRun: Boolean, taskId: String?, synthetic: JSONObject? = null) {
        if (currentRun?.isActive == true) { RunLog.w(tr("已有任務在運行，忽略這次觸發", "A task is already running; ignored")); return }
        currentRun = scope.launch { runOnce(slotKey, dryRun, taskId, synthetic) }
    }

    private fun stopCurrentRun(reason: String) {
        engine?.abortRequested = true
        RunLog.i(tr("停止：", "Stopped: ") + reason)
        val job = currentRun
        scope.launch { delay(3000); job?.cancel() } // FR-10: everything stops within 3 s (only the run being stopped)
    }

    private suspend fun runOnce(slotKey: String, dryRun: Boolean, taskId: String?, synthetic: JSONObject? = null) {
        val runId = UUID.randomUUID().toString()
        val started = System.currentTimeMillis()
        val report = JSONObject().put("run_id", runId).put("slot", slotKey).put("dry_run", dryRun)
        try {
            val pre = preconditions()
            if (pre != null) { report.put("status", "skipped").put("reason", pre); RunLog.w(tr("跳過運行：", "Skipped: ") + pre, runId); return }
            // A task deleted in the meantime is skipped, never swapped for another one.
            val task = synthetic ?: (if (taskId == null) Prefs.tasks().firstOrNull() else Prefs.tasks().firstOrNull { it.optString("id") == taskId })
                ?: run { report.put("status", "skipped").put("reason", "no_task"); RunLog.w(tr("跳過運行：沒有任務", "Skipped: no task"), runId); return }
            runningTaskId = task.optString("id")
            runningRunId = runId
            runStartedAt = started
            report.put("task_id", runningTaskId)
            val binding = runCatching { JSONObject(Prefs.bindingJson) }.getOrElse { JSONObject() } // filled by the script (setAccount)
            report.put("late_sec", Schedule.lateSeconds(task, slotKey, ZonedDateTime.now()))
            val (pkg, ver) = targetAppInfo() ?: run { report.put("status", "skipped").put("reason", "app_not_installed"); return }
            if (!dryRun && synthetic == null) Prefs.setLastStart(task.optString("id"), started) // the run really starts: "every N hours" counts from here
            RunLog.i(tr("開始運行", "Run started") + "（" + (if (dryRun) tr("試跑", "dry run") else tr("正式", "live")) + "）· $pkg v$ver", runId)
            val pack = ScriptLoader.load(task.optString("script_id", "tiktok_comment_like"), ver)
            report.put("script_version", pack.version)
            startInForeground(tr("運行中", "Running") + " · ${pack.id}")
            // 通知欄每 5 秒更新一次進度(懸浮球之外的另一個入口)
            notifJob = scope.launch {
                while (isActive) {
                    delay(5000)
                    val c = engine?.progress()?.optJSONObject("counts") ?: JSONObject()
                    val s = (System.currentTimeMillis() - started) / 1000
                    runCatching { startInForeground(tr("運行中", "Running") + " · 👍 ${c.optLong("like")} · 💬 ${c.optLong("comment")} · " + String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60)) }
                }
            }
            val e = ScriptEngine(applicationContext, pack, task.put("target_package", pkg), binding, dryRun, runId)
            engine = e
            liveEngine = e
            val result = e.run()
            report.put("status", result.optString("status", "done")).put("result", result)
            // The run row has its own reason column: fill it from the script result (time, caps_reached, profile_tab_not_found …).
            result.optString("reason").takeIf { it.isNotEmpty() }?.let { report.put("reason", it.take(200)) }
            handleChallenge(result, runId, dryRun)
            RunLog.i(tr("運行結束：", "Run finished: ") + "${result.optString("status", "done")} ${result.optString("reason")}", runId)
        } catch (e: ScriptLoader.Rejected) {
            report.put("status", "rejected").put("reason", e.reason)
            if (e.reason == "concurrency_limit") {
                // 會員可同時運行的手機數已滿:這次不開始,告訴用戶(登錄不限台數,限的是同時跑的台數)
                val msg = tr("同時運行的手機已達會員上限，等其他手機跑完再試，或升級會員", "Your plan's limit of phones running at once is reached — try later or upgrade")
                RunLog.w(msg, runId)
                android.os.Handler(android.os.Looper.getMainLooper()).post { android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_LONG).show() }
            } else RunLog.e(tr("劇本被拒絕：", "Script refused: ") + e.reason, runId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            report.put("status", "stopped").put("reason", "user_stopped")
        } catch (e: Exception) {
            report.put("status", "error").put("reason", (e.message ?: e.javaClass.simpleName).take(200))
            RunLog.e(tr("運行出錯：", "Run error: ") + e.message, runId)
        } finally {
            // Fill the result also for stopped / failed runs, so per-task totals and credits stay right.
            val res = report.optJSONObject("result") ?: JSONObject()
            val live = engine?.progress()
            if (live != null) {
                if (!res.has("counts")) res.put("counts", live.optJSONObject("counts"))
                if (!res.has("targets")) res.put("targets", live.optJSONObject("targets"))
                if (!res.has("credits")) res.put("credits", live.optLong("credits"))
            }
            report.optString("task_id").takeIf { it.isNotEmpty() }?.let { res.put("task_id", it) } // the server groups runs by result.task_id
            if (res.length() > 0) report.put("result", res)
            engine = null
            liveEngine = null
            runningTaskId = null
            runningRunId = null
            notifJob?.cancel(); notifJob = null
            report.put("duration_ms", System.currentTimeMillis() - started)
            // NonCancellable: the report still goes out when the run was cancelled or the service is stopping.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                if (synthetic == null) runCatching { Api.reportRun(report) }   // 檢測帳號不算一次運行
                if (synthetic == null && report.optString("task_id").isNotEmpty() && report.optString("status") != "skipped") runCatching {
                    Prefs.recordRun(JSONObject().put("run_id", runId).put("task_id", report.optString("task_id"))
                        .put("status", report.optString("status")).put("reason", report.optString("reason"))
                        .put("dry_run", dryRun).put("counts", res.optJSONObject("counts") ?: JSONObject())
                        .put("credits", res.optLong("credits")).put("started_at", started)
                        .put("duration_ms", report.optLong("duration_ms")))
                }
                runCatching { uploadLogs() } // also when automation is off (manual / dry runs)
            }
            if (autostart()) startInForeground(getString(R.string.app_name) + " · " + tr("待命中", "Standing by"))
            else { currentRun = null; stopIfIdle() } // a manual run with automation off: nothing to stand by for
        }
    }

    /**
     * Selector calibration: waits while the tester switches to the TikTok screen to look at, then uploads the
     * redacted accessibility tree (only TikTok windows; typed text masked) with a support code.
     */
    private suspend fun captureScreen(wait: Boolean) {
        if (wait) {
            RunLog.i(tr("8 秒後抓取畫面：請切到 TikTok 要看的那個畫面", "Capturing the screen in 8 s: switch to the TikTok screen to look at"))
            delay(8000)
        }
        val tree = runCatching { com.noobclaw.mobile.a11y.UiOps { NcAccessibilityService.instance }.dumpTree(1500) }
            .getOrElse { JSONObject().put("error", it.message ?: "dump_failed") }
        if (tree.optBoolean("redacted")) { RunLog.w(tr("前台不是 TikTok，沒有抓取", "TikTok isn't in front — nothing captured")); return }
        val logs = org.json.JSONArray()
        RunLog.recent().takeLast(100).forEach { logs.put(JSONObject().put("ts", it.ts).put("level", it.level).put("msg", if (it.level == "step") it.msg else RunLog.redact(it.msg))) }
        val tk = targetAppInfo()
        runCatching {
            Api.sendDiagnostics(JSONObject().put("kind", "screen_capture").put("tree", tree).put("logs", logs)
                .put("target_app", tk?.first ?: "").put("target_ver", tk?.second ?: 0)
                .put("app_ver", com.noobclaw.mobile.BuildConfig.VERSION_NAME).put("android_sdk", Build.VERSION.SDK_INT))
        }.onSuccess {
            RunLog.i(tr("已上傳畫面結構，客服碼 ", "Screen structure uploaded, support code ") + it)
            if (!wait) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(applicationContext, tr("已抓取 ", "Captured ") + it, android.widget.Toast.LENGTH_SHORT).show()
            }
        }.onFailure { RunLog.e(tr("上傳失敗：", "Upload failed: ") + it.message) }
    }

    /** Stops the service when automation is off and neither a run nor a capture is pending. */
    private fun stopIfIdle() {
        if (currentRun?.isActive != true && captureJob?.isActive != true && !autostart()) stopSelf()
    }

    /** Returns a reason code when the run must not start (FR-04 app-side part; the script does the rest). */
    private fun preconditions(): String? {
        if (Prefs.token.isEmpty()) return "not_logged_in"
        if (Prefs.consentVersion < CONSENT_VERSION) return "consent_required"
        if (!NcAccessibilityService.isRunning) return "a11y_disabled"
        if (System.currentTimeMillis() < Prefs.cooldownUntil) return "cooldown"
        return null
    }

    /** FR-08: per-kind cooldown, never shorter than the desktop manifest values; FR-18 notification. */
    private suspend fun handleChallenge(result: JSONObject, runId: String, dryRun: Boolean) {
        val kind = result.optString("challenge")
        if (kind.isEmpty()) return
        val hours = when (kind) {
            "captcha" -> 24L
            "rate_limited" -> 48L
            "verify_identity", "account_flagged" -> 72L
            else -> 24L
        }
        Prefs.cooldownUntil = System.currentTimeMillis() + hours * 3600_000
        RunLog.w(tr("遇到平台驗證（$kind），已停止，冷卻 $hours 小時。請在這臺手機上手動處理。", "TikTok asked for verification ($kind): stopped, cooling down $hours h. Please handle it on this phone."), runId)
        if (!dryRun) runCatching { Api.notify("challenge:$kind", "TikTok 出現驗證（$kind），任務已停止並冷卻 $hours 小時，請在這台雲手機上手動處理。") }
    }

    private fun targetAppInfo(): Pair<String, Long>? {
        for (pkg in A11yGuard.TARGET_PACKAGES) {
            val pi = runCatching { packageManager.getPackageInfo(pkg, 0) }.getOrNull() ?: continue
            return pkg to pi.longVersionCode
        }
        return null
    }

    /** Logged in and consented → stand by: daily times run, and the desktop app can start / stop runs. */
    private fun autostart(): Boolean = Prefs.token.isNotEmpty() && Prefs.consentVersion >= CONSENT_VERSION

    private fun doneSlots(): Set<String> =
        getSharedPreferences("nc_slots", Context.MODE_PRIVATE).getStringSet("done", emptySet()) ?: emptySet()

    private fun markSlotDone(key: String) {
        val sp = getSharedPreferences("nc_slots", Context.MODE_PRIVATE)
        // Keep only the last few days of slot keys ("yyyy-MM-dd@HH:mm" sorts by date).
        val cutoff = runCatching { java.time.LocalDate.parse(key.take(10)).minusDays(3).toString() }.getOrDefault("")
        val keep = (sp.getStringSet("done", emptySet()) ?: emptySet()).filter { it >= cutoff }.toMutableSet()
        keep.add(key)
        sp.edit().putStringSet("done", keep).apply()
    }

    companion object {
        const val CONSENT_VERSION = 1
        const val NOTIF_ID = 42
        const val HEARTBEAT_MS = 15_000L
        const val TASK_REFRESH_TICKS = 4
        const val COMPLIANCE_TICKS = 120          // 120 × 15 s = 30 min
        const val RETRY_MS = 15 * 60_000L         // skipped "every N h" run: try again after 15 min
        const val ACTION_RUN_NOW = "com.noobclaw.mobile.RUN_NOW"
        const val ACTION_DRY_RUN = "com.noobclaw.mobile.DRY_RUN"
        const val ACTION_DETECT_ACCOUNT = "com.noobclaw.mobile.DETECT_ACCOUNT"
        const val ACTION_STOP_RUN = "com.noobclaw.mobile.STOP_RUN"
        const val ACTION_SHUTDOWN = "com.noobclaw.mobile.SHUTDOWN"
        const val ACTION_CAPTURE = "com.noobclaw.mobile.CAPTURE"
        const val ACTION_CAPTURE_NOW = "com.noobclaw.mobile.CAPTURE_NOW"
        const val EXTRA_TASK_ID = "task_id"

        /** Id of the task running right now (null when idle); the UI reads it to show "運行中". */
        @Volatile var runningTaskId: String? = null
        @Volatile var runningRunId: String? = null
        @Volatile var runStartedAt: Long = 0L
        @Volatile private var liveEngine: ScriptEngine? = null

        /** Live progress of the current run: {run_id, task_id, counts, targets, credits, started_at}; null when idle. */
        fun liveProgress(): JSONObject? {
            val run = runningRunId ?: return null
            val p = liveEngine?.progress() ?: JSONObject().put("counts", JSONObject()).put("targets", JSONObject()).put("credits", 0)
            return p.put("run_id", run).put("task_id", runningTaskId ?: "").put("started_at", runStartedAt)
        }

        fun start(ctx: Context, action: String? = null, taskId: String? = null) {
            val i = Intent(ctx, RunService::class.java)
            if (action != null) i.action = action
            if (taskId != null) i.putExtra(EXTRA_TASK_ID, taskId)
            ctx.startForegroundService(i)
        }
    }
}
