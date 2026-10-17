package com.dsh.aihub

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.dsh.aihub.ui.AppState
import com.dsh.aihub.ui.PcRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 软件更新页（沿用 HY_VQ 的信息布局）。
 *
 * 与"只有一个检查按钮"的做法不同，这里扫描全部版本并列出，
 * 用户可以装任意历史版本——需要回退时不必先卸载。
 */
@Composable
fun UpdateScreen(context: Context, app: AppState) {
    // 更新通道来自设置。"stable" 只看正式版，"beta" 含预发布。
    val isBeta = app.settings.optString("updateChannel", "stable") == "beta"
    val scope = rememberCoroutineScope()
    val current = remember { UpdateChecker.currentVersion(context) }

    var scanning by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf<UpdateChecker.Scan?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var showHistory by remember { mutableStateOf(false) }

    var progressDone by remember { mutableStateOf(0L) }
    var progressTotal by remember { mutableStateOf(0L) }
    var targetTag by remember { mutableStateOf<String?>(null) }

    // 已下载但未安装的包，可在重进页面时直接点安装
    var cached by remember { mutableStateOf(UpdateChecker.cachedApks(context).firstOrNull()) }

    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    fun runScan() {
        if (scanning) return
        scanning = true; error = null; status = "正在获取版本列表…"
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { UpdateChecker.scan(context) }
                scan = r
                val cand = r.newerIn(current, isBeta)
                status = when {
                    cand != null -> "发现新版本 ${cand.version}"
                    r.channel(isBeta).isEmpty() -> "仓库暂无可用版本"
                    else -> "已是最新版本 · " +
                        "${if (isBeta) "测试通道" else "正式通道"} " +
                        "${r.channel(isBeta).size} 个版本"
                }
            } catch (e: UpdateChecker.CheckError) {
                error = e.message; status = null
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName; status = null
            } finally {
                scanning = false
            }
        }
    }

    fun downloadAndInstall(r: UpdateChecker.Release) {
        if (downloading) return
        downloading = true; error = null; targetTag = r.tag
        progressDone = 0L; progressTotal = r.apkSize
        status = "开始下载 ${r.version}…"
        scope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    UpdateChecker.download(context, r) { d, t ->
                        progressDone = d
                        if (t > 0) progressTotal = t
                    }
                }
                cached = apk
                status = "下载完成，正在调起安装…"
                withContext(Dispatchers.Main) {
                    runCatching { UpdateChecker.install(context, apk) }
                        .onFailure { error = it.message }
                }
            } catch (e: UpdateChecker.CheckError) {
                error = e.message; status = null
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName; status = null
            } finally {
                downloading = false; targetTag = null
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
                    LabelSummary(
                        if (isBeta) "测试通道 · 含预发布版本"
                        else "正式通道 · 仅稳定版"
                    )
                }
                if (scanning) {
                    CircularProgressIndicator(Modifier.height(24.dp), strokeWidth = 2.dp)
                } else {
                    Button(onClick = { runScan() }) { Text("扫描版本") }
                }
            }
        }

        // 通道切换。默认正式：预发布版本未经充分验证，不该默认推给所有人。
        // 切到测试通道要重新扫描，否则列表仍是上一个通道的结果。
        Spacer(Modifier.height(SpaceM))
        AppCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    LabelMain("接收测试版")
                    LabelSummary(
                        if (isBeta) "会提示预发布版本，可能不稳定；可用于提前试用与回退验证"
                        else "只提示正式版。测试版需在此手动开启"
                    )
                }
                Switch(
                    checked = isBeta,
                    onCheckedChange = { on ->
                        app.saveSetting("updateChannel", if (on) "beta" else "stable")
                        // 切换通道后旧列表口径已失效，清掉并让用户重扫，
                        // 避免"开了测试版却还是看不到"的困惑
                        scan = null
                        status = "通道已切换为${if (on) "测试版" else "正式版"}，请重新扫描"
                    }
                )
            }
        }

        // 已下载未安装：优先展示，避免用户重复下载
        cached?.let { apk ->
            Spacer(Modifier.height(SpaceM))
            AppCard {
                LabelMain("已下载待安装")
                LabelSummary("${apk.name} · ${formatBytes(apk.length())}")
                Spacer(Modifier.height(SpaceS))
                Row(horizontalArrangement = Arrangement.spacedBy(SpaceM)) {
                    Button(onClick = {
                        runCatching { UpdateChecker.install(context, apk) }
                            .onFailure { error = it.message }
                    }) { Text("安装") }
                    OutlinedButton(onClick = {
                        runCatching { apk.delete() }
                        cached = UpdateChecker.cachedApks(context).firstOrNull()
                    }) { Text("删除") }
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
                Text("出错", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(SpaceS))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (downloading) {
            Spacer(Modifier.height(SpaceM))
            AppCard {
                LabelMain("正在下载 ${targetTag ?: ""}")
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

        val s = scan
        if (s != null && s.all.isNotEmpty()) {
            val newest = s.newerIn(current, isBeta)
            val hasNew = newest != null

            if (newest != null && hasNew) {
                Spacer(Modifier.height(SpaceM))
                SectionTitle(text = "最新版本 ${newest.version}")
                AppCard {
                    if (newest.notes.isNotBlank()) {
                        Text(
                            newest.notes.trim(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.height(240.dp).verticalScroll(rememberScrollState())
                        )
                        Spacer(Modifier.height(SpaceM))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(SpaceM)) {
                        Button(
                            onClick = { downloadAndInstall(newest) },
                            enabled = !downloading
                        ) { Text(if (downloading) "下载中…" else "下载并安装") }
                        OutlinedButton(onClick = { openUrl(newest.pageUrl) }) { Text("发布页") }
                    }
                }
            }

            Spacer(Modifier.height(SpaceM))
            TextButton(onClick = { showHistory = !showHistory }) {
                Text(
                    if (showHistory) "收起历史版本"
                    else "查看历史版本（${s.channel(isBeta).size}）"
                )
            }
            if (showHistory) {
                AppCard {
                    s.channel(isBeta).forEach { r ->
                        val isCur = UpdateChecker.compareSemver(r.tag, current) == 0
                        PcRow(
                            title = "v${r.version}" + if (r.prerelease) "（预发布）" else "",
                            summary = if (isCur) "当前已安装" else r.publishedAt.take(10),
                            value = if (isCur) null else formatBytes(r.apkSize),
                            onClick = if (isCur || downloading) null else {
                                { downloadAndInstall(r) }
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(SpaceL))
        Text(
            "下载失败会自动尝试镜像源。所有版本均可自由安装或回退，" +
                "无需先卸载当前版本。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatBytes(n: Long): String = when {
    n >= 1_048_576 -> "%.1f MB".format(n / 1_048_576.0)
    n >= 1024 -> "%.0f KB".format(n / 1024.0)
    else -> "$n B"
}
