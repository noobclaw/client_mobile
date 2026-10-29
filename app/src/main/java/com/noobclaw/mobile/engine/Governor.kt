package com.noobclaw.mobile.engine

import kotlinx.coroutines.delay

/**
 * Caps how fast a script can act, regardless of what it requests (PRD §劇本安全 #3).
 * This is a transparency/anti-spam ceiling, not a "look human" mechanism.
 */
class Governor(
    private val maxInputsPerMinute: Int = 30,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val stamps = ArrayDeque<Long>()

    /** Suspends until one more input fits in the rolling 60 s window. */
    suspend fun acquire() {
        while (true) {
            val t = now()
            while (stamps.isNotEmpty() && t - stamps.first() >= 60_000) stamps.removeFirst()
            if (stamps.size < maxInputsPerMinute) { stamps.addLast(t); return }
            delay((60_000 - (t - stamps.first())).coerceIn(50, 60_000))
        }
    }

    /** Non-suspending check used by tests. */
    fun tryAcquire(): Boolean {
        val t = now()
        while (stamps.isNotEmpty() && t - stamps.first() >= 60_000) stamps.removeFirst()
        if (stamps.size >= maxInputsPerMinute) return false
        stamps.addLast(t)
        return true
    }
}
