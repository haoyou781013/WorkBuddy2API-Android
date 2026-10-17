package com.dsh.aihub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Release 更新日志的轻量 Markdown 渲染。
 *
 * 为什么自己写而不引 Markdown 库：
 *   完整的 CommonMark 解析器（如 commonmark-java）会带来数百 KB 与
 *   一套 HTML→Compose 的转换层，而更新日志实际只用到六七种语法。
 *   为一个只显示几十行文字的面板引入整条渲染管线，性价比不成立。
 *
 * 支持的语法（覆盖 GitHub Release 的常见写法）：
 *   `#` ~ `###`   标题
 *   `-` / `*`     无序列表（含一层缩进）
 *   `1.`          有序列表
 *   `**粗体**`    `*斜体*`    `` `行内代码` ``
 *   ```           代码块（原样等宽显示）
 *   `>`           引用
 *   `---`         分隔线
 *   空行          段落间距
 *
 * 不支持：表格、图片、任务列表、嵌套引用。遇到就当普通文本渲染，
 * 不会崩，只是不美化——比引入完整解析器更可控。
 */
@Composable
fun ReleaseNotes(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parseBlocks(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block -> RenderBlock(block) }
    }
}

// ------------------------------------------------------------------ 解析

private sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class Bullet(val indent: Int, val text: String) : Block
    data class Numbered(val number: String, val text: String) : Block
    data class Code(val text: String) : Block
    data class Quote(val text: String) : Block
    data class Table(val header: List<String>, val rows: List<List<String>>) : Block
    data object Divider : Block
}

private fun parseBlocks(md: String): List<Block> {
    val out = ArrayList<Block>()
    val lines = md.replace("\r\n", "\n").split('\n')
    var i = 0

    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()

        when {
            // 代码块：整段原样收，直到配对的 ```
            line.trimStart().startsWith("```") -> {
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    body.appendLine(lines[i])
                    i++
                }
                if (i < lines.size) i++   // 跳过收尾的 ```
                out.add(Block.Code(body.toString().trimEnd()))
            }

            line.isBlank() -> i++

            // 表格：以 | 开头，且第二行是 |---|---| 分隔行
            line.trimStart().startsWith("|") && i + 1 < lines.size &&
                REGEX_TABLE_SEP.matches(lines[i + 1].trim()) -> {
                val header = splitRow(line)
                i += 2
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    rows.add(splitRow(lines[i]))
                    i++
                }
                out.add(Block.Table(header, rows))
            }

            line.trim() in setOf("---", "***", "___") -> {
                out.add(Block.Divider); i++
            }

            // 标题：# 的个数决定层级，最多到 4
            line.trimStart().startsWith("#") -> {
                val hashes = line.trimStart().takeWhile { it == '#' }.length
                val text = line.trimStart().drop(hashes).trim()
                if (hashes in 1..6 && text.isNotEmpty()) {
                    out.add(Block.Heading(minOf(hashes, 4), text))
                } else {
                    out.add(Block.Paragraph(line.trim()))
                }
                i++
            }

            line.trimStart().startsWith(">") -> {
                out.add(Block.Quote(line.trimStart().removePrefix(">").trim())); i++
            }

            // 有序列表：容忍 "1." 与 "1、"
            REGEX_ORDERED.find(line.trimStart()) != null -> {
                val m = REGEX_ORDERED.find(line.trimStart())!!
                out.add(Block.Numbered(m.groupValues[1], m.groupValues[2].trim())); i++
            }

            // 无序列表：缩进按 2 空格一级
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                val indent = (raw.length - raw.trimStart().length) / 2
                out.add(Block.Bullet(indent.coerceAtMost(2), line.trimStart().drop(2).trim()))
                i++
            }

            else -> {
                // 连续非空行合并成一个段落，避免每行都撑开间距
                val para = StringBuilder(line.trim())
                i++
                while (i < lines.size) {
                    val next = lines[i]
                    if (next.isBlank() || next.trimStart().startsWith("#") ||
                        next.trimStart().startsWith("- ") || next.trimStart().startsWith("* ") ||
                        next.trimStart().startsWith(">") || next.trimStart().startsWith("```") ||
                        next.trimStart().startsWith("|") ||   // 表格起始，不能吞进段落
                        REGEX_ORDERED.find(next.trimStart()) != null
                    ) break
                    para.append(' ').append(next.trim())
                    i++
                }
                out.add(Block.Paragraph(para.toString()))
            }
        }
    }
    return out
}

