package com.dsh.aihub.qoder

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Qoder COSY 协议的编码 / 签名 / 加密原语。
 *
 * 这些算法全部来自 Qoder 客户端，不是通用标准，因此必须逐个照搬：
 *
 *   ① 自定义 base64 —— 换字母表 + 三段换位。换位规则是
 *      [尾部 1/3][中间 1/3][头部 1/3] 的重排，不是简单替换。
 *   ② 请求签名 —— MD5("cosy&<secret>&<date>")。
 *      用 MD5 不是疏忽，上游协议就是这么定的，改 SHA 会直接认证失败。
 *   ③ 载荷加密 —— RSA-1024 加密临时密钥，再用 AES-CBC 加密 payload。
 *
 * 为什么把这些单独放一个文件：它们是「协议事实」而非业务逻辑，
 * 上游一旦变更需要整体替换，与调用方解耦才好维护。
 */
internal object QoderCrypto {

    // ---------------------------------------------------------------- ① 编码

    private const val CUSTOM_ALPHABET =
        "_doRTgHZBKcGVjlvpC,@aFSx#DPuNJme&i*MzLOEn)sUrthbf%Y^w.(kIQyXqWA!"
    private const val STD_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val s2c = ByteArray(128)
    private val c2s = ByteArray(128)
    private val s2cOk = BooleanArray(128)
    private val c2sOk = BooleanArray(128)

    init {
        for (i in 0 until 64) {
            val s = STD_ALPHABET[i]
            val c = CUSTOM_ALPHABET[i]
            s2c[s.code] = c.code.toByte()
            s2cOk[s.code] = true
            c2s[c.code] = s.code.toByte()
            c2sOk[c.code] = true
        }
        // padding 也换：标准 '=' 对应自定义 '$'
        s2c['='.code] = '$'.code.toByte()
        s2cOk['='.code] = true
        c2s['$'.code] = '='.code.toByte()
        c2sOk['$'.code] = true
    }

    /**
     * 标准 base64 → 三段换位 → 自定义字母表。
     *
     * 换位含义（n 为长度，a = n/3）：
     *   原串按 [0,a) [a,n-a) [n-a,n) 切成三段，
     *   输出顺序是 [第三段][第二段][第一段]。
     */
    fun encode(plaintext: ByteArray): String {
        val std = android.util.Base64.encodeToString(plaintext, android.util.Base64.NO_WRAP)
        val n = std.length
        val a = n / 3
        val rearranged = CharArray(n)
        // 第三段 → 开头
        for (i in 0 until a) rearranged[i] = std[n - a + i]
        // 第二段 → 原位
        for (i in a until n - a) rearranged[i] = std[i]
        // 第一段 → 结尾
        for (i in n - a until n) rearranged[i] = std[i - (n - a)]

        val out = CharArray(n)
        for (i in 0 until n) {
            val ch = rearranged[i].code
            require(ch < 128 && s2cOk[ch]) { "char out of alphabet: ${rearranged[i]}" }
            out[i] = s2c[ch].toInt().toChar()
        }
        return String(out)
    }

    /** [encode] 的逆运算。 */
    fun decode(encoded: String): ByteArray {
        val n = encoded.length
        val mapped = CharArray(n)
        for (i in 0 until n) {
            val ch = encoded[i].code
            require(ch < 128 && c2sOk[ch]) { "char out of custom alphabet: ${encoded[i]}" }
            mapped[i] = c2s[ch].toInt().toChar()
        }
        val a = n / 3
        val std = CharArray(n)
        // 逆换位：开头的 1/3 还原到结尾，中间的原地，结尾的还原到开头
        for (i in 0 until a) std[i] = mapped[n - a + i]
        for (i in a until n - a) std[i] = mapped[i]
        for (i in n - a until n) std[i] = mapped[i - (n - a)]
        return android.util.Base64.decode(String(std), android.util.Base64.NO_WRAP)
    }

    // ---------------------------------------------------------------- ② 签名

    private const val APP_CODE = "cosy"

    /** 上游客户端里硬编码的签名密钥。可用系统属性覆盖，便于协议变更时热修。 */
    private val secret: String by lazy {
        System.getProperty("qoder.signature.secret")
            ?.takeIf { it.isNotBlank() }
            ?: "d2FyLCB3YXIgbmV2ZXIgY2hhbmdlcw=="
    }

