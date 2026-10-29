package com.noobclaw.mobile.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory ring buffer of structured log lines (FR-11), plus a pending batch for upload (FR-12).
 * Lines never contain screen contents; scripts log their own summaries.
 */
object RunLog {
    data class Line(val ts: Long, val level: String, val msg: String, val runId: String?)

    private const val MAX = 500
    private val lines = CopyOnWriteArrayList<Line>()
    private val pending = CopyOnWriteArrayList<Line>()
    private val listeners = CopyOnWriteArrayList<(Line) -> Unit>()

    fun init(@Suppress("UNUSED_PARAMETER") ctx: Context) {}

    fun add(level: String, msg: String, runId: String? = null) {
        val l = Line(System.currentTimeMillis(), level, msg.take(1000), runId)
        lines.add(l)
        pending.add(l)
        while (lines.size > MAX) lines.removeAt(0)
        while (pending.size > MAX) pending.removeAt(0)
        listeners.forEach { runCatching { it(l) } }
    }

    fun i(msg: String, runId: String? = null) = add("info", msg, runId)
    fun w(msg: String, runId: String? = null) = add("warn", msg, runId)
    fun e(msg: String, runId: String? = null) = add("error", msg, runId)

    fun recent(): List<Line> = lines.toList()

    fun onLine(cb: (Line) -> Unit) { listeners.add(cb) }
    fun offLine(cb: (Line) -> Unit) { listeners.remove(cb) }

    /** Takes the pending batch as JSON for upload; caller re-adds on failure via [restore]. */
    fun drainPending(): Pair<List<Line>, String> {
        val batch = pending.toList()
        pending.removeAll(batch.toSet())
        val arr = JSONArray()
        batch.forEach {
            arr.put(JSONObject().put("ts", it.ts).put("level", it.level).put("msg", it.msg).put("run_id", it.runId ?: JSONObject.NULL))
        }
        return batch to arr.toString()
    }

    fun restore(batch: List<Line>) { pending.addAll(0, batch) }
}
