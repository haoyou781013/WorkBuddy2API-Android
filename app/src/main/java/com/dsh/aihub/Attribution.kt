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
     * 使用限制，按优先级排列的依据：
     *
     *   ① 原项目（jilin0105）README 的免责声明 —— 最先遵从。
     *      其原文要求「仅供学习与技术研究，请勿用于任何商业用途」，
     *      并禁止生产环境、批量调用、代理转售。这条比 MIT 更严格，
     *      派生版本必须一并继承。
     *   ② 上游 workbuddy2api-panel README 的使用声明 —— 其次。
     *   ③ MIT 协议本身 —— 最后，只约束代码层面的使用与再分发。
     *
     * 本应用同样以「个人免费使用、不商用」为前提发布。
     *
     * 为什么要在应用内展示：MIT 只约束代码层面的使用，不约束"以本项目名义
     * 售卖、捆绑分发"。上游 workbuddy2api-panel 的作者在 README 里写得很明确，
     * 这些限制属于作者意愿而非协议条款，但派生版本有义务把话传到，
     * 否则用户拿到的 APK 与作者的声明脱节。
     */
    val USAGE_NOTICE: List<String> = listOf(
        "仅供学习与技术研究使用，禁止任何商业用途。",
        "禁止用于生产环境、批量调用、代理转售，或任何违反 WorkBuddy / CodeBuddy 服务条款的行为。",
        "仅供个人管理自己的账号（签到 / 保活 / 本地工具接入），禁止批量注册小号、收购账号对外提供付费 API 或共享池。",
        "禁止二次加壳、捆绑卡密售卖，或以「公益服」「低价中转」等名义变相收费分发。",
        "使用本应用须自行遵守上游服务条款并承担全部风险；作者不对由此产生的损失负责。",
        "上游免费开源，任何「收费版」「卡密版」均非原作者发布。加壳分发无法审计，",
        "而 auths 中保存的是明文 accessToken / refreshToken，存在被植入后门的风险。"
    )
}
