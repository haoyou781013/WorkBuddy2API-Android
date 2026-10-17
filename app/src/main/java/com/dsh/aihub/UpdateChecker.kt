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
        "https://ghfast.top/",                     // 公共镜像（国内可达性较好）
        "https://gh-proxy.com/",
        "",                                        // 直连 GitHub，放最后
    )

    /**
     * APK 的备用下载地址，按优先级排列。
     *
     * 顺序理由：
     *   ① Gitee 直发   —— 国内最容易连上，且是我们自己控制的仓库，
     *                     可用性最稳定。release 资产同名可直接替换。
     *   ② GitHub 镜像  —— Gitee 没有对应资产时（比如只发了 GitHub Release），
     *                     走公共镜像，仍然避开直连。
     *   ③ GitHub 直连  —— 兜底，国内经常失败但聊胜于无。
     *
     * 注意：Gitee 仓库只镜像源码，release 资产需要单独上传。
     * 若 Gitee 上找不到同名 APK，会返回 404，随即落到 ②。
     */
    private fun sources(url: String, version: String): List<String> {
        val out = ArrayList<String>(4)
        // ① Gitee 直发。拼出来的地址若 404，循环会自然落到镜像。
        out.add(giteeApkUrl(version))
        // ② 镜像
        MIRRORS.forEach { prefix ->
            mirrorUrl(prefix, url)?.let { out.add(it) }
        }
        return out.distinct()
    }

    /**
     * 按 tag 推导 Gitee 上的 APK 地址。
     *
     * Gitee 的 release 资产路径与 GitHub 不同，且没有 API 可直接列出时，
     * 用约定文件名 `AIHub-<version>-release.apk` 直接拼。
     * 下载失败会自动落到下一源，所以拼错不会造成死路。
     */
    private fun giteeApkUrl(version: String): String =
        "https://gitee.com/haoyou781/aihub/releases/download/v$version/AIHub-$version-release.apk"

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

    /**
     * 一次扫描的结果。
     *
     * @param all     全部版本，按版本号降序
     * @param latest  最新**正式版**；没有正式版时退化为最新预发布
     *
     * 为什么单独留 latest：界面上「当前版本 / 最新版本」的对比要用稳定版口径，
     * 而测试通道的列表又需要看到预发布。两者口径不同，不能混用一个字段。
     */
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

        /**
         * 按通道取候选列表，已按版本号降序。
         *
         * 正式通道只返回非预发布版本：稳定版用户不该被 alpha/beta 打扰。
         * 测试通道返回全部（含预发布），供主动尝鲜的用户使用。
         */
        fun channel(includePrerelease: Boolean): List<Release> =
            if (includePrerelease) all else all.filter { !it.prerelease }

        /** 该通道下比 [current] 新的首个版本。 */
        fun newerIn(current: String, includePrerelease: Boolean): Release? =
            channel(includePrerelease).firstOrNull { compareSemver(it.tag, current) > 0 }
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
        // 按优先级依次尝试：Gitee → GitHub 镜像 → GitHub 直连
        val candidates = sources(release.apkUrl, release.version)
        if (candidates.isEmpty()) throw CheckError("没有可用的下载地址")
        for (url in candidates) {
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

    /**
     * 触发安装。
     *
     * 两个前置条件缺一不可：
     *   ① Manifest 声明了 `REQUEST_INSTALL_PACKAGES` —— 少了它，系统根本不认
     *      这个应用有安装意图，`ACTION_MANAGE_UNKNOWN_APP_SOURCES` 也打不开对应页面
     *   ② 用户已在系统设置里为「安装未知应用」放行
     *
     * 都不满足时**不能静默返回**：Android 8+ 会吞掉失败的安装 Intent，
     * 用户看到的是"点了没反应"。所以这里必须主动跳设置页 + 抛出可读错误。
     */
    fun install(context: Context, apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val opened = openUnknownSourcesSetting(context)
            throw CheckError(
                if (opened) "已跳到系统设置。请打开「允许安装未知应用」，返回后重新点安装"
                else "请在 系统设置 → 应用 → AIHub → 安装未知应用 中允许，然后重试"
            )
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

    /**
     * 跳到「安装未知应用」授权页。
     *
     * 优先带 package: 直达本应用（部分 ROM 支持），
     * 失败则退到列表页——不带参数在多数 ROM 上仍可进入应用选择列表。
     * 返回是否成功拉起任一页面，供调用方决定错误文案。
     */
    private fun openUnknownSourcesSetting(context: Context): Boolean {
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
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
