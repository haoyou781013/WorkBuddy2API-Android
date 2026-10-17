package com.dsh.aihub.qoder

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Qoder 对话客户端（COSY 签名链路）。
 *
 * 端点（cli2api internal/providers/qoder/cosy.go:241 ChatEndpoint）：
 *   https://gateway.qoder.com.cn/algo/api/v2/service/pro/sse/
 *     agent_chat_generation?FetchKeys=llm_model_result&AgentId=agent_common&Encode=1
 *
 * 与签到的区别：
 *   · 签到走 openapi.qoder.com.cn + 普通 Bearer
 *   · 聊天走 gateway.qoder.com.cn + COSY 签名 Bearer
 *
 * 签名链路（cosy.go:151 Sign）：
 *   ① 生成临时 AES 密钥 tempKey
 *   ② cosyKey = base64(RSA(tempKey))
 *   ③ info    = base64(AES-CBC(身份JSON, tempKey))    IV = tempKey 前16字节
 *   ④ payload = base64(紧凑JSON{cosyVersion,ideVersion,info,requestId,version})
 *   ⑤ raw     = payload + "\n" + cosyKey + "\n" + date + "\n" + body + "\n" + path
 *   ⑥ bearer  = "Bearer COSY." + payload + "." + MD5hex(raw)
 *
 * 响应是 SSE，每个事件里 `llm_model_result` 字段携带增量内容。
 */
class QoderChat(private val jobTokenJson: JSONObject) {

    private val uid: String = jobTokenJson.optString("id").ifBlank {
        jobTokenJson.optString("userId")
    }
    private val name: String = jobTokenJson.optString("name")
    private val securityOauthToken: String = jobTokenJson.optString("securityOauthToken")
    private val refreshToken: String = jobTokenJson.optString("refreshToken")

    /** 一次性构建的会话材料，避免每次请求重算 RSA/AES。 */
    private val session: Session? by lazy { runCatching { buildSession() }.getOrNull() }

    private class Session(
        val cosyKey: String,
        val info: String,
        val machineId: String,
        val machineToken: String,
    )

    data class Message(val role: String, val content: String)

