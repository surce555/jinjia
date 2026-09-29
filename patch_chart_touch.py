import re

file_path = 'app/src/main/java/com/example/jinjia/ui/GlassTrendChartView.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Fix updateSelectedPoint
update_selected_pt_old = """    private fun updateSelectedPoint(touchX: Float) {
        if (points.isEmpty()) return"""

update_selected_pt_new = """    private fun updateSelectedPoint(touchX: Float) {
        val totalCount = if (isCompareMode) maxOf(points.size, secondaryPoints.size, thirdPoints.size) else points.size
        if (totalCount == 0) return"""

content = content.replace(update_selected_pt_old, update_selected_pt_new)

# Fix drawCompareTooltip
tooltip_old = 'val p1Str = if (chartMode == 0) "● %s: ¥%.2f %s".format(primaryTitle, p1.price, priceUnit) else "● %s: $%.2f".format(secondaryTitle, p1.price)'
tooltip_new = 'val p1Str = if (chartMode == 0) "● %s: ¥%.2f %s".format(primaryTitle, p1.price, priceUnit) else "● %s: 开$%.2f 高$%.2f 低$%.2f 收$%.2f".format(secondaryTitle, p1.open, p1.high, p1.low, p1.price)'

content = content.replace(tooltip_old, tooltip_new)


with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Patch applied to GlassTrendChartView.kt for Touch selection and Tooltip")
