package com.dsh.aihub

/**
 * 来源与署名信息。
 *
 * 为什么单独抽一个文件而不是写死在界面里：MIT 协议要求保留版权声明，
 * 一旦要改地址或补上游，只需动这里一处；散在 UI 里容易漏改，
 * 漏改就是协议违规。
 *
 * 本项目是 jilin0105/WorkBuddy2API-Android 的派生版本，按 MIT 协议
 * 必须标注原始来源。上游本身又参考了若干 Python 实现，一并列出。
 */
object Attribution {

    /** 本项目（AIHub）的发布仓库。 */
    const val SELF_NAME = "AIHub"
    const val SELF_REPO = "https://github.com/haoyou781013/WorkBuddy2API-Android"

    /** 直接来源：本项目的上游。 */
    const val UPSTREAM_NAME = "jilin0105/WorkBuddy2API-Android"
    const val UPSTREAM_REPO = "https://github.com/jilin0105/WorkBuddy2API-Android"

    /** 上游参考的 Python 实现。 */
    val REFERENCES: List<Pair<String, String>> = listOf(
        "wicm84266964/Buddy2api" to "https://github.com/wicm84266964/Buddy2api",
        "ShouZhuo0413/codebuddy2api" to "https://github.com/ShouZhuo0413/codebuddy2api",
        "hawklithm/workbuddy2api" to "https://github.com/hawklithm/workbuddy2api",
    )

    /** 协议名称，用于展示。 */
    const val LICENSE = "MIT License"

    /** 版权行，原样取自上游 LICENSE 文件。 */
    const val COPYRIGHT = "Copyright (c) 2026 WorkBuddy2API Android Contributors"

    /**
     * 上游作者的使用限制原文要点。
     *
     * 为什么要在应用内展示：MIT 只约束代码层面的使用，不约束"以本项目名义
     * 售卖、捆绑分发"。上游 workbuddy2api-panel 的作者在 README 里写得很明确，
     * 这些限制属于作者意愿而非协议条款，但派生版本有义务把话传到，
     * 否则用户拿到的 APK 与作者的声明脱节。
     */
    val USAGE_NOTICE: List<String> = listOf(
        "本项目仅供个人管理自己的账号使用（签到 / 保活 / 本地工具接入）。",
        "禁止批量注册小号、收购账号对外提供付费 API、共享池或代充业务。",
        "禁止二次加壳、捆绑卡密售卖，或以「公益服」「低价中转」等名义变相收费分发。",
        "上游为免费开源项目，任何「收费版」「卡密版」均非原作者发布，且无法审计——",
        "auths 中保存的是明文 accessToken / refreshToken，来历不明的加壳版本存在后门风险。",
        "批量注册与转售接口配额违反目标平台服务条款，可能导致账号封禁。"
    )
}
