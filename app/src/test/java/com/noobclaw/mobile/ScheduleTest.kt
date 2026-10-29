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
}
