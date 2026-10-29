package com.noobclaw.mobile.run

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Decides whether a slot is due (FR-09). Slots are "HH:mm" in the task's IANA time zone,
 * so the cloud phone's own clock zone does not matter. A missed slot is only caught up inside
 * its catch-up window; quotas never carry over to the next slot.
 */
object Schedule {
    const val CATCH_UP_MINUTES = 120L

    data class Due(val slotKey: String)

    fun zone(task: JSONObject): ZoneId = runCatching { ZoneId.of(task.optString("tz", "UTC")) }.getOrDefault(ZoneId.of("UTC"))

    fun slots(task: JSONObject): List<LocalTime> {
        val arr = task.optJSONArray("slots") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> runCatching { LocalTime.parse(arr.optString(i)) }.getOrNull() }
            .distinct().sorted().take(2) // hard cap: at most 2 runs per day
    }

    /** Returns the slot that should run now, or null. [done] holds keys of slots already run. */
    fun due(task: JSONObject, now: ZonedDateTime, done: Set<String>): Due? {
        if (!task.optBoolean("enabled", false)) return null
        val local = now.withZoneSameInstant(zone(task))
        val today: LocalDate = local.toLocalDate()
        for (slot in slots(task)) {
            val at = ZonedDateTime.of(today, slot, local.zone)
            val key = "$today@$slot"
            if (key in done) continue
            val lateMin = java.time.Duration.between(at, local).toMinutes()
            if (lateMin in 0..CATCH_UP_MINUTES) return Due(key)
        }
        return null
    }

    /** Lateness in seconds for the run_started event (按時啟動率). */
    fun lateSeconds(task: JSONObject, slotKey: String, now: ZonedDateTime): Long {
        val slot = slotKey.substringAfter('@')
        val date = slotKey.substringBefore('@')
        val at = runCatching { ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(slot), zone(task)) }.getOrNull() ?: return -1
        return java.time.Duration.between(at, now).seconds
    }
}
