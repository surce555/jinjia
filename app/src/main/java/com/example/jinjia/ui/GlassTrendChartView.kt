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
 * 支持：
 * 1. 单标的高频走势模式（列表项展开走势图使用：贝塞尔平滑曲线、自适应Y轴缩放、科技蓝渐变填充、极值微气泡、手势交互 Tooltip）
 * 2. 双标的对比走势模式（首页主看板使用：盯盘目标 vs 国际伦敦金 双轴归一化曲线、不同对比色、多资产联动 Tooltip）
 */
class GlassTrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 主数据集（盯盘目标 / 单标的）
    private val points = mutableListOf<ChartPoint>()
    private var priceUnit: String = "元/克"
    private var primaryTitle: String = "盯盘标的"

    // 对比模式与副数据集（国际伦敦金）
    private var isCompareMode = false
    private val secondaryPoints = mutableListOf<ChartPoint>()
    private var secondaryTitle: String = "国际伦敦金"
    private var secondaryPriceUnit: String = "美元/盎司"

    // 触摸交互状态
    private var isTouching = false
    private var touchX = 0f
    private var selectedPointIndex = -1

    // 预分配 Paint 与 Path 杜绝 onDraw 频繁内存分配
    // 主曲线：科技深蓝
    private val linePrimaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f.toPx()
        color = Color.parseColor("#2563EB")
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // 副曲线：翡翠冷绿 (用于国际伦敦金)
    private val lineSecondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f.toPx()
        color = Color.parseColor("#059669")
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

    // 主端点光圈 (科技蓝)
    private val dotPrimaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2563EB")
    }

    private val dotPrimaryHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#252563EB")
    }

    // 副端点光圈 (翡翠绿 - 伦敦金)
    private val dotSecondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#059669")
    }

    private val dotSecondaryHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#25059669")
    }

    private val emptyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f.toSp()
        color = Color.parseColor("#94A3B8")
        textAlign = Paint.Align.CENTER
    }

    // 触摸悬浮气泡
    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#EE0F172A") // 深空石墨冷灰高透
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

    private val tooltipPrimaryPricePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11.5f.toSp()
        isFakeBoldText = true
        color = Color.parseColor("#60A5FA") // 科技蓝高亮
    }

    private val tooltipSecondaryPricePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11.5f.toSp()
        isFakeBoldText = true
        color = Color.parseColor("#34D399") // 翡翠绿高亮
    }

    private val primaryCurvePath = Path()
    private val secondaryCurvePath = Path()
    private val fillPath = Path()
    private val textBounds = Rect()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /**
     * 设置单品种分时走势数据源（保持列表项走势图功能不变）
     */
    fun setChartData(newPoints: List<ChartPoint>, unit: String = "元/克") {
        isCompareMode = false
        points.clear()
        points.addAll(newPoints)
        priceUnit = unit
        secondaryPoints.clear()
        isTouching = false
        selectedPointIndex = -1
        invalidate()
    }

    /**
     * 设置双品种日内分时走势对比数据源（主看板专用：盯盘目标 vs 国际伦敦金）
     */
    fun setCompareChartData(
        primaryPoints: List<ChartPoint>,
        primaryTitle: String,
        primaryUnit: String,
        secondaryPoints: List<ChartPoint>,
        secondaryTitle: String = "国际伦敦金",
        secondaryUnit: String = "美元/盎司"
    ) {
        this.isCompareMode = true
        this.points.clear()
        this.points.addAll(primaryPoints)
        this.primaryTitle = primaryTitle
        this.priceUnit = primaryUnit

        this.secondaryPoints.clear()
        this.secondaryPoints.addAll(secondaryPoints)
        this.secondaryTitle = secondaryTitle
        this.secondaryPriceUnit = secondaryUnit

        this.isTouching = false
        this.selectedPointIndex = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        if (isCompareMode) {
            drawCompareMode(canvas, w, h)
        } else {
            drawSingleMode(canvas, w, h)
        }
    }

    /**
     * 绘制双标的对比走势模式 (主看板：盯盘标的 vs 国际伦敦金)
     */
    private fun drawCompareMode(canvas: Canvas, w: Float, h: Float) {
        if (points.size < 2 && secondaryPoints.size < 2) {
            val emptyMsg = if (points.isEmpty()) "⏳ 正在拉取日内走势对比..." else "走势数据采集中..."
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

        // 1. 绘制 3 条极淡参考线 (高位、中位、低位)
        canvas.drawLine(paddingLeft, paddingTop, w - paddingRight, paddingTop, gridPaint)
        canvas.drawLine(paddingLeft, paddingTop + chartHeight / 2f, w - paddingRight, paddingTop + chartHeight / 2f, gridPaint)
        canvas.drawLine(paddingLeft, h - paddingBottom, w - paddingRight, h - paddingBottom, gridPaint)

        // 2. 计算与绘制副标的（国际伦敦金 - 翡翠冷绿曲线）
        var minP2 = 0.0
        var maxP2 = 0.0
        var xs2: FloatArray? = null
        var ys2: FloatArray? = null

        if (secondaryPoints.size >= 2) {
            minP2 = secondaryPoints[0].price
            maxP2 = secondaryPoints[0].price
            for (p in secondaryPoints) {
                if (p.price < minP2) minP2 = p.price
                if (p.price > maxP2) maxP2 = p.price
            }
            var delta2 = maxP2 - minP2
            if (delta2 <= 0.0001) delta2 = 1.0
            val paddedMin2 = minP2 - delta2 * 0.12
            val paddedDelta2 = (maxP2 + delta2 * 0.12) - paddedMin2

            val count2 = secondaryPoints.size
            val stepX2 = chartWidth / (count2 - 1)
            xs2 = FloatArray(count2)
            ys2 = FloatArray(count2)
            for (i in 0 until count2) {
                xs2[i] = paddingLeft + i * stepX2
                ys2[i] = (paddingTop + (1.0 - (secondaryPoints[i].price - paddedMin2) / paddedDelta2) * chartHeight).toFloat()
            }

            secondaryCurvePath.reset()
            secondaryCurvePath.moveTo(xs2[0], ys2[0])
            for (i in 1 until count2) {
                val prevX = xs2[i - 1]
                val prevY = ys2[i - 1]
                val currX = xs2[i]
                val currY = ys2[i]
                val cx1 = prevX + (currX - prevX) / 2f
                val cy1 = prevY
                val cx2 = prevX + (currX - prevX) / 2f
                val cy2 = currY
                secondaryCurvePath.cubicTo(cx1, cy1, cx2, cy2, currX, currY)
            }
            canvas.drawPath(secondaryCurvePath, lineSecondaryPaint)

            // 伦敦金末端脉冲光点
            val last2 = count2 - 1
            canvas.drawCircle(xs2[last2], ys2[last2], 5.5f.toPx(), dotSecondaryHaloPaint)
            canvas.drawCircle(xs2[last2], ys2[last2], 3f.toPx(), dotSecondaryPaint)
        }

        // 3. 计算与绘制主标的（盯盘目标 - 科技深蓝曲线与微渐变）
        if (points.size >= 2) {
            var minP1 = points[0].price
            var maxP1 = points[0].price
            var minIdx1 = 0
            var maxIdx1 = 0
            for (i in points.indices) {
                val p = points[i].price
                if (p < minP1) {
                    minP1 = p
                    minIdx1 = i
                }
                if (p > maxP1) {
                    maxP1 = p
                    maxIdx1 = i
                }
            }
            var delta1 = maxP1 - minP1
            if (delta1 <= 0.0001) delta1 = 1.0
            val paddedMin1 = minP1 - delta1 * 0.12
            val paddedDelta1 = (maxP1 + delta1 * 0.12) - paddedMin1

            val count1 = points.size
            val stepX1 = chartWidth / (count1 - 1)
            val xs1 = FloatArray(count1)
            val ys1 = FloatArray(count1)
            for (i in 0 until count1) {
                xs1[i] = paddingLeft + i * stepX1
                ys1[i] = (paddingTop + (1.0 - (points[i].price - paddedMin1) / paddedDelta1) * chartHeight).toFloat()
            }

            primaryCurvePath.reset()
            primaryCurvePath.moveTo(xs1[0], ys1[0])
            for (i in 1 until count1) {
                val prevX = xs1[i - 1]
                val prevY = ys1[i - 1]
                val currX = xs1[i]
                val currY = ys1[i]
                val cx1 = prevX + (currX - prevX) / 2f
                val cy1 = prevY
                val cx2 = prevX + (currX - prevX) / 2f
                val cy2 = currY
                primaryCurvePath.cubicTo(cx1, cy1, cx2, cy2, currX, currY)
            }

            // 主曲线微透蓝色渐变底
            fillPath.reset()
            fillPath.addPath(primaryCurvePath)
            fillPath.lineTo(xs1[count1 - 1], h - paddingBottom)
            fillPath.lineTo(xs1[0], h - paddingBottom)
            fillPath.close()

            fillPaint.shader = LinearGradient(
                0f, paddingTop, 0f, h - paddingBottom,
                intArrayOf(Color.parseColor("#182563EB"), Color.parseColor("#002563EB")),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(primaryCurvePath, linePrimaryPaint)

            // 主标的极值气泡 (最高/最低)
            drawValueBubble(canvas, xs1[maxIdx1], ys1[maxIdx1], "▲ %.2f".format(maxP1), isTop = true, w)
            if (minIdx1 != maxIdx1) {
                drawValueBubble(canvas, xs1[minIdx1], ys1[minIdx1], "▼ %.2f".format(minP1), isTop = false, w)
            }

            // 主标的最新价末端脉冲光点
            val last1 = count1 - 1
            canvas.drawCircle(xs1[last1], ys1[last1], 6f.toPx(), dotPrimaryHaloPaint)
            canvas.drawCircle(xs1[last1], ys1[last1], 3.2f.toPx(), dotPrimaryPaint)

            // 4. 手势触摸高亮与双品种联动 Tooltip
            if (isTouching && selectedPointIndex in 0 until count1) {
                val selX = xs1[selectedPointIndex]
                val selY1 = ys1[selectedPointIndex]
                val p1 = points[selectedPointIndex]

                // 竖向虚线辅助线
                canvas.drawLine(selX, paddingTop, selX, h - paddingBottom, gridDashPaint)

                // 主曲线交叉选中光圈
                canvas.drawCircle(selX, selY1, 7f.toPx(), dotPrimaryHaloPaint)
                canvas.drawCircle(selX, selY1, 3.5f.toPx(), dotPrimaryPaint)

                // 副曲线匹配对应时刻点
                var selY2: Float? = null
                var p2: ChartPoint? = null
                if (secondaryPoints.isNotEmpty() && xs2 != null && ys2 != null) {
                    val sIdx = ((selectedPointIndex.toFloat() / (count1 - 1)) * (secondaryPoints.size - 1)).toInt().coerceIn(0, secondaryPoints.size - 1)
                    val matchResult = findClosestSecondaryPoint(p1.timestamp, sIdx)
                    p2 = matchResult.first
                    val matchIdx = matchResult.second
                    if (matchIdx in ys2.indices) {
                        selY2 = ys2[matchIdx]
                        canvas.drawCircle(selX, selY2, 6f.toPx(), dotSecondaryHaloPaint)
                        canvas.drawCircle(selX, selY2, 3.2f.toPx(), dotSecondaryPaint)
                    }
                }

                // 弹出双标的悬浮气泡
                drawCompareTooltip(canvas, selX, selY1, selY2, p1, p2, w, h)
            }
        }
    }

    /**
     * 绘制单标的高频走势模式（列表项展开走势图使用，保持原状）
     */
    private fun drawSingleMode(canvas: Canvas, w: Float, h: Float) {
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
        primaryCurvePath.reset()
        primaryCurvePath.moveTo(xs[0], ys[0])

        for (i in 1 until count) {
            val prevX = xs[i - 1]
            val prevY = ys[i - 1]
            val currX = xs[i]
            val currY = ys[i]

            val cx1 = prevX + (currX - prevX) / 2f
            val cy1 = prevY
            val cx2 = prevX + (currX - prevX) / 2f
            val cy2 = currY

            primaryCurvePath.cubicTo(cx1, cy1, cx2, cy2, currX, currY)
        }

        // 5. 绘制科技深蓝微光渐变填充
        fillPath.reset()
        fillPath.addPath(primaryCurvePath)
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
        canvas.drawPath(primaryCurvePath, linePrimaryPaint)

        // 7. 绘制极值微型气泡与标注 (最高点翡翠绿、最低点警示红)
        drawValueBubble(canvas, xs[maxIdx], ys[maxIdx], "▲ %.2f".format(maxP), isTop = true, w)
        if (minIdx != maxIdx) {
            drawValueBubble(canvas, xs[minIdx], ys[minIdx], "▼ %.2f".format(minP), isTop = false, w)
        }

        // 8. 绘制最新价动态脉冲光点 (末端点)
        val lastIdx = count - 1
        canvas.drawCircle(xs[lastIdx], ys[lastIdx], 6f.toPx(), dotPrimaryHaloPaint)
        canvas.drawCircle(xs[lastIdx], ys[lastIdx], 3.2f.toPx(), dotPrimaryPaint)

        // 9. 手势触摸高亮与浮动 Tooltip
        if (isTouching && selectedPointIndex in 0 until count) {
            val selX = xs[selectedPointIndex]
            val selY = ys[selectedPointIndex]
            val selPoint = points[selectedPointIndex]

            // 竖向虚线
            canvas.drawLine(selX, paddingTop, selX, h - paddingBottom, gridDashPaint)

            // 交叉选中光圈
            canvas.drawCircle(selX, selY, 7f.toPx(), dotPrimaryHaloPaint)
            canvas.drawCircle(selX, selY, 3.5f.toPx(), dotPrimaryPaint)

            // 浮动 Tooltip
            drawSingleTooltip(canvas, selX, selY, selPoint, w, h)
        }
    }

    private fun findClosestSecondaryPoint(targetTimestamp: Long, initialIndex: Int): Pair<ChartPoint, Int> {
        if (secondaryPoints.isEmpty()) return Pair(ChartPoint(targetTimestamp, 0.0), -1)
        var bestIdx = initialIndex.coerceIn(0, secondaryPoints.size - 1)
        var bestPoint = secondaryPoints[bestIdx]
        var minDiff = abs(bestPoint.timestamp - targetTimestamp)

        val start = (initialIndex - 20).coerceAtLeast(0)
        val end = (initialIndex + 20).coerceAtMost(secondaryPoints.size - 1)
        for (i in start..end) {
            val pt = secondaryPoints[i]
            val diff = abs(pt.timestamp - targetTimestamp)
            if (diff < minDiff) {
                minDiff = diff
                bestPoint = pt
                bestIdx = i
            }
        }
        return Pair(bestPoint, bestIdx)
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
        canvas.drawCircle(x, y, 2.5f.toPx(), dotPrimaryPaint)
    }

    private fun drawSingleTooltip(
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

        tooltipPrimaryPricePaint.getTextBounds(priceStr, 0, priceStr.length, textBounds)
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
        canvas.drawText(priceStr, priceX, priceY, tooltipPrimaryPricePaint)
    }

    private fun drawCompareTooltip(
        canvas: Canvas,
        x: Float,
        y1: Float,
        y2: Float?,
        p1: ChartPoint,
        p2: ChartPoint?,
        containerWidth: Float,
        containerHeight: Float
    ) {
        val tMillis = if (p1.timestamp < 100_000_000_000L) p1.timestamp * 1000L else p1.timestamp
        val timeStr = timeFormat.format(Date(tMillis))
        val symbol1 = if (priceUnit.contains("美元") || priceUnit.contains("$")) "$" else "¥"
        val p1Str = "● %s: %s%.2f %s".format(primaryTitle, symbol1, p1.price, priceUnit)

        val symbol2 = if (secondaryPriceUnit.contains("美元") || secondaryPriceUnit.contains("$")) "$" else "¥"
        val p2Str = if (p2 != null) "● %s: %s%.2f %s".format(secondaryTitle, symbol2, p2.price, secondaryPriceUnit) else ""

        tooltipTimePaint.getTextBounds(timeStr, 0, timeStr.length, textBounds)
        val timeW = textBounds.width().toFloat()
        val timeH = textBounds.height().toFloat()

        tooltipPrimaryPricePaint.getTextBounds(p1Str, 0, p1Str.length, textBounds)
        val p1W = textBounds.width().toFloat()
        val p1H = textBounds.height().toFloat()

        var p2W = 0f
        var p2H = 0f
        if (p2Str.isNotEmpty()) {
            tooltipSecondaryPricePaint.getTextBounds(p2Str, 0, p2Str.length, textBounds)
            p2W = textBounds.width().toFloat()
            p2H = textBounds.height().toFloat()
        }

        val padX = 8f.toPx()
        val padY = 6f.toPx()
        val boxW = maxOf(timeW, p1W, p2W) + padX * 2
        val boxH = timeH + p1H + (if (p2Str.isNotEmpty()) p2H + 4f.toPx() else 0f) + padY * 2 + 3f.toPx()

        var boxLeft = x - boxW / 2f
        boxLeft = boxLeft.coerceIn(8f.toPx(), containerWidth - boxW - 8f.toPx())
        val boxRight = boxLeft + boxW

        val anchorY = minOf(y1, y2 ?: y1)
        val boxTop = if (anchorY - boxH - 12f.toPx() > 4f.toPx()) {
            anchorY - boxH - 10f.toPx()
        } else {
            (maxOf(y1, y2 ?: y1) + 12f.toPx())
        }.coerceIn(4f.toPx(), containerHeight - boxH - 4f.toPx())
        val boxBottom = boxTop + boxH

        val rectF = RectF(boxLeft, boxTop, boxRight, boxBottom)
        canvas.drawRoundRect(rectF, 8f.toPx(), 8f.toPx(), tooltipBgPaint)
        canvas.drawRoundRect(rectF, 8f.toPx(), 8f.toPx(), tooltipBorderPaint)

        // 绘制时间文本
        val timeX = boxLeft + padX
        var curY = boxTop + padY + timeH
        canvas.drawText(timeStr, timeX, curY, tooltipTimePaint)

        // 绘制主标的文本 (科技蓝)
        curY += 4f.toPx() + p1H
        canvas.drawText(p1Str, timeX, curY, tooltipPrimaryPricePaint)

        // 绘制副标的伦敦金文本 (翡翠绿)
        if (p2Str.isNotEmpty()) {
            curY += 4f.toPx() + p2H
            canvas.drawText(p2Str, timeX, curY, tooltipSecondaryPricePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val totalCount = if (isCompareMode) maxOf(points.size, secondaryPoints.size) else points.size
        if (totalCount < 2) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isTouching = true
                touchX = event.x

                val paddingLeft = 14f.toPx()
                val paddingRight = 14f.toPx()
                val chartWidth = width - paddingLeft - paddingRight
                val count = points.size.coerceAtLeast(1)
                val stepX = if (count > 1) chartWidth / (count - 1) else chartWidth

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
