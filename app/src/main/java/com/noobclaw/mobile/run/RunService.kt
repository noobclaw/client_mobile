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
 * Long-lived foreground service: heartbeat every 60 s (FR-13), task refresh on each heartbeat (FR-09),
 * and the scheduled runs themselves. One run at a time.
 */
class RunService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    @Volatile private var currentRun: Job? = null
    @Volatile private var captureJob: Job? = null
    @Volatile private var engine: ScriptEngine? = null
    private var overlay: FloatingStop? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(getString(R.string.app_name) + " · 待命中")
        when (intent?.action) {
            ACTION_STOP_RUN -> {
                if (currentRun?.isActive == true) stopCurrentRun("user_stopped")
                else if (!autostart() && captureJob?.isActive != true) { stopSelf(); return START_NOT_STICKY }
            }
            ACTION_RUN_NOW -> startRun("manual-" + System.currentTimeMillis(), dryRun = false, taskId = intent.getStringExtra(EXTRA_TASK_ID))
            ACTION_DRY_RUN -> startRun("dry-" + System.currentTimeMillis(), dryRun = true, taskId = intent.getStringExtra(EXTRA_TASK_ID))
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
        overlay?.hide()
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
            .addAction(0, "停止", android.app.PendingIntent.getService(
                this, 1, Intent(this, RunService::class.java).setAction(ACTION_STOP_RUN),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
            .build()

    private suspend fun mainLoop() {
        while (scope.isActive) {
            runCatching { heartbeat() }.onFailure { RunLog.w("心跳失敗：${it.message}") }
            runCatching { refreshTaskAndMaybeRun() }.onFailure { RunLog.w("排程檢查失敗：${it.message}") }
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
            Api.registerDevice(JSONObject().put("vendor", Build.MANUFACTURER + "/" + Build.MODEL).put("android_sdk", Build.VERSION.SDK_INT))
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
        )
        // Run / stop requests sent from the desktop app ("我的手機").
        val cmds = r.optJSONArray("commands") ?: return
        for (i in 0 until cmds.length()) {
            val c = cmds.optJSONObject(i) ?: continue
            val taskId = c.optString("task_id").takeIf { it.isNotEmpty() && it != "null" }
            when (c.optString("action")) {
                "stop" -> if (currentRun?.isActive == true) stopCurrentRun("user_stopped")
                "run" -> { RunLog.i("電腦端要求運行"); refreshTasks(); startRun("remote-" + System.currentTimeMillis(), dryRun = false, taskId = taskId) }
                "dry_run" -> { RunLog.i("電腦端要求試跑"); refreshTasks(); startRun("remote-dry-" + System.currentTimeMillis(), dryRun = true, taskId = taskId) }
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

    private suspend fun refreshTaskAndMaybeRun() {
        if (Prefs.token.isEmpty()) return
        // Offline: keep using the last list we got, so a network blip does not skip a slot.
        runCatching { Api.fetchTasks().optJSONArray("tasks") }.onSuccess { if (it != null) Prefs.tasksJson = it.toString() }
            .onFailure { RunLog.w("拉取任務失敗，用本地緩存：${it.message}") }
        if (currentRun?.isActive == true) return
        // One run at a time: the first due task runs now, the next one on a later heartbeat (catch-up window 2 h).
        for (task in Prefs.tasks()) {
            val due = Schedule.due(task, ZonedDateTime.now(), doneSlots(), "#" + task.optString("id")) ?: continue
            val key = due.slotKey
            markSlotDone(key) // mark first: a crash mid-run never re-runs the same slot
            startRun(key, dryRun = false, taskId = task.optString("id"))
            return
        }
    }

    private fun startRun(slotKey: String, dryRun: Boolean, taskId: String?) {
        if (currentRun?.isActive == true) { RunLog.w("已有任務在運行，忽略這次觸發"); return }
        currentRun = scope.launch { runOnce(slotKey, dryRun, taskId) }
    }

    private fun stopCurrentRun(reason: String) {
        engine?.abortRequested = true
        RunLog.i("停止：$reason")
        val job = currentRun
        scope.launch { delay(3000); job?.cancel() } // FR-10: everything stops within 3 s (only the run being stopped)
    }

    private suspend fun runOnce(slotKey: String, dryRun: Boolean, taskId: String?) {
        val runId = UUID.randomUUID().toString()
        val started = System.currentTimeMillis()
        val report = JSONObject().put("run_id", runId).put("slot", slotKey).put("dry_run", dryRun)
        try {
            val pre = preconditions()
            if (pre != null) { report.put("status", "skipped").put("reason", pre); RunLog.w("跳過運行：$pre", runId); return }
            // A task deleted in the meantime is skipped, never swapped for another one.
            val task = (if (taskId == null) Prefs.tasks().firstOrNull() else Prefs.tasks().firstOrNull { it.optString("id") == taskId })
                ?: run { report.put("status", "skipped").put("reason", "no_task"); RunLog.w("跳過運行：沒有任務", runId); return }
            runningTaskId = task.optString("id")
            report.put("task_id", runningTaskId)
            val binding = runCatching { JSONObject(Prefs.bindingJson) }.getOrElse { JSONObject() } // filled by the script (setAccount)
            report.put("late_sec", Schedule.lateSeconds(task, slotKey, ZonedDateTime.now()))
            val (pkg, ver) = targetAppInfo() ?: run { report.put("status", "skipped").put("reason", "app_not_installed"); return }
            RunLog.i("開始運行（${if (dryRun) "乾跑" else "正式"}）· $pkg v$ver", runId)
            val pack = ScriptLoader.load(task.optString("script_id", "tiktok_comment_like"), ver)
            report.put("script_version", pack.version)
            startInForeground("運行中 · ${pack.id}")
            overlay = FloatingStop(this) { startService(Intent(this, RunService::class.java).setAction(ACTION_STOP_RUN)) }.also { it.show() }
            val e = ScriptEngine(applicationContext, pack, task.put("target_package", pkg), binding, dryRun, runId)
            engine = e
            val result = e.run()
            report.put("status", result.optString("status", "done")).put("result", result)
            // The run row has its own reason column: fill it from the script result (time, caps_reached, profile_tab_not_found …).
            result.optString("reason").takeIf { it.isNotEmpty() }?.let { report.put("reason", it.take(200)) }
            handleChallenge(result, runId, dryRun)
            RunLog.i("運行結束：${result.optString("status", "done")} ${result.optString("reason")}", runId)
        } catch (e: ScriptLoader.Rejected) {
            report.put("status", "rejected").put("reason", e.reason)
            RunLog.e("劇本被拒絕：${e.reason}", runId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            report.put("status", "stopped").put("reason", "user_stopped")
        } catch (e: Exception) {
            report.put("status", "error").put("reason", (e.message ?: e.javaClass.simpleName).take(200))
            RunLog.e("運行出錯：${e.message}", runId)
        } finally {
            engine = null
            runningTaskId = null
            overlay?.hide(); overlay = null
            report.put("duration_ms", System.currentTimeMillis() - started)
            // NonCancellable: the report still goes out when the run was cancelled or the service is stopping.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { Api.reportRun(report) }
                runCatching { uploadLogs() } // also when automation is off (manual / dry runs)
            }
            if (autostart()) startInForeground(getString(R.string.app_name) + " · 待命中")
            else { currentRun = null; stopIfIdle() } // a manual run with automation off: nothing to stand by for
        }
    }

    /**
     * Selector calibration: waits while the tester switches to the TikTok screen to look at, then uploads the
     * redacted accessibility tree (only TikTok windows; typed text masked) with a support code.
     */
    private suspend fun captureScreen(wait: Boolean) {
        if (wait) {
            RunLog.i("8 秒後抓取畫面：請切到 TikTok 要看的那個畫面")
            delay(8000)
        }
        val tree = runCatching { com.noobclaw.mobile.a11y.UiOps { NcAccessibilityService.instance }.dumpTree(1500) }
            .getOrElse { JSONObject().put("error", it.message ?: "dump_failed") }
        if (tree.optBoolean("redacted")) { RunLog.w("前台不是 TikTok，沒有抓取"); return }
        val logs = org.json.JSONArray()
        RunLog.recent().takeLast(100).forEach { logs.put(JSONObject().put("ts", it.ts).put("level", it.level).put("msg", it.msg)) }
        val tk = targetAppInfo()
        runCatching {
            Api.sendDiagnostics(JSONObject().put("kind", "screen_capture").put("tree", tree).put("logs", logs)
                .put("target_app", tk?.first ?: "").put("target_ver", tk?.second ?: 0)
                .put("app_ver", com.noobclaw.mobile.BuildConfig.VERSION_NAME).put("android_sdk", Build.VERSION.SDK_INT))
        }.onSuccess {
            RunLog.i("已上傳畫面結構，客服碼 $it")
            if (!wait) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(applicationContext, "已抓取 $it", android.widget.Toast.LENGTH_SHORT).show()
            }
        }.onFailure { RunLog.e("上傳失敗：${it.message}") }
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
        RunLog.w("遇到平台驗證（$kind），已停止，冷卻 $hours 小時。請在這台手機上手動處理。", runId)
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
        const val HEARTBEAT_MS = 60_000L
        const val ACTION_RUN_NOW = "com.noobclaw.mobile.RUN_NOW"
        const val ACTION_DRY_RUN = "com.noobclaw.mobile.DRY_RUN"
        const val ACTION_STOP_RUN = "com.noobclaw.mobile.STOP_RUN"
        const val ACTION_SHUTDOWN = "com.noobclaw.mobile.SHUTDOWN"
        const val ACTION_CAPTURE = "com.noobclaw.mobile.CAPTURE"
        const val ACTION_CAPTURE_NOW = "com.noobclaw.mobile.CAPTURE_NOW"
        const val EXTRA_TASK_ID = "task_id"

        /** Id of the task running right now (null when idle); the UI reads it to show "運行中". */
        @Volatile var runningTaskId: String? = null

        fun start(ctx: Context, action: String? = null, taskId: String? = null) {
            val i = Intent(ctx, RunService::class.java)
            if (action != null) i.action = action
            if (taskId != null) i.putExtra(EXTRA_TASK_ID, taskId)
            ctx.startForegroundService(i)
        }
    }
}
