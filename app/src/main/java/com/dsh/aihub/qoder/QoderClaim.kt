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
 *   · **必须手动领取**，错过不补、不累积到次日
 *   · 每笔奖励有效期 30 天，各自独立计算
 *   · 仅适用于个人账号（Teams / Enterprise 不参与）
 *
 * 接口来自社区实现（NoName4Me/mac-daily-credits）：
 *
 *   GET  /sash/api/v1/me/campaigns?clientType=10     查活动列表
 *   POST /sash/api/v1/me/campaigns/{id}/claim        领取
 *
 * 筛选条件是 actionType == "CLAIM_BENEFIT" 且 claimStatus == "CLAIMABLE"。
 *
 * 重要：国际版与国内版域名不同。国内版是 openapi.qoder.com.cn，
 * 国际版在 qoder.sh / qoder.com 域。这里两个都试，以实际能返回
 * 活动列表的为准 —— 硬编码单一域名正是之前 401 的原因。
 */
class QoderClaim(private val personalToken: String) {

    data class Campaign(
        val id: String,
        val title: String,
        val claimable: Boolean,
    )

    data class Result(
        val ok: Boolean,
        val message: String,
        val claimed: Int = 0,
        val campaigns: List<Campaign> = emptyList(),
    )

    /**
     * 依次尝试所有已知区域，返回第一个能拿到活动列表的结果。
     *
     * 为什么逐个试而不是让用户选：用户不关心域名，他只关心能不能领。
     * 而且账号属于哪个区是既成事实，探测比询问更可靠。
     */
    fun listCampaigns(): Result {
        var lastError = ""
        for (base in BASES) {
            val r = runCatching { fetchCampaigns(base) }
            r.onSuccess { return Result(true, "活动列表来自 $base", campaigns = it) }
            lastError = r.exceptionOrNull()?.message ?: "未知错误"
        }
        return Result(false, "所有区域都无法获取活动列表：$lastError")
    }

    /** 领取所有可领的活动。 */
    fun claimAll(): Result {
        val listed = listCampaigns()
        if (!listed.ok) return listed

        val claimable = listed.campaigns.filter { it.claimable }
        if (claimable.isEmpty()) {
            return Result(
                true,
                if (listed.campaigns.isEmpty()) "当前没有活动"
                else "今天已领取过（共 ${listed.campaigns.size} 个活动，无可领项）",
                campaigns = listed.campaigns,
            )
        }

        var claimed = 0
        val errors = ArrayList<String>()
        for (c in claimable) {
            val r = runCatching { claimOne(c.id) }
            if (r.isSuccess) claimed++ else errors += "${c.title}: ${r.exceptionOrNull()?.message}"
        }

        return Result(
            ok = errors.isEmpty(),
            message = buildString {
                append("领取成功 $claimed/${claimable.size}")
                if (errors.isNotEmpty()) append("；失败：${errors.joinToString("; ")}")
            },
            claimed = claimed,
            campaigns = listed.campaigns,
        )
    }

    // ------------------------------------------------------------------ 内部

    private fun fetchCampaigns(base: String): List<Campaign> {
        val text = request("$base/sash/api/v1/me/campaigns?clientType=10", "GET")
        val root = JSONObject(text)
        // 活动列表可能在 data.campaigns 或顶层 campaigns，两种都认。
        val arr = root.optJSONObject("data")?.optJSONArray("campaigns")
            ?: root.optJSONArray("campaigns")
            ?: return emptyList()

        val out = ArrayList<Campaign>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            if (c.optString("actionType") != "CLAIM_BENEFIT") continue
            out += Campaign(
                id = c.optString("campaignId"),
                title = c.optString("title", c.optString("campaignName", "活动")),
                claimable = c.optString("claimStatus") == "CLAIMABLE",
            )
        }
        return out
    }

    private fun claimOne(campaignId: String): String =
        request("$BASE_ACTIVE/sash/api/v1/me/campaigns/$campaignId/claim", "POST")

    /** 记住上次成功的域名，避免每次都从头探测。 */
    private var baseActive: String? = null
    private val BASE_ACTIVE: String
        get() = baseActive ?: BASES.first()

    private fun request(urlStr: String, method: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 30_000
            if (method == "POST") {
                doOutput = true
                setRequestProperty("Content-Length", "0")
            }
            setRequestProperty("Authorization", "Bearer $personalToken")
            // Cosy-ClientType 是官方客户端标识，10 = 桌面端。缺了会被拒。
            setRequestProperty("Cosy-ClientType", "10")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (method == "POST") conn.outputStream.use { it.write(ByteArray(0)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code: ${text.take(300)}")
            // 记录成功域名
            baseActive = urlStr.substringBefore("/sash/")
            text
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        /**
         * 候选域名。国际版在前（当前用户是国际版），国内版作为兜底。
         *
         * 顺序不是猜的：国际版账号发到国内域名会 401，反过来也一样，
         * 所以只能挨个试，用「能返回活动列表」作为判定依据。
         */
        val BASES = listOf(
            BASE_INTL_OPENAPI,
            BASE_INTL,
            BASE_CN,
        )
    }
}
