import re

file_path = 'app/src/main/java/com/example/jinjia/ui/GlassTrendChartView.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# For compare mode:
# Currently it is:
#         if (basePts.isNotEmpty() && baseXs != null) { ... }
#         canvas.restoreToCount(saveCount)
# We need to swap them!

compare_replace = """        canvas.restoreToCount(saveCount)

        // Draw X-axis Labels (Compare Mode)
        val basePts = if (chartMode == 0) points else secondaryPoints
        val baseXs = if (chartMode == 0) xs1 else xs2
        if (basePts.isNotEmpty() && baseXs != null) {
            val lblCount = 5
            for (i in 0 until lblCount) {
                val idx = (i * (basePts.size - 1)) / (lblCount - 1)
                val pt = basePts[idx]
                val px = baseXs[idx]
                val fmt = if (chartMode == 1) java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()) else timeFormat
                val txt = fmt.format(java.util.Date(pt.timestamp * if (pt.timestamp < 100_000_000_000L) 1000L else 1L))
                emptyTextPaint.textSize = 10f.toPx()
                val textWidth = emptyTextPaint.measureText(txt)
                canvas.drawText(txt, px.coerceIn(paddingLeft + textWidth/2, w - paddingRight - textWidth/2), h - 4f.toPx(), emptyTextPaint)
            }
        }"""

content = re.sub(
    r'// Draw X-axis Labels \(Compare Mode\).*?canvas\.restoreToCount\(saveCount\)',
    compare_replace,
    content,
    flags=re.DOTALL
)

single_replace = """        canvas.restoreToCount(saveCount)

        // Draw X-axis Labels (Single Mode)
        val lblCount = 5
        for (i in 0 until lblCount) {
            val idx = (i * (points.size - 1)) / (lblCount - 1)
            val pt = points[idx]
            val px = xs[idx]
            val fmt = if (chartMode == 1) java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()) else timeFormat
            val txt = fmt.format(java.util.Date(pt.timestamp * if (pt.timestamp < 100_000_000_000L) 1000L else 1L))
            emptyTextPaint.textSize = 10f.toPx()
            val textWidth = emptyTextPaint.measureText(txt)
            canvas.drawText(txt, px.coerceIn(paddingLeft + textWidth/2, w - paddingRight - textWidth/2), h - 4f.toPx(), emptyTextPaint)
        }"""

content = re.sub(
    r'// Draw X-axis Labels \(Single Mode\).*?canvas\.restoreToCount\(saveCount\)',
    single_replace,
    content,
    flags=re.DOTALL
)


with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Patch applied to GlassTrendChartView.kt")
