package com.noobclaw.mobile

import com.noobclaw.mobile.engine.Governor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GovernorTest {
    @Test fun capsInputsPerRollingMinute() {
        var t = 0L
        val g = Governor(maxInputsPerMinute = 3) { t }
        repeat(3) { assertTrue(g.tryAcquire()) }
        assertFalse(g.tryAcquire())
        t = 59_999; assertFalse(g.tryAcquire())
        t = 60_000; assertTrue(g.tryAcquire())
        var n = 0; while (g.tryAcquire()) n++
        assertEquals(2, n)
    }
}
