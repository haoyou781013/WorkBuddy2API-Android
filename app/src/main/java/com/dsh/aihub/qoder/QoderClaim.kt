package com.dsh.aihub.qoder

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Qoder 每日积分领取（签到）。
 *
 * 官方规则（docs.qoder.com/events/100credits）：
 *   · 每天 100 Credits，窗口从 UTC+8 10:00 开始，持续 24 小时
 *   · 必须手动领取，错过不补、不累积到次日
 *   · 每笔奖励 30 天有效期，各自独立
 *   · 仅个人账号参与（Teams / Enterprise 除外）
 *
 * 调用顺序（关键）：
 *
 *   ① POST /api/v1/jobToken/exchange   PAT → data.token
 *   ② GET  /sash/api/v1/me/campaigns?clientType=10   用 ① 的 token
 *   ③ POST /sash/api/v1/me/campaigns/{id}/claim      同上
 *
 * **sash 下的接口不接受 PAT**，必须先用 PAT 换一个短期会话 token。
 * 直接拿 PAT 当 Bearer 会得到 401，这是最初的错误。
 *
 * 目前只支持国内版（openapi.qoder.com.cn）：国际版没有这个活动接口，
 * 官方文档明确说国际版要在桌面 App 内手动领取。
 */
class QoderClaim(private val personalToken: String) {

    data class Campaign(
        val id: String,
        val title: String,
        val claimable: Boolean,
        /** CLAIM_BENEFIT / VIEW_DETAILS / …，决定这条活动能不能领。 */
        val actionType: String,
    )

    data class Result(
        val ok: Boolean,
        val message: String,
        val claimed: Int = 0,
        val credits: Int = 0,
        val campaigns: List<Campaign> = emptyList(),
    )

    /** 已换到的会话 token。一次流程内复用，避免每个请求都换。 */
    private var sessionToken: String? = null
    // 签到只走 openapi.qoder.com.cn。
    //
    // 之前写成 BASE_CN（gateway.qoder.com.cn），那是 COSY 签名链路用的域名，
    // 拿它去调 /api/v1/jobToken/exchange 会被阿里云 ALB 直接挡掉返回 503
    // HTML —— 因为它不是同一个服务，路径存在但后端不认。
    //
    // 社区实现（NoName4Me/mac-daily-credits:483）明确用的是
    // `https://openapi.qoder.com.cn`。
    private var baseActive: String = BASE_CN_OPENAPI

    /**
     * 拉取活动列表。
     *
     * 只试国内网关：国际版没有这个接口（实测三域返回 TOKEN_EXPIRE /
     * invalid apikey / token is not active），试了只会得到误导性的错误。
     */
    fun listCampaigns(): Result {
        val r = runCatching {
            ensureSession()
            fetchCampaigns()
        }
        return r.fold(
            onSuccess = { Result(true, "活动列表来自 ${host()} ", campaigns = it) },
            onFailure = { Result(false, it.message ?: "未知错误") },
        )
    }

    /** 领取所有可领的活动。 */
    fun claimAll(): Result {
        val listed = listCampaigns()
        if (!listed.ok) return listed

        // 只有 CLAIM_BENEFIT 类型的活动才真的能领。
        val claimable = listed.campaigns.filter { it.claimable && it.actionType == "CLAIM_BENEFIT" }
        if (claimable.isEmpty()) {
            return Result(
                true,
                if (listed.campaigns.isEmpty()) "当前没有可参与的活动"
                else "今天已领取过（共 ${listed.campaigns.size} 个活动，无可领项）",
                campaigns = listed.campaigns,
            )
        }

        var claimed = 0
        var credits = 0
        val errors = ArrayList<String>()
        for (c in claimable) {
            runCatching { claimOne(c.id) }
                .onSuccess { claimed++; credits += it }
                .onFailure { errors += "${c.title}: ${it.message}" }
        }

        return Result(
            ok = errors.isEmpty(),
            message = buildString {
                append("领取成功 $claimed/${claimable.size}")
                if (credits > 0) append("，到账 $credits 积分")
                if (errors.isNotEmpty()) append("；失败：${errors.joinToString("; ")}")
            },
            claimed = claimed,
            credits = credits,
            campaigns = listed.campaigns,
        )
    }

    // ------------------------------------------------------------------ 内部

