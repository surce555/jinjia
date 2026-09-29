import re

kt_path = 'app/src/main/java/com/example/jinjia/GoldRepository.kt'
with open(kt_path, 'r', encoding='utf-8') as f:
    kt = f.read()

# Make sure not to double add if I already changed actualInterval
if 'val actualInterval = if (interval == "1w" || interval == "1M") "1d" else interval' not in kt:
    kt = kt.replace('val actualInterval = if (interval == "1w") "1d" else interval',
                    'val actualInterval = if (interval == "1w" || interval == "1M") "1d" else interval')

# Caching logic
if 'if (interval == "1M") cachedList = aggregateMonthly(cachedList)' not in kt:
    kt = kt.replace('if (interval == "1w") cachedList = aggregateWeekly(cachedList)',
                    """if (interval == "1w") cachedList = aggregateWeekly(cachedList)
                if (interval == "1M") cachedList = aggregateMonthly(cachedList)""")

if 'if (interval == "1M") list = aggregateMonthly(list)' not in kt:
    kt = kt.replace('if (interval == "1w") list = aggregateWeekly(list)',
                    """if (interval == "1w") list = aggregateWeekly(list)
            if (interval == "1M") list = aggregateMonthly(list)""")

if 'else if (interval == "1M") aggregateMonthly(cached)' not in kt:
    kt = kt.replace('if (interval == "1w") aggregateWeekly(cached) else cached',
                    'if (interval == "1w") aggregateWeekly(cached) else if (interval == "1M") aggregateMonthly(cached) else cached')

monthly_func = """
    private fun aggregateMonthly(dailyPoints: List<ChartPoint>): List<ChartPoint> {
        if (dailyPoints.isEmpty()) return emptyList()
        val sorted = dailyPoints.sortedBy { it.timestamp }
        val aggregated = mutableListOf<ChartPoint>()
        
        var currentMonthStart = 0L
        var monthOpen = 0.0
        var monthHigh = 0.0
        var monthLow = Double.MAX_VALUE
        var monthClose = 0.0
        
        val cal = java.util.Calendar.getInstance()
        cal.timeZone = java.util.TimeZone.getTimeZone("UTC")
        
        for (pt in sorted) {
            val tMillis = if (pt.timestamp < 100_000_000_000L) pt.timestamp * 1000L else pt.timestamp
            cal.timeInMillis = tMillis
            
            cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            val monthStart = cal.timeInMillis // keep in millis
            
            if (currentMonthStart == 0L || monthStart != currentMonthStart) {
                if (currentMonthStart != 0L) {
                    aggregated.add(ChartPoint(currentMonthStart, monthClose, monthOpen, monthHigh, monthLow, true))
                }
                currentMonthStart = monthStart
                monthOpen = pt.open
                monthHigh = pt.high
                monthLow = pt.low
                monthClose = pt.price
            } else {
                monthHigh = maxOf(monthHigh, pt.high)
                monthLow = minOf(monthLow, pt.low)
                monthClose = pt.price
            }
        }
        if (currentMonthStart != 0L) {
            aggregated.add(ChartPoint(currentMonthStart, monthClose, monthOpen, monthHigh, monthLow, true))
        }
        return aggregated
    }
"""

if 'private fun aggregateMonthly' not in kt:
    kt = kt.replace('private fun aggregateWeekly(', monthly_func + '\n    private fun aggregateWeekly(')

with open(kt_path, 'w', encoding='utf-8') as f:
    f.write(kt)

print("Applied aggregateMonthly")
