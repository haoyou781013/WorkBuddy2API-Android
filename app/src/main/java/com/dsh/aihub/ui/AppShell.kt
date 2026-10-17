package com.dsh.aihub.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.dsh.aihub.m3.AppM3Theme
import com.dsh.aihub.m3.M3Icons

/**
 * 应用外壳 —— 全 App 唯一的页面容器与导航（Material 3 版）。
 *
 * 相对 Miuix 原版的改动仅在**外观层**，导航语义完全保留：
 *   ① 底部导航从 Miuix NavigationBar 换成 M3 NavigationBar + NavigationBarItem
 *   ② 顶栏从 SmallTopAppBar 换成 M3 CenterAlignedTopAppBar，返回按钮从文字变成图标
 *   ③ 图标从 MiuixIcons 换成 m3/M3Icons 内联图标集（零依赖、零资源）
 *   ④ 主题挂载点从 AppTheme（Miuix）换成 AppM3Theme
 *
 * 刻意保留的部分（原实现的判断仍然成立）：
 *   - Tab 数量与顺序（概览 / 账号 / 模型 / 应用 / 设置 / 成长）
 *   - 二级页仍不入 Tab，由 [SubPage] 状态切换：层级只有两级，
 *     引入 Navigation 组件的返回栈同步成本大于收益
 *   - 返回键分级策略：二级页 → 所属 Tab → 首页 → 交给系统退出
 *   - 「记录」仍留在设置页的二级入口（6 个 Tab 已够，再多会挤）
 *
 * 为什么不再需要 ProvideNavigationEventDispatcher：
 *   那是 Miuix 的弹层组件在 Compose Dialog 里取不到 NavigationEventDispatcherOwner
 *   才需要的补丁。Material 3 的 AlertDialog / ModalBottomSheet 走自己的窗口
 *   返回处理，不依赖该 owner，所以这个包裹层可以整体去掉。
 */

/**
 * 一级 Tab（底部导航）。
 */
enum class AppTab(val label: String, val icon: ImageVector) {
    OVERVIEW("概览", M3Icons.Overview),
    ACCOUNTS("账号", M3Icons.Accounts),
    MODELS("模型", M3Icons.Models),
    APPS("应用", M3Icons.Apps),
    SETTINGS("设置", M3Icons.Settings),
    GROWTH("成长", M3Icons.Growth)
}
// 图标用 m3/M3Icons 的 7 个内联 ImageVector，不引入任何 icons 依赖：
//   material-icons-extended 会给 APK 增加约 10MB（上万图标，本应用只需 7 个）；
//   material-icons-core 只含约 20 个图标，且在本项目里仅由 Miuix 传递引入——
//   脱离 Miuix 后该依赖也随之消失。内联 7 个 ImageVector 是零成本的确定解。

/** 二级页面（覆盖在 Tab 之上，带返回按钮）。 */
enum class SubPage { NONE, USAGE, INSPECTOR, STORAGE, KEEPALIVE, PROMPT, RECORDS, SCHEDULE }

/** 二级页标题映射，避免在 TopAppBar 里写 when 分支。 */
private fun SubPage.title(): String = when (this) {
    SubPage.USAGE -> "用量"
    SubPage.INSPECTOR -> "出网取证"
    SubPage.STORAGE -> "存储占用"
    SubPage.KEEPALIVE -> "后台保活"
    SubPage.PROMPT -> "提示词注入"
    SubPage.RECORDS -> "调用记录"
    SubPage.SCHEDULE -> "定时参数"
    SubPage.NONE -> ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(context: Context, app: AppState) {
    var tab by rememberSaveable { mutableStateOf(AppTab.OVERVIEW) }
    var sub by rememberSaveable { mutableStateOf(SubPage.NONE) }

    // 系统返回键（含全面屏手势）分级处理。
    //
    // Compose 页面本身不消费返回事件，不注册就会直接传到 Activity 默认实现（finish），
    // 表现为「在任何页面随手势返回都退出应用」。
    // 用 enabled 开关而不是在一个 handler 里分支：未启用的 handler 不参与返回栈，
    // 系统能正确判定「无处可退」并执行退出。
    BackHandler(enabled = sub != SubPage.NONE) { sub = SubPage.NONE }
    BackHandler(enabled = sub == SubPage.NONE && tab != AppTab.OVERVIEW) { tab = AppTab.OVERVIEW }

    AppM3Theme {
        Scaffold(
            topBar = {
                val title = if (sub == SubPage.NONE) tab.label else sub.title()
                CenterAlignedTopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        if (sub != SubPage.NONE) {
                            IconButton(onClick = { sub = SubPage.NONE }) {
                                Icon(
                                    imageVector = M3Icons.Back,
                                    contentDescription = "返回"
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors()
                    // 不设全局「刷新」按钮：各页面自身已有更贴合语境的刷新入口
                    // （概览的「刷新全部」、模型页的「刷新模型目录」、记录页的「刷新统计」、
                    // 账号页的「刷新全部账号额度」），顶栏再挂全局刷新属冗余。
                )
            },
            bottomBar = {
                if (sub == SubPage.NONE) {
                    NavigationBar {
                        AppTab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = {
                                    Icon(item.icon, contentDescription = item.label)
                                },
                                label = { Text(item.label) }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (sub != SubPage.NONE) {
                    when (sub) {
                        SubPage.USAGE -> UsageScreen(context, app)
                        SubPage.INSPECTOR -> InspectorScreen(context, app)
                        SubPage.STORAGE -> StorageScreen(context, app)
                        SubPage.KEEPALIVE -> KeepAliveScreen(context, app)
                        SubPage.PROMPT -> PromptScreen(context)
                        SubPage.RECORDS -> RecordsScreen(context, app)
                        SubPage.SCHEDULE -> ScheduleScreen(context, app)
                        SubPage.NONE -> Unit
                    }
                } else {
                    when (tab) {
                        AppTab.OVERVIEW -> OverviewScreen(context, app) { sub = it }
                        AppTab.ACCOUNTS -> AccountsScreen(context, app)
                        AppTab.MODELS -> ModelsScreen(context, app)
                        AppTab.APPS -> AppsScreen(context, app)
                        AppTab.SETTINGS -> SettingsScreen(context, app) { sub = it }
                        AppTab.GROWTH -> GrowthScreen(context, app.selectedAccount)
                    }
                }
            }
        }
    }
}
