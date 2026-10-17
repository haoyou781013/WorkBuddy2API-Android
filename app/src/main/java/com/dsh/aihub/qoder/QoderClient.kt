package com.dsh.aihub.qoder

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Qoder 网关客户端 —— 最小可用版本。
 *
 * 当前只实现「PAT → jobToken」这一步，目的是验证协议移植是否正确。
 * 拿到 jobToken 就说明自定义编码 / MD5 签名 / RSA 公钥全部对上了；
 * 后续的对话请求只是换 URL 和 body。
 *
 * 为什么先只做这一步：整套 COSY 协议是私有的，任何一处（字母表、换位
 * 规则、签名串顺序、公钥）出错都会表现为笼统的 401，无法定位。
 * 一步一验证比一次性写完再调试快得多。
 */
// 区域域名。qoder.com / qoder.sh 是国际区，gateway.qoder.com.cn 是国内网关。
//
// 在 qoder.com 控制台创建的 PAT 只能用于国际区 —— 发到国内网关必然 401。
// 而且两套域名的**协议完全不同**：
//   国际区   → 标准 Bearer + REST，无需签名
//   国内网关 → COSY 签名 + 自定义编码 + RSA
// 所以选错区域不只是换个 URL，整条链路都不一样。
internal const val BASE_INTL = "https://api.qoder.com"
internal const val BASE_INTL_OPENAPI = "https://openapi.qoder.sh"
internal const val BASE_CN = "https://gateway.qoder.com.cn"

class QoderClient(private val context: Context) {

    private val prefs = context.getSharedPreferences("qoder", Context.MODE_PRIVATE)

    /** 机器标识：首次生成后固定下来。上游把它当设备指纹，频繁变化会被判异常。 */
    private val machineId: String
        get() = prefs.getString(KEY_MACHINE_ID, null) ?: UUID.randomUUID().toString()
            .also { prefs.edit().putString(KEY_MACHINE_ID, it).apply() }

    private val machineToken: String
        get() = prefs.getString(KEY_MACHINE_TOKEN, null) ?: UUID.randomUUID().toString()
            .replace("-", "")
            .also { prefs.edit().putString(KEY_MACHINE_TOKEN, it).apply() }

    // 与官方客户端一致：桌面端为 5。用错误的值会被网关拒绝。
    private val machineType: String = "5"

    /**
     * 用 PAT 换取 jobToken。
     *
     * @return 网关返回的 JSON（含 name / id / securityOauthToken / refreshToken / expireTime）
     * @throws IOException 网络失败或网关返回业务错误
     */
    fun exchangeJobToken(personalToken: String): JSONObject {
        val url = "$AUTH_BASE/algo/api/v3/user/jobToken?Encode=1"

        // 内层 payload。字段顺序必须与官方客户端一致 —— 上游会对 payload
        // 整体签名，顺序变了签名就对不上。
        val inner = JSONObject()
            .put("personalToken", personalToken)
            .put("securityOauthToken", "")
            .put("refreshToken", "")
            .put("needRefresh", false)
            .put("authInfo", JSONObject())

        val outer = JSONObject()
            .put("payload", inner.toString())
            .put("encodeVersion", "1")

        return postEncoded(url, outer)
    }

    /**
     * 把 JSON 编码后 POST，走 COSY 签名头。
     *
     * 编码顺序是有讲究的：先序列化 → 再走自定义 base64。
     * 签名用的是**未编码**的 JSON 与 date，不是编码后的 body。
     */
    private fun postEncoded(urlStr: String, obj: JSONObject): JSONObject {
        val date = QoderCrypto.currentDate()
        val sig = QoderCrypto.sign(date)
        val body = QoderCrypto.encode(obj.toString().toByteArray(Charsets.UTF_8))

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("cosy-machinetoken", machineToken)
            setRequestProperty("cosy-machinetype", machineType)
            setRequestProperty("login-version", "v2")
            setRequestProperty("appcode", "cosy")
            setRequestProperty("accept", "application/json")
            setRequestProperty("accept-encoding", "identity")
            setRequestProperty("cosy-version", "0.1.43")
            setRequestProperty("cosy-clienttype", "5")
            setRequestProperty("date", date)
            setRequestProperty("signature", sig)
            setRequestProperty("content-type", "application/json")
            setRequestProperty("cosy-machineid", machineId)
            setRequestProperty("user-agent", "Go-http-client/2.0")
        }

        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                throw IOException("Qoder 网关 HTTP $code: ${text.take(300)}")
            }
            // 响应可能也是等位编码，也可能是明文 JSON —— 先按明文试，失败再解。
            val json = runCatching { JSONObject(text) }.getOrElse {
                JSONObject(String(QoderCrypto.decode(text), Charsets.UTF_8))
            }
            // 网关用 code 字段表达业务错误，HTTP 200 也可能是失败。
            val bizCode = json.optInt("code", 0)
            if (bizCode != 0 && bizCode != 200) {
                throw IOException("Qoder 业务错误 $bizCode: ${json.optString("message").take(200)}")
            }
            json
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "QoderClient"
        private const val AUTH_BASE = BASE_CN
        private const val KEY_MACHINE_ID = "machine_id"
        private const val KEY_MACHINE_TOKEN = "machine_token"
    }
}

/**
 * 国际区（qoder.com / qoder.sh）的标准 REST 客户端。
 *
 * 与国内网关的区别：这里**不需要** COSY 签名、自定义编码或 RSA 加密，
 * 只是一个普通 Bearer 请求。协议简单得多，也因此更稳定。
 *
 * 用 /api/v1/cloud/agents 做探活：它是官方文档里列出的第一个业务接口，
 * 只要能返回数据就说明 PAT 有效且区域正确。
 */
class QoderIntlClient(private val personalToken: String) {

    /** 探活。成功返回原始 JSON 文本，失败抛 IOException 并带上网关回应。 */
    fun probeAgents(): String = get("$BASE_INTL/api/v1/cloud/agents?limit=1")

    /** 另一个探活端点，走 openapi 域。两个都试可以区分「PAT 无效」与「区域错」。 */
    fun probeOpenApi(): String = get("$BASE_INTL_OPENAPI/api/v1/cloud/agents?limit=1")

    private fun get(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $personalToken")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code: ${text.take(400)}")
            text
        } finally {
            conn.disconnect()
        }
    }
}
