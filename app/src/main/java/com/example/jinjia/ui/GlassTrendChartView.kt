package com.example.jinjia.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import com.example.jinjia.ChartPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 现代液态玻璃化原生分时走势图表控件 (Canvas 纯原生绘制，零外部依赖)
 * 支持：
 * 1. 单标的高频走势模式（列表项展开走势图使用：贝塞尔平滑曲线、自适应Y轴缩放、科技蓝渐变填充、极值微气泡、手势交互 Tooltip）
 * 2. 双标的对比走势模式（首页主看板使用：盯盘目标 vs 国际伦敦金 双轴归一化曲线、冷色调区分、多资产联动 Tooltip）
 * 3. 局域高精度两指捏合放大缩小 (1.0x ~ 8.0x)
 * 4. 框内单指/双指平滑自由平移漫游 (X/Y 自由移动，严格限定在图表边界框内，绝不溢出)
 * 5. 一键平滑动画还原 (UI 按钮、画板悬浮胶囊标签、双击手势快速复位)
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

    // 缩放与自由移动状态矩阵
    private var scaleX = 1.0f
    private var scaleY = 1.0f
    private var viewportStartX = 0f // 归一化可视起始X [0 .. 1 - 1/scaleX]
    private var viewportStartY = 0f // 归一化可视起始Y [0 .. 1 - 1/scaleY]

    private var isScaling = false
    private var isDragging = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // 触摸交互悬浮 Tooltip 状态
    private var isTouching = false
    private var touchX = 0f
    private var selectedPointIndex = -1

    // 画板悬浮“↺ 还原”胶囊按钮区域
    private val resetBadgeRect = RectF()

    // 外部缩放状态回调
    var onZoomChangeListener: ((isZoomed: Boolean, scale: Float) -> Unit)? = null

    // 预分配 Paint 与 Path
    private val linePrimaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f.toPx()
        color = Color.parseColor("#2563EB") // 科技深蓝
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val lineSecondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f.toPx()
        color = Color.parseColor("#059669") // 翡翠冷绿
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
        color = Color.parseColor("#059669")
    }

    private val bubbleTextMinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f.toSp()
        isFakeBoldText = true
        color = Color.parseColor("#DC2626")
    }

    private val dotPrimaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2563EB")
    }

    private val dotPrimaryHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#252563EB")
    }

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
        color = Color.parseColor("#EE0F172A")
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
        color = Color.parseColor("#60A5FA")
    }

    private val tooltipSecondaryPricePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11.5f.toSp()
        isFakeBoldText = true
        color = Color.parseColor("#34D399")
    }

    // 画板悬浮一键还原小胶囊
    private val resetBadgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#D90F172A")
    }

    private val resetBadgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f.toPx()
        color = Color.parseColor("#4038BDF8")
    }

    private val resetBadgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f.toSp()
        isFakeBoldText = true
        color = Color.parseColor("#38BDF8")
        textAlign = Paint.Align.CENTER
    }

    private val primaryCurvePath = Path()
    private val secondaryCurvePath = Path()
    private val fillPath = Path()
    private val textBounds = Rect()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // 两指捏合手势探测器
    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            applyScale(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            isScaling = true
            isTouching = false
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isScaling = false
        }
    })

    // 双击快速复位/放大探测器
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isZoomed()) {
                resetZoom(animated = true)
            } else {
                animateZoomTo(2.5f, e.x, e.y)
            }
            return true
        }
    })

    /**
     * 当前是否处于局部放大状态
     */
    fun isZoomed(): Boolean = scaleX > 1.05f || scaleY > 1.05f

    /**
     * 一键平滑动画还原缩放与平移 (复位至 1.0x 全局视角)
     */
    fun resetZoom(animated: Boolean = true) {
        if (!isZoomed() && viewportStartX == 0f && viewportStartY == 0f) return

        if (animated) {
            val startScaleX = scaleX
            val startScaleY = scaleY
            val startX = viewportStartX
            val startY = viewportStartY
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 260
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { anim ->
                    val f = anim.animatedFraction
                    scaleX = startScaleX + (1.0f - startScaleX) * f
                    scaleY = startScaleY + (1.0f - startScaleY) * f
                    viewportStartX = startX + (0f - startX) * f
                    viewportStartY = startY + (0f - startY) * f
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        scaleX = 1.0f
                        scaleY = 1.0f
                        viewportStartX = 0f
                        viewportStartY = 0f
                        isTouching = false
                        selectedPointIndex = -1
                        onZoomChangeListener?.invoke(false, 1.0f)
                        invalidate()
                    }
                })
            }
            animator.start()
        } else {
            scaleX = 1.0f
            scaleY = 1.0f
            viewportStartX = 0f
            viewportStartY = 0f
            isTouching = false
            selectedPointIndex = -1
            onZoomChangeListener?.invoke(false, 1.0f)
            invalidate()
        }
    }

    /**
     * 平滑缩放至指定倍率（以焦点为中心）
     */
    fun animateZoomTo(targetScale: Float, focusX: Float, focusY: Float) {
        val chartLeft = 14f.toPx()
        val chartTop = 22f.toPx()
        val chartWidth = (width - chartLeft - 14f.toPx()).coerceAtLeast(1f)
        val chartHeight = (height - chartTop - 18f.toPx()).coerceAtLeast(1f)

        val startScaleX = scaleX
        val startScaleY = scaleY
        val startX = viewportStartX
        val startY = viewportStartY

        val normFocusX = ((focusX - chartLeft) / chartWidth).coerceIn(0f, 1f)
        val dataXAtFocus = startX + normFocusX / startScaleX
        val targetStartX = (dataXAtFocus - normFocusX / targetScale).coerceIn(0f, (1.0f - 1.0f / targetScale).coerceAtLeast(0f))

        val normFocusY = ((focusY - chartTop) / chartHeight).coerceIn(0f, 1f)
        val dataYAtFocus = startY + normFocusY / startScaleY
        val targetScaleY = targetScale.coerceAtMost(3.0f)
        val targetStartY = (dataYAtFocus - normFocusY / targetScaleY).coerceIn(0f, (1.0f - 1.0f / targetScaleY).coerceAtLeast(0f))

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                scaleX = startScaleX + (targetScale - startScaleX) * f
                scaleY = startScaleY + (targetScaleY - startScaleY) * f
                viewportStartX = startX + (targetStartX - startX) * f
                viewportStartY = startY + (targetStartY - startY) * f
                onZoomChangeListener?.invoke(isZoomed(), scaleX)
                invalidate()
            }
            start()
        }
    }

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
        resetZoom(animated = false)
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

        // 1. 绘制极淡底色参考线 (高位、中位、低位，高度较大时自适应增补四等分参考线)
        canvas.drawLine(paddingLeft, paddingTop, w - paddingRight, paddingTop, gridPaint)
        if (chartHeight > 180f.toPx()) {
            canvas.drawLine(paddingLeft, paddingTop + chartHeight * 0.25f, w - paddingRight, paddingTop + chartHeight * 0.25f, gridPaint)
            canvas.drawLine(paddingLeft, paddingTop + chartHeight * 0.75f, w - paddingRight, paddingTop + chartHeight * 0.75f, gridPaint)
        }
        canvas.drawLine(paddingLeft, paddingTop + chartHeight / 2f, w - paddingRight, paddingTop + chartHeight / 2f, gridPaint)
        canvas.drawLine(paddingLeft, h - paddingBottom, w - paddingRight, h - paddingBottom, gridPaint)

        // 2. 严格框内裁剪：保证缩放、移动期间所有曲线、填充、节点 100% 局限在此框内，绝不外溢
        val saveCount = canvas.save()
        canvas.clipRect(paddingLeft, paddingTop, w - paddingRight, h - paddingBottom)

        // 计算与绘制副标的（国际伦敦金 - 翡翠冷绿曲线）
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
            xs2 = FloatArray(count2)
            ys2 = FloatArray(count2)
            for (i in 0 until count2) {
                val normX = i.toFloat() / (count2 - 1)
                val normY = (1.0 - (secondaryPoints[i].price - paddedMin2) / paddedDelta2).toFloat()
                xs2[i] = paddingLeft + (normX - viewportStartX) * scaleX * chartWidth
                ys2[i] = paddingTop + (normY - viewportStartY) * scaleY * chartHeight
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

            // 伦敦金末端脉冲光点 (在视野内时绘制)
            val last2 = count2 - 1
            if (xs2[last2] in (paddingLeft - 20f)..(w - paddingRight + 20f)) {
                canvas.drawCircle(xs2[last2], ys2[last2], 5.5f.toPx(), dotSecondaryHaloPaint)
                canvas.drawCircle(xs2[last2], ys2[last2], 3f.toPx(), dotSecondaryPaint)
            }
        }

        // 计算与绘制主标的（盯盘目标 - 科技深蓝曲线与微渐变）
        var xs1: FloatArray? = null
        var ys1: FloatArray? = null
        var minIdx1 = 0
        var maxIdx1 = 0
        var maxP1 = 0.0
        var minP1 = 0.0

        if (points.size >= 2) {
            minP1 = points[0].price
            maxP1 = points[0].price
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
            xs1 = FloatArray(count1)
            ys1 = FloatArray(count1)
            for (i in 0 until count1) {
                val normX = i.toFloat() / (count1 - 1)
                val normY = (1.0 - (points[i].price - paddedMin1) / paddedDelta1).toFloat()
                xs1[i] = paddingLeft + (normX - viewportStartX) * scaleX * chartWidth
                ys1[i] = paddingTop + (normY - viewportStartY) * scaleY * chartHeight
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

            // 主曲线微透蓝色渐变填充
            fillPath.reset()
            fillPath.addPath(primaryCurvePath)
            fillPath.lineTo(xs1[count1 - 1], h - paddingBottom + 50f.toPx())
            fillPath.lineTo(xs1[0], h - paddingBottom + 50f.toPx())
            fillPath.close()

            fillPaint.shader = LinearGradient(
                0f, paddingTop, 0f, h - paddingBottom,
                intArrayOf(Color.parseColor("#182563EB"), Color.parseColor("#002563EB")),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(primaryCurvePath, linePrimaryPaint)

            // 主标的极值气泡 (最高/最低，在视野内时绘制)
            if (xs1[maxIdx1] in (paddingLeft - 20f)..(w - paddingRight + 20f)) {
                drawValueBubble(canvas, xs1[maxIdx1], ys1[maxIdx1], "▲ %.2f".format(maxP1), isTop = true, w)
            }
            if (minIdx1 != maxIdx1 && xs1[minIdx1] in (paddingLeft - 20f)..(w - paddingRight + 20f)) {
                drawValueBubble(canvas, xs1[minIdx1], ys1[minIdx1], "▼ %.2f".format(minP1), isTop = false, w)
            }

            // 主标的末端脉冲光点
            val last1 = count1 - 1
            if (xs1[last1] in (paddingLeft - 20f)..(w - paddingRight + 20f)) {
                canvas.drawCircle(xs1[last1], ys1[last1], 6f.toPx(), dotPrimaryHaloPaint)
                canvas.drawCircle(xs1[last1], ys1[last1], 3.2f.toPx(), dotPrimaryPaint)
            }
        }

        // 恢复裁剪前的画布
        canvas.restoreToCount(saveCount)

        // 3. 手势触摸高亮与双标的联动 Tooltip
        if (isTouching && points.isNotEmpty() && xs1 != null && ys1 != null && selectedPointIndex in points.indices) {
            val selX = xs1[selectedPointIndex]
            val selY1 = ys1[selectedPointIndex]
            val p1 = points[selectedPointIndex]

            if (selX in paddingLeft..(w - paddingRight)) {
                // 竖向虚线辅助线 (裁剪在图表框内)
                val lineTop = maxOf(paddingTop, selY1 - 200f)
                val lineBottom = minOf(h - paddingBottom, selY1 + 200f)
                canvas.drawLine(selX, paddingTop, selX, h - paddingBottom, gridDashPaint)

                // 主曲线交叉选中光圈
                canvas.drawCircle(selX, selY1.coerceIn(paddingTop, h - paddingBottom), 7f.toPx(), dotPrimaryHaloPaint)
                canvas.drawCircle(selX, selY1.coerceIn(paddingTop, h - paddingBottom), 3.5f.toPx(), dotPrimaryPaint)

                // 副曲线匹配对应时刻点
                var selY2: Float? = null
                var p2: ChartPoint? = null
                if (secondaryPoints.isNotEmpty() && xs2 != null && ys2 != null) {
                    val sIdx = ((selectedPointIndex.toFloat() / (points.size - 1)) * (secondaryPoints.size - 1)).toInt().coerceIn(0, secondaryPoints.size - 1)
                    val matchResult = findClosestSecondaryPoint(p1.timestamp, sIdx)
                    p2 = matchResult.first
                    val matchIdx = matchResult.second
                    if (matchIdx in ys2.indices) {
                        selY2 = ys2[matchIdx]
                        canvas.drawCircle(selX, selY2.coerceIn(paddingTop, h - paddingBottom), 6f.toPx(), dotSecondaryHaloPaint)
                        canvas.drawCircle(selX, selY2.coerceIn(paddingTop, h - paddingBottom), 3.2f.toPx(), dotSecondaryPaint)
                    }
                }

                // 弹出双标的悬浮气泡
                drawCompareTooltip(canvas, selX, selY1, selY2, p1, p2, w, h)
            }
        }

        // 4. 画板右上角悬浮一键还原小胶囊（放大时常驻显示，点击立即可复位）
        if (isZoomed()) {
            drawResetBadge(canvas, w)
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

        // 极淡参考线 (高度较大时自适应增补四等分参考线)
        canvas.drawLine(paddingLeft, paddingTop, w - paddingRight, paddingTop, gridPaint)
        if (chartHeight > 180f.toPx()) {
            canvas.drawLine(paddingLeft, paddingTop + chartHeight * 0.25f, w - paddingRight, paddingTop + chartHeight * 0.25f, gridPaint)
            canvas.drawLine(paddingLeft, paddingTop + chartHeight * 0.75f, w - paddingRight, paddingTop + chartHeight * 0.75f, gridPaint)
        }
        canvas.drawLine(paddingLeft, paddingTop + chartHeight / 2f, w - paddingRight, paddingTop + chartHeight / 2f, gridPaint)
        canvas.drawLine(paddingLeft, h - paddingBottom, w - paddingRight, h - paddingBottom, gridPaint)

        // 框内裁剪
        val saveCount = canvas.save()
        canvas.clipRect(paddingLeft, paddingTop, w - paddingRight, h - paddingBottom)

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
        val paddedDelta = (maxP + delta * 0.12) - paddedMin

        val count = points.size
        val xs = FloatArray(count)
        val ys = FloatArray(count)

        for (i in 0 until count) {
            val normX = i.toFloat() / (count - 1)
            val normY = (1.0 - (points[i].price - paddedMin) / paddedDelta).toFloat()
            xs[i] = paddingLeft + (normX - viewportStartX) * scaleX * chartWidth
            ys[i] = paddingTop + (normY - viewportStartY) * scaleY * chartHeight
        }

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

        fillPath.reset()
        fillPath.addPath(primaryCurvePath)
        fillPath.lineTo(xs[count - 1], h - paddingBottom + 50f.toPx())
        fillPath.lineTo(xs[0], h - paddingBottom + 50f.toPx())
        fillPath.close()

        fillPaint.shader = LinearGradient(
            0f, paddingTop, 0f, h - paddingBottom,
            intArrayOf(Color.parseColor("#262563EB"), Color.parseColor("#002563EB")),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(primaryCurvePath, linePrimaryPaint)

        // 极值微型气泡
        if (xs[maxIdx] in (paddingLeft - 20f)..(w - paddingRight + 20f)) {
            drawValueBubble(canvas, xs[maxIdx], ys[maxIdx], "▲ %.2f".format(maxP), isTop = true, w)
        }
        if (minIdx != maxIdx && xs[minIdx] in (paddingLeft - 20f)..(w - paddingRight + 20f)) {
            drawValueBubble(canvas, xs[minIdx], ys[minIdx], "▼ %.2f".format(minP), isTop = false, w)
        }

        // 最新价末端脉冲点
        val lastIdx = count - 1
        if (xs[lastIdx] in (paddingLeft - 20f)..(w - paddingRight + 20f)) {
            canvas.drawCircle(xs[lastIdx], ys[lastIdx], 6f.toPx(), dotPrimaryHaloPaint)
            canvas.drawCircle(xs[lastIdx], ys[lastIdx], 3.2f.toPx(), dotPrimaryPaint)
        }

        canvas.restoreToCount(saveCount)

        // 手势触摸高亮与浮动 Tooltip
        if (isTouching && selectedPointIndex in 0 until count) {
            val selX = xs[selectedPointIndex]
            val selY = ys[selectedPointIndex]
            val selPoint = points[selectedPointIndex]

            if (selX in paddingLeft..(w - paddingRight)) {
                canvas.drawLine(selX, paddingTop, selX, h - paddingBottom, gridDashPaint)
                canvas.drawCircle(selX, selY.coerceIn(paddingTop, h - paddingBottom), 7f.toPx(), dotPrimaryHaloPaint)
                canvas.drawCircle(selX, selY.coerceIn(paddingTop, h - paddingBottom), 3.5f.toPx(), dotPrimaryPaint)
                drawSingleTooltip(canvas, selX, selY, selPoint, w, h)
            }
        }

        if (isZoomed()) {
            drawResetBadge(canvas, w)
        }
    }

    /**
     * 绘制右上角画板悬浮复位小气泡 [ ↺ 还原 2.5x ]
     */
    private fun drawResetBadge(canvas: Canvas, containerWidth: Float) {
        val badgeText = "↺ 还原 (%.1fx)".format(scaleX)
        resetBadgeTextPaint.getTextBounds(badgeText, 0, badgeText.length, textBounds)
        val textW = textBounds.width().toFloat()
        val textH = textBounds.height().toFloat()

        val padX = 8f.toPx()
        val padY = 4f.toPx()
        val badgeW = textW + padX * 2
        val badgeH = textH + padY * 2

        val right = containerWidth - 14f.toPx()
        val left = right - badgeW
        val top = 22f.toPx() + 6f.toPx()
        val bottom = top + badgeH

        resetBadgeRect.set(left, top, right, bottom)
        canvas.drawRoundRect(resetBadgeRect, 10f.toPx(), 10f.toPx(), resetBadgeBgPaint)
        canvas.drawRoundRect(resetBadgeRect, 10f.toPx(), 10f.toPx(), resetBadgeStrokePaint)

        val textX = left + badgeW / 2f
        val textY = top + padY + textH - 1f.toPx()
        canvas.drawText(badgeText, textX, textY, resetBadgeTextPaint)
    }

    private fun findClosestSecondaryPoint(targetTimestamp: Long, initialIndex: Int): Pair<ChartPoint, Int> {
        if (secondaryPoints.isEmpty()) return Pair(ChartPoint(targetTimestamp, 0.0), -1)
        var bestIdx = initialIndex.coerceIn(0, secondaryPoints.size - 1)
        var bestPoint = secondaryPoints[bestIdx]
        var minDiff = abs(bestPoint.timestamp - targetTimestamp)

        val start = (initialIndex - 25).coerceAtLeast(0)
        val end = (initialIndex + 25).coerceAtMost(secondaryPoints.size - 1)
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

        val timeX = boxLeft + padX
        val timeY = boxTop + padY + timeH
        canvas.drawText(timeStr, timeX, timeY, tooltipTimePaint)

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

        val timeX = boxLeft + padX
        var curY = boxTop + padY + timeH
        canvas.drawText(timeStr, timeX, curY, tooltipTimePaint)

        curY += 4f.toPx() + p1H
        canvas.drawText(p1Str, timeX, curY, tooltipPrimaryPricePaint)

        if (p2Str.isNotEmpty()) {
            curY += 4f.toPx() + p2H
            canvas.drawText(p2Str, timeX, curY, tooltipSecondaryPricePaint)
        }
    }

    /**
     * 缩放算法应用（以两指触摸中心为焦点保持不动）
     */
    private fun applyScale(factor: Float, focusX: Float, focusY: Float) {
        val chartLeft = 14f.toPx()
        val chartTop = 22f.toPx()
        val chartWidth = (width - chartLeft - 14f.toPx()).coerceAtLeast(1f)
        val chartHeight = (height - chartTop - 18f.toPx()).coerceAtLeast(1f)

        val oldScaleX = scaleX
        val oldScaleY = scaleY

        // X轴支持 1.0x ~ 8.0x 高精缩放
        val newScaleX = (oldScaleX * factor).coerceIn(1.0f, 8.0f)
        // Y轴支持 1.0x ~ 4.0x 适度纵向缩放
        val newScaleY = (oldScaleY * factor).coerceIn(1.0f, 4.0f)

        if (abs(newScaleX - oldScaleX) < 0.001f && abs(newScaleY - oldScaleY) < 0.001f) {
            return
        }

        // X 轴锚点防漂移
        val normFocusX = ((focusX - chartLeft) / chartWidth).coerceIn(0f, 1f)
        val dataXAtFocus = viewportStartX + normFocusX / oldScaleX
        val maxStartX = (1.0f - 1.0f / newScaleX).coerceAtLeast(0f)
        viewportStartX = (dataXAtFocus - normFocusX / newScaleX).coerceIn(0f, maxStartX)

        // Y 轴锚点防漂移
        val normFocusY = ((focusY - chartTop) / chartHeight).coerceIn(0f, 1f)
        val dataYAtFocus = viewportStartY + normFocusY / oldScaleY
        val maxStartY = (1.0f - 1.0f / newScaleY).coerceAtLeast(0f)
        viewportStartY = (dataYAtFocus - normFocusY / newScaleY).coerceIn(0f, maxStartY)

        scaleX = newScaleX
        scaleY = newScaleY

        onZoomChangeListener?.invoke(isZoomed(), scaleX)
        invalidate()
    }

    /**
     * 自由平移移动（严格限定在图表边界框内，绝不外溢）
     */
    private fun pan(dx: Float, dy: Float) {
        if (!isZoomed()) return
        val chartLeft = 14f.toPx()
        val chartTop = 22f.toPx()
        val chartWidth = (width - chartLeft - 14f.toPx()).coerceAtLeast(1f)
        val chartHeight = (height - chartTop - 18f.toPx()).coerceAtLeast(1f)

        val maxStartX = (1.0f - 1.0f / scaleX).coerceAtLeast(0f)
        val maxStartY = (1.0f - 1.0f / scaleY).coerceAtLeast(0f)

        viewportStartX = (viewportStartX - dx / (scaleX * chartWidth)).coerceIn(0f, maxStartX)
        viewportStartY = (viewportStartY - dy / (scaleY * chartHeight)).coerceIn(0f, maxStartY)

        invalidate()
    }

    private fun updateSelectedPoint(touchX: Float) {
        if (points.isEmpty()) return
        val chartLeft = 14f.toPx()
        val chartWidth = (width - chartLeft - 14f.toPx()).coerceAtLeast(1f)
        val normX = (viewportStartX + (touchX - chartLeft) / (scaleX * chartWidth)).coerceIn(0f, 1f)
        val count = points.size
        val idx = (normX * (count - 1)).roundToInt().coerceIn(0, count - 1)
        selectedPointIndex = idx
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val totalCount = if (isCompareMode) maxOf(points.size, secondaryPoints.size) else points.size
        if (totalCount < 2) return super.onTouchEvent(event)

        parent?.requestDisallowInterceptTouchEvent(true)

        // 优先将多指缩放与双击委托给 Detector
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        val pointerCount = event.pointerCount
        if (pointerCount >= 2) {
            // 双指操作阶段隐藏单点 Tooltip，聚焦两指缩放与移动
            isTouching = false
            isDragging = true
            return true
        }

        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x
                lastTouchY = y
                downX = x
                downY = y
                isDragging = false

                // 点击右上角悬浮“↺ 还原”按钮直接平滑复位
                if (isZoomed() && resetBadgeRect.contains(x, y)) {
                    resetZoom(animated = true)
                    return true
                }

                if (!isZoomed()) {
                    isTouching = true
                    touchX = x
                    updateSelectedPoint(x)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                val dist = hypot(x - downX, y - downY)

                if (isZoomed()) {
                    // 局部放大模式下：单指拖拽即在框内自由移动全方位漫游
                    if (dist > touchSlop || isDragging) {
                        isDragging = true
                        isTouching = false
                        pan(dx, dy)
                    }
                } else {
                    // 未放大模式下：单指滑动即十字星吸附 Tooltip
                    isTouching = true
                    touchX = x
                    updateSelectedPoint(x)
                }
                lastTouchX = x
                lastTouchY = y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isZoomed()) {
                    // 若放大模式下是轻点而不是拖拽，切换/吸附该点 Tooltip
                    val dist = hypot(x - downX, y - downY)
                    if (dist < touchSlop && !isDragging) {
                        isTouching = !isTouching
                        if (isTouching) {
                            touchX = x
                            updateSelectedPoint(x)
                        } else {
                            selectedPointIndex = -1
                            invalidate()
                        }
                    }
                } else {
                    isTouching = false
                    selectedPointIndex = -1
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                }
                isDragging = false
            }
        }
        return true
    }

    private fun Float.toPx(): Float = this * context.resources.displayMetrics.density
    private fun Float.toSp(): Float = this * context.resources.displayMetrics.scaledDensity
}
