package com.joy4fire.workbuddy2api.m3

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 内联图标集 —— 零依赖、零资源文件。
 *
 * 为什么不用 androidx 的 material-icons：
 *   - material-icons-extended 会给 APK 增加约 10MB（上万图标，本应用只需 7 个）
 *   - material-icons-core 只含约 20 个常用图标，且在本项目里它只是被 Miuix
 *     传递引入的 —— Miuix 一旦移除，这个依赖也随之消失，靠传递依赖拿图标很脆
 *
 * 实现方式：用官方 API `addPathNodes(String)` 解析 Material Symbols 的 pathData。
 *
 * 为什么用 addPathNodes 而不是自己写 SVG 解析器：
 *   这是 androidx.compose.ui.graphics.vector 的公开 API（已核对 1.11.2 的字节码签名），
 *   能直接把 SVG 的 d 属性字符串转成 PathNode 列表。自写解析器要处理相对/绝对坐标、
 *   隐式命令重复、负号粘连等规则，出错时表现为「图标画歪」而不是编译失败 ——
 *   是最难排查的一类 bug。用官方 API 就没有这一层风险。
 *
 * pathData 取自 Google Material Symbols（Apache-2.0），24x24 viewport。
 */
object M3Icons {

    private const val VP = 24f

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = VP,
            viewportHeight = VP
        ).apply {
            path(fill = SolidColor(Color.White)) {
                addPathNodes(pathData)
            }
        }.build()

    /** 概览 —— 四格仪表盘。 */
    val Overview: ImageVector by lazy {
        icon(
            "Overview",
            "M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z"
        )
    }

    /** 账号 —— 单人剪影。 */
    val Accounts: ImageVector by lazy {
        icon(
            "Accounts",
            "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"
        )
    }

    /** 模型 —— 图层叠放。 */
    val Models: ImageVector by lazy {
        icon(
            "Models",
            "M11.99 18.54l-7.37-5.73L3 14.07l9 7 9-7-1.63-1.27-7.38 5.74zM12 16l7.36-5.73L21 9l-9-7-9 7 1.63 1.27L12 16z"
        )
    }

    /** 应用 —— 九宫格。 */
    val Apps: ImageVector by lazy {
        icon(
            "Apps",
            "M3 3h8v8H3V3zm10 0h8v8h-8V3zM3 13h8v8H3v-8zm10 0h8v8h-8v-8z"
        )
    }

    /**
     * 设置 —— 齿轮。
     *
     * pathData 是官方 Material Symbols 的 settings 图标，含弧线段。
     * addPathNodes 支持全部 SVG 命令（含 A 弧线），所以这里可以直接用完整路径。
     */
    val Settings: ImageVector by lazy {
        icon(
            "Settings",
            "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"
        )
    }

    /** 成长 —— 五角星。 */
    val Growth: ImageVector by lazy {
        icon(
            "Growth",
            "M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z"
        )
    }

    /** 返回箭头。 */
    val Back: ImageVector by lazy {
        icon(
            "Back",
            "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"
        )
    }
}
