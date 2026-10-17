package com.dsh.aihub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 软件更新检查。
 *
 * 为什么走 GitHub Releases 而不是自建服务端：这个项目没有服务端，Release 页面
 * 本身就是版本清单的事实来源——CI 每次打 tag 就把 APK 挂上去，客户端只管读。
 *
 * 为什么用 HttpURLConnection 而不是 OkHttp：OkHttpClient 实例属于 NativeCore，
 * 那里带着上游 API 需要的众多 header 与超时配置；更新检查是独立的外部请求，
 * 复用那套配置既耦合又容易被无关改动影响。这里一个简单连接足够。
 *
 * 为什么解析 JSON 而不依赖 GitHub 的 /releases/latest API 做版本比较：
 * 该 API 的 "latest" 判定基于发布时间而非语义化版本号，回滚或重发时不可靠。
 * 这里自己取 tag 列表按 SemVer 比较。
 */
object UpdateChecker {

    /** 仓库坐标。改动发行位置只需改这两行。 */
    private const val OWNER = "haoyou781013"
    private const val REPO = "WorkBuddy2API-Android"

    private const val API_LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** 网络超时。手机上弱网常见，给足但不过分。 */
    private const val TIMEOUT_MS = 15_000

    /**
     * 一次更新检查的结果。
     *
     * @param newer       远端是否存在更新版本
     * @param latestName  远端版本号（已去掉前缀 v）
     * @param latestTag   远端 tag 原文，用于拼下载地址
     * @param releaseUrl  发布页地址，供"查看详情"跳转
     * @param notes       发布说明（Release body 原文）
     * @param apkUrl      发布页附带的 APK 下载地址；未找到为 null
     * @param apkSize     APK 字节数，用于显示与校验；未找到为 0
     */
    data class Result(
        val newer: Boolean,
        val latestName: String,
        val latestTag: String,
        val releaseUrl: String,
        val notes: String,
        val apkUrl: String?,
        val apkSize: Long,
    )

    /** 检查过程中的失败原因，直接可展示给用户。 */
    class CheckError(message: String) : Exception(message)

    /**
     * 当前 App 的版本号。
     *
     * 用 versionName 而不是 versionCode 比较：versionCode 在用户侧不可见，
     * 且本项目按 SemVer 发版，versionName 才是对外承诺的语义。
     */
    fun currentVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    }.getOrDefault("0.0.0")

    /**
     * 检查是否有新版本。阻塞调用，必须在后台线程执行。
     *
     * @throws CheckError 网络失败、响应异常或解析失败
     */
    fun check(context: Context, includePrerelease: Boolean = false): Result {
        val raw = httpGet(API_LATEST)
        val obj = runCatching { JSONObject(raw) }
            .getOrElse { throw CheckError("版本信息解析失败") }

        // GitHub 在无 Release 时返回 message 字段而不是 tag_name
        if (!obj.has("tag_name")) {
            throw CheckError(obj.optString("message").ifBlank { "仓库暂未发布版本" })
        }

        val tag = obj.optString("tag_name")
        val name = obj.optString("name").ifBlank { tag }
        val htmlUrl = obj.optString("html_url")
        val body = obj.optString("body")
        val prerelease = obj.optBoolean("prerelease", false)

        // 预发布版本默认不提示：用户装的是稳定版时，不该被 alpha/beta 打扰。
        val current = currentVersion(context)
        val newer = compareSemver(tag, current) > 0 && (includePrerelease || !prerelease)

        // 挑 APK 附件。优先 release 包，排除 debug 包与校验文件。
        var apkUrl: String? = null
        var apkSize = 0L
        val assets = obj.optJSONArray("assets")
        if (assets != null) {
            var fallbackUrl: String? = null
            var fallbackSize = 0L
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val assetName = a.optString("name")
                if (!assetName.endsWith(".apk", ignoreCase = true)) continue
                val url = a.optString("browser_download_url")
                val size = a.optLong("size", 0L)
                if (assetName.contains("debug", ignoreCase = true)) {
                    if (fallbackUrl == null) { fallbackUrl = url; fallbackSize = size }
                } else {
                    apkUrl = url
                    apkSize = size
                    break
                }
            }
            if (apkUrl == null) { apkUrl = fallbackUrl; apkSize = fallbackSize }
        }

        return Result(
            newer = newer,
            latestName = name,
            latestTag = tag,
            releaseUrl = htmlUrl,
            notes = body,
            apkUrl = apkUrl,
            apkSize = apkSize,
        )
    }

    /**
     * 下载 APK 到应用外部文件目录。
     *
     * 为什么存 external-files-dir 而不是 cache：安装器需要读这个文件，
     * cache 目录可能被系统随时清掉，下载完成到用户点安装之间的空档会出错。
     *
     * @param onProgress 已下载字节数回调，在后台线程执行
     */
    fun download(
        context: Context,
        url: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        val dir = context.getExternalFilesDir("updates") ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        // 清掉旧包，避免多版本堆积占空间
        runCatching { dir.listFiles()?.forEach { it.delete() } }

        val target = File(dir, "update.apk")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            // GitHub 的下载地址会 302 到 objects.githubusercontent.com
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw CheckError("下载失败：HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: 0L
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                    output.flush()
                }
            }
            if (target.length() <= 0L) throw CheckError("下载内容为空")
            return target
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 触发 APK 安装。
     *
     * 为什么用 FileProvider：Android 7 起不能把 file:// 直接交给安装器，
     * 必须走 content:// 授权。authority 由 manifest 的 provider 声明决定。
     *
     * 为什么先检查「安装未知应用」权限：Android 8 起这是按应用授予的，
     * 没有它 startActivity 会静默失败，用户只会看到"点了没反应"。
     */
    fun install(context: Context, apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            // 跳转到本应用的「安装未知应用」授权页，用户授权后需重新点安装
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            throw CheckError("请先允许本应用安装未知应用，然后重新点击安装")
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.getOrElse { throw CheckError("无法启动安装器：${it.message}") }
    }

    // ---------------------------------------------------------------- 内部实现

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub API 要求 UA，否则部分网络环境返回 403
            setRequestProperty("User-Agent", "AIHub-Updater")
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw CheckError("检查更新失败：HTTP ${conn.responseCode}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: CheckError) {
            throw e
        } catch (e: Exception) {
            throw CheckError("网络错误：${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 语义化版本比较。返回 >0 表示 a 比 b 新。
     *
     * 支持 "1.2.3"、"v1.2.3"、"1.2.3-beta.1" 三种形态。
     * 预发布段（-之后）按 SemVer 规则视为低于同号正式版。
     */
    internal fun compareSemver(a: String, b: String): Int {
        fun split(v: String): Pair<List<Int>, String> {
            val s = v.trim().removePrefix("v").removePrefix("V")
            val dash = s.indexOf('-')
            val core = if (dash >= 0) s.substring(0, dash) else s
            val pre = if (dash >= 0) s.substring(dash + 1) else ""
            val nums = core.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            return nums to pre
        }
        val (na, preA) = split(a)
        val (nb, preB) = split(b)
        for (i in 0 until maxOf(na.size, nb.size)) {
            val x = na.getOrElse(i) { 0 }
            val y = nb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        // 主版本相同：有预发布段的更低
        return when {
            preA.isEmpty() && preB.isEmpty() -> 0
            preA.isEmpty() -> 1
            preB.isEmpty() -> -1
            else -> preA.compareTo(preB)
        }
    }
}
