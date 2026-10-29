package com.noobclaw.mobile.a11y

/**
 * Hard allowlist enforced in Kotlin, independent of whatever the downloaded script asks for (PRD §劇本安全 #2).
 * Taps, swipes and text input are refused unless the active window belongs to one of these packages.
 */
object A11yGuard {
    val TARGET_PACKAGES = setOf(
        "com.zhiliaoapp.musically", // TikTok (global)
        "com.ss.android.ugc.trill",  // TikTok (some APAC builds)
    )

    fun isTarget(pkg: CharSequence?): Boolean = pkg != null && pkg.toString() in TARGET_PACKAGES

    class Blocked(reason: String) : SecurityException(reason)

    fun requireTarget(activePkg: CharSequence?) {
        if (!isTarget(activePkg)) throw Blocked("guard_blocked: active window is ${activePkg ?: "unknown"}")
    }
}
