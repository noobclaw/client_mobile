package com.noobclaw.mobile.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.noobclaw.mobile.util.RunLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The accessibility service only exposes primitives (root node, gestures, global actions).
 * All policy (allowlist, rate limits, dry-run) lives in [UiOps] / the ctx bridge.
 */
class NcAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        RunLog.i(com.noobclaw.mobile.util.tr("無障礙服務已連接", "Accessibility service connected"))
        // 「抓」校准悬浮钮不再显示(用户装的就是 debug 包,会看到它);需要校准时临时打开这一行
        // if (com.noobclaw.mobile.BuildConfig.DEBUG) showDevBubble()
    }

    private var bubble: android.view.View? = null

    /**
     * Debug builds only: a small "抓" bubble drawn on the accessibility overlay layer. Tapping it uploads the
     * current TikTok screen tree (redacted) without leaving TikTok, for selector calibration.
     */
    private fun showDevBubble() {
        if (bubble != null) return
        val wm = getSystemService(android.view.WindowManager::class.java)
        val tv = android.widget.TextView(this).apply {
            text = "抓"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            setPadding(22, 14, 22, 14)
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 40f; setColor(0xAA3E63DD.toInt()) }
            setOnClickListener { com.noobclaw.mobile.run.RunService.start(this@NcAccessibilityService, com.noobclaw.mobile.run.RunService.ACTION_CAPTURE_NOW) }
        }
        val lp = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.WRAP_CONTENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL; x = 0; y = 0 }
        runCatching { wm.addView(tv, lp); bubble = tv }.onFailure { RunLog.w("開發懸浮鈕失敗：${it.message}") }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events are only used to know which package is in front; the script polls the tree itself.
        event?.packageName?.let { lastEventPackage = it.toString() }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        bubble?.let { b -> runCatching { getSystemService(android.view.WindowManager::class.java).removeView(b) } }
        bubble = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun root(): AccessibilityNodeInfo? = rootInActiveWindow

    /**
     * Roots of the front app's windows, top-most first. TikTok shows the comment sheet and the reply box in
     * their own windows on top of the video, so reading only the "active" window misses them.
     * Returns nothing unless the front application window belongs to a target app.
     */
    fun frontAppRoots(): List<AccessibilityNodeInfo> {
        val wins = runCatching { windows }.getOrNull().orEmpty()
            .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
        if (wins.isEmpty()) return listOfNotNull(rootInActiveWindow)
        val roots = wins.mapNotNull { runCatching { it.root }.getOrNull() }
        val frontPkg = roots.firstOrNull()?.packageName?.toString() ?: return emptyList()
        if (!A11yGuard.isTarget(frontPkg)) return emptyList()
        return roots.filter { it.packageName?.toString() == frontPkg }
    }

    /** Package of the front application window; null (= blocked by the guard) when it cannot be read. */
    fun activePackage(): String? {
        val wins = runCatching { windows }.getOrNull().orEmpty()
            .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
        val top = wins.firstOrNull()?.let { runCatching { it.root }.getOrNull() }
        return (top ?: rootInActiveWindow)?.packageName?.toString()
    }

    /**
     * Dispatches a gesture and suspends until the system reports completion or cancellation.
     * The floating ball is made untouchable meanwhile, so a tap under it reaches TikTok, not the ball.
     */
    suspend fun gesture(path: Path, durationMs: Long): Boolean {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { com.noobclaw.mobile.run.FloatBall.setPassThrough(true) }
        kotlinx.coroutines.delay(60)
        try { return gestureRaw(path, durationMs) } finally { com.noobclaw.mobile.run.FloatBall.setPassThrough(false) }
    }

    private suspend fun gestureRaw(path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 3000)))
            .build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!ok && cont.isActive) cont.resume(false)
    }

    companion object {
        @Volatile var instance: NcAccessibilityService? = null
            private set
        @Volatile private var lastEventPackage: String? = null

        val isRunning: Boolean get() = instance != null
    }
}
