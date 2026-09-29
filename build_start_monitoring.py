import re

kt_path = 'app/src/main/java/com/example/jinjia/MainActivity.kt'
with open(kt_path, 'r', encoding='utf-8') as f:
    kt = f.read()

start_monitor = """
    private fun startMonitoring() {
        // Now it monitors ALL targets configured in SharedPreferences map
        // We only need to pass the interval
        val interval = intervalOptions.getOrNull(binding.spInterval.selectedItemPosition)?.second ?: 5.0
        val target = selectedTargetItem ?: allTargetsList.firstOrNull()

        try {
            val intent = Intent(this, GoldPriceService::class.java).apply {
                action = GoldPriceService.ACTION_START
                putExtra(GoldPriceService.EXTRA_INTERVAL_MINUTES, interval)
                putExtra(GoldPriceService.EXTRA_TARGET_ID, target?.id ?: "realtime_icbc")
                putExtra(GoldPriceService.EXTRA_TARGET_NAME, target?.displayName ?: "[实时] 工商银行")
            }
            ContextCompat.startForegroundService(this, intent)
            binding.btnToggleMonitor.text = "停止全局监控"
        } catch (e: Exception) {
            Toast.makeText(this, "启动监控失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
"""

kt = re.sub(r'\n\s*private fun startMonitoring\(\).*?\}\n\s*\}', '\n' + start_monitor, kt, flags=re.DOTALL)

with open(kt_path, 'w', encoding='utf-8') as f:
    f.write(kt)

print("Patched startMonitoring")
