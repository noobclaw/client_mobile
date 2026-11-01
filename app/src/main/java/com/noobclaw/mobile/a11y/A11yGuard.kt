package com.noobclaw.mobile.a11y

/**
 * Hard allowlist enforced in Kotlin, independent of whatever the downloaded script asks for (PRD §劇本安全 #2).
 * Taps, swipes and text input are refused unless the active window belongs to one of these packages.
 */
object A11yGuard {
    /** Packages per platform (2026-11-02: 抖音 / 小紅書 added for cloud-phone tasks). */
    val PLATFORM_PACKAGES: Map<String, List<String>> = mapOf(
        "tiktok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),   // TikTok global / some APAC builds
        "douyin" to listOf("com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite"),   // 抖音 / 抖音極速版
        "xhs" to listOf("com.xingin.xhs"),                                                 // 小紅書
    )
    val TARGET_PACKAGES: Set<String> = PLATFORM_PACKAGES.values.flatten().toSet()
    fun platformOfPackage(pkg: CharSequence?): String? = PLATFORM_PACKAGES.entries.firstOrNull { pkg?.toString() in it.value }?.key

    fun isTarget(pkg: CharSequence?): Boolean = pkg != null && pkg.toString() in TARGET_PACKAGES

    class Blocked(reason: String) : SecurityException(reason)

    fun requireTarget(activePkg: CharSequence?) {
        if (!isTarget(activePkg)) throw Blocked("guard_blocked: active window is ${activePkg ?: "unknown"}")
    }
}
