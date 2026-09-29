import re

kt_path = 'app/src/main/java/com/example/jinjia/GoldPriceService.kt'
with open(kt_path, 'r', encoding='utf-8') as f:
    kt = f.read()

# Add spike logic
if "lastPricesForSpike" not in kt:
    kt = kt.replace('private val mainScope = CoroutineScope(Dispatchers.Main + Job())',
                    'private val mainScope = CoroutineScope(Dispatchers.Main + Job())\n    private val lastPricesForSpike = mutableMapOf<String, Pair<Double, Long>>()')
    
    spike_logic = """
        // Feature 2: 异动预警 (Spike Alert)
        val now = System.currentTimeMillis()
        val lastData = lastPricesForSpike[item.id]
        if (lastData != null) {
            val (lastPrice, lastTime) = lastData
            val timeDiffMins = (now - lastTime) / 60000.0
            if (timeDiffMins <= 30) { // within 30 mins
                val changePercent = Math.abs(item.price - lastPrice) / lastPrice
                if (changePercent > 0.005) { // 0.5% in short time
                    val dir = if (item.price > lastPrice) "急涨" else "急跌"
                    val msg = "⚠️ ${item.displayName} 出现短线异动$dir! (30分钟振幅超0.5%) 当前: ${item.price}"
                    sendNotification(item.id.hashCode() + 1000, "异动警报", msg)
                    lastPricesForSpike[item.id] = Pair(item.price, now) // Reset base
                }
            } else {
                lastPricesForSpike[item.id] = Pair(item.price, now)
            }
        } else {
            lastPricesForSpike[item.id] = Pair(item.price, now)
        }
        
"""
    kt = kt.replace('if (isForeground) {', spike_logic + '        if (isForeground) {')
    
    # Also update widget from service
    widget_call = """
        try {
            com.example.jinjia.widget.GoldWidgetProvider.updateWidget(this, 
                "N/A", item.displayName, item.price.toString())
        } catch (e: Exception) {}
"""
    kt = kt.replace('sendBroadcast(intent)', 'sendBroadcast(intent)\n' + widget_call)

with open(kt_path, 'w', encoding='utf-8') as f:
    f.write(kt)

print("Feature 2: Spike alert and Widget Update applied")
