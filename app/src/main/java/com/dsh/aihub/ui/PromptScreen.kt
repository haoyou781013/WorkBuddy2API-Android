package com.dsh.aihub.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.aihub.GrowthFacade
import com.dsh.aihub.PromptInjection
import com.dsh.aihub.m3.LabelMain
import com.dsh.aihub.m3.LabelSummary
import com.dsh.aihub.m3.SectionTitle

/**
 * 提示词注入设置页 —— 对应 1.2.7 的「提示词」配置块（prompt_mode / prompt_text /
 * sanitize_fingerprints / use_degraded_prompt）。
 *
 * 关键交互说明：
 *   - 模式用 TabRow 二选一（custom / passthrough），不做成 Switch —— 因为
 *     「原样透传」与「接管 system」是互斥的两种语义，而不是一个开关；
 *   - 降级开关只读展示 + 一键恢复：1.2.7 的降级是【自动触发】的（内容被拦时），
 *     让用户能手动关掉它（说明误伤已排除），但不鼓励平时打开。
 *
 * 【M3 移植】
 *   Miuix 依赖已全部替换为 Material 3：TabRow / Tab、Switch、OutlinedTextField、
 *   Button、Text 与 MaterialTheme 主题 token。开关类配置沿用契约 §4.2 的
 *   「AppRow 等价布局」——本页需要让 Switch 与多行说明并排，所以直接写 Row 组合。
 *   业务逻辑（GrowthFacade 调用、状态与回调）逐字未动。
 */
