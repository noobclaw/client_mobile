package com.noobclaw.mobile.util

import java.util.Locale

/**
 * App languages: English, 简体中文, 繁體中文. Follows the system language unless the user picked one in 我的 → 語言.
 * Strings are written once in Traditional Chinese + English: `tr("繁體", "English")`; Simplified is converted from
 * the Traditional text at runtime with [T2S] (covers every character the app uses).
 */
object I18n {
    enum class Lang(val code: String, val label: String) {
        EN("en", "English"), HANS("zh-Hans", "简体中文"), HANT("zh-Hant", "繁體中文");
        companion object { fun of(code: String) = values().firstOrNull { it.code == code } }
    }

    /** The language in effect right now. */
    val lang: Lang
        get() = Lang.of(Prefs.lang) ?: system()

    fun system(): Lang {
        val l = Locale.getDefault()
        if (l.language != "zh") return Lang.EN
        val hant = l.script == "Hant" || (l.script.isEmpty() && l.country in setOf("TW", "HK", "MO"))
        return if (hant) Lang.HANT else Lang.HANS
    }

    fun tr(zhHant: String, en: String): String = when (lang) {
        Lang.EN -> en
        Lang.HANT -> zhHant
        Lang.HANS -> toSimp(zhHant)
    }

    private val T2S: Map<Char, Char> by lazy {
        val pairs = "個个內内刪删劇剧動动務务區区執执帳账戶户後后敗败數数時时暫暂會会東东機机權权沒没無无獲获現现" +
            "發发線线編编號号裝装裡里記记設设評评詞词試试誌志請请論论讚赞載载輯辑這这運运選选還还錄录鍵键鏈链" +
            "鐘钟關关離离點点卻却雲云確确認认臺台員员級级開开啟启懸悬窗窗臺台營营條条說说讀读驗验擇择錯错" +
            "診诊斷断傳传誤误繫系聯联復复談谈換换語语言言簡简體体繁繁單单項项應应從从來来傳传頁页書书紅红" +
            "與与產产處处據据話话視视頻频廣广為为於于並并將将專专長长門门問问題题對对導导覽览靜静態态" +
            "標标簽签續续總总積积額额費费過过期期碼码優优勢势寫写當当準准備备畫画週周鬆松網网絡络實实" +
            "際际計计劃划樣样種种類类觸触擊击滑滑滾滚擋挡異异常常狀状況况" +
            "買买賣卖價价錢钱幣币臉脸邊边顯显範范圍围歷历響响觀观壓压擴扩縮缩齊齐儘尽軟软體体隱隐私私" +
            "團团隊队幫帮夠够佈布礎础環环峰峰鑰钥謝谢鬧闹騰腾驟骤" +
            "別别檢检滿满潰溃礙碍結结絕绝給给統统緒绪緩缓腦脑艦舰證证識识進进郵邮閉闭階阶隨随險险電电風风"
        val m = HashMap<Char, Char>()
        var i = 0
        while (i + 1 < pairs.length) { m[pairs[i]] = pairs[i + 1]; i += 2 }
        m
    }

    fun toSimp(s: String): String {
        val b = StringBuilder(s.length)
        // 覆 is the same in both scripts (覆蓋 → 覆盖) except in 回覆 (→ 回复)
        for (c in s.replace("回覆", "回复")) b.append(T2S[c] ?: c)
        return b.toString()
    }
}

/** Shorthand used across the app. */
fun tr(zhHant: String, en: String) = I18n.tr(zhHant, en)
