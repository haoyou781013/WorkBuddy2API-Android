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
        private const val AUTH_BASE = "https://gateway.qoder.com.cn"
        private const val KEY_MACHINE_ID = "machine_id"
        private const val KEY_MACHINE_TOKEN = "machine_token"
    }
}
