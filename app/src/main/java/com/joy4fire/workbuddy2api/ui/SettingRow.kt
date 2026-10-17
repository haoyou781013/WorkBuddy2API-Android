package com.joy4fire.workbuddy2api.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * 设置项：点击弹窗编辑。
 *
 * 【为什么不再把 TextField 直接放在设置页里】
 *   Miuix 的 TextField 内部每个实例都会跑 4 个动画
 *   （边框宽度 animateDpAsState、边框颜色 animateColorAsState、
 *   标签位移、标签字号各一个 animateDpAsState）。
 *   设置页原先首屏放了 5 个 TextField，等于首次组合时要同时创建并启动
 *   20 个 Animatable 与对应协程 —— 这是"点设置要等 3 秒"的直接原因
 *   （其它 Tab 没有文本输入框，所以不卡，与内存无关）。
 *
 *   现在首屏只渲染一行只读文本 + 一个箭头，TextField 仅在用户点击后
 *   于弹窗内创建，代价从"每次进页面"变成"每次编辑一项"，且只建 1 个。
 *
 *   【M3 移植补充】这个「延迟创建 TextField」的结论在 Material 3 下依然成立 ——
 *   M3 的 OutlinedTextField 同样带标签位移动画与边框颜色动画（数量级相当），
 *   所以不能因为换了组件库就把 TextField 放回首屏。原判断与具体 UI 库无关，
 *   它针对的是「Compose 组合期的动画实例数量」这件事本身。
 */
@Composable
fun SettingRow(
    label: String,
    value: String,
    hint: String? = null,
    onCommit: (String) -> Unit
) {
    var editing by remember { mutableStateOf(false) }

    PcRow(
        title = label,
        summary = hint,
        value = value.ifBlank { "未设置" },
        onClick = { editing = true }
    )

    if (editing) {
        SettingEditDialog(
            label = label,
            initial = value,
            hint = hint,
            onDismiss = { editing = false },
            onConfirm = {
                onCommit(it)
                editing = false
            }
        )
    }
}

/** 单项设置编辑弹窗（只在这里创建唯一的那个 TextField）。 */
@Composable
private fun SettingEditDialog(
    label: String,
    initial: String,
    hint: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    // 用状态持有输入值：弹窗关闭即随组合销毁，不需要额外的清理。
    var text by remember { mutableStateOf(initial) }

    // 【M3 移植】Miuix 的 WindowDialog(show, title, content) 换成 M3 的 AlertDialog。
    //   两个语义差异，已在下面各自处理：
    //   ① AlertDialog 没有 show 参数，靠外层 if (editing) 控制渲染 —— 调用点本来
    //      就在 SettingRow 的 if (editing) 里，所以不需要额外包一层。
    //   ② AlertDialog 的按钮是具名的 confirmButton/dismissButton 而不是自由布局。
    //      原实现把「取消 + 保存」放在 content 的 Row 里并手写 weight(1f) 平分宽度，
    //      移植后交给具名插槽：M3 规格本身就是按钮右对齐、由组件负责间距，
    //      比手写等分更贴规范，也少一处布局代码。
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                if (hint != null) {
                    Spacer(Modifier.height(PcTokens.SpaceS))
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