    /** RFC 1123 格式的 UTC 时间，形如 `Mon, 02 Jan 2006 15:04:05 GMT`。 */
    fun currentDate(): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    /** `MD5("cosy&<secret>&<date>")`，小写十六进制。 */
    fun sign(date: String): String {
        val input = "$APP_CODE&$secret&$date"
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format(Locale.US, "%02x", b))
        return sb.toString()
    }

    // ---------------------------------------------------------------- ③ 加密

    /** 服务端 RSA 公钥。上游轮换后必须同步替换，否则认证直接失败。 */
    private const val SERVER_PUB_KEY_PEM = """-----BEGIN PUBLIC KEY-----
MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDA8iMH5c02LilrsERw9t6Pv5Nc
4k6Pz1EaDicBMpdpxKduSZu5OANqUq8er4GM95omAGIOPOh+Nx0spthYA2BqGz+l
6HRkPJ7S236FZz73In/KVuLnwI8JJ2CbuJap8kvheCCZpmAWpb/cPx/3Vr/J6I17
XcW+ML9FoCI6AOvOzwIDAQAB
-----END PUBLIC KEY-----"""

    private val rsaKey: java.security.PublicKey by lazy {
        val b64 = SERVER_PUB_KEY_PEM
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace(Regex("\\s"), "")
        val spec = java.security.spec.X509EncodedKeySpec(
            android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
        )
        java.security.KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    /** RSA-1024 / PKCS1 加密临时密钥。 */
    fun rsaEncrypt(tempKey: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, rsaKey)
        return cipher.doFinal(tempKey)
    }

    /**
     * AES-CBC/PKCS5 加密。
     *
     * IV 取密钥本身的前 16 字节 —— 这是上游客户端的做法，不是安全疏忽
     * （协议双向约定），不能「顺手改成随机 IV」，否则上游解不开。
     */
    fun aesEncrypt(plain: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(key.copyOfRange(0, 16)))
        return cipher.doFinal(plain)
    }

    // ---------------------------------------------------------------- 聊天链路

    /**
     * 构造 COSY bearer 的 payload 段。
     *
     * 格式（cli2api internal/providers/qoder/cosy.go:281 payloadB64）：
     *   字段按 key 字母序压成紧凑 JSON → 标准 base64（不是自定义表！）
     *
     *   {"cosyVersion":..,"ideVersion":"","info":"<jobToken 的 info>",
     *    "requestId":"<uuid>","version":"v1"}
     *
     * 注意：这里用 **标准 base64**，和签到的自定义字母表编码不同。
     * 两套编码并存是 Qoder 协议里容易搞混的地方。
     */
    fun payloadB64(info: String, requestId: String): String {
        // 按字母序拼紧凑 JSON，不依赖 JSONObject 的迭代顺序。
        val json = "{\"cosyVersion\":\"$COSY_VERSION\"," +
            "\"ideVersion\":\"\"," +
            "\"info\":${quote(info)}," +
            "\"requestId\":${quote(requestId)}," +
            "\"version\":\"v1\"}"
        return android.util.Base64.encodeToString(
            json.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
    }

    /**
     * 计算 COSY bearer：`Bearer COSY.<payload>.<md5hex>`。
     *
     * 签名串（cosy.go:161）：
     *   raw = payload + "\n" + cosyKey + "\n" + date + "\n" + body + "\n" + path
     *   sum = MD5(raw)
     *
     * path 要剥掉前导 `/algo`（cosySignPath 做的事），否则签名对不上。
     */
    fun cosyBearer(
        payload: String,
        cosyKey: String,
        date: String,
        body: String,
        path: String,
    ): String {
        val raw = payload + "\n" + cosyKey + "\n" + date + "\n" + body + "\n" + path
        val md = java.security.MessageDigest.getInstance("MD5")
        val sum = md.digest(raw.toByteArray(Charsets.UTF_8))
        val hex = sum.joinToString("") { "%02x".format(it) }
        return "Bearer COSY.$payload.$hex"
    }

    /** 签名用的 path：去掉前导 /algo。 */
    fun cosySignPath(fullUrl: String): String {
        val path = runCatching { java.net.URI(fullUrl).path }.getOrNull() ?: "/"
        val trimmed = if (path.startsWith("/algo")) path.removePrefix("/algo") else path
        return trimmed.ifEmpty { "/" }
    }

    /** JSON 字符串安全引用。 */
    private fun quote(v: String): String {
        val sb = StringBuilder("\"")
        for (c in v) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }

    internal const val COSY_VERSION = "1.1.32"
}
