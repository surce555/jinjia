import re

file_path = 'app/src/main/java/com/example/jinjia/GoldRepository.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

aggregate_func = """
    private fun aggregateWeekly(dailyPoints: List<ChartPoint>): List<ChartPoint> {
        if (dailyPoints.isEmpty()) return emptyList()
        val weeklyMap = java.util.TreeMap<Long, MutableList<ChartPoint>>()
        for (pt in dailyPoints) {
            val days = pt.timestamp / 86400000L
            val weekIdx = (days + 3) / 7
            val weekStartTs = (weekIdx * 7 - 3) * 86400000L
            weeklyMap.getOrPut(weekStartTs) { mutableListOf() }.add(pt)
        }
        val weeklyPoints = mutableListOf<ChartPoint>()
        for ((ts, pts) in weeklyMap) {
            val open = pts.first().open
            val close = pts.last().price
            val high = pts.maxOf { it.high }
            val low = pts.minOf { it.low }
            weeklyPoints.add(ChartPoint(ts, close, open, high, low, true))
        }
        return weeklyPoints
    }
"""

if "private fun aggregateWeekly" not in content:
    # insert before the last brace
    content = content[:content.rfind('}')] + aggregate_func + "\n}"

fetch_logic_replace = """
    suspend fun fetchBiquoteOHLC(
        symbol: String, 
        interval: String,
        onCachedData: ((List<ChartPoint>) -> Unit)? = null
    ): List<ChartPoint> = withContext(Dispatchers.IO) {
        val actualInterval = if (interval == "1w") "1d" else interval
        val base = if (!proxyUrl.isNullOrBlank()) proxyUrl!!.trimEnd('/') else "https://jinjia.lingchenyidianban.site"
        val url = "$base/api/$symbol/ohlc?interval=$actualInterval"
        DebugLogger.log("fetchBiquoteOHLC: START $symbol $interval")

        val cacheFile = cacheDir?.let { java.io.File(it, "biquote_${symbol}_${interval}.json") }
        
        // 尝试先读取并回调本地缓存
        if (onCachedData != null && cacheFile != null && cacheFile.exists()) {
            try {
                val cachedString = cacheFile.readText(Charsets.UTF_8)
                var cachedList = parseBiquoteJson(cachedString)
                if (interval == "1w") cachedList = aggregateWeekly(cachedList)
                if (cachedList.isNotEmpty()) {
                    withContext(Dispatchers.Main) { onCachedData(cachedList) }
                }
            } catch (e: Exception) {}
        }
"""

# Replace the top of fetchBiquoteOHLC
content = re.sub(
    r'suspend fun fetchBiquoteOHLC\(.*?try \{',
    fetch_logic_replace.strip() + '\n        try {',
    content,
    flags=re.DOTALL
)

content = content.replace(
    'val list = parseBiquoteJson(bodyString)\n            DebugLogger.log("fetchBiquoteOHLC: SUCCESS',
    'var list = parseBiquoteJson(bodyString)\n            if (interval == "1w") list = aggregateWeekly(list)\n            DebugLogger.log("fetchBiquoteOHLC: SUCCESS'
)

content = content.replace(
    'return@withContext parseBiquoteJson(cacheFile.readText(Charsets.UTF_8))',
    'val cached = parseBiquoteJson(cacheFile.readText(Charsets.UTF_8))\n                    return@withContext if (interval == "1w") aggregateWeekly(cached) else cached'
)


with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Patch applied to GoldRepository.kt")
