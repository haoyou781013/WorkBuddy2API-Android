package com.dsh.aihub.ui

import androidx.compose.runtime.Composable
import com.dsh.aihub.m3.AppM3Theme

/**
 * 主题入口 —— 全 App 唯一的主题挂载点。
 *
 * 【M3 移植】原实现直接挂 MiuixTheme，现在改为转发到 m3/AppM3Theme。
 *   之所以保留这个壳而不是让调用方直接用 AppM3Theme：
 *   AppShell 等文件已引用 AppTheme，保留同名入口可以让移植改动集中在
 *   组件替换本身，不必顺带改调用点（减少无关 diff）。
 *
 * 为什么用「静态配色」而不是 ThemeController + Monet 动态取色：
 * 本 App 是本地网关工具，界面只在用户主动打开时看几眼，不需要跟随壁纸变色；
 * 而 Monet 需要 Android 12+ 且会引入额外的资源解析路径（沙箱实测在低版本
 * 回落时偶发首帧透明）。静态配色行为完全确定，且浅深两套已覆盖需求。
 *
 * 【M3 移植】这条判断与具体组件库无关，在 Material 3 下同样成立，
 * 所以 AppM3Theme 依然不接 dynamicColorScheme()。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    AppM3Theme(content = content)
}
