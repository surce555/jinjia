import re

kt_path = 'app/src/main/java/com/example/jinjia/MainActivity.kt'
with open(kt_path, 'r', encoding='utf-8') as f:
    kt = f.read()

kt = re.sub(r'if \(state\.targetThreshold != null && state\.targetThreshold > 0\) \{.*?\} else \{[\s\n]+binding\.tvCurrentThreshold\.text = ".*?"[\s\n]+\}', 
'''        val sp2 = getSharedPreferences(GoldPriceService.PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = sp2.getString("monitor_configs", "{}") ?: "{}"
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
        }''', kt, flags=re.DOTALL)

with open(kt_path, 'w', encoding='utf-8') as f:
    f.write(kt)

print("Patched UI state")
