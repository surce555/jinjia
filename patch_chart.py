import re

file_path = 'app/src/main/java/com/example/jinjia/ui/GlassTrendChartView.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Fix 1: Change tooltip date format to yyyy-MM-dd HH:mm for k-line mode (or just dynamically format)
content = re.sub(
    r'val timeStr = timeFormat\.format\(Date\(tMillis\)\)',
    r'val fmt = if (chartMode == 1) java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()) else timeFormat\n        val timeStr = fmt.format(Date(tMillis))',
    content
)

content = re.sub(
    r'val timeStr = timeFormat\.format\(java\.util\.Date\(tMillis\)\)',
    r'val fmt = if (chartMode == 1) java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()) else timeFormat\n        val timeStr = fmt.format(java.util.Date(tMillis))',
    content
)

# Fix 2: Draw X-axis dates
single_mode_inject = """
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
        }
        
        canvas.restoreToCount(saveCount)
"""

compare_mode_inject = """
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
        }
        
        canvas.restoreToCount(saveCount)
"""

parts = content.split('canvas.restoreToCount(saveCount)')
if len(parts) >= 3:
    content = parts[0] + compare_mode_inject + parts[1] + single_mode_inject + "canvas.restoreToCount(saveCount)".join(parts[2:])

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Patch applied to GlassTrendChartView.kt")
