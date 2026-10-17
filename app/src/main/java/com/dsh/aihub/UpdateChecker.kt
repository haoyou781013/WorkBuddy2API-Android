package com.dsh.aihub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 软件更新检查（沿用 HY_VQ 的实现思路）。
 *
 * 与常见做法不同的三处，都是踩过坑之后的结论：
 *
 * ① 用 `/releases?per_page=100` 而不是 `/releases/latest`。
 *    `latest` 由 GitHub 按**发布时间**挑选，不是按版本号；重发、回滚或补丁版本
 *    晚于大版本发布时，`latest` 会指错。取全量列表自己按 SemVer 排序才可靠。
 *    附带好处：用户可以在历史版本间自由重装，不必先卸载。
 *
 * ② 下载直链取自每个 Release 自带的 `browser_download_url`，
 *    不自己拼 `releases/download/<tag>/<文件名>`。文件名改过之后拼出来的地址
 *    会 404，而 API 返回的是精确地址。
 *
 * ③ 直链失败自动回退镜像。GitHub 的资产实际托管在
 *    `objects.githubusercontent.com`，国内网络经常连不上。镜像只对
 *    `github.com` / `objects.githubusercontent.com` 的地址生效，
 *    不给任意 URL 加前缀（否则等于把可控地址交给第三方代理）。
 */
object UpdateChecker {

    private const val OWNER = "haoyou781013"
    private const val REPO = "WorkBuddy2API-Android"

    /** 一次拉全量版本列表。100 是 GitHub 单页上限。 */
    private const val API_RELEASES = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=100"

    /**
     * 下载镜像前缀。按顺序尝试，全部失败才报错。
     *
     * 只用于 GitHub 资产地址，见 [mirrorUrl] 的前缀校验。
     */
    private val MIRRORS = listOf(
        "",                                        // 直连
        "https://ghfast.top/",                     // 常用公共镜像
        "https://gh-proxy.com/",
    )

    private const val TIMEOUT_MS = 15_000


    /** 单个可用版本。 */
    data class Release(
        val tag: String,
        val name: String,
        val version: String,
        val notes: String,
        val pageUrl: String,
        val apkUrl: String,
        val apkSize: Long,
        val prerelease: Boolean,
        val publishedAt: String,
    )

    /** 一次扫描的结果：全部版本（已按版本号降序）+ 最新正式版下标。 */
    data class Scan(
        val all: List<Release>,
        val latest: Release?,
    ) {
        /** 首个比 [current] 新的版本；没有则 null。 */
        fun newerThan(current: String): Release? =
            all.firstOrNull { compareSemver(it.tag, current) > 0 }

        /** 是否有比 [current] 新的正式版（预发布不算）。 */
        fun hasStableNewer(current: String): Boolean =
            all.any { !it.prerelease && compareSemver(it.tag, current) > 0 }
    }

    class CheckError(message: String) : Exception(message)

