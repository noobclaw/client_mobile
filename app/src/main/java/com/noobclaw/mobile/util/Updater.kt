package com.noobclaw.mobile.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.noobclaw.mobile.BuildConfig
import com.noobclaw.mobile.net.Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * In-app forced update (2026-11-01).
 * The backend's GET /api/mobile-app/latest returns the release the admin marked as latest
 * (「安裝包管理 → 手機App」). When its version is newer than this build, the app must update:
 * download the APK into the cache and hand it to the system installer (the user still confirms once,
 * Android requires that for apps outside a store).
 */
object Updater {
    data class Release(val version: String, val url: String, val notes: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true)
        .build()

    /** -1 / 0 / 1 like compareTo; non-numeric parts count as 0. */
    fun compare(a: String, b: String): Int {
        val x = a.trim().split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val y = b.trim().split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** The newer release, or null (up to date / no release / network error). */
    suspend fun check(): Release? = runCatching {
        val r = Api.get("/api/mobile-app/latest")
        val v = r.optString("version").trim()
        val url = r.optString("url").trim()
        if (v.isEmpty() || !url.startsWith("https://", true)) null
        else if (compare(v, BuildConfig.VERSION_NAME) > 0) Release(v, url, r.optString("notes")) else null
    }.getOrNull()

    private fun dir(ctx: Context) = File(ctx.cacheDir, "updates").apply { mkdirs() }

    /** Downloads the APK; [progress] gets 0..100 (or -1 when the size is unknown). Throws on failure. */
    suspend fun download(ctx: Context, rel: Release, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val d = dir(ctx)
        d.listFiles()?.forEach { it.delete() } // old downloads
        val out = File(d, "noobclaw-${rel.version.filter { it.isDigit() || it == '.' }}.apk")
        val tmp = File(d, out.name + ".part")
        http.newCall(Request.Builder().url(rel.url).build()).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
            val body = res.body ?: throw IllegalStateException("empty body")
            val total = body.contentLength()
            var done = 0L
            var last = -2
            body.byteStream().use { inp ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        val p = if (total > 0) (done * 100 / total).toInt() else -1
                        if (p != last) { last = p; withContext(Dispatchers.Main) { progress(p) } }
                    }
                }
            }
            if (total > 0 && done != total) throw IllegalStateException("incomplete download")
        }
        if (tmp.length() < 100_000) throw IllegalStateException("file too small")
        if (!tmp.renameTo(out)) throw IllegalStateException("rename failed")
        out
    }

    /** Android 8+: the user has to allow "install unknown apps" for us once. */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Opens the system installer for [apk]. */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