@Composable
fun PromptScreen(context: Context) {
    val state = rememberTextFieldState()
    var modeIndex by remember { mutableStateOf(0) }
    var sanitize by remember { mutableStateOf(true) }
    var degraded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf("") }

    // 【M3 移植】UI 层镜像一份文本：Miuix 的 TextField 能直接吃 TextFieldState，
    //   而 Material 3 1.3.2 的 OutlinedTextField 只有 String / TextFieldValue 两种入参
    //   （无 TextFieldState 重载）。为不动业务逻辑，state 仍是唯一事实来源：
    //   输入经 onValueChange 写回 state，state 的编辑结果再镜像回 text 供输入框与
    //   字符计数显示。用户正常键入时两者恒等；「载入默认 / 还原默认」只改 state，
    //   由 LaunchedEffect 同步回 text，避免两处各自维护文本而产生分叉。
    var text by remember { mutableStateOf(state.text.toString()) }
    LaunchedEffect(state.text.toString()) {
        val current = state.text.toString()
        if (current != text) text = current
    }

    // 进入页面时载入现有配置
    LaunchedEffect(Unit) {
        val settings = GrowthFacade.promptSettings(context)
        modeIndex = if (settings.optString("prompt_mode") == "passthrough") 1 else 0
        sanitize = settings.optString("sanitize_fingerprints", "1") !in setOf("0", "false")
        degraded = settings.optBoolean("use_degraded_prompt", false)
        state.edit { replace(0, state.text.length, settings.optString("prompt_text")) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.SpaceS)
    ) {
        item {
            PcCard(title = "模式") {
                // 【M3 移植】Miuix 的 TabRow 是「tabs 列表 + selectedTabIndex + onTabSelected」，
                //   Material3 的 TabRow 则是内容槽（尾随 lambda 里自己放 Tab）。
                //   语义等价：选中索引仍由 modeIndex 驱动，点击回调逐项对应。
                val modeTabs = listOf("接管 system", "原样透传")
                TabRow(selectedTabIndex = modeIndex) {
                    modeTabs.forEachIndexed { index, label ->
                        Tab(
                            selected = modeIndex == index,
                            onClick = { modeIndex = index },
                            text = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(PcTokens.SpaceS))
                Text(
                    if (modeIndex == 0) {
                        "会把客户端传来的 system / developer 消息全部替换为下方的提示词，" +
                            "再插到 messages 首位。适用于希望统一上游人设、避免暴露第三方客户端身份的场景。"
                    } else {
                        "完全不改动 messages，客户端发什么就传什么。" +
                            "此时指纹清洗仍然生效（两者是独立开关）。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            PcCard(title = "提示词内容") {
                // 【M3 移植】原 Miuix TextField 的 label = "system 提示词" 与
                //   useLabelAsPlaceholder = true，对应 M3 的 label 插槽（label 同时充当
                //   未输入时的占位）；M3 无 useLabelAsPlaceholder 参数，该语义是默认行为。
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        state.edit { replace(0, state.text.length, it) }
                    },
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    label = { Text("system 提示词") }
                )
                Spacer(Modifier.height(PcTokens.SpaceM))
                // 「载入默认」占满整行、字符数提示单独一行：
                // 此前两者同处一个 Row，按钮用默认宽度 + 文本用 weight，
                // 导致按钮被压成窄条、文字贴着按钮，视觉上互相挤压。
                Button(
                    onClick = {
                        val text = GrowthFacade.promptSettings(context)
                            .optString("prompt_text")
                            .ifBlank { PromptInjection.DEFAULT_PROMPT }
                        state.edit { replace(0, state.text.length, text) }
                        status = "已载入默认提示词，记得点「保存配置」"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("载入默认提示词") }
                Spacer(Modifier.height(PcTokens.SpaceS))
                Text(
                    "当前输入 ${state.text.length} 字符" +
                        if (state.text.isBlank()) "（留空则使用内置默认提示词）" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            PcCard(title = "指纹清洗") {
                // 【M3 移植】Miuix SwitchPreference → Row(标题 + 说明) + material3.Switch，
                //   与契约 §4.2 的等价布局一致；文案、状态读写与回调签名均未改动。
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = PcTokens.SpaceS),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        LabelMain("清洗客户端指纹")
                        LabelSummary(
                            "从对话内容里去掉第三方客户端痕迹（billing 头、cc_ 键值、模板句、裸 11128）。" +
                                "命中这些特征时上游可能直接拦掉整条请求，建议保持开启。",
                            Modifier.padding(top = 2.dp)
                        )
                    }
                    Switch(checked = sanitize, onCheckedChange = { sanitize = it })
                }
                Spacer(Modifier.height(PcTokens.SpaceS))
                Text("试一下清洗效果：", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(PcTokens.SpaceS / 2))
                Button(onClick = {
                    val sample = "You are a helpful software engineering assistant\n" +
                        "\n" +
                        "one-to-many"
                    preview = GrowthFacade.previewSanitize(sample)
                }) { Text("预览清洗结果") }
                if (preview.isNotEmpty()) {
                    Spacer(Modifier.height(PcTokens.SpaceS))
                    Text(preview, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        item {
            PcCard(title = "降级模式") {
                // 【M3 移植】同上：SwitchPreference → Row + Switch。
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = PcTokens.SpaceS),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        LabelMain("使用降级提示词")
                        LabelSummary(
                            "上游返回内容拦截时系统会自动开启，直到当天午夜。若确认是误伤，" +
                                "可在这里手动关闭。",
                            Modifier.padding(top = 2.dp)
                        )
                    }
                    Switch(
                        checked = degraded,
                        onCheckedChange = { value ->
                            degraded = value
                            GrowthFacade.savePromptSettings(context,
                                org.json.JSONObject().put("use_degraded_prompt", value))
                            status = if (value) "已开启降级：将使用通用英文提示词" else "已恢复自定义提示词"
                        }
                    )
                }
            }
        }

        item {
            PcCard(title = "保存") {
                // 统一按钮组：等宽 + 固定间距（此前两个按钮紧贴，且行内没有间距）。
                PcActionGrid(
                    actions = listOf(
                        PcAction("保存配置", primary = true) {
                            val payload = org.json.JSONObject()
                                .put("prompt_mode", if (modeIndex == 0) "custom" else "passthrough")
                                .put("prompt_text", state.text.toString())
                                .put("sanitize_fingerprints", if (sanitize) "1" else "0")
                            GrowthFacade.savePromptSettings(context, payload)
                            // 保存后回读一次，以库里的真实值作为反馈依据。
                            // 之前只提示"已保存"而不校验，掩盖了「白名单过滤导致写入被丢弃」
                            // 的问题 —— 用户会以为存上了，实际每次都在用内置提示词。
                            val saved = GrowthFacade.promptSettings(context)
                            // 注意：`x == if (c) a else b` 在 Kotlin 中会被解析成
                            // `(x == if (c) a else b)` 之外的歧义，必须给 if 加括号。
                            val same = saved.optString("prompt_text") == state.text.toString() &&
                                saved.optString("prompt_mode") == (if (modeIndex == 0) "custom" else "passthrough") &&
                                saved.optString("sanitize_fingerprints") == (if (sanitize) "1" else "0")
                            status = if (same) "已保存并生效（${state.text.length} 字符）"
                            else "保存后校验不一致，请重试"
                        },
                        PcAction("还原默认") {
                            val defaults = PromptInjection.defaultSettings()
                            GrowthFacade.savePromptSettings(context, defaults)
                            modeIndex = 0
                            sanitize = true
                            degraded = false
                            state.edit { replace(0, state.text.length, defaults.optString("prompt_text")) }
                            status = "已还原为 1.2.7 默认配置"
                        }
                    )
                )
                if (status.isNotEmpty()) {
                    Spacer(Modifier.height(PcTokens.SpaceM))
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 【M3 移植】原 Miuix 的 SmallTitle 换成 m3 包的 SectionTitle。
        item { SectionTitle(text = "生效范围") }
        item {
            PcCard {
                Text(
                    "上述配置对本机网关的所有协议（OpenAI / Anthropic / Responses）统一生效，" +
                        "在请求转发到上游之前处理一次。修改后无需重启服务，下一条请求即生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