    fun currentVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    }.getOrDefault("0.0.0")

    fun currentVersionCode(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)

    /**
     * 扫描全部可用版本。阻塞调用，需在后台线程执行。
     */
    fun scan(context: Context): Scan {
        val raw = httpGet(API_RELEASES)
        val arr = runCatching { JSONArray(raw) }.getOrElse {
            // GitHub 在出错时返回对象而不是数组（如 rate limit）
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            throw CheckError(obj?.optString("message")?.ifBlank { null } ?: "版本列表解析失败")
        }

        val list = ArrayList<Release>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val tag = o.optString("tag_name")
            if (tag.isBlank()) continue

            // 挑 APK：优先 release 包，排除 debug
            var apkUrl: String? = null
            var apkSize = 0L
            var debugUrl: String? = null
            var debugSize = 0L
            val assets = o.optJSONArray("assets")
            if (assets != null) {
                for (j in 0 until assets.length()) {
                    val a = assets.optJSONObject(j) ?: continue
                    val n = a.optString("name")
                    if (!n.endsWith(".apk", ignoreCase = true)) continue
                    val u = a.optString("browser_download_url")
                    val s = a.optLong("size", 0L)
                    if (n.contains("debug", ignoreCase = true)) {
                        if (debugUrl == null) { debugUrl = u; debugSize = s }
                    } else if (apkUrl == null) { apkUrl = u; apkSize = s }
                }
            }
            if (apkUrl == null) { apkUrl = debugUrl; apkSize = debugSize }
            if (apkUrl == null) continue      // 没有 APK 附件的 Release 不参与更新

            // 跳过改名前的旧包。
            //
            // 本项目在 v1.1.0 把 applicationId 从 com.joy4fire.workbuddy2api
            // 改成 com.dsh.aihub，两者互不兼容（装不上、数据不通）。但旧版本的
            // 版本号更高（v1.3.4-m3），单纯按 SemVer 排序会把它们排在前面，
            // 于是更新页会推荐用户装一个根本装不上的包。
            //
            // 判断依据**只看 APK 文件名**，不看版本号。
            //
            // 为什么不按 tag 卡：v1.0.0 是改名后正常发布的版本，包名已是
            // com.dsh.aihub，必须允许用户回退到它。按版本号设下限会把它一起误杀。
            // 文件名前缀才是可靠的判别依据——改名时同步改了资产命名规则。
            val assetName = apkUrl!!.substringAfterLast('/')
            if (assetName.startsWith("WorkBuddy2API", ignoreCase = true)) continue

            list.add(
                Release(
                    tag = tag,
                    name = o.optString("name").ifBlank { tag },
                    version = tag.removePrefix("v").removePrefix("V"),
                    notes = o.optString("body"),
                    pageUrl = o.optString("html_url"),
                    apkUrl = apkUrl,
                    apkSize = apkSize,
                    prerelease = o.optBoolean("prerelease", false),
                    publishedAt = o.optString("published_at"),
                )
            )
        }

        list.sortWith { a, b -> compareSemver(b.tag, a.tag) }
        val latest = list.firstOrNull { !it.prerelease } ?: list.firstOrNull()
        return Scan(list, latest)
    }

    /**
     * 下载 APK。按 [MIRRORS] 顺序尝试，任一成功即返回。
     *
     * 存到 external-files-dir 而不是 cache：cache 随时可能被系统清掉，
     * 而"下载完成"到"用户点安装"之间可能隔很久（甚至跨进程重启）。
     */
    fun download(
        context: Context,
        release: Release,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        val dir = context.getExternalFilesDir("updates") ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, "update-${release.version}.apk")

        var lastErr: String? = null
        for (prefix in MIRRORS) {
            val url = mirrorUrl(prefix, release.apkUrl) ?: continue
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/octet-stream")
                    setRequestProperty("User-Agent", "AIHub-Updater")
                }
                try {
                    if (conn.responseCode !in 200..299) {
                        lastErr = "HTTP ${conn.responseCode}"
                        continue
                    }
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
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
                    // 完整性兜底：长度对不上说明中途断了，删掉重试下一个源
                    if (target.length() <= 0L) { lastErr = "下载内容为空"; continue }
                    if (release.apkSize > 0 && target.length() != release.apkSize) {
                        lastErr = "大小不符（期望 ${release.apkSize}，实际 ${target.length()}）"
                        target.delete()
                        continue
                    }
                    return target
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
                runCatching { target.delete() }
            }
        }
        throw CheckError("下载失败：${lastErr ?: "所有下载源均不可用"}")
    }

    /**
     * 给 GitHub 资产地址套镜像前缀。
     *
     * 为什么校验来源：镜像本质是第三方代理。若不加限制，任何 URL 都会被
     * 转发给镜像方，等于把可控的下载地址交给外部。这里只允许 GitHub 域名。
     */
    internal fun mirrorUrl(prefix: String, url: String): String? {
        if (url.isBlank()) return null
        if (prefix.isEmpty()) return url
        val ok = url.startsWith("https://github.com/") ||
            url.startsWith("https://objects.githubusercontent.com/")
        return if (ok) prefix + url else null
    }

    /** 触发安装。前置检查「安装未知应用」权限，否则 Android 8+ 静默失败。 */
    fun install(context: Context, apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
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

    /** 找已下载但未安装的包（重进页面时复用，不必重下）。 */
    fun cachedApks(context: Context): List<File> {
        val dir = context.getExternalFilesDir("updates") ?: context.filesDir
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    // ---------------------------------------------------------------- 内部

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AIHub-Updater")
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw CheckError("获取版本列表失败：HTTP ${conn.responseCode}")
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
     * 语义化版本比较，>0 表示 a 比 b 新。
     * 支持 "1.2.3" / "v1.2.3" / "1.2.3-beta.1"；预发布段低于同号正式版。
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
        return when {
            preA.isEmpty() && preB.isEmpty() -> 0
            preA.isEmpty() -> 1
            preB.isEmpty() -> -1
            else -> preA.compareTo(preB)
        }
    }
}
