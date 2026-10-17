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
}
