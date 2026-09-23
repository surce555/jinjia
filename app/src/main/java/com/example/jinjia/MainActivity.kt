package com.example.jinjia

import android.Manifest
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
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var goldItemAdapter: GoldItemAdapter

    private var hasPromptedBatteryOptimization = false

    // 统一标的数据列表，初始必须使用 DEFAULT_TARGETS，确保任何时候绝不为空白
    private val allTargetsList = mutableListOf<GoldItem>()
    private var selectedTargetItem: GoldItem? = null

    // 刷新频率选项映射
    private val intervalOptions = listOf(
        Pair("0.5 分钟 (30秒)", 0.5),
        Pair("1 分钟", 1.0),
        Pair("2 分钟", 2.0),
        Pair("5 分钟 (推荐)", 5.0),
        Pair("10 分钟", 10.0),
        Pair("15 分钟", 15.0),
        Pair("30 分钟", 30.0)
    )

    // 分类展示开关
    private val categoryKeys = listOf(
        Pair(GoldDataParser.CAT_BANKS, "各大银行投资金条"),
        Pair(GoldDataParser.CAT_STORES, "品牌金店金价"),
        Pair(GoldDataParser.CAT_METALS, "大盘贵金属行情"),
        Pair(GoldDataParser.CAT_RECYCLE, "黄金回收报价")
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

            // 1. 初始化预置标的数据（杜绝 Spinner 空白）
            allTargetsList.clear()
            allTargetsList.addAll(GoldDataParser.DEFAULT_TARGETS)

            initRecyclerView()
            initIntervalSpinner()
            initTabs()
            initViews()

            // 预填充 Spinner，默认选中首项
            updateTargetSpinner()
            selectedTargetItem = allTargetsList.firstOrNull()
            selectedTargetItem?.let {
                binding.tvCurrentTargetTitle.text = it.displayName
                binding.tvTargetPrice.text = "¥ %.2f /克".format(it.price)
            }

            observeServiceState()

            // 2. 自动异步拉取全网最新数据
            fetchGoldDataImmediately()
        } catch (t: Throwable) {
            Log.e("MainActivity", "Error in onCreate: ${t.message}", t)
            Toast.makeText(this, "启动初始化提示: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            // 读取本地持久化缓存直出界面
            val saved = GoldPriceService.getSavedState(this)
            updateUi(saved)

            if (saved.allItems.isNotEmpty()) {
                allTargetsList.clear()
                allTargetsList.addAll(saved.allItems)
                updateTargetSpinner()
                filterAndDisplayList()
            }

            checkBatteryOptimization()
        } catch (t: Throwable) {
            Log.e("MainActivity", "Error in onResume: ${t.message}", t)
        }
    }

    /**
     * 进入 App 时的冷启动即时拉取逻辑（无需点击启动监控）
     */
    private fun fetchGoldDataImmediately() {
        val currentState = GoldPriceService.monitorState.value
        if (!currentState.isRunning) {
            binding.tvServiceStatus.text = "正在拉取最新行情..."
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.gold_primary_dark))
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (items, rawJson) = GoldRepository.fetchGoldData()

                // 持久化保存
                val sp = getSharedPreferences(GoldPriceService.PREFS_NAME, Context.MODE_PRIVATE)
                sp.edit()
                    .putString(GoldPriceService.KEY_ALL_ITEMS_JSON, rawJson)
                    .putLong(GoldPriceService.KEY_UPDATE_TIME, System.currentTimeMillis())
                    .apply()

                withContext(Dispatchers.Main) {
                    if (items.isNotEmpty()) {
                        allTargetsList.clear()
                        allTargetsList.addAll(items)
                        updateTargetSpinner()
                        filterAndDisplayList()

                        // 若未手动选择过标的，则默认选第一项
                        if (selectedTargetItem == null) {
                            setTargetItem(items.first())
                        } else {
                            // 保持当前选中标的的最新价格更新
                            val current = items.find { it.id == selectedTargetItem?.id }
                            if (current != null) {
                                setTargetItem(current)
                            }
                        }
                    }

                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    binding.tvUpdateTime.text = sdf.format(Date())

                    val running = GoldPriceService.monitorState.value.isRunning
                    if (!running) {
                        binding.tvServiceStatus.text = "已更新最新行情 (${items.size}项)"
                        binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
                    }
                }
            } catch (t: Throwable) {
                Log.e("MainActivity", "fetchGoldDataImmediately failed: ${t.message}", t)
                withContext(Dispatchers.Main) {
                    val running = GoldPriceService.monitorState.value.isRunning
                    if (!running) {
                        binding.tvServiceStatus.text = "拉取提示: ${t.message}"
                        binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_red))
                    }
                    Toast.makeText(this@MainActivity, "行情拉取提示: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun initRecyclerView() {
        goldItemAdapter = GoldItemAdapter { item ->
            // 点击列表卡片直接设为盯盘标的并联动 Spinner 与顶部卡片
            setTargetItem(item)
            Toast.makeText(this, "已将【${item.displayName}】选为盯盘标的", Toast.LENGTH_SHORT).show()
        }
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
        binding.tabLayout.removeAllTabs()
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("各大银行").setTag(GoldDataParser.CAT_BANKS))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("品牌金店").setTag(GoldDataParser.CAT_STORES))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("大盘行情").setTag(GoldDataParser.CAT_METALS))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("黄金回收").setTag(GoldDataParser.CAT_RECYCLE))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
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
            showCategorySettingsDialog()
        }
    }

    /**
     * 设置当前选中的标的，并立即联动刷新顶部卡片
     */
    private fun setTargetItem(item: GoldItem) {
        selectedTargetItem = item
        binding.tvCurrentTargetTitle.text = item.displayName
        binding.tvTargetPrice.text = "¥ %.2f /克".format(item.price)

        val idx = allTargetsList.indexOfFirst { it.id == item.id }
        if (idx >= 0 && binding.spTarget.adapter != null && binding.spTarget.selectedItemPosition != idx) {
            binding.spTarget.setSelection(idx)
        }

        // 若输入框为空，推荐预填当前价少 5 元作为参考
        if (binding.etThreshold.text.isNullOrBlank()) {
            val suggested = (item.price - 5.0).coerceAtLeast(1.0)
            binding.etThreshold.setText("%.2f".format(suggested))
        }
    }

    /**
     * 刷新并更新下拉标的列表，选择联动顶部价格
     */
    private fun updateTargetSpinner() {
        if (allTargetsList.isEmpty()) return

        val displayLabels = allTargetsList.map { "${it.displayName} (¥%.2f/克)".format(it.price) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayLabels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spTarget.adapter = adapter

        // 默认恢复之前已选或首项
        val saved = GoldPriceService.getSavedState(this)
        val selectedIdx = allTargetsList.indexOfFirst { it.id == (selectedTargetItem?.id ?: saved.targetId) }.let {
            if (it >= 0) it else 0
        }
        binding.spTarget.setSelection(selectedIdx)
        selectedTargetItem = allTargetsList.getOrNull(selectedIdx)

        binding.spTarget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in allTargetsList.indices) {
                    val item = allTargetsList[position]
                    selectedTargetItem = item
                    // 标的选择立即联动：顶部卡片即时刷新该标的名称与价格
                    binding.tvCurrentTargetTitle.text = item.displayName
                    binding.tvTargetPrice.text = "¥ %.2f /克".format(item.price)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun filterAndDisplayList() {
        val selectedTab = binding.tabLayout.getTabAt(binding.tabLayout.selectedTabPosition)
        val catTag = selectedTab?.tag as? String ?: GoldDataParser.CAT_BANKS

        val sp = getSharedPreferences(GoldPriceService.PREFS_NAME, Context.MODE_PRIVATE)
        val isCatEnabled = sp.getBoolean("show_cat_$catTag", true)

        if (!isCatEnabled) {
            goldItemAdapter.submitList(emptyList())
            binding.tvEmptyList.visibility = View.VISIBLE
            binding.tvEmptyList.text = "该分类展示已在【分类开关】中被关闭"
            return
        }

        val filtered = allTargetsList.filter { it.category == catTag }
        goldItemAdapter.submitList(filtered)

        if (filtered.isEmpty()) {
            binding.tvEmptyList.visibility = View.VISIBLE
            binding.tvEmptyList.text = "暂无数据，正在等待拉取..."
        } else {
            binding.tvEmptyList.visibility = View.GONE
        }
    }

    private fun showCategorySettingsDialog() {
        val sp = getSharedPreferences(GoldPriceService.PREFS_NAME, Context.MODE_PRIVATE)
        val checkedItems = BooleanArray(categoryKeys.size) { i ->
            sp.getBoolean("show_cat_${categoryKeys[i].first}", true)
        }
        val labels = categoryKeys.map { it.second }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("分类模块显示开关")
            .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("保存") { _, _ ->
                val editor = sp.edit()
                categoryKeys.forEachIndexed { index, pair ->
                    editor.putBoolean("show_cat_${pair.first}", checkedItems[index])
                }
                editor.apply()
                filterAndDisplayList()
                Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
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
                putExtra(GoldPriceService.EXTRA_TARGET_ID, target?.id ?: "")
                putExtra(GoldPriceService.EXTRA_TARGET_NAME, target?.displayName ?: "[大盘] 今日金价")
            }

            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "已启动【${target?.displayName ?: "金价"}】实时监控", Toast.LENGTH_SHORT).show()
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

        // 2. 状态标签
        if (state.isRunning) {
            binding.tvServiceStatus.text = "监控中 (%.1fm 轮询)".format(state.intervalMinutes)
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            if (binding.tvServiceStatus.text == "未运行" || binding.tvServiceStatus.text == "已停止") {
                binding.tvServiceStatus.text = state.statusMessage
                binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))
            }
        }

        // 3. 标的名称与单价
        if (state.targetTitle.isNotBlank()) {
            binding.tvCurrentTargetTitle.text = state.targetTitle
        }
        if (state.targetPrice != null && state.targetPrice > 0) {
            binding.tvTargetPrice.text = "¥ %.2f /克".format(state.targetPrice)
        }

        // 4. 设定阈值与刷新频率
        if (state.targetThreshold != null && state.targetThreshold > 0) {
            binding.tvCurrentThreshold.text = "¥ %.2f /克".format(state.targetThreshold)
            if (binding.etThreshold.text.isNullOrBlank()) {
                binding.etThreshold.setText("%.2f".format(state.targetThreshold))
            }
        } else {
            binding.tvCurrentThreshold.text = "未设置"
        }
        binding.tvCurrentInterval.text = "%.1f 分钟".format(state.intervalMinutes)

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
