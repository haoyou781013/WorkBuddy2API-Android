package com.dsh.aihub

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dsh.aihub.m3.AppCard
import com.dsh.aihub.m3.LabelMain
import com.dsh.aihub.m3.LabelSummary
import com.dsh.aihub.m3.SectionTitle
import com.dsh.aihub.m3.SpaceL
import com.dsh.aihub.m3.SpaceM
import com.dsh.aihub.m3.SpaceS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 软件更新页。
 *
 * 状态机刻意写成扁平的几个 Boolean 而不是密封类：这个页面的状态组合实际很少
 * （检查中 / 有更新 / 无更新 / 出错 / 下载中），用密封类会让 Compose 的
 * 状态恢复和预览都变复杂，收益不抵成本。
 */
@Composable
fun UpdateScreen(context: Context) {
    val scope = rememberCoroutineScope()
    val current = remember { UpdateChecker.currentVersion(context) }

    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateChecker.Result?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    var progressDone by remember { mutableStateOf(0L) }
    var progressTotal by remember { mutableStateOf(0L) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }

    fun runCheck() {
        if (checking) return
        checking = true; error = null; status = null; downloadedApk = null
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { UpdateChecker.check(context) }
                result = r
                status = if (r.newer) "发现新版本 ${r.latestName}" else "已是最新版本"
            } catch (e: UpdateChecker.CheckError) {
                error = e.message
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                checking = false
            }
        }
    }

    fun runDownloadAndInstall(r: UpdateChecker.Result) {
        val url = r.apkUrl
        if (url == null) { error = "该版本未提供 APK 附件"; return }
        if (downloading) return
        downloading = true; error = null; status = "开始下载…"
        progressDone = 0L; progressTotal = r.apkSize
        scope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    UpdateChecker.download(context, url) { done, total ->
                        progressDone = done
                        if (total > 0) progressTotal = total
                    }
                }
                downloadedApk = apk
                status = "下载完成，可以安装了"
                // 下载完直接拉起安装器；失败原因交给 catch 展示
                withContext(Dispatchers.Main) {
                    runCatching { UpdateChecker.install(context, apk) }
                        .onFailure { error = it.message }
                }
            } catch (e: UpdateChecker.CheckError) {
                error = e.message
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                downloading = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SpaceL)
            .padding(bottom = SpaceL)
    ) {
        SectionTitle(text = "当前版本")
        AppCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    LabelMain("AIHub $current")
                    LabelSummary("发布渠道：GitHub Releases")
                }
                if (checking) {
                    CircularProgressIndicator(Modifier.height(24.dp), strokeWidth = 2.dp)
                } else {
                    Button(onClick = { runCheck() }) { Text("检查更新") }
                }
            }
        }

        status?.let {
            Spacer(Modifier.height(SpaceM))
            AppCard { LabelMain(it) }
        }

        error?.let {
            Spacer(Modifier.height(SpaceM))
            AppCard {
                Text("出错", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(SpaceS))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // 下载进度：只在下载中或有总量时显示进度条
        if (downloading) {
            Spacer(Modifier.height(SpaceM))
            AppCard {
                LabelMain("正在下载")
                LabelSummary(formatBytes(progressDone) +
                    if (progressTotal > 0) " / ${formatBytes(progressTotal)}" else "")
                Spacer(Modifier.height(SpaceS))
                if (progressTotal > 0) {
                    LinearProgressIndicator(
                        progress = { (progressDone.toFloat() / progressTotal).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }

        val r = result
        if (r != null && r.newer) {
            Spacer(Modifier.height(SpaceM))
            SectionTitle(text = "新版本 ${r.latestName}")
            AppCard {
                if (r.notes.isNotBlank()) {
                    Text(
                        r.notes.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.height(240.dp).verticalScroll(rememberScrollState())
                    )
                    Spacer(Modifier.height(SpaceM))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(SpaceM)) {
                    Button(
                        onClick = { runDownloadAndInstall(r) },
                        enabled = !downloading
                    ) { Text(if (downloading) "下载中…" else "下载并安装") }

                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(r.releaseUrl)
                                    )
                                )
                            }
                        }
                    ) { Text("查看发布页") }
                }
                downloadedApk?.let { apk ->
                    Spacer(Modifier.height(SpaceM))
                    OutlinedButton(
                        onClick = {
                            runCatching { UpdateChecker.install(context, apk) }
                                .onFailure { error = it.message }
                        }
                    ) { Text("重新安装") }
                }
            }
        }

        Spacer(Modifier.height(SpaceL))
        Text(
            "更新从 GitHub Releases 获取。若网络不通，可点「查看发布页」手动下载安装。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 字节数转可读文本。只用于展示，精度到 0.1 单位即可。 */
private fun formatBytes(n: Long): String = when {
    n >= 1_048_576 -> "%.1f MB".format(n / 1_048_576.0)
    n >= 1024 -> "%.0f KB".format(n / 1024.0)
    else -> "$n B"
}
