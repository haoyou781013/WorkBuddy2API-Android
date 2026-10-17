package com.dsh.aihub

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * 悬浮球展开后的信息面板：一眼看清「跑着没 / 几个号能用 / 今天用了多少」。
 *
 * 为什么不做成完整的第二界面：悬浮窗的价值在于「不打断当前操作」。
 * 用户点开它通常只想知道一件事——服务还活着吗、还能不能用。
 * 所以只放四行核心数据，不做操作入口（操作去 App 里做）。
 *
 * 绘制方式与 BallView 一致：直接用 Canvas 画，不引入 Compose
 * —— 悬浮窗 View 由 WindowManager 直接挂载，没有 ComposeView 的生命周期支持，
 * 强行用 ComposeView 会和 WindowManager 的 attach/detach 冲突。
 */
@SuppressLint("ViewConstructor")
class FloatingPanel(
    context: Context,
    private val snapshot: Snapshot,
) : View(context) {

    /** 面板要展示的数据快照。由 [FloatingWindowKeeper.showPanel] 在展开时抓取。 */
    data class Snapshot(
        val running: Boolean,
        val healthy: Int,
        val total: Int,
        val todayRequests: Int,
        val todayCredits: Double,
        val port: Int,
    )

    private val density = resources.displayMetrics.density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2181B26.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x22FFFFFF
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9CA3AF.toInt()
        textSize = 11f * density
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        textAlign = Paint.Align.RIGHT
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x14FFFFFF
        strokeWidth = 1f * density
    }
    private val roundRect = RectF()

    override fun onDraw(canvas: Canvas) {
        val pad = 12f * density
        val corner = 14f * density
        roundRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(roundRect, corner, corner, bgPaint)
        canvas.drawRoundRect(roundRect, corner, corner, borderPaint)

        var y = pad + titlePaint.textSize

        // 标题行 + 状态点
        val dotR = 3.5f * density
        dotPaint.color = if (snapshot.running) 0xFF4ADE80.toInt() else 0xFFEF4444.toInt()
        canvas.drawCircle(pad + dotR, y - titlePaint.textSize / 3.2f, dotR, dotPaint)
        canvas.drawText(
            if (snapshot.running) "AIHub 运行中" else "AIHub 已停止",
            pad + dotR * 2 + 6f * density, y, titlePaint
        )

        // 端口放标题右侧
        canvas.drawText(":$snapshot.port", width - pad, y, labelPaint.apply {
            textAlign = Paint.Align.RIGHT
        })
        labelPaint.textAlign = Paint.Align.LEFT

        y += 8f * density
        canvas.drawLine(pad, y, width - pad, y, dividerPaint)

        // 三行指标
        val rows = listOf(
            "可用账号" to "${snapshot.healthy}/${snapshot.total}",
            "今日请求" to "${snapshot.todayRequests}",
            "今日消耗" to FormatKit.credits(snapshot.todayCredits),
        )
        y += 14f * density
        for ((label, value) in rows) {
            canvas.drawText(label, pad, y, labelPaint)
            canvas.drawText(value, width - pad, y, valuePaint)
            y += 20f * density
        }
    }
}
