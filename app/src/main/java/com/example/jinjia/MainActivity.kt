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

    private lateinit var binding: ActivityMainBinding
    private lateinit var goldItemAdapter: GoldItemAdapter

    private var hasPromptedBatteryOptimization = false
    private var foregroundRefreshJob: Job? = null

    // 统一标的数据列表，初始置入 DEFAULT_TARGETS，确保任何时候绝不为空白
    private val allTargetsList = mutableListOf<GoldItem>()
    private var selectedTargetItem: GoldItem? = null
    private var currentSelectedCategory: String = GoldDataParser.CAT_REALTIME
    private var mainDashboardChartPoints: List<ChartPoint>? = null

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

            initRecyclerView()
            initIntervalSpinner()
            initTabs()
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
     * 刷新主看板日内分时动态走势图
     */
    private fun updateMainDashboardChart(target: GoldItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val points = GoldRepository.fetchIntradayChart(target.id)
                mainDashboardChartPoints = points
                withContext(Dispatchers.Main) {
                    binding.chartMainDashboard.setChartData(points, target.unit)
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

    private fun initTabs() {
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

        binding.btnDashboardAiPrompt.setOnClickListener {
            copyAiAnalysisPrompt()
        }

        // 手动刷新按钮
        binding.btnManualRefresh.setOnClickListener {
            manualRefreshPrice()
        }
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
        if (binding.etThreshold.text.isNullOrBlank()) {
            val suggested = (item.price - 5.0).coerceAtLeast(1.0)
            binding.etThreshold.setText("%.2f".format(suggested))
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
                if (position in displayItems.indices) {
                    val item = displayItems[position]
                    selectedTargetItem = item
                    // 标的选择立即联动：顶部卡片即时刷新该标的名称与价格及走势
                    bindTopCardItem(item)
                    updateMainDashboardChart(item)
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
    private fun copyAiAnalysisPrompt() {
        val item = selectedTargetItem ?: allTargetsList.firstOrNull() ?: return
        binding.btnCopyAiPrompt.isEnabled = false
        binding.btnCopyAiPrompt.text = "⏳ 正在抓取走势数据..."
        binding.btnDashboardAiPrompt.isEnabled = false
        binding.btnDashboardAiPrompt.text = "⏳ 分析中"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                copyAiAnalysisPromptForItem(item, mainDashboardChartPoints)
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

    /**
     * 统一抽取：为任意标的复制专业 AI 量化分析 Prompt
     */
    private fun copyAiAnalysisPromptForItem(item: GoldItem, preloadedPoints: List<ChartPoint>?) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. 获取日内走势分时数据
                val chartPoints = if (!preloadedPoints.isNullOrEmpty()) {
                    preloadedPoints
                } else {
                    GoldRepository.fetchIntradayChart(item.id)
                }
                val sampledPoints = sampleChartPoints(chartPoints, targetCount = 36)

                val highPrice = if (chartPoints.isNotEmpty()) chartPoints.maxOf { it.price } else item.price
                val lowPrice = if (chartPoints.isNotEmpty()) chartPoints.minOf { it.price } else item.price
                val latestPrice = if (chartPoints.isNotEmpty()) chartPoints.last().price else item.price
                val amplitude = if (lowPrice > 0) ((highPrice - lowPrice) / lowPrice * 100.0) else 0.0

                val timeSdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                val dateSdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val currentTime = dateSdf.format(Date())

                // 2. 组装分时抽样数据清单 (时间 -> 价格)
                val sbPoints = StringBuilder()
                if (sampledPoints.isNotEmpty()) {
                    sampledPoints.forEach { pt ->
                        val tMillis = if (pt.timestamp < 100_000_000_000L) pt.timestamp * 1000L else pt.timestamp
                        val timeStr = timeSdf.format(Date(tMillis))
                        sbPoints.append("- %s: %.2f %s\n".format(timeStr, pt.price, item.unit))
                    }
                } else {
                    sbPoints.append("- 当前即时报价: %.2f %s\n".format(item.price, item.unit))
                }

                // 3. 构造专业量化专家提示词
                val prompt = """
你是一名拥有15年经验的贵金属量化交易专家。请根据以下我刚从实盘抓取的【${item.displayName}】今日高频分时走势数据，进行专业技术面剖析与行情预测：

【盘口概况】
- 标的名称：${item.displayName}
- 当前最新价：%.2f %s
- 日内最高价：%.2f %s
- 日内最低价：%.2f %s
- 日内振幅：%.2f%%
- 数据更新时间：$currentTime

【日内分时抽样数据 (时间 -> 价格)】
${sbPoints.toString().trimEnd()}

【请从以下 4 个维度给出深度分析报告】：
1. 短期均线与动量：当前处于拉升、阴跌还是窄幅蓄势震荡？
2. 关键点位研判：测算日内关键的支撑位（买点）与阻力位（压力位）。
3. 盘口多空情绪与风险评估：是否存在诱多/诱空或加速见顶信号？
4. 具体实操策略建议：给出明确的激进/稳健做单点位、止损防守位与止盈目标。
                """.trimIndent().format(latestPrice, item.unit, highPrice, item.unit, lowPrice, item.unit, amplitude)

                withContext(Dispatchers.Main) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Gold AI Analysis Prompt", prompt)
                    clipboard.setPrimaryClip(clip)

                    Toast.makeText(this@MainActivity, "已生成【${item.displayName}】专业AI量化分析提示词，可直接去对话框粘贴！", Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                Log.e("MainActivity", "copyAiAnalysisPromptForItem error: ${t.message}", t)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "走势拉取提示: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 对高频走势点进行均匀抽样（提取 30~50 个关键点，保留开盘、收盘、全天极值）
     */
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

    /**
     * 监控与展示管理弹窗（包含 7 家高频实时机构开关与 5 大分类模块开关）
     */
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
                .apply()

            dialog.dismiss()

            // 即时刷新 UI
            initTabs()
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
        val inputStr = binding.etThreshold.text?.toString()?.trim()
        val threshold = inputStr?.toDoubleOrNull()

        if (threshold == null || threshold <= 0) {
            binding.tilThreshold.error = "请输入有效的监控金价阈值 (如 930.00)"
            return
        }
        binding.tilThreshold.error = null

        val interval = intervalOptions.getOrNull(binding.spInterval.selectedItemPosition)?.second ?: 5.0
        val target = selectedTargetItem ?: allTargetsList.firstOrNull()

        try {
            val intent = Intent(this, GoldPriceService::class.java).apply {
                action = GoldPriceService.ACTION_START
                putExtra(GoldPriceService.EXTRA_THRESHOLD, threshold)
                putExtra(GoldPriceService.EXTRA_INTERVAL_MINUTES, interval)
                putExtra(GoldPriceService.EXTRA_TARGET_ID, target?.id ?: "realtime_icbc")
                putExtra(GoldPriceService.EXTRA_TARGET_NAME, target?.displayName ?: "[实时] 工商银行")
            }

            ContextCompat.startForegroundService(this, intent)
            if (threshold == GoldPriceService.THRESHOLD_TEST_30S) {
                Toast.makeText(this, "【测试模式已启动】将在 30 秒后推送锁屏测试通知，请立即熄屏测试！", Toast.LENGTH_LONG).show()
            } else if (threshold == GoldPriceService.THRESHOLD_TEST_5M) {
                Toast.makeText(this, "【测试模式已启动】将在 5 分钟后推送锁屏测试通知，请熄屏测试！", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "已启动【${target?.displayName ?: "金价"}】高频监控", Toast.LENGTH_SHORT).show()
            }
        } catch (t: Throwable) {
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
        binding.etThreshold.isEnabled = !state.isRunning
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
        if (state.targetThreshold != null && state.targetThreshold > 0) {
            val symbol = if (state.targetTitle.contains("伦敦金") || state.targetId == "realtime_gj") "$" else "¥"
            if (state.targetThreshold == GoldPriceService.THRESHOLD_TEST_30S) {
                binding.tvCurrentThreshold.text = "测试(30秒)"
            } else if (state.targetThreshold == GoldPriceService.THRESHOLD_TEST_5M) {
                binding.tvCurrentThreshold.text = "测试(5分钟)"
            } else {
                binding.tvCurrentThreshold.text = "$symbol %.2f".format(state.targetThreshold)
            }
            if (binding.etThreshold.text.isNullOrBlank()) {
                binding.etThreshold.setText("%.2f".format(state.targetThreshold))
            }
        } else {
            binding.tvCurrentThreshold.text = "未设置"
        }
        binding.tvCurrentInterval.text = "前台1m / 后台5m"

        // 5. 更新时间
        if (state.updateTime != null && state.updateTime > 0) {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            binding.tvUpdateTime.text = sdf.format(Date(state.updateTime))
        }
    }

    private fun checkBatteryOptimization() {
        if (hasPromptedBatteryOptimization) return

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
