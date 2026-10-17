package com.joy4fire.workbuddy2api.m3

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Material 3 主题 —— 替换原 Miuix 主题（ui/AppTheme.kt）。
 *
 * 配色策略与原实现一致：**静态配色，不接 Monet 动态取色**。
 * 理由也一致：本 App 是本地网关工具，界面只在用户主动打开时看几眼，
 * 不需要跟随壁纸变色；静态配色的行为完全确定，浅深两套已覆盖需求。
 *
 * 色值取自 Material 3 基线配色的紫调（baseline purple），
 * 与 M3 官方组件的默认外观一致，替换 Miuix 后视觉上不会显得突兀。
 */

// ---------------------------------------------------------------- 浅色

private val LightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),

    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),

    tertiary = Color(0xFF7D5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),

    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    background = Color(0xFFFEF7FF),
    onBackground = Color(0xFF1D1B20),

    // surface 与 background 刻意取不同值：本应用大量使用 elevated card，
    // 需要卡片与页面底色之间有可辨识的层次，同值会让卡片边界完全消失。
    surface = Color(0xFFFEF7FF),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),

    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0)
)

// ---------------------------------------------------------------- 深色

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),

    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),

    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),

    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9),

    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),

    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F)
)

/**
 * 主题入口 —— 全 App 唯一的挂载点。
 *
 * 只做一件事：按系统深浅色选一套静态配色并交给 MaterialTheme。
 * 不做 typography / shapes 覆盖：M3 默认值本身就是官方规格，
 * 自定义反而容易偏离（这也是原 Miuix 实现未覆盖 typography 的同一判断）。
 */
@Composable
fun AppM3Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
