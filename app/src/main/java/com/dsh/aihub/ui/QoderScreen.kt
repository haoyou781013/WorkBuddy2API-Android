package com.dsh.aihub.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dsh.aihub.qoder.QoderClaim
import com.dsh.aihub.qoder.QoderVerify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Qoder 协议自检页。
 *
 * 定位很明确：这是**验证工具**，不是正式功能。等协议确认无误后，
 * 这里会被真正的账号管理界面替代。
 *
 * 放在设置里而不是主流程：它现在只能告诉你「协议通不通」，
 * 还不能帮你用 Qoder 对话。先摆正预期，避免当成可用功能。
 */
@Composable
fun QoderScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pat by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<List<String>?>(null) }
    var claiming by remember { mutableStateOf(false) }
    var claimResult by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PcTokens.SpaceL)
    ) {
        Text("Qoder 协议验证", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(PcTokens.SpaceS))
        Text(
            "填入 Qoder 的个人访问令牌（PAT），点验证即可检查协议是否可用。\n" +
                "密码字段只用于本机校验，不会上传到任何第三方。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(PcTokens.SpaceM))

        OutlinedTextField(
            value = pat,
            onValueChange = { pat = it },
            label = { Text("Qoder PAT") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(PcTokens.SpaceM))

        Button(
            onClick = {
                running = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { QoderVerify.run(context, pat) }
                    report = r.lines
                    ok = r.ok
                    running = false
                }
            },
            enabled = !running && pat.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (running) "验证中…" else "开始验证") }

        Spacer(Modifier.height(PcTokens.SpaceM))
        Button(
            onClick = {
                claiming = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { QoderClaim(pat).claimAll() }
                    claimResult = r.message
                    claiming = false
                }
            },
            enabled = !claiming && pat.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (claiming) "领取中…" else "领取今日积分") }

        claimResult?.let {
            Spacer(Modifier.height(PcTokens.SpaceS))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        report?.let { lines ->
            Spacer(Modifier.height(PcTokens.SpaceL))
            PcCard(title = if (ok) "验证通过" else "验证未通过") {
                lines.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (line.startsWith("✓")) MaterialTheme.colorScheme.primary
                        else if (line.startsWith("✗")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}
