import re

def main():
    file_path = r"d:\Portable software\ai\jinjia\app\src\main\java\com\example\jinjia\ui\GlassTrendChartView.kt"
    with open(file_path, "r", encoding="utf-8") as f:
        content = f.read()

    # 1. Add properties for third line and chartMode
    props_to_add = """
    // 第三数据集（美元指数）
    private val thirdPoints = mutableListOf<ChartPoint>()
    private var thirdTitle: String = "美元指数"
    private var thirdPriceUnit: String = ""
    private var chartMode: Int = 0 // 0: 实时3曲线, 1: K线2资产(伦敦金+美元指数)
    """
    if "private val thirdPoints =" not in content:
        content = content.replace("private var secondaryPriceUnit: String = \"美元/盎司\"", "private var secondaryPriceUnit: String = \"美元/盎司\"\n" + props_to_add)

    # 2. Add paints for third line and kline
    paints_to_add = """
    private val lineThirdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f.toPx()
        color = Color.parseColor("#9333EA") // 紫色
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotThirdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#9333EA") }
    private val dotThirdHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#259333EA") }

    private val klineUpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#EF4444") // 涨红
    }
    private val klineDownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#10B981") // 跌绿
    }
    private val klineWickUpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f.toPx()
        color = Color.parseColor("#EF4444")
    }
    private val klineWickDownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f.toPx()
        color = Color.parseColor("#10B981")
    }
    private val tooltipThirdPricePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11.5f.toSp()
        isFakeBoldText = true
        color = Color.parseColor("#C084FC")
    }
    private val thirdCurvePath = Path()
    """
    if "private val lineThirdPaint =" not in content:
        content = content.replace("private val lineSecondaryPaint =", paints_to_add + "\n    private val lineSecondaryPaint =")

    # 3. Update setCompareChartData
    if "thirdPoints: List<ChartPoint> = emptyList()" not in content:
        old_set_compare = """    fun setCompareChartData(
        primaryPoints: List<ChartPoint>,
        primaryTitle: String,
        primaryUnit: String,
        secondaryPoints: List<ChartPoint>,
        secondaryTitle: String = "国际伦敦金",
        secondaryUnit: String = "美元/盎司"
    ) {"""
        new_set_compare = """    fun setCompareChartData(
        primaryPoints: List<ChartPoint>,
        primaryTitle: String,
        primaryUnit: String,
        secondaryPoints: List<ChartPoint>,
        secondaryTitle: String = "国际伦敦金",
        secondaryUnit: String = "美元/盎司",
        thirdPoints: List<ChartPoint> = emptyList(),
        thirdTitle: String = "美元指数",
        thirdUnit: String = "",
        chartMode: Int = 0
    ) {
        this.chartMode = chartMode
        this.thirdPoints.clear()
        this.thirdPoints.addAll(thirdPoints)
        this.thirdTitle = thirdTitle
        this.thirdPriceUnit = thirdUnit"""
        content = content.replace(old_set_compare, new_set_compare)

    # 4. Replace drawCompareMode
    # We will use regex to find drawCompareMode block and replace it completely.
    # It ends before `private fun drawSingleMode(canvas: Canvas, w: Float, h: Float) {`
    
    pattern1 = re.compile(r'private fun drawCompareMode\(canvas: Canvas, w: Float, h: Float\) \{.*?(?=private fun drawSingleMode)', re.DOTALL)
    
    new_draw_compare = """private fun drawCompareMode(canvas: Canvas, w: Float, h: Float) {
        val totalPts = if (chartMode == 0) maxOf(points.size, secondaryPoints.size, thirdPoints.size) else maxOf(secondaryPoints.size, thirdPoints.size)
        if (totalPts < 2) {
            val emptyMsg = "走势数据采集中..."
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

        // 1. 绘制极淡底色参考线
        canvas.drawLine(paddingLeft, paddingTop, w - paddingRight, paddingTop, gridPaint)
        if (chartHeight > 180f.toPx()) {
            canvas.drawLine(paddingLeft, paddingTop + chartHeight * 0.25f, w - paddingRight, paddingTop + chartHeight * 0.25f, gridPaint)
            canvas.drawLine(paddingLeft, paddingTop + chartHeight * 0.75f, w - paddingRight, paddingTop + chartHeight * 0.75f, gridPaint)
        }
        canvas.drawLine(paddingLeft, paddingTop + chartHeight / 2f, w - paddingRight, paddingTop + chartHeight / 2f, gridPaint)
        canvas.drawLine(paddingLeft, h - paddingBottom, w - paddingRight, h - paddingBottom, gridPaint)

        // 2. 严格框内裁剪
        val saveCount = canvas.save()
        canvas.clipRect(paddingLeft, paddingTop, w - paddingRight, h - paddingBottom)

        // 绘制辅助函数
        fun drawCurve(pts: List<ChartPoint>, path: Path, paint: Paint, dotPaint: Paint, haloPaint: Paint, isCandle: Boolean = false): Triple<FloatArray?, FloatArray?, FloatArray?> {
            if (pts.size < 2) return Triple(null, null, null)
            var minP = pts[0].low
            var maxP = pts[0].high
            for (p in pts) {
                if (p.low < minP) minP = p.low
                if (p.high > maxP) maxP = p.high
            }
            var delta = maxP - minP
            if (delta <= 0.0001) delta = 1.0
            val paddedMin = minP - delta * 0.12
            val paddedDelta = (maxP + delta * 0.12) - paddedMin

            val count = pts.size
            val xs = FloatArray(count)
            val ys = FloatArray(count) // close / price
            val ysOpen = if (isCandle) FloatArray(count) else null
            val ysHigh = if (isCandle) FloatArray(count) else null
            val ysLow = if (isCandle) FloatArray(count) else null

            val candleWidth = ((chartWidth * scaleX) / count) * 0.6f
            val halfCandle = candleWidth / 2f

            for (i in 0 until count) {
                val normX = i.toFloat() / (count - 1)
                xs[i] = paddingLeft + (normX - viewportStartX) * scaleX * chartWidth
                
                val p = pts[i]
                val normY = (1.0 - (p.price - paddedMin) / paddedDelta).toFloat()
                ys[i] = paddingTop + (normY - viewportStartY) * scaleY * chartHeight
                
                if (isCandle) {
                    ysOpen!![i] = paddingTop + ((1.0 - (p.open - paddedMin) / paddedDelta).toFloat() - viewportStartY) * scaleY * chartHeight
                    ysHigh!![i] = paddingTop + ((1.0 - (p.high - paddedMin) / paddedDelta).toFloat() - viewportStartY) * scaleY * chartHeight
                    ysLow!![i] = paddingTop + ((1.0 - (p.low - paddedMin) / paddedDelta).toFloat() - viewportStartY) * scaleY * chartHeight
                }
            }

            if (isCandle) {
                // Draw candlesticks
                for (i in 0 until count) {
                    val x = xs[i]
                    if (x < paddingLeft - candleWidth || x > w - paddingRight + candleWidth) continue
                    val o = ysOpen!![i]
                    val c = ys[i]
                    val hi = ysHigh!![i]
                    val lo = ysLow!![i]
                    val isUp = pts[i].close >= pts[i].open // Wait, price is close. ChartPoint needs open/close. 
                    // Actually, if price > open then it's UP in green? No, red in China.
                    val rectTop = minOf(o, c)
                    val rectBottom = maxOf(o, c)
                    val isRed = pts[i].price >= pts[i].open
                    val wp = if (isRed) klineWickUpPaint else klineWickDownPaint
                    val bp = if (isRed) klineUpPaint else klineDownPaint
                    
                    canvas.drawLine(x, hi, x, lo, wp)
                    if (rectBottom - rectTop < 1f) {
                        canvas.drawLine(x - halfCandle, rectTop, x + halfCandle, rectTop, bp)
                    } else {
                        canvas.drawRect(x - halfCandle, rectTop, x + halfCandle, rectBottom, bp)
                    }
                }
            } else {
                path.reset()
                path.moveTo(xs[0], ys[0])
                for (i in 1 until count) {
                    val prevX = xs[i - 1]
                    val prevY = ys[i - 1]
                    val currX = xs[i]
                    val currY = ys[i]
                    val cx1 = prevX + (currX - prevX) / 2f
                    val cx2 = prevX + (currX - prevX) / 2f
                    path.cubicTo(cx1, prevY, cx2, currY, currX, currY)
                }
                canvas.drawPath(path, paint)
                
                val last = count - 1
                if (xs[last] in (paddingLeft - 20f)..(w - paddingRight + 20f)) {
                    canvas.drawCircle(xs[last], ys[last], 5.5f.toPx(), haloPaint)
                    canvas.drawCircle(xs[last], ys[last], 3f.toPx(), dotPaint)
                }
            }
            return Triple(xs, ys, null)
        }

        // Draw Third (USD Index - Purple Line)
        val thirdRes = drawCurve(thirdPoints, thirdCurvePath, lineThirdPaint, dotThirdPaint, dotThirdHaloPaint, false)
        val xs3 = thirdRes.first
        val ys3 = thirdRes.second

        // Draw Secondary (London Gold - Green Line or Candlestick)
        val isLondonCandle = chartMode == 1
        val secRes = drawCurve(secondaryPoints, secondaryCurvePath, lineSecondaryPaint, dotSecondaryPaint, dotSecondaryHaloPaint, isLondonCandle)
        val xs2 = secRes.first
        val ys2 = secRes.second

        // Draw Primary (Target Gold - Blue Line, only in Realtime mode 0)
        var xs1: FloatArray? = null
        var ys1: FloatArray? = null
        if (chartMode == 0 && points.size >= 2) {
            val count1 = points.size
            xs1 = FloatArray(count1)
            ys1 = FloatArray(count1)
            var minP1 = points[0].price
            var maxP1 = points[0].price
            var minIdx1 = 0
            var maxIdx1 = 0
            for (i in points.indices) {
                val p = points[i].price
                if (p < minP1) { minP1 = p; minIdx1 = i }
                if (p > maxP1) { maxP1 = p; maxIdx1 = i }
            }
            var delta1 = maxP1 - minP1
            if (delta1 <= 0.0001) delta1 = 1.0
            val paddedMin1 = minP1 - delta1 * 0.12
            val paddedDelta1 = (maxP1 + delta1 * 0.12) - paddedMin1

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
                primaryCurvePath.cubicTo(cx1, prevY, cx1, currY, currX, currY)
            }
            
            fillPath.reset()
            fillPath.addPath(primaryCurvePath)
            fillPath.lineTo(xs1[count1 - 1], h - paddingBottom + 50f.toPx())
            fillPath.lineTo(xs1[0], h - paddingBottom + 50f.toPx())
            fillPath.close()

            fillPaint.shader = LinearGradient(0f, paddingTop, 0f, h - paddingBottom,
                intArrayOf(Color.parseColor("#182563EB"), Color.parseColor("#002563EB")),
                null, Shader.TileMode.CLAMP)
            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(primaryCurvePath, linePrimaryPaint)

            if (xs1[maxIdx1] in (paddingLeft - 20f)..(w - paddingRight + 20f)) drawValueBubble(canvas, xs1[maxIdx1], ys1[maxIdx1], "▲ %.2f".format(maxP1), true, w)
            if (minIdx1 != maxIdx1 && xs1[minIdx1] in (paddingLeft - 20f)..(w - paddingRight + 20f)) drawValueBubble(canvas, xs1[minIdx1], ys1[minIdx1], "▼ %.2f".format(minP1), false, w)

            val last1 = count1 - 1
            if (xs1[last1] in (paddingLeft - 20f)..(w - paddingRight + 20f)) {
                canvas.drawCircle(xs1[last1], ys1[last1], 6f.toPx(), dotPrimaryHaloPaint)
                canvas.drawCircle(xs1[last1], ys1[last1], 3.2f.toPx(), dotPrimaryPaint)
            }
        }

        canvas.restoreToCount(saveCount)

        // 3. 触摸高亮与联动 Tooltip
        if (isTouching) {
            // Find base index (from primary in mode 0, or secondary in mode 1)
            val basePts = if (chartMode == 0) points else secondaryPoints
            val baseXs = if (chartMode == 0) xs1 else xs2
            val baseYs = if (chartMode == 0) ys1 else ys2
            
            if (basePts.isNotEmpty() && baseXs != null && baseYs != null && selectedPointIndex in basePts.indices) {
                val selX = baseXs[selectedPointIndex]
                val selY1 = baseYs[selectedPointIndex]
                val p1 = basePts[selectedPointIndex]
                val ts = p1.timestamp

                if (selX in paddingLeft..(w - paddingRight)) {
                    canvas.drawLine(selX, paddingTop, selX, h - paddingBottom, gridDashPaint)
                    
                    if (chartMode == 0) {
                        canvas.drawCircle(selX, selY1.coerceIn(paddingTop, h - paddingBottom), 7f.toPx(), dotPrimaryHaloPaint)
                        canvas.drawCircle(selX, selY1.coerceIn(paddingTop, h - paddingBottom), 3.5f.toPx(), dotPrimaryPaint)
                    } else {
                        // K-line mode tooltip point for London Gold
                        canvas.drawCircle(selX, selY1.coerceIn(paddingTop, h - paddingBottom), 6f.toPx(), dotSecondaryHaloPaint)
                        canvas.drawCircle(selX, selY1.coerceIn(paddingTop, h - paddingBottom), 3.2f.toPx(), dotSecondaryPaint)
                    }

                    // Match secondary (if in mode 0)
                    var selY2: Float? = null
                    var p2: ChartPoint? = null
                    if (chartMode == 0 && secondaryPoints.isNotEmpty() && xs2 != null && ys2 != null) {
                        val sIdx = ((selectedPointIndex.toFloat() / (points.size - 1)) * (secondaryPoints.size - 1)).toInt().coerceIn(0, secondaryPoints.size - 1)
                        val matchResult = findClosestPoint(secondaryPoints, ts, sIdx)
                        p2 = matchResult.first
                        val matchIdx = matchResult.second
                        if (matchIdx in ys2.indices) {
                            selY2 = ys2[matchIdx]
                            canvas.drawCircle(selX, selY2.coerceIn(paddingTop, h - paddingBottom), 6f.toPx(), dotSecondaryHaloPaint)
                            canvas.drawCircle(selX, selY2.coerceIn(paddingTop, h - paddingBottom), 3.2f.toPx(), dotSecondaryPaint)
                        }
                    } else if (chartMode == 1) {
                        p2 = p1 // p1 is London gold in mode 1
                        selY2 = selY1
                    }

                    // Match third
                    var selY3: Float? = null
                    var p3: ChartPoint? = null
                    if (thirdPoints.isNotEmpty() && xs3 != null && ys3 != null) {
                        val baseSz = basePts.size
                        val thirdSz = thirdPoints.size
                        val sIdx = ((selectedPointIndex.toFloat() / maxOf(1, baseSz - 1)) * maxOf(1, thirdSz - 1)).toInt().coerceIn(0, thirdSz - 1)
                        val matchResult = findClosestPoint(thirdPoints, ts, sIdx)
                        p3 = matchResult.first
                        val matchIdx = matchResult.second
                        if (matchIdx in ys3.indices) {
                            selY3 = ys3[matchIdx]
                            canvas.drawCircle(selX, selY3.coerceIn(paddingTop, h - paddingBottom), 6f.toPx(), dotThirdHaloPaint)
                            canvas.drawCircle(selX, selY3.coerceIn(paddingTop, h - paddingBottom), 3.2f.toPx(), dotThirdPaint)
                        }
                    }

                    // 弹出联动悬浮气泡
                    if (chartMode == 0) {
                        drawCompareTooltip(canvas, selX, selY1, selY2, selY3, p1, p2, p3, w, h)
                    } else {
                        drawCompareTooltip(canvas, selX, selY1, null, selY3, p1, null, p3, w, h)
                    }
                }
            }
        }

        if (isZoomed()) drawResetBadge(canvas, w)
    }
    """
    
    content = pattern1.sub(new_draw_compare, content)

    # 5. Fix `findClosestSecondaryPoint` to be generic `findClosestPoint`
    old_find_closest = """private fun findClosestSecondaryPoint(targetTimestamp: Long, initialIndex: Int): Pair<ChartPoint, Int> {"""
    new_find_closest = """private fun findClosestPoint(list: List<ChartPoint>, targetTimestamp: Long, initialIndex: Int): Pair<ChartPoint, Int> {
        if (list.isEmpty()) return Pair(ChartPoint(targetTimestamp, 0.0), -1)
        var bestIdx = initialIndex.coerceIn(0, list.size - 1)
        var bestPoint = list[bestIdx]
        var minDiff = kotlin.math.abs(bestPoint.timestamp - targetTimestamp)

        val start = (initialIndex - 25).coerceAtLeast(0)
        val end = (initialIndex + 25).coerceAtMost(list.size - 1)
        for (i in start..end) {
            val pt = list[i]
            val diff = kotlin.math.abs(pt.timestamp - targetTimestamp)
            if (diff < minDiff) {
                minDiff = diff
                bestPoint = pt
                bestIdx = i
            }
        }
        return Pair(bestPoint, bestIdx)
    }"""
    content = re.sub(r'private fun findClosestSecondaryPoint.*?return Pair\(bestPoint, bestIdx\)\n    \}', new_find_closest, content, flags=re.DOTALL)

    # 6. Update drawCompareTooltip
    pattern_tooltip = re.compile(r'private fun drawCompareTooltip.*?canvas\.drawText\(p2Str, timeX, curY, tooltipSecondaryPricePaint\)\n        \}\n    \}', re.DOTALL)
    new_tooltip = """private fun drawCompareTooltip(
        canvas: Canvas, x: Float, y1: Float, y2: Float?, y3: Float?,
        p1: ChartPoint, p2: ChartPoint?, p3: ChartPoint?,
        containerWidth: Float, containerHeight: Float
    ) {
        val tMillis = if (p1.timestamp < 100_000_000_000L) p1.timestamp * 1000L else p1.timestamp
        val timeStr = timeFormat.format(java.util.Date(tMillis))
        
        val p1Str = if (chartMode == 0) "● %s: ¥%.2f %s".format(primaryTitle, p1.price, priceUnit) else "● %s: $%.2f".format(secondaryTitle, p1.price)
        val p2Str = if (chartMode == 0 && p2 != null) "● %s: $%.2f %s".format(secondaryTitle, p2.price, secondaryPriceUnit) else ""
        val p3Str = if (p3 != null) "● %s: %.2f".format(thirdTitle, p3.price) else ""

        tooltipTimePaint.getTextBounds(timeStr, 0, timeStr.length, textBounds)
        val timeW = textBounds.width().toFloat()
        val timeH = textBounds.height().toFloat()

        tooltipPrimaryPricePaint.getTextBounds(p1Str, 0, p1Str.length, textBounds)
        val p1W = textBounds.width().toFloat(); val p1H = textBounds.height().toFloat()

        var p2W = 0f; var p2H = 0f
        if (p2Str.isNotEmpty()) { tooltipSecondaryPricePaint.getTextBounds(p2Str, 0, p2Str.length, textBounds); p2W = textBounds.width().toFloat(); p2H = textBounds.height().toFloat() }
        
        var p3W = 0f; var p3H = 0f
        if (p3Str.isNotEmpty()) { tooltipThirdPricePaint.getTextBounds(p3Str, 0, p3Str.length, textBounds); p3W = textBounds.width().toFloat(); p3H = textBounds.height().toFloat() }

        val padX = 8f.toPx(); val padY = 6f.toPx()
        val boxW = maxOf(timeW, p1W, p2W, p3W) + padX * 2
        val boxH = timeH + p1H + (if (p2Str.isNotEmpty()) p2H + 4f.toPx() else 0f) + (if (p3Str.isNotEmpty()) p3H + 4f.toPx() else 0f) + padY * 2 + 3f.toPx()

        var boxLeft = x - boxW / 2f
        boxLeft = boxLeft.coerceIn(8f.toPx(), containerWidth - boxW - 8f.toPx())
        val boxRight = boxLeft + boxW

        val anchorY = minOf(y1, y2 ?: y1, y3 ?: y1)
        val boxTop = if (anchorY - boxH - 12f.toPx() > 4f.toPx()) { anchorY - boxH - 10f.toPx() } else { (maxOf(y1, y2 ?: y1, y3 ?: y1) + 12f.toPx()) }.coerceIn(4f.toPx(), containerHeight - boxH - 4f.toPx())
        val boxBottom = boxTop + boxH

        val rectF = RectF(boxLeft, boxTop, boxRight, boxBottom)
        canvas.drawRoundRect(rectF, 8f.toPx(), 8f.toPx(), tooltipBgPaint)
        canvas.drawRoundRect(rectF, 8f.toPx(), 8f.toPx(), tooltipBorderPaint)

        val timeX = boxLeft + padX
        var curY = boxTop + padY + timeH
        canvas.drawText(timeStr, timeX, curY, tooltipTimePaint)

        curY += 4f.toPx() + p1H
        val p1Paint = if (chartMode == 0) tooltipPrimaryPricePaint else tooltipSecondaryPricePaint
        canvas.drawText(p1Str, timeX, curY, p1Paint)

        if (p2Str.isNotEmpty()) {
            curY += 4f.toPx() + p2H
            canvas.drawText(p2Str, timeX, curY, tooltipSecondaryPricePaint)
        }
        if (p3Str.isNotEmpty()) {
            curY += 4f.toPx() + p3H
            canvas.drawText(p3Str, timeX, curY, tooltipThirdPricePaint)
        }
    }"""
    content = pattern_tooltip.sub(new_tooltip, content)

    # updateSelectedPoint fix
    content = content.replace("val count = points.size", "val count = if (isCompareMode && chartMode == 1) secondaryPoints.size else points.size")

    # touch fix
    content = content.replace("val totalCount = if (isCompareMode) maxOf(points.size, secondaryPoints.size) else points.size", 
                              "val totalCount = if (isCompareMode) maxOf(points.size, secondaryPoints.size, thirdPoints.size) else points.size")

    with open(file_path, "w", encoding="utf-8") as f:
        f.write(content)
        
    print("Patch applied successfully.")

if __name__ == "__main__":
    main()
