package com.noobclaw.mobile

import com.noobclaw.mobile.run.Schedule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleTest {
    private fun task(vararg slots: String, tz: String = "America/Los_Angeles", enabled: Boolean = true) =
        JSONObject().put("enabled", enabled).put("tz", tz).put("slots", JSONArray(slots.toList()))

    private val la = ZoneId.of("America/Los_Angeles")

    @Test fun dueInsideCatchUpWindowUsingTaskZone() {
        // 09:45 LA == 01:45 next day Seoul: the phone's own zone must not matter.
        val now = ZonedDateTime.of(2026, 10, 30, 9, 45, 0, 0, la).withZoneSameInstant(ZoneId.of("Asia/Seoul"))
        assertEquals("2026-10-30@09:30", Schedule.due(task("09:30"), now, emptySet())?.slotKey)
    }

    @Test fun notDueBeforeSlotOrAfterWindowOrWhenDone() {
        val t = task("09:30")
        assertNull(Schedule.due(t, ZonedDateTime.of(2026, 10, 30, 9, 29, 0, 0, la), emptySet()))
        assertNull(Schedule.due(t, ZonedDateTime.of(2026, 10, 30, 11, 31, 0, 0, la), emptySet()))
        assertNull(Schedule.due(t, ZonedDateTime.of(2026, 10, 30, 9, 40, 0, 0, la), setOf("2026-10-30@09:30")))
        assertNull(Schedule.due(task("09:30", enabled = false), ZonedDateTime.of(2026, 10, 30, 9, 40, 0, 0, la), emptySet()))
    }

    @Test fun atMostTwoSlotsPerDay() {
        assertEquals(2, Schedule.slots(task("08:00", "12:00", "20:00")).size)
    }

    private fun sched(kind: JSONObject, tz: String = "America/Los_Angeles") = JSONObject().put("id", "t1").put("tz", tz).put("schedule", kind)

    @Test fun everyDueWhenNeverRanOrIntervalPassed() {
        val now = ZonedDateTime.of(2026, 10, 31, 14, 5, 0, 0, la)
        val t = sched(JSONObject().put("kind", "every").put("hours", 3))
        assertEquals("2026-10-31@14:05#t1", Schedule.due(t, now, emptySet(), "#t1", null)?.slotKey)
        val ms = now.toInstant().toEpochMilli()
        assertNull(Schedule.due(t, now, emptySet(), "#t1", ms - 2 * 3_600_000))
        assertEquals("2026-10-31@14:05#t1", Schedule.due(t, now, emptySet(), "#t1", ms - 3 * 3_600_000)?.slotKey)
        val half = sched(JSONObject().put("kind", "every").put("hours", 0.5))
        assertNull(Schedule.due(half, now, emptySet(), "#t1", ms - 29 * 60_000))
        assertEquals(Schedule.Kind.Every(0.5), Schedule.kind(half))
    }

    @Test fun dailyAndManualKinds() {
        val t = sched(JSONObject().put("kind", "daily").put("at", "09:30"))
        assertEquals("2026-10-30@09:30#t1", Schedule.due(t, ZonedDateTime.of(2026, 10, 30, 9, 45, 0, 0, la), emptySet(), "#t1")?.slotKey)
        assertNull(Schedule.due(t, ZonedDateTime.of(2026, 10, 30, 9, 45, 0, 0, la), setOf("2026-10-30@09:30#t1"), "#t1"))
        val m = sched(JSONObject().put("kind", "manual"))
        assertNull(Schedule.due(m, ZonedDateTime.of(2026, 10, 30, 9, 45, 0, 0, la), emptySet(), "#t1", null))
        assertNull(Schedule.nextRunAt(m, null, emptySet()))
    }

    @Test fun legacyTasksMapLikeTheServer() {
        assertEquals(Schedule.Kind.Daily(java.time.LocalTime.of(9, 30)), Schedule.kind(task("09:30", "20:00")))
        assertEquals(Schedule.Kind.Manual, Schedule.kind(task("09:30", enabled = false)))
    }

    @Test fun lastStartInTheFutureCountsAsNeverRan() {
        val now = ZonedDateTime.of(2026, 10, 31, 14, 5, 0, 0, la)
        val ms = now.toInstant().toEpochMilli()
        val t = sched(JSONObject().put("kind", "every").put("hours", 6))
        // 1 h ahead (clock moved back): due now; 2 min ahead (small drift): still counts
        assertEquals("2026-10-31@14:05#t1", Schedule.due(t, now, emptySet(), "#t1", ms + 3_600_000)?.slotKey)
        assertNull(Schedule.due(t, now, emptySet(), "#t1", ms + 2 * 60_000))
        assertEquals(ms, Schedule.nextRunAt(t, ms + 3_600_000, emptySet(), now))
    }
}
