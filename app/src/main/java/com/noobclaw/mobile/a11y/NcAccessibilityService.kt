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
        RunLog.i("無障礙服務已連接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events are only used to know which package is in front; the script polls the tree itself.
        event?.packageName?.let { lastEventPackage = it.toString() }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun root(): AccessibilityNodeInfo? = rootInActiveWindow

    /** Package of the window in front; null (= blocked by the guard) when it cannot be read. */
    fun activePackage(): String? = rootInActiveWindow?.packageName?.toString()

    /** Dispatches a gesture and suspends until the system reports completion or cancellation. */
    suspend fun gesture(path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
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
