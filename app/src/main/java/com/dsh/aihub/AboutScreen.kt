package com.dsh.aihub

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dsh.aihub.m3.AppCard
import com.dsh.aihub.m3.LabelMain
import com.dsh.aihub.m3.LabelSummary
import com.dsh.aihub.ui.PcRow
import com.dsh.aihub.m3.SectionTitle
import com.dsh.aihub.m3.SpaceL
import com.dsh.aihub.m3.SpaceM

/**
 * 关于页：版本、来源署名、开源协议。
 *
 * 署名不是可选项：本项目按 MIT 协议派生自 jilin0105/WorkBuddy2API-Android，
 * 协议要求在所有副本中保留原始版权声明。放在应用内（而非只在 README）
 * 是因为用户拿到的是 APK，看不到仓库。
 */
@Composable
fun AboutScreen(context: Context, onOpenUpdate: () -> Unit) {
    val version = UpdateChecker.currentVersion(context)

    fun open(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SpaceL)
            .padding(bottom = SpaceL)
    ) {
        SectionTitle(text = "版本")
        AppCard {
            PcRow(title = "AIHub", value = version, summary = "Material 3 重构版")
            PcRow(
                title = "软件更新",
                summary = "检查并安装新版本",
                onClick = onOpenUpdate
            )
        }

        Spacer(Modifier.height(SpaceM))
        SectionTitle(text = "来源")
        AppCard {
            // MIT 协议要求：必须列出原始来源
            PcRow(
                title = "原项目",
                summary = Attribution.UPSTREAM_NAME,
                onClick = { open(Attribution.UPSTREAM_REPO) }
            )
            PcRow(
                title = "本项目",
                summary = "haoyou781013/WorkBuddy2API-Android",
                onClick = { open(Attribution.SELF_REPO) }
            )
        }

        Spacer(Modifier.height(SpaceM))
        SectionTitle(text = "参考实现")
        AppCard {
            Attribution.REFERENCES.forEach { (name, url) ->
                PcRow(title = name, onClick = { open(url) })
            }
        }

        Spacer(Modifier.height(SpaceM))
        SectionTitle(text = "开源协议")
        AppCard {
            LabelMain(Attribution.LICENSE)
            Spacer(Modifier.height(4.dp))
            LabelSummary(Attribution.COPYRIGHT)
            Spacer(Modifier.height(4.dp))
            LabelSummary(
                "本应用基于上述原项目派生，并按 MIT 协议保留原始版权声明。" +
                    "原项目及其上游的著作权归各自作者所有。"
            )
        }

        Spacer(Modifier.height(SpaceL))
        Text(
            "本应用仅作为本地 API 网关使用，不隶属于任何上游服务提供方。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
