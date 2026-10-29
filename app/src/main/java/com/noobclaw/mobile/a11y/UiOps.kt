package com.noobclaw.mobile.a11y

import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Screen reading and input on top of [NcAccessibilityService].
 *
 * Node handles: [find] / [descendants] return integer handles for the matched nodes; the last
 * [MAX_HANDLES] handles stay valid. Scripts never get raw node objects.
 */
class UiOps(private val svc: () -> NcAccessibilityService?) {

    // Handles stay valid across find() calls (a script may check for a challenge between reading a row and tapping it).
    // Oldest entries are dropped past MAX_HANDLES; a dropped handle fails with stale_handle.
    private val handles = object : LinkedHashMap<Int, AccessibilityNodeInfo>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, AccessibilityNodeInfo>?) = size > MAX_HANDLES
    }
    private var nextHandle = 1

    private fun service(): NcAccessibilityService =
        svc() ?: throw IllegalStateException("a11y_not_running")

    fun activePackage(): String? = svc()?.activePackage()

    data class Query(
        val text: String? = null,
        val textContains: String? = null,
        val textRegex: Regex? = null,
        val desc: String? = null,
        val descContains: String? = null,
        val descRegex: Regex? = null,
        val viewId: String? = null,
        val cls: String? = null,
        val clickable: Boolean? = null,
        val scrollable: Boolean? = null,
        val selected: Boolean? = null,
        val visibleOnly: Boolean = true,
        val limit: Int = 20,
    ) {
        companion object {
            fun from(j: JSONObject) = Query(
                text = j.optString("text").ifEmpty { null },
                textContains = j.optString("textContains").ifEmpty { null },
                textRegex = j.optString("textRegex").ifEmpty { null }?.let { Regex(it, RegexOption.IGNORE_CASE) },
                desc = j.optString("desc").ifEmpty { null },
                descContains = j.optString("descContains").ifEmpty { null },
                descRegex = j.optString("descRegex").ifEmpty { null }?.let { Regex(it, RegexOption.IGNORE_CASE) },
                viewId = j.optString("viewId").ifEmpty { null },
                cls = j.optString("cls").ifEmpty { null },
                clickable = if (j.has("clickable")) j.optBoolean("clickable") else null,
                scrollable = if (j.has("scrollable")) j.optBoolean("scrollable") else null,
                selected = if (j.has("selected")) j.optBoolean("selected") else null,
                visibleOnly = j.optBoolean("visibleOnly", true),
                limit = j.optInt("limit", 20).coerceIn(1, 200),
            )
        }
    }

    private fun matches(n: AccessibilityNodeInfo, q: Query): Boolean {
        val t = n.text?.toString().orEmpty()
        val d = n.contentDescription?.toString().orEmpty()
        if (q.text != null && t != q.text) return false
        if (q.textContains != null && !t.contains(q.textContains, ignoreCase = true)) return false
        if (q.textRegex != null && !q.textRegex.containsMatchIn(t)) return false
        if (q.desc != null && d != q.desc) return false
        if (q.descContains != null && !d.contains(q.descContains, ignoreCase = true)) return false
        if (q.descRegex != null && !q.descRegex.containsMatchIn(d)) return false
        if (q.viewId != null) {
            val id = n.viewIdResourceName.orEmpty()
            if (id != q.viewId && !id.endsWith(":id/${q.viewId}")) return false
        }
        if (q.cls != null && !n.className?.toString().orEmpty().endsWith(q.cls)) return false
        if (q.clickable != null && n.isClickable != q.clickable) return false
        if (q.scrollable != null && n.isScrollable != q.scrollable) return false
        if (q.selected != null && n.isSelected != q.selected) return false
        if (q.visibleOnly && !n.isVisibleToUser) return false
        return true
    }

    /** Breadth-first search of the active window. Returns node descriptors with handles. */
    fun find(q: Query): JSONArray {
        val out = JSONArray()
        val root = service().root() ?: return out
        // Never read other apps' screens, even if the system hands us their window.
        if (!A11yGuard.isTarget(root.packageName)) return out
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && out.length() < q.limit && visited < 4000) {
            val n = queue.removeFirst()
            visited++
            if (matches(n, q)) out.put(describe(n, register(n)))
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    /**
     * Descendants of a node in depth-first order (the node itself excluded), each with its depth.
     * Used to read one list row (a comment) as a unit. Does not invalidate existing handles.
     */
    fun descendants(handle: Int, maxDepth: Int = 6, limit: Int = 60): JSONArray {
        val out = JSONArray()
        val start = handles[handle] ?: return out
        if (!A11yGuard.isTarget(start.packageName)) return out
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            for (i in 0 until n.childCount) {
                if (out.length() >= limit) return
                val c = n.getChild(i) ?: continue
                out.put(describe(c, register(c)).put("depth", depth))
                if (depth < maxDepth) walk(c, depth + 1)
            }
        }
        walk(start, 1)
        return out
    }

    private fun register(n: AccessibilityNodeInfo): Int {
        val h = nextHandle++
        handles[h] = n
        return h
    }

    private fun describe(n: AccessibilityNodeInfo, handle: Int): JSONObject {
        val r = Rect().also { n.getBoundsInScreen(it) }
        val isEditable = n.isEditable
        return JSONObject()
            .put("handle", handle)
            // Text of editable fields is never exposed to scripts (it may hold what the user typed).
            .put("text", if (isEditable) "" else n.text?.toString().orEmpty().take(500))
            .put("desc", n.contentDescription?.toString().orEmpty().take(300))
            .put("viewId", n.viewIdResourceName.orEmpty())
            .put("cls", n.className?.toString().orEmpty())
            .put("clickable", n.isClickable)
            .put("selected", n.isSelected)
            .put("checked", n.isChecked)
            .put("editable", isEditable)
            .put("scrollable", n.isScrollable)
            .put("bounds", JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom))
    }

    fun nodeBounds(handle: Int): Rect? = handles[handle]?.let { n -> Rect().also { n.getBoundsInScreen(it) } }

    /** Re-reads a node's current state (e.g. a like button's selected flag after tapping). */
    fun refresh(handle: Int): JSONObject? {
        val n = handles[handle] ?: return null
        if (!n.refresh()) return null
        return describe(n, handle)
    }

    private fun guard() = A11yGuard.requireTarget(service().activePackage())

    suspend fun tapXY(x: Int, y: Int, pressMs: Long = 60): Boolean {
        guard()
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return service().gesture(p, pressMs)
    }

    /** Taps a node: ACTION_CLICK when the node (or an ancestor) is clickable, else a tap at its centre. */
    suspend fun tapNode(handle: Int): Boolean {
        guard()
        val n = handles[handle] ?: throw IllegalArgumentException("stale_handle")
        var c: AccessibilityNodeInfo? = n
        var depth = 0
        while (c != null && depth < 4) {
            if (c.isClickable) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            c = c.parent
            depth++
        }
        val r = Rect().also { n.getBoundsInScreen(it) }
        if (r.isEmpty) return false
        return tapXY(r.centerX(), r.centerY())
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): Boolean {
        guard()
        val p = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        return service().gesture(p, ms.coerceIn(80, 2000))
    }

    fun input(handle: Int, text: String): Boolean {
        guard()
        val n = handles[handle] ?: throw IllegalArgumentException("stale_handle")
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text.take(500))
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Scrolls a scrollable node (or its nearest scrollable ancestor) one page forward/backward. */
    fun scrollNode(handle: Int, forward: Boolean): Boolean {
        guard()
        var c: AccessibilityNodeInfo? = handles[handle] ?: throw IllegalArgumentException("stale_handle")
        var depth = 0
        while (c != null && depth < 6) {
            if (c.isScrollable) {
                return c.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            }
            c = c.parent
            depth++
        }
        return false
    }

    /** Presses the keyboard's action key (Search / Send) on an input field. Android 11+. */
    fun imeEnter(handle: Int): Boolean {
        guard()
        val n = handles[handle] ?: throw IllegalArgumentException("stale_handle")
        if (android.os.Build.VERSION.SDK_INT < 30) return false
        return n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    fun back(): Boolean = guard().let { service() }.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)

    /**
     * Compact, redacted tree for diagnostics (FR-12). Editable text is masked and only target-app
     * windows are dumped. The result stays on the device unless the user opted in to diagnostics.
     */
    fun dumpTree(maxNodes: Int = 800): JSONObject {
        val svc = service()
        val pkg = svc.activePackage()
        if (!A11yGuard.isTarget(pkg)) return JSONObject().put("package", pkg ?: "").put("redacted", true)
        val root = svc.root() ?: return JSONObject().put("package", pkg ?: "")
        var count = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int): JSONObject {
            count++
            val r = Rect().also { n.getBoundsInScreen(it) }
            val o = JSONObject()
                .put("cls", n.className?.toString().orEmpty().substringAfterLast('.'))
                .put("id", n.viewIdResourceName.orEmpty().substringAfter(":id/"))
                .put("text", if (n.isEditable) "***" else n.text?.toString().orEmpty().take(80))
                .put("desc", n.contentDescription?.toString().orEmpty().take(80))
                .put("b", "${r.left},${r.top},${r.right},${r.bottom}")
            if (n.isClickable) o.put("click", true)
            if (n.isSelected) o.put("sel", true)
            val kids = JSONArray()
            if (depth < 40) {
                for (i in 0 until n.childCount) {
                    if (count >= maxNodes) break
                    n.getChild(i)?.let { kids.put(walk(it, depth + 1)) }
                }
            }
            if (kids.length() > 0) o.put("kids", kids)
            return o
        }
        return JSONObject().put("package", pkg).put("root", walk(root, 0)).put("nodes", count)
    }

    companion object { const val MAX_HANDLES = 5000 }
}
