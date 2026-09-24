package com.example.jinjia.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.jinjia.ChartPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 现代液态玻璃化原生分时走势图表控件 (Canvas 纯原生绘制，零外部依赖)
 * 支持：贝塞尔平滑曲线、自适应Y轴缩放、香槟金流光渐变填充、极值微型气泡、手势交互高亮 Tooltip
 */
class GlassTrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val points = mutableListOf<ChartPoint>()
    private var priceUnit: String = "元/克"

    // 触摸交互状态
    private var isTouching = false
    private var touchX = 0f
    private var selectedPointIndex = -1

    // 预分配 Paint 与 Path 杜绝 onDraw 频繁内存分配
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f.toPx()
        color = Color.parseColor("#2563EB") // 科技深蓝曲线
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f.toPx()
        color = Color.parseColor("#14000000") // 极淡微灰分割网格
    }

    private val gridDashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f.toPx()
        color = Color.parseColor("#332563EB")
        pathEffect = DashPathEffect(floatArrayOf(4f.toPx(), 4f.toPx()), 0f)
    }

    private val bubbleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F8FFFFFF")
    }

    private val bubbleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f.toPx()
        color = Color.parseColor("#332563EB")
    }

    private val bubbleTextMaxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f.toSp()
        isFakeBoldText = true
        color = Color.parseColor("#059669") // 翡翠冷绿高点
    }

    private val bubbleTextMinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f.toSp()
        isFakeBoldText = true
        color = Color.parseColor("#DC2626") // 警示冷红低点
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2563EB")
    }

    private val dotHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#252563EB")
    }

    private val emptyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f.toSp()
        color = Color.parseColor("#94A3B8")
        textAlign = Paint.Align.CENTER
    }

    // 触摸悬浮气泡
    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E60F172A") // 深空石墨冷灰高透
    }

    private val tooltipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f.toPx()
        color = Color.parseColor("#33CBD5E1")
    }

    private val tooltipTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f.toSp()
        color = Color.parseColor("#94A3B8")
    }

    private val tooltipPricePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11.5f.toSp()
        isFakeBoldText = true
        color = Color.parseColor("#38BDF8") // 电光冰蓝高亮
    }

    private val curvePath = Path()
    private val fillPath = Path()
    private val textBounds = Rect()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /**
     * 设置图表分时走势数据源
     */
    fun setChartData(newPoints: List<ChartPoint>, unit: String = "元/克") {
        points.clear()
        points.addAll(newPoints)
        priceUnit = unit
        isTouching = false
        selectedPointIndex = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        if (points.size < 2) {
            val emptyMsg = if (points.isEmpty()) "⏳ 正在拉取日内分时走势..." else "走势数据采集中..."
            canvas.drawText(emptyMsg, w / 2f, h / 2f + 4f.toPx(), emptyTextPaint)
            return
        }

        val paddingLeft = 14f.toPx()
        val paddingRight = 14f.toPx()
        val paddingTop = 22f.toPx()
        val paddingBottom = 18f.toPx()

        val chartWidth = w - paddingLeft - paddingRight
        val chartHeight = h - paddingTop - paddingBottom
        if (chartWidth <= 0 || chartHeight <= 0) return

        // 1. 计算极值与自适应范围
        var minP = points[0].price
        var maxP = points[0].price
        var minIdx = 0
        var maxIdx = 0

        for (i in points.indices) {
            val p = points[i].price
            if (p < minP) {
                minP = p
                minIdx = i
            }
            if (p > maxP) {
                maxP = p
                maxIdx = i
            }
        }

        var delta = maxP - minP
        if (delta <= 0.0001) delta = 1.0

        val paddedMin = minP - delta * 0.12
        val paddedMax = maxP + delta * 0.12
        val paddedDelta = paddedMax - paddedMin

        // 2. 绘制 3 条极淡参考线 (高位、中位、低位)
        val yTop = paddingTop + (1.0 - (maxP - paddedMin) / paddedDelta).toFloat() * chartHeight
        val yBottom = paddingTop + (1.0 - (minP - paddedMin) / paddedDelta).toFloat() * chartHeight
        val yMid = (yTop + yBottom) / 2f

        canvas.drawLine(paddingLeft, yTop, w - paddingRight, yTop, gridPaint)
        canvas.drawLine(paddingLeft, yMid, w - paddingRight, yMid, gridPaint)
        canvas.drawLine(paddingLeft, yBottom, w - paddingRight, yBottom, gridPaint)

        // 3. 计算各点坐标
        val count = points.size
        val stepX = chartWidth / (count - 1)
        val xs = FloatArray(count)
        val ys = FloatArray(count)

        for (i in 0 until count) {
            xs[i] = paddingLeft + i * stepX
            ys[i] = (paddingTop + (1.0 - (points[i].price - paddedMin) / paddedDelta) * chartHeight).toFloat()
        }

        // 4. 构建平滑贝塞尔曲线
        curvePath.reset()
        curvePath.moveTo(xs[0], ys[0])

        for (i in 1 until count) {
            val prevX = xs[i - 1]
            val prevY = ys[i - 1]
            val currX = xs[i]
            val currY = ys[i]

            val cx1 = prevX + (currX - prevX) / 2f
            val cy1 = prevY
            val cx2 = prevX + (currX - prevX) / 2f
            val cy2 = currY

            curvePath.cubicTo(cx1, cy1, cx2, cy2, currX, currY)
        }

        // 5. 绘制科技深蓝微光渐变填充
        fillPath.reset()
        fillPath.addPath(curvePath)
        fillPath.lineTo(xs[count - 1], h - paddingBottom)
        fillPath.lineTo(xs[0], h - paddingBottom)
        fillPath.close()

        fillPaint.shader = LinearGradient(
            0f, paddingTop, 0f, h - paddingBottom,
            intArrayOf(Color.parseColor("#262563EB"), Color.parseColor("#002563EB")),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)

        // 6. 绘制曲线轮廓
        canvas.drawPath(curvePath, linePaint)

        // 7. 绘制极值微型气泡与标注 (最高点翡翠绿、最低点警示红)
        drawValueBubble(canvas, xs[maxIdx], ys[maxIdx], "▲ %.2f".format(maxP), isTop = true, w)
        if (minIdx != maxIdx) {
            drawValueBubble(canvas, xs[minIdx], ys[minIdx], "▼ %.2f".format(minP), isTop = false, w)
        }

        // 8. 绘制最新价动态脉冲光点 (末端点)
        val lastIdx = count - 1
        canvas.drawCircle(xs[lastIdx], ys[lastIdx], 6f.toPx(), dotHaloPaint)
        canvas.drawCircle(xs[lastIdx], ys[lastIdx], 3.2f.toPx(), dotPaint)

        // 9. 手势触摸高亮与浮动 Tooltip
        if (isTouching && selectedPointIndex in 0 until count) {
            val selX = xs[selectedPointIndex]
            val selY = ys[selectedPointIndex]
            val selPoint = points[selectedPointIndex]

            // 竖向虚线
            canvas.drawLine(selX, paddingTop, selX, h - paddingBottom, gridDashPaint)

            // 交叉选中光圈
            canvas.drawCircle(selX, selY, 7f.toPx(), dotHaloPaint)
            canvas.drawCircle(selX, selY, 3.5f.toPx(), dotPaint)

            // 浮动 Tooltip
            drawTooltip(canvas, selX, selY, selPoint, w, h)
        }
    }

    private fun drawValueBubble(
        canvas: Canvas,
        x: Float,
        y: Float,
        text: String,
        isTop: Boolean,
        containerWidth: Float
    ) {
        val textPaint = if (isTop) bubbleTextMaxPaint else bubbleTextMinPaint
        textPaint.getTextBounds(text, 0, text.length, textBounds)
        val textW = textBounds.width().toFloat()
        val textH = textBounds.height().toFloat()

        val padX = 6f.toPx()
        val padY = 3f.toPx()
        val bubbleW = textW + padX * 2
        val bubbleH = textH + padY * 2

        var bubbleLeft = x - bubbleW / 2f
        bubbleLeft = bubbleLeft.coerceIn(8f.toPx(), containerWidth - bubbleW - 8f.toPx())
        val bubbleRight = bubbleLeft + bubbleW

        val offsetY = if (isTop) -(bubbleH + 6f.toPx()) else 6f.toPx()
        val bubbleTop = y + offsetY
        val bubbleBottom = bubbleTop + bubbleH

        val rectF = RectF(bubbleLeft, bubbleTop, bubbleRight, bubbleBottom)
        canvas.drawRoundRect(rectF, 6f.toPx(), 6f.toPx(), bubbleBgPaint)
        canvas.drawRoundRect(rectF, 6f.toPx(), 6f.toPx(), bubbleStrokePaint)

        val textX = bubbleLeft + padX
        val textY = bubbleTop + padY + textH
        canvas.drawText(text, textX, textY, textPaint)

        // 锚点小实心圆
        canvas.drawCircle(x, y, 2.5f.toPx(), dotPaint)
    }

    private fun drawTooltip(
        canvas: Canvas,
        x: Float,
        y: Float,
        point: ChartPoint,
        containerWidth: Float,
        containerHeight: Float
    ) {
        val tMillis = if (point.timestamp < 100_000_000_000L) point.timestamp * 1000L else point.timestamp
        val timeStr = timeFormat.format(Date(tMillis))
        val symbol = if (priceUnit.contains("美元") || priceUnit.contains("$")) "$" else "¥"
        val priceStr = "$symbol %.2f %s".format(point.price, priceUnit)

        tooltipTimePaint.getTextBounds(timeStr, 0, timeStr.length, textBounds)
        val timeW = textBounds.width().toFloat()
        val timeH = textBounds.height().toFloat()

        tooltipPricePaint.getTextBounds(priceStr, 0, priceStr.length, textBounds)
        val priceW = textBounds.width().toFloat()
        val priceH = textBounds.height().toFloat()

        val padX = 8f.toPx()
        val padY = 6f.toPx()
        val boxW = maxOf(timeW, priceW) + padX * 2
        val boxH = timeH + priceH + padY * 2 + 3f.toPx()

        var boxLeft = x - boxW / 2f
        boxLeft = boxLeft.coerceIn(8f.toPx(), containerWidth - boxW - 8f.toPx())
        val boxRight = boxLeft + boxW

        val boxTop = if (y - boxH - 12f.toPx() > 4f.toPx()) {
            y - boxH - 10f.toPx()
        } else {
            y + 12f.toPx()
        }.coerceIn(4f.toPx(), containerHeight - boxH - 4f.toPx())
        val boxBottom = boxTop + boxH

        val rectF = RectF(boxLeft, boxTop, boxRight, boxBottom)
        canvas.drawRoundRect(rectF, 8f.toPx(), 8f.toPx(), tooltipBgPaint)
        canvas.drawRoundRect(rectF, 8f.toPx(), 8f.toPx(), tooltipBorderPaint)

        // 绘制时间文本
        val timeX = boxLeft + padX
        val timeY = boxTop + padY + timeH
        canvas.drawText(timeStr, timeX, timeY, tooltipTimePaint)

        // 绘制金价文本
        val priceX = boxLeft + padX
        val priceY = timeY + 4f.toPx() + priceH
        canvas.drawText(priceStr, priceX, priceY, tooltipPricePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (points.size < 2) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isTouching = true
                touchX = event.x

                val paddingLeft = 14f.toPx()
                val paddingRight = 14f.toPx()
                val chartWidth = width - paddingLeft - paddingRight
                val count = points.size
                val stepX = chartWidth / (count - 1)

                var closestIdx = 0
                var minDiff = Float.MAX_VALUE
                for (i in 0 until count) {
                    val x = paddingLeft + i * stepX
                    val diff = abs(x - touchX)
                    if (diff < minDiff) {
                        minDiff = diff
                        closestIdx = i
                    }
                }
                selectedPointIndex = closestIdx
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                selectedPointIndex = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun Float.toPx(): Float = this * context.resources.displayMetrics.density
    private fun Float.toSp(): Float = this * context.resources.displayMetrics.scaledDensity
}
