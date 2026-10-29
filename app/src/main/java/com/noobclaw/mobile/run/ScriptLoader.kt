package com.noobclaw.mobile.run

import com.noobclaw.mobile.BuildConfig
import com.noobclaw.mobile.engine.ScriptPack
import com.noobclaw.mobile.engine.ScriptVerifier
import com.noobclaw.mobile.net.Api
import com.noobclaw.mobile.util.RunLog

/** Downloads a script pack for the installed target-app version and refuses anything unsigned. */
object ScriptLoader {

    class Rejected(val reason: String) : Exception(reason)

    suspend fun load(scriptId: String, targetAppVersionCode: Long): ScriptPack {
        val r = Api.fetchScript(scriptId, targetAppVersionCode, BuildConfig.CAP_VER)
        if (!r.optBoolean("ok", false)) throw Rejected(r.optString("reason", "script_unavailable"))
        val version = r.optString("version")
        val code = r.optString("code")
        val signature = r.optString("signature")
        val manifest = r.optJSONObject("manifest") ?: org.json.JSONObject()
        if (code.isEmpty() || version.isEmpty()) throw Rejected("script_empty")

        val pub = BuildConfig.SCRIPT_PUBKEY_HEX
        if (pub.isEmpty()) {
            // Only debug builds may run without a pinned key, for local development against staging.
            if (!BuildConfig.DEBUG) throw Rejected("no_pinned_key")
            RunLog.w("開發版：未配置劇本公鑰，跳過簽名校驗（正式版會拒絕運行）")
        } else if (!ScriptVerifier.verify(pub, scriptId, version, code, signature)) {
            throw Rejected("bad_signature")
        }
        if (manifest.optInt("capability_api_min", 1) > BuildConfig.CAP_VER) throw Rejected("app_update_required")
        return ScriptPack(scriptId, version, code, manifest)
    }
}
