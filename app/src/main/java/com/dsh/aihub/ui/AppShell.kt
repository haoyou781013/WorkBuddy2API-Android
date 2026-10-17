package com.dsh.aihub.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.dsh.aihub.UpdateScreen
import com.dsh.aihub.AboutScreen

/**
 * 应用外壳 —— 全 App 唯一的页面容器与导航（Material 3 版）。
 *
 * 相对 Miuix 原版的改动仅在**外观层**，导航语义完全保留：
 *   ① 底部导航从 Miuix NavigationBar 换成自建 Row（M3 的 NavigationBarItem
 *      会把每格撑到屏宽/6、固定 80dp 高，纯文字 Tab 显得太空旷）
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
enum class SubPage { NONE, USAGE, INSPECTOR, STORAGE, KEEPALIVE, PROMPT, RECORDS, SCHEDULE, UPDATE, ABOUT }

/** 二级页标题映射，避免在 TopAppBar 里写 when 分支。 */
private fun SubPage.title(): String = when (this) {
    SubPage.USAGE -> "用量"
    SubPage.INSPECTOR -> "出网取证"
    SubPage.STORAGE -> "存储占用"
    SubPage.KEEPALIVE -> "后台保活"
    SubPage.PROMPT -> "提示词注入"
    SubPage.RECORDS -> "调用记录"
    SubPage.SCHEDULE -> "定时参数"
    SubPage.UPDATE -> "软件更新"
    SubPage.ABOUT -> "关于"
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
                    // 不用 M3 的 NavigationBarItem：它把每个 item 撑成
                    // 「屏宽/6 + 固定 80dp 高」，纯文字 Tab 会被拉开一大片空白。
                    //
                    // 1081dp 有效宽度下每格约 180dp，而一个两字标签只需
                    // 约 60dp —— 宽松感主要来自这里，不是 padding。
                    //
                    // 改为 Row + 等分 weight，自行控制高度与内边距。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppTab.entries.forEach { item ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { tab = item },
                                contentAlignment = Alignment.Center,
                            ) {
                                TabContent(item, selected = tab == item)
                            }
                        }
                    }
                }
            }
        ) { padding ->
            // 页面切换加过渡动画。
            //
            // 两个维度分开处理，因为语义不同：
            //   · Tab 之间切换 —— 同级水平切换。用「位移 + 淡入淡出」，
            //     方向由 Tab 索引差决定：往右切就内容往左滑，反之亦然。
            //   · 进入二级页 —— 层级加深。统一从右侧推入，返回时反向。
            //
            // 位移取屏宽的 1/5（不是整屏）：整屏滑动的动画时间会把
            // 内容拉伸成模糊条带，1/5 既有方向感又保持内容可辨。
            //
            // 用 AnimatedContent 而不是 Crossfade：Crossfade 只有透明度，
            // 快速连点 Tab 时看不出方向，用户会觉得"页面闪了一下"。
            val tabIndex = AppTab.entries.indexOf(tab)
            val prevTabIndex = remember { mutableIntStateOf(tabIndex) }
            val forward = tabIndex >= prevTabIndex.intValue
            LaunchedEffect(tabIndex) { prevTabIndex.intValue = tabIndex }

            AnimatedContent(
                targetState = if (sub != SubPage.NONE) "sub:${sub.name}" else "tab:${tab.name}",
                transitionSpec = {
                    val isSubEnter = targetState.startsWith("sub:")
                    val isSubExit = initialState.startsWith("sub:")

                    when {
                        // 进二级页 / 出二级页：横向推入推出
                        isSubEnter && !isSubExit -> {
                            (slideInHorizontally { it / 5 } + fadeIn()) togetherWith
                                (slideOutHorizontally { -it / 5 } + fadeOut())
                        }
                        isSubExit && !isSubEnter -> {
                            (slideInHorizontally { -it / 5 } + fadeIn()) togetherWith
                                (slideOutHorizontally { it / 5 } + fadeOut())
                        }
                        // Tab 之间：按索引方向滑
                        else -> {
                            val dir = if (forward) 1 else -1
                            (slideInHorizontally { dir * it / 5 } + fadeIn()) togetherWith
                                (slideOutHorizontally { -dir * it / 5 } + fadeOut())
                        }
                    }.apply {
                        targetContentZIndex = if (isSubEnter || forward) 1f else -1f
                    }
                },
                label = "page",
            ) { key ->
                Box(Modifier.fillMaxSize()) {
                    if (key.startsWith("sub:")) {
                        val page = SubPage.valueOf(key.removePrefix("sub:"))
                        when (page) {
                            SubPage.USAGE -> UsageScreen(context, app)
                            SubPage.INSPECTOR -> InspectorScreen(context, app)
                            SubPage.STORAGE -> StorageScreen(context, app)
                            SubPage.KEEPALIVE -> KeepAliveScreen(context, app)
                            SubPage.PROMPT -> PromptScreen(context)
                            SubPage.RECORDS -> RecordsScreen(context, app)
                            SubPage.SCHEDULE -> ScheduleScreen(context, app)
                            SubPage.UPDATE -> UpdateScreen(context, app)
                            SubPage.ABOUT -> AboutScreen(context) { sub = SubPage.UPDATE }
                            SubPage.NONE -> Unit
                        }
                    } else {
                        val page = AppTab.valueOf(key.removePrefix("tab:"))
                        when (page) {
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
}

/**
 * 底部栏单个 Tab 的视觉。
 *
 * 选中态用整体 Surface 承载标签，选中时整块高亮。
 * （M3 的 NavigationBarItem 只圈图标，文字会落在背景块外面。）
 *
 * 为什么是纯文字、没有图标：
 *   内联 ImageVector 在 release 包里始终画不出来。R8 裁剪、
 *   addPathNodes 解析、keep 规则都排查过 —— pathData 字符串确实
 *   进了 dex（7/7 存活），但 ImageVector 仍然不渲染。
 *   与其在这条「字符串 pathData → ImageVector」的路上继续耗时，
 *   不如直接用文字：6 个两字标签本身足够区分，也少一层依赖。
 */
@Composable
private fun TabContent(tab: AppTab, selected: Boolean) {
    val fg = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(
            text = tab.label,
            // labelLarge（14sp）：没有图标之后文字要自己撑住 Tab，
            // labelSmall 会显得单薄。6 个两字标签在 14sp 下仍放得下。
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            // 纯文字 Tab 若只有字高，点击区会太窄，这里补足触摸高度。
            // 横向 16dp 足够容纳两字标签；纵向 7dp 让整体高度落在
            // 36dp 左右（14sp 字高 ~18dp + 14dp padding），
            // 比 M3 默认的 80dp 矮一半，视觉上紧凑得多。
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
        )
    }
}
