package com.dsh.aihub.qoder

import android.content.Context
import org.json.JSONObject

/**
 * Qoder 协议连通性自检。
 *
 * 这是「最小验证」的落地形式：用户填入 PAT，点一下，把每一步的结果
 * 原样展示出来。成功说明整套 COSY 协议移植正确，可以继续做对话链路；
 * 失败则能直接看出卡在哪一步（编码 / 签名 / 公钥 / 网关业务码）。
 *
 * 为什么做成自检而不是直接接进账号池：协议是私有的，任何一处错了
 * 都是笼统的 401。先把「哪一步错」这个问题回答清楚，再谈业务集成。
 */
object QoderVerify {

    data class Result(
        val ok: Boolean,
        val lines: List<String>,
    )

    /**
     * 分步验证。返回可读的检查报告。
     *
     * 每一步都单独 try —— 一处失败不应掩盖后面的信息。
     */
    fun run(context: Context, personalToken: String): Result {
        val lines = ArrayList<String>()

        if (personalToken.isBlank()) {
            return Result(false, listOf("✗ PAT 为空"))
        }
        lines += "PAT 长度 ${personalToken.length}，前缀 ${personalToken.take(6)}…"

        // ① 编码往返：验证自定义 base64 的换位与字母表
        val roundTrip = runCatching {
            val probe = "qoder-probe-测试".toByteArray(Charsets.UTF_8)
            val enc = QoderCrypto.encode(probe)
            val dec = QoderCrypto.decode(enc)
            probe.contentEquals(dec) to enc
        }
        roundTrip.onSuccess { (same, enc) ->
            lines += if (same) "✓ 编码往返一致（样例 $enc）"
            else "✗ 编码往返不一致 —— 换位或字母表有误"
        }.onFailure {
            lines += "✗ 编码异常：${it.message}"
        }
        if (roundTrip.getOrNull()?.first != true) return Result(false, lines)

        // ② 签名：必须是 32 位小写十六进制
        val sig = runCatching { QoderCrypto.sign(QoderCrypto.currentDate()) }
        sig.onSuccess {
            lines += if (it.length == 32 && it.all { c -> c in "0123456789abcdef" })
                "✓ 签名格式正确（$it）"
            else "✗ 签名格式异常：$it"
        }.onFailure { lines += "✗ 签名异常：${it.message}" }

        // ③ RSA 公钥：能否解析并完成一次加密
        val rsa = runCatching {
            val out = QoderCrypto.rsaEncrypt(ByteArray(16) { it.toByte() })
            out.size
        }
        rsa.onSuccess { lines += "✓ RSA 公钥可用（密文 $it 字节）" }
            .onFailure { lines += "✗ RSA 公钥异常：${it.message}" }

        // ④ 区域探测：国际区还是国内网关？
        //
        // PAT 在哪个控制台创建，就只能用于那个区域。发错区域返回 401，
        // 但 401 本身不告诉你「区域错了」还是「token 无效」，所以两个都试。
        lines += ""
        lines += "—— 区域探测 ——"
        val intl = QoderIntlClient(personalToken)
        val intlResult = runCatching { intl.probeAgents() }
        intlResult.onSuccess {
            lines += "✓ 国际区（api.qoder.com）可用"
            lines += "  响应前 200 字符：${it.take(200)}"
        }.onFailure { e ->
            lines += "✗ 国际区不可用：${e.message?.take(200)}"
        }

        if (intlResult.isSuccess) {
            lines += ""
            lines += "结论：这张 PAT 属于国际区，应走标准 Bearer 协议。"
            lines += "国内网关的 COSY 签名链路对它是无用的。"
            // 注意：这里**不能提前 return**。
            // 之前提前返回导致签到探测根本没跑到，用户看不到签到接口是否存在。
            // 验证（吃 token）和签到（吃 campaign 接口）是两条独立链路，
            // 一条通不代表另一条也通。
            lines += ""
            lines += "—— 签到接口探测 ——"
            val claim = QoderClaim(personalToken)
            val campaigns = runCatching { claim.listCampaigns() }
            campaigns.onSuccess { r ->
                if (r.ok) {
                    lines += "✓ 活动接口可用（${r.message}）"
                    if (r.campaigns.isEmpty()) {
                        lines += "  当前没有活动"
                    } else {
                        r.campaigns.forEach { c ->
                            lines += "  · ${c.title}  ${if (c.claimable) "可领取" else "已领取/不可领"}"
                        }
                    }
                } else {
                    lines += "✗ ${r.message}"
                }
            }.onFailure { e ->
                lines += "✗ 签到探测异常：${e.message}"
            }
            return Result(true, lines)
        }

        // ⑤ 国内网关：PAT → jobToken（需 COSY 签名）
        lines += ""
        lines += "—— 国内网关（gateway.qoder.com.cn）——"
        val exchange = runCatching { QoderClient(context).exchangeJobToken(personalToken) }
        exchange.onSuccess { json ->
            lines += "✓ jobToken 获取成功"
            // 只回显非敏感字段，token 本身一律打码
            json.optString("name").takeIf { it.isNotBlank() }?.let { lines += "  账号：$it" }
            json.optString("id").takeIf { it.isNotBlank() }?.let { lines += "  ID：$it" }
            json.optLong("expireTime").takeIf { it > 0 }?.let {
                val mins = (it - System.currentTimeMillis()) / 60_000
                lines += "  有效期：约 $mins 分钟"
            }
            lines += if (json.optString("securityOauthToken").isNotBlank())
                "  securityOauthToken：已获取（${json.optString("securityOauthToken").length} 字符）"
            else "  securityOauthToken：缺失（后续对话请求会失败）"
        }.onFailure { e ->
            lines += "✗ jobToken 获取失败：${e.message}"
        }

        if (!exchange.isSuccess) {
            lines += ""
            lines += "两个区域都失败。请确认 PAT 未过期、账号已验证。"
            return Result(false, lines)
        }

        // 国内网关通了 → 继续探测签到。
        // 之前这里也是直接返回，用户看不到签到状态 —— 同一个坑踩了两次
        // （上次是国际区提前 return，这次是国内网关提前 return）。
        // 教训：验证与签到是两条链路，任何一条通了都不该终止后续探测。
        lines += ""
        lines += "—— 签到接口探测 ——"
        val claim = QoderClaim(personalToken)
        runCatching { claim.listCampaigns() }
            .onSuccess { r ->
                if (r.ok) {
                    lines += "✓ 活动接口可用（${r.message}）"
                    if (r.campaigns.isEmpty()) {
                        lines += "  当前没有活动"
                    } else {
                        r.campaigns.forEach { c ->
                            lines += "  · ${c.title}  ${if (c.claimable) "可领取" else "已领/不可领"}"
                        }
                    }
                } else {
                    lines += "✗ ${r.message}"
                }
            }
            .onFailure { e -> lines += "✗ 签到探测异常：${e.message}" }

        return Result(true, lines)
    }
}
