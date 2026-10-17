package com.dsh.aihub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 优先级设置对话框 —— 对应原界面的 showPriorityDialog。
 *
 * 为什么保留「步进 + 滑块 + 快捷档位」三条调节路径：
 * 优先级影响的是多账号之间的选号概率，用户常常需要 +1/−1 反复微调，
 * 只给滑块很难精确定位到某个整数；只给步进在大跨度调整时又太慢。
 *
 * 上限取值规则与原实现一致：以 max(默认上限, 当前值) 为上限，
 * 保证历史遗留的大值一定能往下调（否则用户会被"卡"在高位上无法降低）。
 *
 * 【M3 移植】原 Miuix 的 WindowDialog(show, title, summary, content) 换成
 * Material 3 的 AlertDialog。语义差异与处理方式：
 *   ① AlertDialog 没有 show 参数，是条件渲染（调用点 AccountsScreen 已在
 *      `if (showPriority)` 里调用本函数，所以这里直接渲染即可）；
 *   ② AlertDialog 的按钮是具名的 confirmButton/dismissButton，而不是自由布局，
 *      原先放在 content 底部、手写 weight(1f) 等分的「取消 / 保存」两个按钮
 *      改由这两个插槽承载（M3 规格本身就是按钮右对齐、由组件负责间距）；
 *   ③ title 之外的副标题（accountLabel）在 AlertDialog 没有对应插槽，
 *      并入 text 区域顶部，渲染顺序与视觉层级保持不变。
 */
@Composable
fun PcPriorityDialogContent(
    value: Int,
    onValueChange: (Int) -> Unit,
    accountLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val ceiling = 20
    val presets = listOf(0, 1, 2, 5, 10).filter { it <= ceiling }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置优先级") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // 副标题：原 WindowDialog 的 summary 参数，迁移后并入正文顶部。
                Text(
                    accountLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(PcTokens.SpaceS))
                // 数值面板：口径与后果写在一处，避免用户只看到数字不知道含义。
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "选号权重 ×${value + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(PcTokens.SpaceS))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { if (value > 0) onValueChange(value - 1) },
                            modifier = Modifier.weight(1f)
                        ) { Text("−") }
                        Text(
                            "$value",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { if (value < ceiling) onValueChange(value + 1) },
                            modifier = Modifier.weight(1f)
                        ) { Text("+") }
                    }
                }

                Spacer(Modifier.height(PcTokens.SpaceM))
                Slider(
                    value = value.toFloat(),
                    onValueChange = { onValueChange(it.toInt().coerceIn(0, ceiling)) },
                    valueRange = 0f..ceiling.toFloat(),
                    steps = ceiling - 1,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0 · 不加权", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("上限 $ceiling", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(PcTokens.SpaceS))
                Row(horizontalArrangement = Arrangement.spacedBy(PcTokens.SpaceS)) {
                    Text("快捷", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    presets.forEach { preset ->
                        TextButton(
                            onClick = { onValueChange(preset) }
                        ) { Text(if (preset == value) "● $preset" else "$preset") }
                    }
                }

                Spacer(Modifier.height(PcTokens.SpaceS))
                Text(
                    "只影响多账号之间的选号概率：账号额度、成功率与闲置时间仍照常参与加权。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
