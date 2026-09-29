import re

kt_path = 'app/src/main/java/com/example/jinjia/MainActivity.kt'
with open(kt_path, 'r', encoding='utf-8') as f:
    kt = f.read()

# Models
multi_logic = """
    // ------------------ Multi-Target Monitoring Logic ------------------
    private fun getMonitorConfigs(): MutableMap<String, org.json.JSONObject> {
        val sp = getSharedPreferences(GoldPriceService.PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = sp.getString("monitor_configs", "{}") ?: "{}"
        val map = mutableMapOf<String, org.json.JSONObject>()
        try {
            val root = org.json.JSONObject(jsonStr)
            for (key in root.keys()) {
                map[key] = root.getJSONObject(key)
            }
        } catch (e: Exception) {}
        return map
    }

    private fun saveMonitorConfigs(map: Map<String, org.json.JSONObject>) {
        val sp = getSharedPreferences(GoldPriceService.PREFS_NAME, Context.MODE_PRIVATE)
        val root = org.json.JSONObject()
        for ((k, v) in map) root.put(k, v)
        sp.edit().putString("monitor_configs", root.toString()).apply()
    }

    private fun loadThresholdsForCurrentTarget() {
        val configs = getMonitorConfigs()
        val cfg = configs[currentSelectedId]
        if (cfg != null) {
            val high = cfg.optDouble("high", 0.0)
            val low = cfg.optDouble("low", 0.0)
            binding.etHighThreshold.setText(if (high > 0) high.toString() else "")
            binding.etLowThreshold.setText(if (low > 0) low.toString() else "")
        } else {
            binding.etHighThreshold.setText("")
            binding.etLowThreshold.setText("")
        }
    }

    private fun saveThresholdsForCurrentTarget() {
        if (currentSelectedId.isEmpty()) return
        val highStr = binding.etHighThreshold.text.toString()
        val lowStr = binding.etLowThreshold.text.toString()
        val high = highStr.toDoubleOrNull() ?: 0.0
        val low = lowStr.toDoubleOrNull() ?: 0.0
        
        val configs = getMonitorConfigs()
        if (high == 0.0 && low == 0.0) {
            configs.remove(currentSelectedId)
        } else {
            val obj = org.json.JSONObject()
            obj.put("high", high)
            obj.put("low", low)
            obj.put("title", currentSelectedTitle)
            configs[currentSelectedId] = obj
        }
        saveMonitorConfigs(configs)
    }
"""

if "private fun getMonitorConfigs" not in kt:
    kt = kt.replace('class MainActivity : AppCompatActivity() {', 'class MainActivity : AppCompatActivity() {\n' + multi_logic)

# Replace etThreshold bindings
if "etHighThreshold" not in kt:
    # Need to replace textwatchers and logic
    # The original has: 
    # val thresholdText = binding.etThreshold.text.toString()
    # It might also have saved it to SP directly.
    pass

# Replace spTarget.onItemSelectedListener
sp_logic = """
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position < spinnerItems.size) {
                    val item = spinnerItems[position]
                    currentSelectedId = item.id
                    currentSelectedTitle = item.displayName
                    saveLastSelectedTarget(item.id)
                    loadThresholdsForCurrentTarget()
                    
                    // лʱҲˢͼ
                    if (isKLineMode) {
                        fetchAndDrawKLine(currentSelectedId)
                    } else {
                        updateChartUI(item.id)
                    }
                }
            }
"""
kt = re.sub(r'override fun onItemSelected\(parent: AdapterView<\*>\?, view: View\?, position: Int, id: Long\) \{.*?\n\s+\}', 
            sp_logic.strip(), kt, flags=re.DOTALL)


# Replace etThreshold text watcher
et_watcher = """
        val thresholdWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                saveThresholdsForCurrentTarget()
            }
        }
        binding.etHighThreshold.addTextChangedListener(thresholdWatcher)
        binding.etLowThreshold.addTextChangedListener(thresholdWatcher)
"""
# Find and remove old etThreshold watcher if it exists, or just add it to onCreate.
# I'll just append it to initTabs() or onCreate()
if "binding.etHighThreshold.addTextChangedListener" not in kt:
    kt = kt.replace('initTabs()', 'initTabs()\n' + et_watcher)


# Replace button click listener
btn_logic = """
        binding.btnToggleMonitor.setOnClickListener {
            val sp = getSharedPreferences(GoldPriceService.PREFS_NAME, Context.MODE_PRIVATE)
            val isRunning = sp.getBoolean("is_running", false)
            if (isRunning) {
                stopMonitor()
            } else {
                startMonitor()
            }
        }
"""
kt = re.sub(r'binding\.btnToggleMonitor\.setOnClickListener \{.*?\}', btn_logic.strip(), kt, flags=re.DOTALL)

with open(kt_path, 'w', encoding='utf-8') as f:
    f.write(kt)

print("Patched MainActivity")
