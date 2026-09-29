package com.example.jinjia

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.jinjia.databinding.ActivityMainBinding
import com.example.jinjia.databinding.DialogDisplaySettingsBinding
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

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


    private lateinit var binding: ActivityMainBinding
    private lateinit var goldItemAdapter: GoldItemAdapter

    private var hasPromptedBatteryOptimization = false
    private var foregroundRefreshJob: Job? = null

    // 统一标的数据列表，初始置入 DEFAULT_TARGETS，确保任何时候绝不为空白
    private val allTargetsList = mutableListOf<GoldItem>()
    private var selectedTargetItem: GoldItem? = null
    private var currentSelectedCategory: String = GoldDataParser.CAT_REALTIME
    private var mainDashboardChartPoints: List<ChartPoint>? = null
    private var mainDashboardLondonPoints: List<ChartPoint>? = null

    // 刷新频率选项映射 (严格限制下限 >= 0.5 分钟/30秒)
    private val intervalOptions = listOf(
        Pair("0.5 分钟 (30秒)", 0.5),
        Pair("1 分钟", 1.0),
        Pair("2 分钟", 2.0),
        Pair("5 分钟 (推荐)", 5.0),
        Pair("10 分钟", 10.0),
        Pair("15 分钟", 15.0),
        Pair("30 分钟", 30.0)
    )

    // 分类展示标签列表
    private val allCategories = listOf(
        Pair(GoldDataParser.CAT_REALTIME, "⚡ 实时机构"),
        Pair(GoldDataParser.CAT_BANKS, "🏦 银行金条"),
        Pair(GoldDataParser.CAT_STORES, "🏬 品牌金店"),
        Pair(GoldDataParser.CAT_METALS, "📈 大盘行情"),
        Pair(GoldDataParser.CAT_RECYCLE, "♻️ 黄金回收")
    )

    // Android 13+ 通知权限启动器
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startMonitoring()
        } else {
            Toast.makeText(this, "需要通知权限以在前台常驻展示金价和低价告警", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // 支持锁屏展示与亮屏
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }

            // 1. 初始化预置标的数据（杜绝任何空白状态，首项必为工商银行实时行情）
            allTargetsList.clear()
            allTargetsList.addAll(GoldDataParser.DEFAULT_TARGETS)
            
            val sp = getSharedPreferences(GoldPriceService.PREFS_NAME, Context.MODE_PRIVATE)
            GoldRepository.proxyUrl = sp.getString("biquote_proxy", "")
            GoldRepository.cacheDir = cacheDir

            initRecyclerView()
            initIntervalSpinner()
            initTabs()

        val thresholdWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                saveThresholdsForCurrentTarget()
            }
        }
        binding.etHighThreshold.addTextChangedListener(thresholdWatcher)
        binding.etLowThreshold.addTextChangedListener(thresholdWatcher)

        updateMacroCalendar()
            initViews()

            // 预填充 Spinner，默认选中首项
            selectedTargetItem = allTargetsList.firstOrNull()
            updateTargetSpinner()
            selectedTargetItem?.let {
                bindTopCardItem(it)
                updateMainDashboardChart(it)
            }

            observeServiceState()

            // 2. 自动异步并发拉取双数据源最新数据（毫秒级刷新）
            fetchGoldDataImmediately()
        } catch (t: Throwable) {
            Log.e("MainActivity", "Error in onCreate: ${t.message}", t)
            Toast.makeText(this, "启动初始化提示: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            // 1. 读取本地持久化缓存直出界面
            val saved = GoldPriceService.getSavedState(this)
            updateUi(saved)

            if (saved.allItems.isNotEmpty()) {
                allTargetsList.clear()
                allTargetsList.addAll(saved.allItems)
                updateTargetSpinner()
                filterAndDisplayList()
            }

            // 2. 通知后台服务切入前台活跃模式
            try {
                startService(Intent(this, GoldPriceService::class.java).apply {
                    action = GoldPriceService.ACTION_ENTER_FOREGROUND
                })
            } catch (_: Throwable) {}

            // 3. 第一时间立即静默拉取一次最新数据
            fetchGoldDataImmediately(isSilent = true)

            // 4. 启动前台每 60 秒 (1 分钟) 自动刷新协程循环
            foregroundRefreshJob?.cancel()
            foregroundRefreshJob = lifecycleScope.launch {
                while (isActive) {
                    delay(60_000L) // 前台活跃固定 1 分钟轮询
                    if (isActive) {
                        fetchGoldDataImmediately(isSilent = true)
                    }
                }
            }

            checkBatteryOptimization()
        } catch (t: Throwable) {
            Log.e("MainActivity", "Error in onResume: ${t.message}", t)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            // 1. 暂停前台 1 分钟高频协程循环，避免熄屏/切后台时无谓消耗
            foregroundRefreshJob?.cancel()
            foregroundRefreshJob = null

            // 2. 通知后台监控服务切入后台 5 分钟常驻模式
            try {
                startService(Intent(this, GoldPriceService::class.java).apply {
                    action = GoldPriceService.ACTION_ENTER_BACKGROUND
                })
            } catch (_: Throwable) {}
        } catch (t: Throwable) {
            Log.e("MainActivity", "Error in onPause: ${t.message}", t)
        }
    }

    /**
     * 手动刷新功能：防连续点击、优先快速刷新当前选中标的并全量更新
     */
    private fun manualRefreshPrice() {
        binding.btnManualRefresh.isEnabled = false
        binding.btnManualRefresh.text = "🔄 刷新中"

        val target = selectedTargetItem ?: allTargetsList.firstOrNull()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. 若当前为高频实时机构，单独快速请求以秒级极速呈现最新价格
                if (target != null && target.id.startsWith("realtime_")) {
                    val code = target.id.removePrefix("realtime_")
                    val singleItem = GoldRepository.fetchRealtimeBank(code)
                    if (singleItem != null) {
                        withContext(Dispatchers.Main) {
                            setTargetItem(singleItem)
                        }
                    }
                }

                // 2. 触发全局行情同步
                fetchGoldDataInternal(isSilent = false)

                if (target != null) {
                    updateMainDashboardChart(target)
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "已更新至最新金价", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                Log.e("MainActivity", "manualRefreshPrice error: ${t.message}", t)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "刷新提示: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                delay(1200L) // 1.2 秒防重复点击防抖
                withContext(Dispatchers.Main) {
                    binding.btnManualRefresh.isEnabled = true
                    binding.btnManualRefresh.text = "🔄 刷新"
                }
            }
        }
    }

    /**
     * 刷新主看板日内分时动态走势对比图 (盯盘目标 vs 国际伦敦金)
     */
    private fun updateMainDashboardChart(target: GoldItem) {
        val cleanTargetName = target.title
            .removePrefix("[实时] ")
            .removePrefix("[银行] ")
            .removePrefix("[金店] ")
            .removePrefix("[大盘] ")
            .removePrefix("[回收] ")

        val tabIndex = binding.tabChartTimeframe.selectedTabPosition
        val isRealtime = tabIndex == 0
        val chartMode = if (isRealtime) 0 else 1

        val timeframeStr = when (tabIndex) {
            1 -> "1d"
            2 -> "1w"
            3 -> "1M"
            else -> "5m"
        }

        binding.tvLegendTarget.visibility = if (isRealtime) View.VISIBLE else View.GONE
        binding.tvLegendTarget.text = "● $cleanTargetName"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (isRealtime) {
                    val targetJob = async { GoldRepository.fetchIntradayChart(target.id) }
                    val londonJob = async { GoldRepository.fetchIntradayChart("realtime_gj") }
                                        var cachedDxy = emptyList<ChartPoint>()
                    val updateRealtimeCache = {
                        if (cachedDxy.isNotEmpty() && mainDashboardChartPoints != null && mainDashboardLondonPoints != null) {
                            binding.chartMainDashboard.setCompareChartData(
                                primaryPoints = mainDashboardChartPoints!!,
                                primaryTitle = cleanTargetName,
                                primaryUnit = target.unit,
                                secondaryPoints = mainDashboardLondonPoints!!,
                                secondaryTitle = "伦敦金",
                                secondaryUnit = "美元/盎司",
                                thirdPoints = cachedDxy,
                                thirdTitle = "美元指数",
                                thirdUnit = "",
                                chartMode = 0
                            )
                        }
                    }

                    val dxyJob = async { GoldRepository.fetchBiquoteOHLC("DXY", "5m") { pts ->
                        cachedDxy = pts
                        runOnUiThread { updateRealtimeCache() }
                    } }
                    
                    val targetPoints = targetJob.await()
                    val londonPoints = londonJob.await()
                    val dxyPoints = dxyJob.await()

                    mainDashboardChartPoints = targetPoints
                    mainDashboardLondonPoints = londonPoints

                    withContext(Dispatchers.Main) {
                        binding.chartMainDashboard.setCompareChartData(
                            primaryPoints = targetPoints,
                            primaryTitle = cleanTargetName,
                            primaryUnit = target.unit,
                            secondaryPoints = londonPoints,
                            secondaryTitle = "伦敦金",
                            secondaryUnit = "美元/盎司",
                            thirdPoints = dxyPoints,
                            thirdTitle = "美元指数",
                            thirdUnit = "",
                            chartMode = 0
                        )
                    }
                } else {
                                        var cachedLondon = emptyList<ChartPoint>()
                    var cachedDxy = emptyList<ChartPoint>()
                    
                    val updateKlineCache = {
                        binding.chartMainDashboard.setCompareChartData(
                            primaryPoints = emptyList(),
                            primaryTitle = "",
                            primaryUnit = "",
                            secondaryPoints = cachedLondon,
                            secondaryTitle = "伦敦金",
                            secondaryUnit = "美元/盎司",
                            thirdPoints = cachedDxy,
                            thirdTitle = "美元指数",
                            thirdUnit = "",
                            chartMode = 1
                        )
                    }

                    val londonJob = async { GoldRepository.fetchBiquoteOHLC("XAUUSD", timeframeStr) { pts -> 
                        cachedLondon = pts; runOnUiThread { updateKlineCache() }
                    } }
                    val dxyJob = async { GoldRepository.fetchBiquoteOHLC("DXY", timeframeStr) { pts -> 
                        cachedDxy = pts; runOnUiThread { updateKlineCache() }
                    } }
                    
                    val londonPoints = londonJob.await()
                    val dxyPoints = dxyJob.await()

                    withContext(Dispatchers.Main) {
                        binding.chartMainDashboard.setCompareChartData(
                            primaryPoints = emptyList(),
                            primaryTitle = "",
                            primaryUnit = "",
                            secondaryPoints = londonPoints,
                            secondaryTitle = "伦敦金",
                            secondaryUnit = "美元/盎司",
                            thirdPoints = dxyPoints,
                            thirdTitle = "美元指数",
                            thirdUnit = "",
                            chartMode = 1
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "updateMainDashboardChart error: ${e.message}")
            }
        }
    }

    /**
     * 进入 App 时的冷启动即时并发双数据源拉取（无需点击启动监控）
     */
    private fun fetchGoldDataImmediately(isSilent: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            fetchGoldDataInternal(isSilent)
        }
    }

    private suspend fun fetchGoldDataInternal(isSilent: Boolean) {
        withContext(Dispatchers.Main) {
            val currentState = GoldPriceService.monitorState.value
            if (!currentState.isRunning && !isSilent) {
                binding.tvServiceStatus.text = "● 同步行情中..."
                binding.tvServiceStatus.setBackgroundResource(R.drawable.bg_status_chip_gray)
                binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            }
        }

        val sp = getSharedPreferences(GoldPriceService.PREFS_NAME, Context.MODE_PRIVATE)
        val enabledBankCodes = GoldDataParser.REALTIME_BANKS
            .map { it.code }
            .filter { sp.getBoolean("bank_enabled_$it", true) }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. 并发拉取高频实时机构源 (数据源 A)
                val realtimeJob = async {
                    try {
                        GoldRepository.fetchAllRealtimeBanks(enabledBankCodes)
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Realtime fetch error: ${e.message}")
                        emptyList()
                    }
                }

                // 2. 并发拉取综合大盘参考行情 (数据源 B)
                val marketJob = async {
                    try {
                        GoldRepository.fetchGoldData()
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Market fetch error: ${e.message}")
                        Pair(emptyList(), "")
                    }
                }

                val realtimeItems = realtimeJob.await()
                val (marketItems, _) = marketJob.await()

                val combined = mutableListOf<GoldItem>()

                // 高频实时机构数据置顶
                if (realtimeItems.isNotEmpty()) {
                    combined.addAll(realtimeItems)
                } else {
                    combined.addAll(GoldDataParser.DEFAULT_REALTIME_BANKS.filter {
                        enabledBankCodes.contains(it.id.removePrefix("realtime_"))
                    })
                }

                // 综合行情数据紧随其后
                if (marketItems.isNotEmpty()) {
                    combined.addAll(marketItems)
                } else {
                    combined.addAll(GoldDataParser.DEFAULT_MARKET_TARGETS)
                }

                // 持久化保存合并数据
                val serialized = GoldDataParser.serializeItems(combined)
                sp.edit()
                    .putString(GoldPriceService.KEY_ALL_ITEMS_JSON, serialized)
                    .putLong(GoldPriceService.KEY_UPDATE_TIME, System.currentTimeMillis())
                    .apply()

                withContext(Dispatchers.Main) {
                    if (combined.isNotEmpty()) {
                        allTargetsList.clear()
                        allTargetsList.addAll(combined)
                        updateTargetSpinner()
                        filterAndDisplayList()

                        // 若未手动选择过标的，则默认选第一项
                        if (selectedTargetItem == null) {
                            setTargetItem(combined.first())
                        } else {
                            // 保持当前选中标的的最新价格更新
                            val current = combined.find { it.id == selectedTargetItem?.id }
                            if (current != null) {
                                setTargetItem(current)
                            }
                        }
                    }

                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    binding.tvUpdateTime.text = sdf.format(Date())

                    val running = GoldPriceService.monitorState.value.isRunning
                    if (!running) {
                        binding.tvServiceStatus.text = "● 行情已更新 (${combined.size}项)"
                        binding.tvServiceStatus.setBackgroundResource(R.drawable.bg_status_chip_gray)
                        binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    }
                }
            } catch (t: Throwable) {
                Log.e("MainActivity", "fetchGoldDataImmediately failed: ${t.message}", t)
                withContext(Dispatchers.Main) {
                    val running = GoldPriceService.monitorState.value.isRunning
                    if (!running) {
                        binding.tvServiceStatus.text = "● 拉取提示: ${t.message}"
                        binding.tvServiceStatus.setBackgroundResource(R.drawable.bg_status_chip_red)
                        binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_red))
                    }
                    Toast.makeText(this@MainActivity, "行情同步提示: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun initRecyclerView() {
        goldItemAdapter = GoldItemAdapter(
            onSelectAsTarget = { item ->
                // 点击列表卡片直接设为盯盘标的并联动 Spinner、顶部卡片与走势图
                setTargetItem(item)
                Toast.makeText(this, "已将【${item.displayName}】选为盯盘标的", Toast.LENGTH_SHORT).show()
            },
            onCopyAiPrompt = { item, cachedPoints ->
                copyAiAnalysisPromptForItem(item, cachedPoints)
            },
            onFetchChart = { item, callback ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val points = GoldRepository.fetchIntradayChart(item.id)
                    withContext(Dispatchers.Main) {
                        callback(points)
                    }
                }
            }
        )
        binding.rvGoldList.layoutManager = LinearLayoutManager(this)
        binding.rvGoldList.adapter = goldItemAdapter
    }

    private fun initIntervalSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            intervalOptions.map { it.first }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spInterval.adapter = adapter

        val saved = GoldPriceService.getSavedState(this)
        val defaultIdx = intervalOptions.indexOfFirst { it.second == saved.intervalMinutes }.let {
            if (it >= 0) it else 3
        }
        binding.spInterval.setSelection(defaultIdx)
    }

    private fun updateMacroCalendar() {
        // Find next Non-Farm Payrolls (NFP) - Usually 1st Friday of the month
        val cal = java.util.Calendar.getInstance()
        val now = cal.timeInMillis
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        while (cal.get(java.util.Calendar.DAY_OF_WEEK) != java.util.Calendar.FRIDAY) {
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        cal.set(java.util.Calendar.HOUR_OF_DAY, 20)
        cal.set(java.util.Calendar.MINUTE, 30)
        cal.set(java.util.Calendar.SECOND, 0)
        
        if (cal.timeInMillis < now) {
            cal.add(java.util.Calendar.MONTH, 1)
            cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
            while (cal.get(java.util.Calendar.DAY_OF_WEEK) != java.util.Calendar.FRIDAY) {
                cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            }
        }
        
        val diffDays = (cal.timeInMillis - now) / (1000 * 60 * 60 * 24)
        val eventStr = if (diffDays == 0L) "🔥大非农 今晚20:30公布!" else "距大非农还有 $diffDays 天"
        binding.tvMacroEvent.text = eventStr
    }

    private fun initTabs()

        val thresholdWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                saveThresholdsForCurrentTarget()
            }
        }
        binding.etHighThreshold.addTextChangedListener(thresholdWatcher)
        binding.etLowThreshold.addTextChangedListener(thresholdWatcher)
 {
        val sp = getSharedPreferences(GoldPriceService.PREFS_NAME, Context.MODE_PRIVATE)
        binding.tabLayout.removeAllTabs()

        var selectedIndex = 0
        var addedCount = 0

        for (cat in allCategories) {
            val isEnabled = sp.getBoolean("show_cat_${cat.first}", true)
            if (isEnabled) {
                val tab = binding.tabLayout.newTab().setText(cat.second).setTag(cat.first)
                binding.tabLayout.addTab(tab)
                if (cat.first == currentSelectedCategory) {
                    selectedIndex = addedCount
                }
                addedCount++
            }
        }

        if (binding.tabLayout.tabCount > 0) {
            val tabToSelect = binding.tabLayout.getTabAt(selectedIndex)
            tabToSelect?.select()
            currentSelectedCategory = tabToSelect?.tag as? String ?: GoldDataParser.CAT_REALTIME
        }

        binding.tabLayout.clearOnTabSelectedListeners()
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentSelectedCategory = tab?.tag as? String ?: GoldDataParser.CAT_REALTIME
                filterAndDisplayList()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {
                filterAndDisplayList()
            }
        })
    }

    private fun initViews() {
        binding.btnStart.setOnClickListener {
            checkPermissionAndStart()
        }

        binding.btnStop.setOnClickListener {
            stopMonitoring()
        }

        binding.btnSettings.setOnClickListener {
            showDisplaySettingsDialog()
        }

        // 核心 AI 辅助分析：一键复制走势给 AI 分析
        binding.btnCopyAiPrompt.setOnClickListener {
            copyAiAnalysisPrompt()
        }

        binding.btnProfitCalculator.setOnClickListener {
            val target = selectedTargetItem ?: allTargetsList.firstOrNull()
            if (target != null) {
                com.example.jinjia.ui.ProfitCalculatorDialog(this, target).show()
            } else {
                Toast.makeText(this, "请先选择盯盘标的", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCopyAiPrompt.setOnLongClickListener {
            val logs = GoldRepository.DebugLogger.getLogText()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Debug Logs", logs)
            clipboard.setPrimaryClip(clip)
            android.widget.Toast.makeText(this, "调试日志已复制到剪贴板", android.widget.Toast.LENGTH_LONG).show()
            true
        }

        binding.btnDashboardAiPrompt.setOnClickListener {
            copyAiAnalysisPrompt()
        }

        // 手动刷新按钮
        binding.btnManualRefresh.setOnClickListener {
            manualRefreshPrice()
        }

        // 小米/澎湃 OS 系统锁屏通知与保活设置向导
        binding.btnHyperOsGuide.setOnClickListener {
            showHyperOsGuideDialog()
        }

        // 主看板走势图缩放监听与一键还原按钮联动
        binding.chartMainDashboard.onZoomChangeListener = { isZoomed, scale ->
            binding.btnResetChartZoom.visibility = if (isZoomed) View.VISIBLE else View.GONE
            if (isZoomed) {
                binding.btnResetChartZoom.text = "↺ 还原 (%.1fx)".format(scale)
            }
        }

        binding.btnResetChartZoom.setOnClickListener {
            binding.chartMainDashboard.resetZoom()
        }

        binding.tabChartTimeframe.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val target = selectedTargetItem ?: allTargetsList.firstOrNull()
                if (target != null) {
                    updateMainDashboardChart(target)
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {
                val target = selectedTargetItem ?: allTargetsList.firstOrNull()
                if (target != null) {
                    updateMainDashboardChart(target)
                }
            }
        })
    }

    /**
     * 设置当前选中的标的，并立即联动刷新顶部卡片与走势图
     */
    private fun setTargetItem(item: GoldItem) {
        selectedTargetItem = item
        bindTopCardItem(item)

        val idx = allTargetsList.indexOfFirst { it.id == item.id }
        if (idx >= 0 && binding.spTarget.adapter != null && binding.spTarget.selectedItemPosition != idx) {
            binding.spTarget.setSelection(idx)
        }

        // 若输入框为空，推荐预填当前价少 5 元作为参考
        if (false) {
            val suggested = (item.price - 5.0).coerceAtLeast(1.0)
            // binding.etThreshold.setText("%.2f".format(suggested))
        }

        updateMainDashboardChart(item)
    }

    private fun bindTopCardItem(item: GoldItem) {
        binding.tvCurrentTargetTitle.text = item.displayName
        binding.tvTargetPrice.text = item.priceDisplay

        binding.tvPriceBadge.text = when (item.category) {
            GoldDataParser.CAT_REALTIME -> "高频实盘"
            GoldDataParser.CAT_BANKS -> "银行金条"
            GoldDataParser.CAT_STORES -> "品牌金价"
            GoldDataParser.CAT_METALS -> "大盘现货"
            GoldDataParser.CAT_RECYCLE -> "回收指导"
            else -> "实盘参考"
        }
    }

    /**
     * 刷新并更新下拉标的列表，选择联动顶部价格
     * 高频实时标的带有 ⚡ 标识并优先展示
     */
    private fun updateTargetSpinner() {
        if (allTargetsList.isEmpty()) return

        val sp = getSharedPreferences(GoldPriceService.PREFS_NAME, Context.MODE_PRIVATE)

        // 过滤掉已被禁用的高频银行
        val displayItems = allTargetsList.filter { item ->
            if (item.category == GoldDataParser.CAT_REALTIME) {
                val code = item.id.removePrefix("realtime_")
                sp.getBoolean("bank_enabled_$code", true)
            } else {
                true
            }
        }

        if (displayItems.isEmpty()) return

        val displayLabels = displayItems.map { item ->
            val symbol = if (item.unit.contains("美元") || item.id == "realtime_gj") "$" else "¥"
            val icon = when (item.category) {
                GoldDataParser.CAT_REALTIME -> "⚡"
                GoldDataParser.CAT_BANKS -> "🏦"
                GoldDataParser.CAT_STORES -> "🏬"
                GoldDataParser.CAT_METALS -> "📈"
                GoldDataParser.CAT_RECYCLE -> "♻️"
                else -> "📌"
            }
            "$icon ${item.displayName}  ($symbol%.2f %s)".format(item.price, item.unit)
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayLabels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spTarget.adapter = adapter

        // 默认恢复之前已选或首项
        val saved = GoldPriceService.getSavedState(this)
        val selectedIdx = displayItems.indexOfFirst { it.id == (selectedTargetItem?.id ?: saved.targetId) }.let {
            if (it >= 0) it else 0
        }
        binding.spTarget.setSelection(selectedIdx)
        selectedTargetItem = displayItems.getOrNull(selectedIdx)

        binding.spTarget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
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
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun filterAndDisplayList() {
        val sp = getSharedPreferences(GoldPriceService.PREFS_NAME, Context.MODE_PRIVATE)
        val isCatEnabled = sp.getBoolean("show_cat_$currentSelectedCategory", true)

        if (!isCatEnabled || binding.tabLayout.tabCount == 0) {
            goldItemAdapter.submitList(emptyList())
            binding.tvEmptyList.visibility = View.VISIBLE
            binding.tvEmptyList.text = "该分类展示已在【展示管理】中被关闭"
            return
        }

        var filtered = allTargetsList.filter { it.category == currentSelectedCategory }

        // 若是实时机构分类，过滤掉未勾选启用的银行
        if (currentSelectedCategory == GoldDataParser.CAT_REALTIME) {
            filtered = filtered.filter { item ->
                val code = item.id.removePrefix("realtime_")
                sp.getBoolean("bank_enabled_$code", true)
            }
        }

        goldItemAdapter.submitList(filtered)

        if (filtered.isEmpty()) {
            binding.tvEmptyList.visibility = View.VISIBLE
            binding.tvEmptyList.text = "暂无数据或所选机构已被关闭显示"
        } else {
            binding.tvEmptyList.visibility = View.GONE
        }
    }

    /**
     * 核心功能：主看板复制走势给 AI 分析
     */
    private fun copyAiAnalysisPromptForItem(item: GoldItem, preloadedPoints: List<ChartPoint>?) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "⏳ 正在为【${item.displayName}】构建全维度数据...", Toast.LENGTH_SHORT).show() }
                val londonMonthlyJob = async { GoldRepository.fetchBiquoteOHLC("XAUUSD", "1M") }
                val dxyMonthlyJob = async { GoldRepository.fetchBiquoteOHLC("DXY", "1M") }
                val londonDailyJob = async { GoldRepository.fetchBiquoteOHLC("XAUUSD", "1d") }
                val dxyDailyJob = async { GoldRepository.fetchBiquoteOHLC("DXY", "1d") }
                val londonRtJob = async { GoldRepository.fetchBiquoteOHLC("XAUUSD", "5m") }
                val dxyRtJob = async { GoldRepository.fetchBiquoteOHLC("DXY", "5m") }
                val targetRtJob = async { if (!preloadedPoints.isNullOrEmpty()) preloadedPoints else GoldRepository.fetchIntradayChart(item.id) }

                val promptText = buildMegaAiPrompt(
                    item = item,
                    londonMonthly = londonMonthlyJob.await(),
                    dxyMonthly = dxyMonthlyJob.await(),
                    londonDaily = londonDailyJob.await(),
                    dxyDaily = dxyDailyJob.await(),
                    londonRt = londonRtJob.await(),
                    dxyRt = dxyRtJob.await(),
                    targetRt = targetRtJob.await()
                )
                withContext(Dispatchers.Main) {
                    val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Gold AI Analysis Prompt", promptText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@MainActivity, "已生成【${item.displayName}】全维度专业提示词！", Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {}
        }
    }

    private fun copyAiAnalysisPrompt() {
        val item = selectedTargetItem ?: allTargetsList.firstOrNull() ?: return
        binding.btnCopyAiPrompt.isEnabled = false
        binding.btnCopyAiPrompt.text = "⏳ 正在构建全维度多周期数据..."
        binding.btnDashboardAiPrompt.isEnabled = false
        binding.btnDashboardAiPrompt.text = "⏳ 分析中"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val londonMonthlyJob = async { GoldRepository.fetchBiquoteOHLC("XAUUSD", "1M") }
                val dxyMonthlyJob = async { GoldRepository.fetchBiquoteOHLC("DXY", "1M") }
                
                val londonDailyJob = async { GoldRepository.fetchBiquoteOHLC("XAUUSD", "1d") }
                val dxyDailyJob = async { GoldRepository.fetchBiquoteOHLC("DXY", "1d") }
                
                val londonRtJob = async { GoldRepository.fetchBiquoteOHLC("XAUUSD", "5m") }
                val dxyRtJob = async { GoldRepository.fetchBiquoteOHLC("DXY", "5m") }
                
                val targetRtJob = async { GoldRepository.fetchIntradayChart(item.id) }

                val promptText = buildMegaAiPrompt(
                    item = item,
                    londonMonthly = londonMonthlyJob.await(),
                    dxyMonthly = dxyMonthlyJob.await(),
                    londonDaily = londonDailyJob.await(),
                    dxyDaily = dxyDailyJob.await(),
                    londonRt = londonRtJob.await(),
                    dxyRt = dxyRtJob.await(),
                    targetRt = targetRtJob.await()
                )

                withContext(Dispatchers.Main) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Gold AI Analysis Prompt", promptText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@MainActivity, "已生成【宏观->微观】多维度专业研报提示词！", Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                Log.e("MainActivity", "copyAiAnalysisPrompt error: ${t.message}", t)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "拉取大周期数据提示: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.btnCopyAiPrompt.isEnabled = true
                    binding.btnCopyAiPrompt.text = "🤖 复制走势给 AI 分析"
                    binding.btnDashboardAiPrompt.isEnabled = true
                    binding.btnDashboardAiPrompt.text = "🤖 AI分析"
                }
            }
        }
    }

    private fun formatKLinePoints(londonPts: List<ChartPoint>, dxyPts: List<ChartPoint>, maxCount: Int, isMonth: Boolean): String {
        val sampled = if (londonPts.size > maxCount) londonPts.takeLast(maxCount) else londonPts
        val sampledDxy = if (dxyPts.size > maxCount) dxyPts.takeLast(maxCount) else dxyPts
        val sb = java.lang.StringBuilder()
        val formatStr = if (isMonth) "yyyy-MM" else "MM-dd"
        val dateSdf = java.text.SimpleDateFormat(formatStr, java.util.Locale.getDefault())
        
        sampled.forEach { pt ->
            val tMillis = if (pt.timestamp < 100_000_000_000L) pt.timestamp * 1000L else pt.timestamp
            val date = dateSdf.format(java.util.Date(tMillis))
            val matchDxy = sampledDxy.minByOrNull { kotlin.math.abs(it.timestamp - pt.timestamp) }
            val dxyPriceStr = if (matchDxy != null) String.format("%.2f", matchDxy.price) else "N/A"
            sb.append("- $date: 黄金[开$${String.format("%.2f", pt.open)} 高$${String.format("%.2f", pt.high)} 低$${String.format("%.2f", pt.low)} 收$${String.format("%.2f", pt.price)}] | 美指[收$dxyPriceStr]\n")
        }
        return sb.toString().trimEnd()
    }

    private fun buildMegaAiPrompt(
        item: GoldItem,
        londonMonthly: List<ChartPoint>, dxyMonthly: List<ChartPoint>,
        londonDaily: List<ChartPoint>, dxyDaily: List<ChartPoint>,
        londonRt: List<ChartPoint>, dxyRt: List<ChartPoint>,
        targetRt: List<ChartPoint>
    ): String {
        val monthlyStr = formatKLinePoints(londonMonthly, dxyMonthly, 15, true)
        val dailyStr = formatKLinePoints(londonDaily, dxyDaily, 20, false)
        
        val sampledRt = sampleChartPoints(targetRt, 15)
        val rtSb = java.lang.StringBuilder()
        val timeSdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        sampledRt.forEach { pt ->
            val tMillis = if (pt.timestamp < 100_000_000_000L) pt.timestamp * 1000L else pt.timestamp
            rtSb.append("- ${timeSdf.format(java.util.Date(tMillis))}: ${String.format("%.2f", pt.price)}\n")
        }
        
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        
        return """
# Role
你是一位拥有20年实战经验的华尔街大宗商品首席宏观策略师与贵金属量化交易专家。你精通“宏观大趋势研判 -> 中期波段结构 -> 日内微观盘口”的自上而下(Top-Down)工程化分析框架，并熟练运用美元指数(DXY)作为黄金定价的核心锚点进行跨资产联动定性。

# Task
请基于我刚刚抓取的多周期（月K、日K、今日分时）数据，为我出具一份“自上而下、分步递进”的黄金行情综合研报。当前重点操作标的为：【${item.displayName}】。

# Context (数据提取时间：$dateStr)
## 第一梯队：宏观大趋势 (最近15个月的 月K线 黄金与美指收盘对比)
$monthlyStr

## 第二梯队：中期波段形态 (最近20个交易日 日K线 黄金与美指对比)
$dailyStr

## 第三梯队：日内微观盘口 (【${item.displayName}】今日分时抽样，单位：${item.unit})
${rtSb.toString().trimEnd()}
*(参考：今日外盘伦敦金最新价 $${String.format("%.2f", londonRt.lastOrNull()?.price ?: 0.0)}/盎司，美元指数最新 ${String.format("%.2f", dxyRt.lastOrNull()?.price ?: 0.0)})*

# Output Format (请严格按照以下步骤分段输出，展现专业性)
### 第一步：大趋势定调 (Macro Trend)
通过月K线级别的黄金与美元指数负相关性钝化/强化程度，判断当前黄金处于宏观上的什么周期（如：战略性主升浪、高位宽幅洗盘、熊市下跌通道），为整体操作方向定调（战略看多/看空）。

### 第二步：中期形态与关键位 (Medium-Term Structure)
通过日K线走势，识别当前是否出现了关键的反转或中继形态（如楔形突破、吞没形态等）。给出下方铁底支撑区间与上方强阻力位。

### 第三步：今日日内博弈研判 (Intraday Micro-Action)
结合【${item.displayName}】的分时走势、日内振幅以及今日外盘/美指的最新状态，判断今天的多空力量对比（例如：窄幅震荡、单边逼空、诱多杀跌）。

### 第四步：实操交易指令 (Actionable Plan)
1. **长线现货/实物囤金者**：当前是否为左侧定投/重仓买入的绝佳节点？
2. **中短线/日内杠杆交易者**：给出明确的右侧跟进策略、做单点位（入场价）、防守底线（止损价）及目标利润空间（止盈价）。
""".trimIndent()
    }

    private fun sampleChartPoints(points: List<ChartPoint>, targetCount: Int = 36): List<ChartPoint> {
        if (points.size <= targetCount) return points
        val result = mutableListOf<ChartPoint>()
        val step = points.size.toDouble() / (targetCount - 1)
        for (i in 0 until targetCount - 1) {
            val index = (i * step).toInt().coerceIn(0, points.size - 1)
            result.add(points[index])
        }
        result.add(points.last())
        return result
    }

    private fun showDisplaySettingsDialog() {
        val sp = getSharedPreferences(GoldPriceService.PREFS_NAME, Context.MODE_PRIVATE)
        val dialogBinding = DialogDisplaySettingsBinding.inflate(layoutInflater)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        // 1. 初始化实时机构勾选状态
        dialogBinding.cbBankIcbc.isChecked = sp.getBoolean("bank_enabled_icbc", true)
        dialogBinding.cbBankZs.isChecked = sp.getBoolean("bank_enabled_zs", true)
        dialogBinding.cbBankMs.isChecked = sp.getBoolean("bank_enabled_ms", true)
        dialogBinding.cbBankCgb.isChecked = sp.getBoolean("bank_enabled_cgb", true)
        dialogBinding.cbBankCib.isChecked = sp.getBoolean("bank_enabled_cib", true)
        dialogBinding.cbBankJd.isChecked = sp.getBoolean("bank_enabled_jd", true)
        dialogBinding.cbBankGj.isChecked = sp.getBoolean("bank_enabled_gj", true)

        // 2. 初始化分类模块勾选状态
        dialogBinding.cbCatRealtime.isChecked = sp.getBoolean("show_cat_${GoldDataParser.CAT_REALTIME}", true)
        dialogBinding.cbCatBanks.isChecked = sp.getBoolean("show_cat_${GoldDataParser.CAT_BANKS}", true)
        dialogBinding.cbCatStores.isChecked = sp.getBoolean("show_cat_${GoldDataParser.CAT_STORES}", true)
        dialogBinding.cbCatMetals.isChecked = sp.getBoolean("show_cat_${GoldDataParser.CAT_METALS}", true)
        dialogBinding.cbCatRecycle.isChecked = sp.getBoolean("show_cat_${GoldDataParser.CAT_RECYCLE}", true)

        // 初始化代理地址
        val currentProxy = sp.getString("biquote_proxy", "")
        dialogBinding.etProxyUrl.setText(currentProxy)

        // 全选 / 反选机构快捷按钮
        val bankCheckBoxes = listOf(
            dialogBinding.cbBankIcbc,
            dialogBinding.cbBankZs,
            dialogBinding.cbBankMs,
            dialogBinding.cbBankCgb,
            dialogBinding.cbBankCib,
            dialogBinding.cbBankJd,
            dialogBinding.cbBankGj
        )
        dialogBinding.btnToggleAllBanks.setOnClickListener {
            val allChecked = bankCheckBoxes.all { it.isChecked }
            bankCheckBoxes.forEach { it.isChecked = !allChecked }
        }

        // 取消按钮
        dialogBinding.btnDialogCancel.setOnClickListener {
            dialog.dismiss()
        }

        // 保存并应用按钮
        dialogBinding.btnDialogSave.setOnClickListener {
            val newProxy = dialogBinding.etProxyUrl.text.toString().trim()
            sp.edit()
                // 保存银行开关
                .putBoolean("bank_enabled_icbc", dialogBinding.cbBankIcbc.isChecked)
                .putBoolean("bank_enabled_zs", dialogBinding.cbBankZs.isChecked)
                .putBoolean("bank_enabled_ms", dialogBinding.cbBankMs.isChecked)
                .putBoolean("bank_enabled_cgb", dialogBinding.cbBankCgb.isChecked)
                .putBoolean("bank_enabled_cib", dialogBinding.cbBankCib.isChecked)
                .putBoolean("bank_enabled_jd", dialogBinding.cbBankJd.isChecked)
                .putBoolean("bank_enabled_gj", dialogBinding.cbBankGj.isChecked)
                // 保存分类开关
                .putBoolean("show_cat_${GoldDataParser.CAT_REALTIME}", dialogBinding.cbCatRealtime.isChecked)
                .putBoolean("show_cat_${GoldDataParser.CAT_BANKS}", dialogBinding.cbCatBanks.isChecked)
                .putBoolean("show_cat_${GoldDataParser.CAT_STORES}", dialogBinding.cbCatStores.isChecked)
                .putBoolean("show_cat_${GoldDataParser.CAT_METALS}", dialogBinding.cbCatMetals.isChecked)
                .putBoolean("show_cat_${GoldDataParser.CAT_RECYCLE}", dialogBinding.cbCatRecycle.isChecked)
                .putString("biquote_proxy", newProxy)
                .apply()

            GoldRepository.proxyUrl = newProxy
            dialog.dismiss()

            // 即时刷新 UI
            initTabs()

        val thresholdWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                saveThresholdsForCurrentTarget()
            }
        }
        binding.etHighThreshold.addTextChangedListener(thresholdWatcher)
        binding.etLowThreshold.addTextChangedListener(thresholdWatcher)

        updateMacroCalendar()
            updateTargetSpinner()
            filterAndDisplayList()
            fetchGoldDataImmediately()

            Toast.makeText(this, "设置已保存并生效", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun checkPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionStatus = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startMonitoring()
    }

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
 catch (t: Throwable) {
            Log.e("MainActivity", "startMonitoring failed: ${t.message}", t)
            // 全量防崩溃：启动异常时自动重置运行状态，绝不导致死循环闪退
            getSharedPreferences(GoldPriceService.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(GoldPriceService.KEY_IS_RUNNING, false)
                .apply()
            updateUi(GoldPriceService.getSavedState(this))
            Toast.makeText(this, "启动监控异常: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopMonitoring() {
        try {
            val intent = Intent(this, GoldPriceService::class.java).apply {
                action = GoldPriceService.ACTION_STOP
            }
            startService(intent)
            Toast.makeText(this, "监控服务已停止", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Log.e("MainActivity", "stopMonitoring failed: ${t.message}", t)
        }
    }

    private fun observeServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                GoldPriceService.monitorState.collect { state ->
                    updateUi(state)
                    if (state.allItems.isNotEmpty() && state.allItems != allTargetsList) {
                        allTargetsList.clear()
                        allTargetsList.addAll(state.allItems)
                        updateTargetSpinner()
                        filterAndDisplayList()
                    }
                }
            }
        }
    }

    private fun updateUi(state: GoldPriceService.MonitorState) {
        // 1. 按钮与输入框交互控制
        binding.btnStart.isEnabled = !state.isRunning
        binding.btnStop.isEnabled = state.isRunning
        binding.etHighThreshold.isEnabled = !state.isRunning
        binding.etLowThreshold.isEnabled = !state.isRunning
        binding.spInterval.isEnabled = !state.isRunning
        binding.spTarget.isEnabled = !state.isRunning

        // 2. 状态胶囊 Badge 展现
        if (state.isRunning) {
            binding.tvServiceStatus.text = "● 监控中 (自适应)"
            binding.tvServiceStatus.setBackgroundResource(R.drawable.bg_status_chip_green)
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            if (binding.tvServiceStatus.text.contains("未运行") || binding.tvServiceStatus.text.contains("已停止")) {
                binding.tvServiceStatus.text = "● " + state.statusMessage
                binding.tvServiceStatus.setBackgroundResource(R.drawable.bg_status_chip_red)
                binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))
            }
        }

        // 3. 标的名称与单价
        if (state.targetTitle.isNotBlank()) {
            binding.tvCurrentTargetTitle.text = state.targetTitle
        }
        if (state.targetPrice != null && state.targetPrice > 0) {
            val symbol = if (state.targetTitle.contains("伦敦金") || state.targetId == "realtime_gj") "$" else "¥"
            val unit = if (state.targetId == "realtime_gj") "美元/盎司" else "元/克"
            binding.tvTargetPrice.text = "$symbol %.2f %s".format(state.targetPrice, unit)
        }

        // 4. 设定阈值与刷新频率 (前台1分钟，后台固定5分钟)
                val sp2 = getSharedPreferences(GoldPriceService.PREFS_NAME, Context.MODE_PRIVATE)
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
        }
        binding.tvCurrentInterval.text = "前台1m / 后台5m"

        // 5. 更新时间
        if (state.updateTime != null && state.updateTime > 0) {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            binding.tvUpdateTime.text = sdf.format(Date(state.updateTime))
        }
    }

    private var hyperOsGuideDialog: AlertDialog? = null
    private var hasVisitedStep1 = false
    private var hasVisitedStep2 = false
    private var hasVisitedStep3 = false

    /**
     * 小米澎湃 OS (HyperOS) / MIUI 锁屏通知与后台保活设置专向弹窗
     * 解决设置返回后弹窗消失问题：弹窗常驻不关闭，直到用户主动点击【确定】才退出
     */
    private fun showHyperOsGuideDialog() {
        if (hyperOsGuideDialog?.isShowing == true) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_hyperos_guide, null)
        val btnStep1 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGuideStep1)
        val btnStep2 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGuideStep2)
        val btnStep3 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGuideStep3)
        val btnDone = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGuideDone)

        val tvStep1Title = dialogView.findViewById<android.widget.TextView>(R.id.tvGuideStep1Title)
        val tvStep2Title = dialogView.findViewById<android.widget.TextView>(R.id.tvGuideStep2Title)
        val tvStep3Title = dialogView.findViewById<android.widget.TextView>(R.id.tvGuideStep3Title)

        fun updateStepStatusUi() {
            if (hasVisitedStep1) {
                btnStep1.text = "已前往 ✓"
                tvStep1Title.text = "1. 开启锁屏与悬浮通知 (已前往)"
            }
            if (hasVisitedStep2) {
                btnStep2.text = "已前往 ✓"
                tvStep2Title.text = "2. 省电策略设为【无限制】(已前往)"
            }
            if (hasVisitedStep3) {
                btnStep3.text = "已前往 ✓"
                tvStep3Title.text = "3. 自启动与后台弹出界面 (已前往)"
            }
        }

        updateStepStatusUi()

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        // 步骤1：通知管理（点击不关闭弹窗，方便返回后继续设置步骤2）
        btnStep1.setOnClickListener {
            hasVisitedStep1 = true
            updateStepStatusUi()
            XiaomiPermissionHelper.openNotificationSettings(this)
        }

        // 步骤2：省电策略（点击不关闭弹窗，方便返回后继续设置步骤3）
        btnStep2.setOnClickListener {
            hasVisitedStep2 = true
            updateStepStatusUi()
            XiaomiPermissionHelper.openBatterySettings(this)
        }

        // 步骤3：权限管理与自启动（点击不关闭弹窗）
        btnStep3.setOnClickListener {
            hasVisitedStep3 = true
            updateStepStatusUi()
            XiaomiPermissionHelper.openPermissionsSettings(this)
        }

        // 唯一点确定才关闭弹窗
        btnDone.setOnClickListener {
            dialog.dismiss()
            hyperOsGuideDialog = null
        }

        dialog.setOnDismissListener {
            hyperOsGuideDialog = null
        }

        hyperOsGuideDialog = dialog
        dialog.show()
    }

    private fun checkBatteryOptimization() {
        if (hasPromptedBatteryOptimization) return

        if (XiaomiPermissionHelper.isXiaomi()) {
            hasPromptedBatteryOptimization = true
            showHyperOsGuideDialog()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    hasPromptedBatteryOptimization = true
                    AlertDialog.Builder(this)
                        .setTitle("后台长效运行权限")
                        .setMessage("为保证手机熄屏或切到后台时系统能准时唤醒闹钟并拉取最新行情，建议允许本应用忽略电池优化。")
                        .setPositiveButton("去允许") { _, _ ->
                            try {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:$packageName")
                                }
                                startActivity(intent)
                            } catch (e: Exception) {
                                try {
                                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                } catch (_: Exception) {}
                            }
                        }
                        .setNegativeButton("稍后", null)
                        .show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
