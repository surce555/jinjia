import re

kt_path = 'app/src/main/java/com/example/jinjia/GoldPriceService.kt'
with open(kt_path, 'r', encoding='utf-8') as f:
    kt = f.read()

# Fix handleParsedData
old_parsed = """        // 1. 边缘触发告警状态机
        if (shouldTriggerAlertOld(currentPrice, targetThreshold)) {
            sendAlertNotification(currentTitle, currentPrice, targetThreshold, targetItem.unit)
        }"""
new_parsed = """        // 1. 全局配置告警状态机 (Multi-monitor Check)
        for (item in allItems) {
            checkAndTriggerAlerts(item)
            
            // Spike alert checks for all items too!
            val now = System.currentTimeMillis()
            val lastData = lastPricesForSpike[item.id]
            if (lastData != null) {
                val (lastPrice, lastTime) = lastData
                val timeDiffMins = (now - lastTime) / 60000.0
                if (timeDiffMins <= 30) { 
                    val changePercent = Math.abs(item.price - lastPrice) / lastPrice
                    if (changePercent > 0.005) { 
                        val dir = if (item.price > lastPrice) "急涨" else "急跌"
                        val msg = "⚠️ ${item.displayName} 出现短线异动$dir! (30分钟振幅超0.5%) 当前: ${item.price}"
                        sendNotification(item.id.hashCode() + 1000, "异动警报", msg)
                        lastPricesForSpike[item.id] = Pair(item.price, now) 
                    }
                } else {
                    lastPricesForSpike[item.id] = Pair(item.price, now)
                }
            } else {
                lastPricesForSpike[item.id] = Pair(item.price, now)
            }
        }"""

kt = kt.replace(old_parsed, new_parsed)

with open(kt_path, 'w', encoding='utf-8') as f:
    f.write(kt)

print("Patched handleParsedData")
