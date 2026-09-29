import re

kt_path = 'app/src/main/java/com/example/jinjia/GoldPriceService.kt'
with open(kt_path, 'r', encoding='utf-8') as f:
    kt = f.read()

# Models
models = """
data class TargetThresholds(val high: Double, val low: Double, val title: String)
data class AlertState(var hasAlertedHigh: Boolean = false, var hasAlertedLow: Boolean = false, var lastAlertTime: Long = 0L)
"""

if "data class TargetThresholds" not in kt:
    kt = kt.replace('class GoldPriceService : Service() {', models + '\nclass GoldPriceService : Service() {')

# Add maps
if "val activeMonitors =" not in kt:
    kt = kt.replace('private var isAppInForeground: Boolean = false',
                    'private var isAppInForeground: Boolean = false\n    private val alertStates = mutableMapOf<String, AlertState>()')

# Update load logic in onCreate
kt = re.sub(r'if \(saved\.targetThreshold != null\) targetThreshold = saved\.targetThreshold', 
            '', kt)

# Function to read configs
read_cfg = """
    private fun getActiveMonitors(): Map<String, TargetThresholds> {
        val sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = sp.getString("monitor_configs", "{}") ?: "{}"
        val map = mutableMapOf<String, TargetThresholds>()
        try {
            val root = org.json.JSONObject(jsonStr)
            for (key in root.keys()) {
                val obj = root.getJSONObject(key)
                map[key] = TargetThresholds(obj.optDouble("high", 0.0), obj.optDouble("low", 0.0), obj.optString("title", key))
            }
        } catch (e: Exception) {}
        return map
    }
"""

if "private fun getActiveMonitors" not in kt:
    kt = kt.replace('override fun onCreate() {', read_cfg + '\n    override fun onCreate() {')

# The shouldTriggerAlert logic
should_trigger = """
    private fun checkAndTriggerAlerts(item: GoldItem) {
        val configs = getActiveMonitors()
        val cfg = configs[item.id] ?: return
        val state = alertStates.getOrPut(item.id) { AlertState() }
        val now = System.currentTimeMillis()
        
        // Check High
        if (cfg.high > 0 && item.price >= cfg.high) {
            if (!state.hasAlertedHigh || (now - state.lastAlertTime > 30 * 60 * 1000L)) {
                sendAlertNotification(item.displayName, item.price, cfg.high, item.unit, "上涨突破")
                state.hasAlertedHigh = true
                state.hasAlertedLow = false
                state.lastAlertTime = now
            }
        } else if (cfg.high > 0 && item.price < cfg.high * 0.998) {
            state.hasAlertedHigh = false // reset if it falls back down significantly
        }
        
        // Check Low
        if (cfg.low > 0 && item.price <= cfg.low) {
            if (!state.hasAlertedLow || (now - state.lastAlertTime > 30 * 60 * 1000L)) {
                sendAlertNotification(item.displayName, item.price, cfg.low, item.unit, "下跌跌破")
                state.hasAlertedLow = true
                state.hasAlertedHigh = false
                state.lastAlertTime = now
            }
        } else if (cfg.low > 0 && item.price > cfg.low * 1.002) {
            state.hasAlertedLow = false // reset if it bounces back up
        }
    }
"""

if "private fun checkAndTriggerAlerts" not in kt:
    kt = kt.replace('private fun shouldTriggerAlert(', should_trigger + '\n    private fun shouldTriggerAlertOld(')
    
# Replace shouldTriggerAlert logic in handleSingleItemUpdate
kt = kt.replace('if (shouldTriggerAlert(currentPrice, targetThreshold)) {\n            sendAlertNotification(currentTitle, currentPrice, targetThreshold, item.unit)\n        }',
                'checkAndTriggerAlerts(item)')

# Also replace sendAlertNotification signature
kt = kt.replace('private fun sendAlertNotification(title: String, price: Double, threshold: Double, unit: String) {',
                'private fun sendAlertNotification(title: String, price: Double, threshold: Double, unit: String, dir: String = "跌破") {')
kt = kt.replace('val message = "【$title】已跌破预警价 $threshold $unit，当前最新价格: $price"',
                'val message = "【$title】已$dir预警价 $threshold $unit，当前最新价格: $price"')

# We also need to fetch all active monitors, not just the currently selected one!
# In processPriceUpdate():
# Wait, processPriceUpdate is the loop. It checks targetId.
# We need to make sure we fetch data for ALL targets in getActiveMonitors.
fetch_logic = """
        val configs = getActiveMonitors()
        val allIds = configs.keys.toMutableSet()
        allIds.add(targetId) // keep UI selected one updated too
        
        var fetchedSina = false
        for (id in allIds) {
            if (id.startsWith("realtime_") && id != "realtime_gj") {
                val bankCode = id.removePrefix("realtime_")
                try {
                    val item = GoldRepository.fetchRealtimeBank(bankCode)
                    if (item != null) handleSingleItemUpdate(item)
                } catch(e: Exception){}
            } else {
                if (!fetchedSina) {
                    try {
                        val (allItems, jsonString) = GoldRepository.fetchGoldData()
                        if (allItems.isNotEmpty()) {
                            handleParsedData(allItems, jsonString)
                        }
                    } catch(e: Exception){}
                    fetchedSina = true
                }
            }
        }
"""
# We need to replace the while loop body in processPriceUpdate
old_fetch_logic = """
            var retryCount = 0
            var success = false
            while (retryCount < 3 && !success) {
                try {
                    if (targetId.startsWith("realtime_")) {
                        val bankCode = targetId.removePrefix("realtime_")
                        val item = GoldRepository.fetchRealtimeBank(bankCode)
                        if (item != null) {
                            handleSingleItemUpdate(item)
                            success = true
                        } else {
                            retryCount++
                            if (retryCount < 3) delay(1500L)
                        }
                    } else {
                        val (allItems, jsonString) = GoldRepository.fetchGoldData()
                        if (allItems.isNotEmpty()) {
                            handleParsedData(allItems, jsonString)
                            success = true
                        } else {
                            retryCount++
                            if (retryCount < 3) delay(1500L)
                        }
                    }
                } catch (t: Throwable) {
                    retryCount++
                    if (retryCount < 3) delay(1500L)
                }
            }
"""

if "val configs = getActiveMonitors()" not in kt:
    kt = kt.replace(old_fetch_logic.strip(), fetch_logic.strip())

# Note: handleParsedData already calls handleSingleItemUpdate for each item inside it!
# Wait, handleParsedData:
# for (item in allItems) { checkAndTriggerAlerts(item) } ?
# No, handleParsedData updates state. Let's see handleParsedData.

with open(kt_path, 'w', encoding='utf-8') as f:
    f.write(kt)

print("Updated GoldPriceService")
