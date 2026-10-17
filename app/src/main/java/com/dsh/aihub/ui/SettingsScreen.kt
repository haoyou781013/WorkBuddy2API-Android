package com.dsh.aihub.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dsh.aihub.m3.SectionTitle

/**
 * 设置页 —— 分组入口式布局（性能优先版）。
 *
 * 【为什么改成分组入口】
 *   实测埋点数据：本页「主线程读数据库」仅 1ms，而「渲染完成」要 2612ms。
 *   卡顿与数据无关，全部来自组合/布局/绘制的组件开销。
 *   因此唯一有效的解法是【减少首屏需要实例化的组件数量】。
 *
 *   原布局首屏要建：3 张卡片 + 1 个 SwitchPreference + 10 个 ArrowPreference
 *   + 2 个 Button，约 16 个可交互组件，且 ArrowPreference 各自带图标与
 *   交互状态订阅。现布局首屏只有 8 行纯文本入口 + 1 个开关，
 *   其余全部下钻到二级页，由 LazyColumn 懒加载 —— 不在视口内的行不实例化。
 */
@Composable
fun SettingsScreen(context: Context, app: AppState, onOpenSub: (SubPage) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        // ── 分组原则：按「用户此刻想做什么」分，不按技术模块分 ──
        //
        // 旧版把 9 个二级页塞进「常用 / 自动化 / 数据与诊断」三组，
        // 问题在「数据与诊断」混装了四件不同的事：
        //   看用量（高频，关心数字）
        //   看记录（高频，关心明细）
        //   清空间（低频，出问题才来）
        //   抓包取证（极低频，排查上游不通）
        // 用户想清空间时得先在一堆用量入口里找，这就是分类不清的代价。

        item {
            // 服务层面的总开关。放在最前：用户进设置页，
            // 第一件事通常是确认服务是否按预期运行。
            PcCard(title = "运行状态") {
                SwitchRow(
                    title = "启用账号保活",
                    summary = "定时刷新令牌与额度，保持账号可用",
                    checked = app.settings.optString("keepalive_enabled", "1") in setOf("1", "true"),
                    onChange = { app.saveSetting("keepalive_enabled", if (it) "1" else "0") }
                )
                SwitchRow(
                    title = "允许局域网访问",
                    summary = "默认只监听 127.0.0.1（仅本机可调用）。开启后同一 WiFi 下的设备也能访问，"
                        + "请确认 API Key 未泄漏再打开",
                    checked = app.settings.optString("lanAccess", "0") in setOf("1", "true"),
                    onChange = { app.saveSetting("lanAccess", if (it) "1" else "0") }
                )
                // 保活与取证都回答同一个问题：「服务为什么不通」。
                // 前者查系统限制，后者查上游请求，排查时是连续动作。
                PcRow(
                    title = "后台保活",
                    summary = "按厂商逐步放开限制，并检查漏项",
                    value = "›",
                    onClick = { onOpenSub(SubPage.KEEPALIVE) }
                )
                PcRow(
                    title = "出网取证",
                    summary = "与官方 CLI 逐字节比对的请求记录",
                    value = "›",
                    onClick = { onOpenSub(SubPage.INSPECTOR) }
                )
            }
        }

        item {
            // 同一数据源的两个视角：聚合看趋势，明细看单次。
            // 放同一组是因为用户看用量时经常在两者间来回切。
            PcCard(title = "用量与记录") {
                PcRow(
                    title = "用量统计",
                    summary = "请求、Tokens 与额度概览",
                    value = "›",
                    onClick = { onOpenSub(SubPage.USAGE) }
                )
                PcRow(
                    title = "调用记录",
                    summary = "模型、扣费账号、Tokens 与积分消耗",
                    value = "›",
                    onClick = { onOpenSub(SubPage.RECORDS) }
                )
            }
        }

        item {
            // 这一组改的是「发给上游的请求长什么样」，与用量无关，
            // 所以从原来的「常用 / 自动化」里抽出来单独成组。
            PcCard(title = "行为配置") {
                PcRow(
                    title = "提示词注入",
                    summary = "接管 system / 指纹清洗 / 降级开关",
                    value = "›",
                    onClick = { onOpenSub(SubPage.PROMPT) }
                )
                // 定时参数决定签到与刷新在什么时间发起，同样是对上游行为的设定
                PcRow(
                    title = "定时参数",
                    summary = "签到时段 / 额度刷新 / 模型缓存 / 记录保留",
                    value = "›",
                    onClick = { onOpenSub(SubPage.SCHEDULE) }
                )
            }
        }

        item {
            // 维护类：低频、破坏性、出问题才来。
            // 单独成组而不是塞进「用量与记录」——那会让"清理"混在"查看"里，
            // 增加误触风险。
            PcCard(title = "维护") {
                PcRow(
                    title = "存储占用",
                    summary = "数据库体积、目录占用与清理",
                    value = "›",
                    onClick = { onOpenSub(SubPage.STORAGE) }
                )
            }
        }

        // 【M3 移植】原 Miuix 的 SmallTitle 换成 m3 包的 SectionTitle。
        item { SectionTitle(text = "关于") }
        item {
            PcCard {
                PcInfoRow("版本", installedVersion(context))
                PcInfoRow(
                    "监听地址",
                    if (app.settings.optString("lanAccess", "0") in setOf("1", "true"))
                        "0.0.0.0:8788（本机与局域网）"
                    else "127.0.0.1:8788（仅本机）"
                )
                // 更新入口放在「关于」而非独立 Tab：更新是低频操作，
                // 占一个底部导航位会稀释主功能入口的权重。
                // 用 PcRow（支持 onClick）而不是 PcInfoRow（纯展示）。
                PcRow(
                    title = "软件更新",
                    summary = "检查并安装新版本",
                    value = installedVersion(context),
                    onClick = { onOpenSub(SubPage.UPDATE) }
                )
                // 关于页承载 MIT 协议要求的原始项目署名，以及参考实现列表
                PcRow(
                    title = "关于",
                    summary = "版本、来源、开源协议",
                    onClick = { onOpenSub(SubPage.ABOUT) }
                )
            }
        }
    }
}

/** 轻量开关行：直接用 Switch，不经过 Preference 包装层。 */
@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = PcTokens.SpaceS / 2)) {
        // 【M3 移植】文本样式全部改取 Material 3 的 typography token：
        //   原 MiuixTheme.textStyles.main → bodyLarge；
        //   原 MiuixTheme.textStyles.body2 → bodySmall；
        //   原 MiuixTheme.colorScheme.onSurfaceVariantSummary → colorScheme.onSurfaceVariant。
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // 【M3 移植】Miuix 的 Switch 自带左侧留白与固定轨道配色；
        //   Material 3 的 Switch 轨道默认色对「已开启」状态区分度较低，
        //   这里显式传 SwitchDefaults.colors()，行为与默认一致，
        //   只是为了把「配色来自 M3 默认规格」这件事写明确。
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors()
        )
    }
}

/**
 * 读取本机已安装的版本名。
 *
 * 从 PackageManager 读而非 BuildConfig：本项目未开启 buildConfig 生成，
 * 且这样得到的是【设备上实际运行的版本】—— 排查"是否装上新版"时更可信。
 */
private fun installedVersion(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
    else @Suppress("DEPRECATION") info.versionCode.toLong()
    "${info.versionName} ($code)"
}.getOrDefault("未知")