    /**
     * 发起对话，逐段回调增量文本。
     *
     * 用回调而不是返回 List：SSE 是流式的，攒完再返回会让用户盯着空屏
     * 等几十秒。回调能让 UI 边收边显示。
     *
     * @return 完整文本（供调用方记录）
     */
    fun chat(
        model: String,
        messages: List<Message>,
        maxTokens: Int = 8000,
        onDelta: (String) -> Unit,
    ): String {
        val s = session ?: throw IOException("会话材料构建失败（可能 jobToken 缺少字段）")
        val endpoint = ENDPOINT_CN
        // Encode=1 表示请求体要按自定义字母表编码（cosy.go:184 EncodeBody）。
        // 签名用的是**编码后**的 body，两者必须一致，否则 MD5 对不上。
        val body = QoderCrypto.encode(buildBody(model, messages, maxTokens).toByteArray(Charsets.UTF_8))
        val date = (System.currentTimeMillis() / 1000).toString()
        val payload = QoderCrypto.payloadB64(s.info, UUID.randomUUID().toString())
        val bearer = QoderCrypto.cosyBearer(
            payload = payload,
            cosyKey = s.cosyKey,
            date = date,
            body = body,
            path = QoderCrypto.cosySignPath(endpoint),
        )

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 300_000
            doOutput = true
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", bearer)
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Content-Type", "application/json")
            // 聊天链路用 ClientType 5（CLI 身份），签到才用 10。
            setRequestProperty("Cosy-ClientType", "5")
            setRequestProperty("Cosy-Data-Policy", "AGREE")
            setRequestProperty("Cosy-Date", date)
            setRequestProperty("Cosy-Key", s.cosyKey)
            setRequestProperty("Cosy-MachineId", s.machineId)
            setRequestProperty("Cosy-MachineToken", s.machineToken)
            setRequestProperty("Cosy-MachineType", "5")
            setRequestProperty("Cosy-User", uid)
            setRequestProperty("Cosy-Version", QoderCrypto.COSY_VERSION)
            setRequestProperty("Login-Version", "v2")
            setRequestProperty("Accept-Encoding", "identity")
        }

        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw IOException("HTTP $code: ${err.take(300)}")
            }
            return readSse(conn, onDelta)
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 解析 SSE，抠出 llm_model_result 里的增量文本。 */
    private fun readSse(conn: HttpURLConnection, onDelta: (String) -> Unit): String {
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data:")) continue
                val data = l.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") continue
                val delta = extractDelta(data) ?: continue
                if (delta.isNotEmpty()) {
                    sb.append(delta)
                    onDelta(delta)
                }
            }
        }
        return sb.toString()
    }

    /**
     * 从一条 SSE data 里取增量内容。
     *
     * 上游字段没有公开文档，按优先级依次尝试：
     *   llm_model_result.content  → 最常见
     *   llm_model_result.delta.content
     *   choices[0].delta.content  → 兼容 OpenAI 形状
     * 拿到就返回，拿不到返回 null（不代表出错，很多事件是心跳/状态）。
     */
    private fun extractDelta(data: String): String? {
        val root = runCatching { JSONObject(data) }.getOrNull() ?: return null
        val result = root.optJSONObject("llm_model_result")

        result?.let { r ->
            r.optString("content").takeIf { it.isNotEmpty() }?.let { return it }
            r.optJSONObject("delta")?.optString("content")
                ?.takeIf { it.isNotEmpty() }?.let { return it }
            r.optJSONObject("message")?.optString("content")
                ?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        root.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("delta")?.optString("content")
            ?.takeIf { it.isNotEmpty() }?.let { return it }

        // 有的实现把内容放在 data 下一层
        root.optJSONObject("data")?.let { d ->
            d.optJSONObject("llm_model_result")?.optString("content")
                ?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    /**
     * 请求体（direct.go:251 BuildPlainChatBody）。
     *
     * 这是 Encode=1 之前的**明文**结构，不是 OpenAI 格式。
     * 网关要的字段比 OpenAI 多很多：request_set_id / chat_task /
     * model_config / business 等，缺一个都可能被拒。
     */
    private fun buildBody(model: String, messages: List<Message>, maxTokens: Int): String {
        val systemParts = messages.filter { it.role == "system" || it.role == "developer" }
            .map { it.content }
        val chat = JSONArray()
        messages.filter { it.role != "system" && it.role != "developer" }.forEach {
            chat.put(JSONObject().put("role", it.role).put("content", it.content))
        }
        if (chat.length() == 0) {
            chat.put(JSONObject().put("role", "user").put("content", "ping"))
        }
        val systemText = systemParts.joinToString("\n\n")
        val lastText = messages.lastOrNull()?.content?.trim().orEmpty()
        val title = (if (lastText.isBlank()) "chat" else lastText).take(40)

        return JSONObject()
            .put("request_id", UUID.randomUUID().toString())
            .put("request_set_id", UUID.randomUUID().toString())
            .put("chat_record_id", UUID.randomUUID().toString())
            .put("session_id", UUID.randomUUID().toString())
            .put("stream", true)
            .put("chat_task", "FREE_INPUT")
            .put(
                "chat_context",
                JSONObject()
                    .put("text", "")
                    .put("features", JSONObject())
                    .put("extra", JSONObject())
                    .put("chatPrompt", "")
                    .put("imageUrls", JSONArray()),
            )
            .put("is_reply", false)
            .put("is_retry", false)
            .put("source", "cli")
            .put("version", "1.0")
            .put("agent_id", "agent_common")
            .put("task_id", "common")
            .put("session_type", "assistant")
            .put("aliyun_user_type", "")
            .put(
                "model_config",
                JSONObject()
                    .put("key", model)
                    .put("display_name", model)
                    .put("model", "")
                    .put("format", "openai")
                    .put("is_vl", true)
                    .put("is_reasoning", false)
                    .put("api_key", "")
                    .put("url", "")
                    .put("source", "system")
                    .put("max_input_tokens", 180000),
            )
            .put("custom_model", JSONObject.NULL)
            .put("system", systemText)
            .put("messages", chat)
            .put("tools", JSONArray())
            .put("parameters", JSONObject().put("max_tokens", maxTokens))
            .put(
                "business",
                JSONObject()
                    .put("product", "cli")
                    .put("version", QoderCrypto.COSY_VERSION)
                    .put("type", "agent")
                    .put("id", UUID.randomUUID().toString())
                    .put("name", title)
                    .put("begin_at", System.currentTimeMillis())
                    .put("stage", "start"),
            )
            .toString()
    }

    /**
     * 构建 COSY 会话材料。
     *
     * 步骤照抄 cosy.go:119 NewSession：
     *   tempKey  = 32 字节随机
     *   cosyKey  = base64(RSA_PKCS1(tempKey))
     *   info     = base64(AES-CBC(identityJSON, tempKey, IV=tempKey[0..16]))
     */
    private fun buildSession(): Session {
        if (uid.isBlank() || securityOauthToken.isBlank()) {
            throw IOException("jobToken 缺少 uid 或 securityOauthToken")
        }
        val tempKey = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val wrapped = QoderCrypto.rsaEncrypt(tempKey)
        val cosyKey = android.util.Base64.encodeToString(wrapped, android.util.Base64.NO_WRAP)

        // identityJSON 字段按字母序（cosy.go:267）
        val identity = JSONObject()
            .put("aid", uid)
            .put("name", name)
            .put("organization_id", "")
            .put("organization_name", "")
            .put("refresh_token", refreshToken)
            .put("security_oauth_token", securityOauthToken)
            .put("uid", uid)
            .put("user_type", jobTokenJson.optString("userType"))
            .put("yx_uid", "")
        // 紧凑且保序：手写拼接，不用 JSONObject.toString（顺序不保证）
        val identityStr = buildString {
            append("{")
            append("\"aid\":").append(quote(uid)).append(",")
            append("\"name\":").append(quote(name)).append(",")
            append("\"organization_id\":\"\",")
            append("\"organization_name\":\"\",")
            append("\"refresh_token\":").append(quote(refreshToken)).append(",")
            append("\"security_oauth_token\":").append(quote(securityOauthToken)).append(",")
            append("\"uid\":").append(quote(uid)).append(",")
            append("\"user_type\":").append(quote(jobTokenJson.optString("userType"))).append(",")
            append("\"yx_uid\":\"\"")
            append("}")
        }
        val enc = aesCbc(identityStr.toByteArray(Charsets.UTF_8), tempKey)
        val info = android.util.Base64.encodeToString(enc, android.util.Base64.NO_WRAP)

        return Session(
            cosyKey = cosyKey,
            info = info,
            machineId = deriveId(uid, "machine"),
            machineToken = deriveMachineToken(uid),
        )
    }

    /** AES-CBC/PKCS5，IV 取 key 前 16 字节（cosy.go:137 aesCBCEncrypt）。 */
    private fun aesCbc(plain: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val iv = key.copyOfRange(0, 16)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv),
        )
        return cipher.doFinal(plain)
    }

    private fun quote(v: String): String = JSONObject.quote(v)

    /**
     * 机器 ID 是**推导**出来的，不是随机生成的（cosy.go:323 deriveID）：
     *   md5(salt + ":" + uid)  →  32 位小写 hex
     *
     * 这点很关键：随机 ID 每次不同，上游会当成新设备，风控可能直接拒。
     */
    private fun deriveId(uid: String, salt: String): String {
        val src = if (uid.isBlank()) "anonymous" else uid
        val md = java.security.MessageDigest.getInstance("MD5")
        return md.digest("$salt:$src".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * 机器 token（cosy.go:339 deriveMachineToken）：
     *   base64url(sha512("machinetoken:" + uid))  取前 43 位
     */
    private fun deriveMachineToken(uid: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-512")
        val sum = md.digest("machinetoken:$uid".toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(
            sum,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
        ).take(43)
    }

    companion object {
        /**
         * 国内网关聊天端点。
         *
         * 注意路径里有 `Encode=1` —— 表示请求体需要按自定义字母表编码。
         * 但 cli2api 的 direct 实现里 EncodedBody 用的是 **EncodeBody**，
         * 与签到的编码表相同，所以直接复用 QoderCrypto.encode。
         */
        const val ENDPOINT_CN =
            "https://gateway.qoder.com.cn/algo/api/v2/service/pro/sse/" +
                "agent_chat_generation?FetchKeys=llm_model_result&AgentId=agent_common&Encode=1"

        /**
         * 可用模型。对齐 qodercli 1.1.41 的 --list-models
         * （来自 qoder-proxy clean/models.js）。
         *
         * 只收主模型，去掉 effort 变体 —— 那些是同一模型的
         * 不同推理预算档位，界面上放太多会淹没真正有用的选项。
         */
        val MODELS: List<Pair<String, String>> = listOf(
            "auto" to "Auto（自动选择）",
            "ultimate" to "Ultimate",
            "performance" to "Performance",
            "efficient" to "Efficient",
            "lite" to "Lite",
            "cantus" to "Cantus",
            "qwen3.8-max" to "Qwen3.8-Max",
            "qwen3.8-flash" to "Qwen3.8-Flash",
            "qwen3.7-max" to "Qwen3.7-Max",
            "qwen3.7-plus" to "Qwen3.7-Plus",
            "kimi-k3" to "Kimi-K3",
            "kimi-k2.7-code" to "Kimi-K2.7-Code",
            "glm-5.3" to "GLM-5.3",
            "glm-5.3-flash" to "GLM-5.3-Flash",
            "deepseek-v4-pro" to "DeepSeek-V4-Pro",
            "deepseek-v4-flash" to "DeepSeek-V4-Flash",
            "minimax-m3" to "MiniMax-M3",
        )

        /** 国际版端点，备用。 */
        const val ENDPOINT_INTL =
            "https://api1.qoder.sh/algo/api/v2/service/pro/sse/" +
                "agent_chat_generation?FetchKeys=llm_model_result&AgentId=agent_common&Encode=1"
    }
}
