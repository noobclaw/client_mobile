package com.noobclaw.mobile.ui

import com.noobclaw.mobile.util.tr
import com.noobclaw.mobile.util.trf
import org.json.JSONObject

/**
 * Friendly run steps: scripts call ctx.say(code, args); the app stores a RunLog line with level "step" and
 * msg = {"c": code, "a": args}. This turns it into one short plain sentence in the app's language.
 * Unknown codes return null (the line is skipped).
 */
object Steps {
    fun text(msg: String): String? {
        val j = runCatching { JSONObject(msg) }.getOrNull() ?: return null
        // 認識的代號按界面語言翻譯;不認識的用劇本帶來的兜底文字
        return text(j.optString("c"), j.optJSONObject("a") ?: JSONObject()) ?: j.optString("t").trim().takeIf { it.isNotEmpty() }
    }

    fun text(code: String, a: JSONObject): String? {
        fun s(k: String) = a.optString(k).take(60)
        fun n(k: String) = a.optLong(k)
        return when (code) {
            "open_app" -> tr("打開 App", "Opening the app")
            "account_ok" -> tr("確認帳號", "Account checked")
            "search" -> trf("搜索「{}」", "Searching \"{}\"", s("kw"))
            "open_link" -> tr("打開指定作品", "Opening the chosen video")
            "open_rival" -> tr("打開同行主頁", "Opening a competitor's profile")
            "open_comments" -> tr("打開視頻，看評論區", "Opening a video's comments")
            "ai_writing" -> tr("AI 正在寫回覆", "AI is writing a reply")
            "replied" -> trf("回覆已發出：“{}”", "Reply sent: \"{}\"", s("text"))
            "liked" -> tr("點了一個讚", "Liked a post")
            "commented" -> trf("評論已發出：“{}”", "Comment sent: \"{}\"", s("text"))
            "video_summary" -> tr("這條視頻：點讚 ${n("likes")} · 回覆 ${n("replies")}", "This video: ${n("likes")} likes · ${n("replies")} replies")
            "next_video" -> tr("換下一條視頻", "Next video")
            "reach_old_leads" -> tr("先聯繫之前找到的潛在客戶", "Reaching leads found earlier first")
            "collect_leads" -> tr("在評論區找潛在客戶", "Looking for leads in the comments")
            "leads_found" -> tr("已找到 ${n("n")} 個潛在客戶", "Found ${n("n")} leads")
            "reach_leads" -> tr("開始聯繫潛在客戶", "Reaching out to leads")
            "reach_lead" -> tr("打開一位潛在客戶的主頁", "Opening a lead's profile")
            "lead_liked" -> tr("給 TA 的作品點了讚", "Liked their video")
            "lead_commented" -> trf("給 TA 留言：“{}”", "Commented: \"{}\"", s("text"))
            "lead_followed" -> tr("關注了 TA", "Followed them")
            "done" -> tr("本次運行完成", "Run finished")
            "cap_reached" -> tr("達到本次上限，結束", "Reached this run's limit")
            "time_up" -> tr("到達本次運行時長，結束", "Run time is up")
            "not_logged_in" -> tr("App 未登錄，已停止", "The app isn't logged in — stopped")
            "verify_stop" -> tr("App 彈出驗證，已自動停止", "The app asked for verification — stopped")
            "stopped" -> tr("已停止", "Stopped")
            "stopped_other" -> tr("運行中斷", "Run interrupted")
            else -> null
        }
    }
}