    /** PAT → 会话 token。成功后缓存在 [sessionToken]。 */
    private fun ensureSession() {
        if (!sessionToken.isNullOrBlank()) return
        val urlStr = "$baseActive/api/v1/jobToken/exchange"
        val body = JSONObject().put("personal_token", personalToken).toString()

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            // 换取接口用 ClientType 5（CLI 身份），活动接口用 10（桌面端）。
            // 两者不同，混用会被网关拒绝。
            setRequestProperty("User-Agent", "qodercli/1.0.0")
            setRequestProperty("Cosy-Version", "1.0.0")
            setRequestProperty("Cosy-ClientType", "5")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("换取会话失败 HTTP $code: ${text.take(200)}")

            // 响应可能是明文 JSON，也可能是等位编码后的，两种都试。
            val json = runCatching { JSONObject(text) }.getOrElse {
                JSONObject(String(QoderCrypto.decode(text), Charsets.UTF_8))
            }
            val token = json.optJSONObject("data")?.optString("token")
                ?: json.optString("token")
            if (token.isNullOrBlank()) throw IOException("换取会话响应缺少 token")
            sessionToken = token
        } finally {
            conn.disconnect()
        }
    }

    /** 活动列表原始响应，供诊断确认字段名。 */
    var lastListRaw: String? = null
        private set

    private fun fetchCampaigns(): List<Campaign> {
        val text = request("/sash/api/v1/me/campaigns?clientType=10", "GET")
        lastListRaw = text
        val root = JSONObject(text)
        val arr = root.optJSONObject("data")?.optJSONArray("campaigns")
            ?: root.optJSONArray("campaigns")
            ?: return emptyList()

        val out = ArrayList<Campaign>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            // 不再在这里过滤 —— 过滤挪到调用方。
            //
            // 原因：活动列表里混着多种 actionType，例如
            //   CLAIM_BENEFIT  → 领积分
            //   VIEW_DETAILS   → 只是个展示型活动（实测 act-20260901-922）
            // 直接 continue 掉会让用户看到「已领/不可领」，误以为今天领过了。
            // 保留全部，由 UI 按类型显示不同状态。
            // 真实响应里没有 title / campaignName 字段（实测 2026-10-17）：
            // {"campaignId":"01a130eb-...","campaignKey":"act-20261013-462",
            //  "actionType":"CLAIM_BENEFIT","claimStatus":"CLAIMABLE",
            //  "benefit":{"kind":"CREDITS","amount":100,...}}
            //
            // 所以名字要自己拼：benefit 决定类型和数量，campaignKey 作为兜底。
            val benefit = c.optJSONObject("benefit")
            val kind = benefit?.optString("kind") ?: ""
            val amount = benefit?.optInt("amount", 0) ?: 0
            val name = when {
                kind == "CREDITS" && amount > 0 -> "$amount Credits"
                kind.isNotBlank() -> kind
                else -> c.optString("campaignKey", "活动")
            }
            out += Campaign(
                id = c.optString("campaignId"),
                title = name,
                claimable = c.optString("claimStatus") == "CLAIMABLE",
                actionType = c.optString("actionType"),
            )
        }
        return out
    }

    /**
     * 领取单个活动。
     *
     * 返回本活动到账的积分数；解析不出来返回 0。
     * 金额字段（benefit.amount）取自社区实现 qc.sh:508，
     * 但上游可能改字段名，所以多处尝试 + 失败不影响「已领取」的判定。
     */
    private fun claimOne(campaignId: String): Int {
        val text = request("/sash/api/v1/me/campaigns/$campaignId/claim", "POST")
        lastClaimRaw = text
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return 0
        // amount 的类型在各接口里不统一：列表里 benefit.amount 是整数
        // (100)，领取响应可能给字符串或浮点，所以用 optString 再转，最稳。
        fun pick(o: JSONObject?): Int? {
            val v = o?.opt("amount") ?: return null
            return v.toString().toDoubleOrNull()?.toInt()
        }
        val data = json.optJSONObject("data")
        return pick(data?.optJSONObject("benefit"))
            ?: pick(data)
            ?: pick(json.optJSONObject("benefit"))
            ?: pick(json)
            ?: 0
    }

    /** 最近一次领取的原始响应，供诊断显示。 */
    var lastClaimRaw: String? = null
        private set

    private fun request(path: String, method: String): String {
        val token = sessionToken ?: throw IOException("会话未建立")
        val conn = (URL(baseActive + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 30_000
            if (method == "POST") {
                doOutput = true
                setRequestProperty("Content-Length", "0")
            }
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Cosy-ClientType", "10")
            // 版本号和平台是硬性要求（CreditDaddy constants.js:80）。
            // 之前只带 ClientType，缺 Cosy-Version / Cosy-MachineOS，
            // 上游会直接拒绝。
            setRequestProperty("Cosy-Version", "0.3.3")
            setRequestProperty("Cosy-MachineOS", "android")
            // 活动/配额接口用 Qoder，换取接口用 qodercli —— 两者不同。
            setRequestProperty("User-Agent", "Qoder")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (method == "POST") conn.outputStream.use { it.write(ByteArray(0)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code: ${text.take(300)}")
            text
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 查询当前 Credits 用量与余额。
     *
     * 接口来自 CreditDaddy src/constants.js：
     *   QUOTA_USAGE_PATH = "/api/v2/quota/usage"
     *   USERINFO_PATH    = "/api/v1/userinfo"
     *
     * 之前我猜过 /api/v1/me/credits、/me/quota、/me/usage、/user/status，
     * 全是 404 —— 真实路径是 v2 且叫 quota/usage，不在 me 命名空间下。
     *
     * **请求头有硬性要求**（constants.js:80 buildQoderHeaders）：
     *   Cosy-ClientType: 10
     *   Cosy-Version:    0.3.3     ← 版本号不对会被拒
     *   Cosy-MachineOS:  windows / macos / linux
     *   User-Agent:      Qoder      ← 不是 qodercli，活动接口才用 qodercli
     *   Accept:          application/json
     */
    fun queryBalance(): Result {
        val r = runCatching {
            ensureSession()
            request(QUOTA_USAGE_PATH, "GET")
        }
        return r.fold(
            onSuccess = { text ->
                lastBalanceRaw = text
                val summary = summarizeQuota(text)
                if (summary != null) Result(true, summary)
                else Result(true, "接口已通，但未识别出额度字段。原始响应：${text.take(400)}")
            },
            onFailure = { Result(false, "余额查询失败：${it.message}") },
        )
    }

    /** 余额原始响应，供诊断。 */
    var lastBalanceRaw: String? = null
        private set

    /**
     * 把 quota/usage 响应整理成一行可读文本。
     *
     * 响应结构（2026-10-17 实测）：
     * {
     *   "userId": "01a14973-...",
     *   "userType": "personal_professional_trial",
     *   "usageType": "credits",
     *   "totalUsagePercentage": 0.0,
     *   "isQuotaExceeded": false,
     *   "expiresAt": 1793443277298,
     *   "upgradeUrl": "https://qoder.com.cn/pricing?client=qoder",
     *   "userQuota": { "total": 300.0, "used": <n> }
     * }
     *
     * **total / used 在 userQuota 里面，不在顶层。** 上一版直接扫顶层
     * 找 total 撞上了别的 0 值字段，所以显示成「总量 0」。
     * 现在优先读 userQuota，读不到才退回顶层搜索。
     */
    private fun summarizeQuota(text: String): String? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null

        val q = json.optJSONObject("userQuota")
        val total = q?.optDouble("total", Double.NaN)?.takeIf { !it.isNaN() }
            ?: json.optJSONObject("data")?.optJSONObject("userQuota")?.optDouble("total", Double.NaN)?.takeIf { !it.isNaN() }
        val used = q?.optDouble("used", Double.NaN)?.takeIf { !it.isNaN() }
            ?: json.optJSONObject("data")?.optJSONObject("userQuota")?.optDouble("used", Double.NaN)?.takeIf { !it.isNaN() }

        if (total == null && used == null) {
            return null
        }

        val remain = if (total != null && used != null) total - used else null
        val sb = StringBuilder()
        remain?.let { sb.append("剩余 ${fmt(it)}") }
        used?.let { if (sb.isNotEmpty()) sb.append("  ·  "); sb.append("已用 ${fmt(it)}") }
        total?.let { if (sb.isNotEmpty()) sb.append("  ·  "); sb.append("总量 ${fmt(it)}") }

        // 套餐类型和过期时间对使用者有实际意义，一并显示。
        json.optString("userType").takeIf { it.isNotBlank() }?.let {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("套餐：$it")
        }
        val exp = json.optLong("expiresAt", 0L)
        if (exp > 0) {
            val d = java.util.Date(exp)
            val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
            if (sb.isNotEmpty()) sb.append("  ·  ")
            sb.append("到期 ${f.format(d)}")
        }
        if (json.optBoolean("isQuotaExceeded", false)) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("⚠️ 额度已用尽")
        }

        return sb.toString().ifBlank { null }
    }

    private fun fmt(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else String.format("%.1f", d)

    private fun host(): String = baseActive.removePrefix("https://").substringBefore('/')

    private companion object {
        /**
         * 配额查询路径。来自 CreditDaddy src/constants.js:29
         * `QUOTA_USAGE_PATH = '/api/v2/quota/usage'`。
         *
         * 注意是 v2，而且不在 me 命名空间下 —— 这是之前连试五个
         * me 命名空间全部 404 的原因。
         */
        const val QUOTA_USAGE_PATH = "/api/v2/quota/usage"
    }
}