private val REGEX_ORDERED = Regex("""^(\d{1,2})[.、]\s+(.*)$""")

/** 表格分隔行：|---|---| 或 |:--|--:| */
private val REGEX_TABLE_SEP = Regex("""^\|?[\s:-]*-[\s:|-]*\|?$""")

/** 按 | 切分表格行，去掉首尾空单元格 */
private fun splitRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

// ------------------------------------------------------------------ 渲染

@Composable
private fun RenderBlock(block: Block) {
    when (block) {
        is Block.Heading -> {
            if (block.level > 1) Spacer(Modifier.height(4.dp))
            Text(
                text = inline(block.text),
                style = when (block.level) {
                    1 -> MaterialTheme.typography.titleMedium
                    2 -> MaterialTheme.typography.titleSmall
                    else -> MaterialTheme.typography.labelLarge
                },
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        is Block.Paragraph -> Text(
            text = inline(block.text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is Block.Bullet -> Row(
            Modifier.padding(start = (block.indent * 12).dp).fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                "·",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = inline(block.text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }

        is Block.Numbered -> Row(
            Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
        ) {
            Text(
                "${block.number}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = inline(block.text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }

        is Block.Code -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                block.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }

        is Block.Quote -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            // 左侧竖线用方块模拟，避免为一根线引入 Divider 的尺寸约束
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                shape = RoundedCornerShape(2.dp),
            ) { Spacer(Modifier.width(2.dp).height(14.dp)) }
            Spacer(Modifier.width(8.dp))
            Text(
                text = inline(block.text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }

        is Block.Table -> TableBlock(block)

        Block.Divider -> Spacer(Modifier.height(2.dp))
    }
}

/**
 * 行内语法：`**粗体**`、`*斜体*`、`` `代码` ``。
 *
 * 逐字符扫描而不是用正则替换：需要正确处理「\*\* 未闭合」的情况，
 * 正则替换在遇到落单标记时容易产出一半加粗的文本。
 */
private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            // 行内代码优先：内部不再解析其它标记
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(text[i]); i++ }
            }

            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else { append("**"); i += 2 }
            }

            text[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(text[i]); i++ }
            }

            else -> { append(text[i]); i++ }
        }
    }
}

/**
 * 表格渲染。
 *
 * Release 日志里大量用「改动 | 说明」的对照表，如果按段落渲染会挤成
 * 一行竖线文本，可读性比纯文字更差。这里按列等分渲染成网格。
 *
 * 列宽等分而非按内容自适应：更新日志的表格多为 2~3 列且内容长度接近，
 * 等分已经够用；做自适应测量（measure 每列最大宽度）需要额外的布局
 * 遍历，对这个面板不值得。
 */
@Composable
private fun TableBlock(table: Block.Table) {
    val cols = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (cols == 0) return

    fun cells(row: List<String>): List<String> =
        List(cols) { idx -> row.getOrElse(idx) { "" } }

    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        // 表头
        Row(Modifier.fillMaxWidth()) {
            cells(table.header).forEach { c ->
                Text(
                    inline(c),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Surface(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth().height(1.dp),
        ) {}
        table.rows.forEach { row ->
            Spacer(Modifier.height(3.dp))
            Row(Modifier.fillMaxWidth()) {
                cells(row).forEach { c ->
                    Text(
                        inline(c),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                }
            }
        }
    }
}
