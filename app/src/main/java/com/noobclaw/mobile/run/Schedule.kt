package com.noobclaw.mobile.run

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Decides whether a task is due (FR-09). Three kinds (task.schedule, 2026-10-31, same as the browser app's run interval):
 *  - every N hours (0.5 / 1 / 3 / 6): due when now − last start ≥ N h; never ran on this phone → due now.
 *  - daily at HH:mm in the task's IANA time zone (the phone's own zone does not matter); a missed time is
 *    caught up inside a 2 h window; key "date@HH:mm#taskId" so each day runs once.
 *  - manual: never automatic.
 * Old tasks without `schedule` are mapped like the server's normalizeSchedule: enabled + slots → daily at the first slot, else manual.
 */
object Schedule {
    const val CATCH_UP_MINUTES = 120L
    val EVERY_HOURS = listOf(0.5, 1.0, 3.0, 6.0)

    data class Due(val slotKey: String)

    sealed class Kind {
        data class Every(val hours: Double) : Kind()
        data class Daily(val at: LocalTime) : Kind()
        object Manual : Kind()
    }

    fun zone(task: JSONObject): ZoneId = runCatching { ZoneId.of(task.optString("tz", "UTC")) }.getOrDefault(ZoneId.of("UTC"))

    /** Legacy slots ("HH:mm", at most 2). */
    fun slots(task: JSONObject): List<LocalTime> {
        val arr = task.optJSONArray("slots") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> runCatching { LocalTime.parse(arr.optString(i)) }.getOrNull() }
            .distinct().sorted().take(2) // hard cap: at most 2 runs per day
    }

    fun kind(task: JSONObject): Kind {
        val s = task.optJSONObject("schedule")
        when (s?.optString("kind")) {
            "every" -> {
                val h = s.optDouble("hours", 3.0)
                return Kind.Every(if (EVERY_HOURS.any { it == h }) h else 3.0)
            }
            "daily" -> return Kind.Daily(runCatching { LocalTime.parse(s.optString("at")) }.getOrDefault(LocalTime.of(9, 30)))
            "manual" -> return Kind.Manual
        }
        val sl = slots(task)
        return if (task.optBoolean("enabled", false) && sl.isNotEmpty()) Kind.Daily(sl.first()) else Kind.Manual
    }

    /** JSON for task.schedule. */
    fun toJson(k: Kind): JSONObject = when (k) {
        is Kind.Every -> JSONObject().put("kind", "every").put("hours", if (k.hours == Math.floor(k.hours)) k.hours.toInt() as Any else k.hours)
        is Kind.Daily -> JSONObject().put("kind", "daily").put("at", "%02d:%02d".format(k.at.hour, k.at.minute))
        Kind.Manual -> JSONObject().put("kind", "manual")
    }

    /**
     * Returns the run that should start now, or null. [done] holds daily keys already run; [lastStart] is the
     * task's last start (epoch ms) for "every"; [suffix] is "#taskId".
     */
    fun due(task: JSONObject, now: ZonedDateTime, done: Set<String>, suffix: String = "", lastStart: Long? = null): Due? {
        val local = now.withZoneSameInstant(zone(task))
        when (val k = kind(task)) {
            Kind.Manual -> return null
            is Kind.Every -> {
                val nowMs = now.toInstant().toEpochMilli()
                if (lastStart != null && nowMs - lastStart < (k.hours * 3_600_000).toLong()) return null
                val t = local.toLocalTime()
                return Due("${local.toLocalDate()}@" + "%02d:%02d".format(t.hour, t.minute) + suffix)
            }
            is Kind.Daily -> {
                val today: LocalDate = local.toLocalDate()
                val slot = k.at
                val at = ZonedDateTime.of(today, slot, local.zone)
                val key = "$today@$slot$suffix"
                if (key in done) return null
                // Keys written by apps before the task list had no suffix; they belong to the single old task ("default").
                if (suffix == "#default" && "$today@$slot" in done) return null
                val lateMin = java.time.Duration.between(at, local).toMinutes()
                return if (lateMin in 0..CATCH_UP_MINUTES) Due(key) else null
            }
        }
    }

    /** Next planned start (epoch ms), or null for manual. every: last start + N h (never ran → now). daily: next HH:mm. */
    fun nextRunAt(task: JSONObject, lastStart: Long?, done: Set<String>, now: ZonedDateTime = ZonedDateTime.now()): Long? {
        val nowMs = now.toInstant().toEpochMilli()
        return when (val k = kind(task)) {
            Kind.Manual -> null
            is Kind.Every -> if (lastStart == null) nowMs else maxOf(nowMs, lastStart + (k.hours * 3_600_000).toLong())
            is Kind.Daily -> {
                val local = now.withZoneSameInstant(zone(task))
                val today = local.toLocalDate()
                val at = ZonedDateTime.of(today, k.at, local.zone)
                val key = "$today@${k.at}#" + task.optString("id")
                val lateMin = java.time.Duration.between(at, local).toMinutes()
                when {
                    lateMin < 0 -> at.toInstant().toEpochMilli()
                    lateMin <= CATCH_UP_MINUTES && key !in done -> nowMs
                    else -> at.plusDays(1).toInstant().toEpochMilli()
                }
            }
        }
    }

    /** Lateness in seconds for the run_started event (按時啟動率). */
    fun lateSeconds(task: JSONObject, slotKey: String, now: ZonedDateTime): Long {
        val slot = slotKey.substringAfter('@').substringBefore('#') // "date@HH:mm#taskId"
        val date = slotKey.substringBefore('@')
        val at = runCatching { ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(slot), zone(task)) }.getOrNull() ?: return -1
        return java.time.Duration.between(at, now).seconds
    }
}
