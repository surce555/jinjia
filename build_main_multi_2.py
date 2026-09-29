import re

kt_path = 'app/src/main/java/com/example/jinjia/MainActivity.kt'
with open(kt_path, 'r', encoding='utf-8') as f:
    kt = f.read()

# Replace etThreshold auto-populate logic
auto_pop = """        if (binding.etHighThreshold.text.isNullOrBlank() && binding.etLowThreshold.text.isNullOrBlank()) {
            val suggestedHigh = lastFetchedPrice * 1.01
            val suggestedLow = lastFetchedPrice * 0.99
            // binding.etHighThreshold.setText("%.2f".format(suggestedHigh))
            // binding.etLowThreshold.setText("%.2f".format(suggestedLow))
        }"""
kt = re.sub(r'if \(binding\.etThreshold\.text\.isNullOrBlank\(\)\) \{.*?\n\s+\}', auto_pop, kt, flags=re.DOTALL)

# Replace etThreshold.isEnabled = !state.isRunning
kt = kt.replace('binding.etThreshold.isEnabled = !state.isRunning',
                'binding.etHighThreshold.isEnabled = !state.isRunning\n        binding.etLowThreshold.isEnabled = !state.isRunning')
kt = kt.replace('binding.tilThreshold.isEnabled = !state.isRunning',
                'binding.tilHighThreshold.isEnabled = !state.isRunning\n        binding.tilLowThreshold.isEnabled = !state.isRunning')

# Replace tvCurrentThreshold update logic
tv_logic = """
        val sp = getSharedPreferences(GoldPriceService.PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = sp.getString("monitor_configs", "{}") ?: "{}"
        try {
            val root = org.json.JSONObject(jsonStr)
            val count = root.length()
            if (count > 0) {
                binding.tvCurrentThreshold.text = "已配置 $count 个目标"
            } else {
                binding.tvCurrentThreshold.text = "未设置"
            }
        } catch (e: Exception) {
            binding.tvCurrentThreshold.text = "未设置"
        }
        
        // Removed old targetThreshold logic
"""
# The old logic is:
# if (state.targetThreshold != null && state.targetThreshold > 0) { ... } else { binding.tvCurrentThreshold.text = "未设置" }
# Wait, I don't know the exact string to replace. I'll just write a script to find and replace that block.
