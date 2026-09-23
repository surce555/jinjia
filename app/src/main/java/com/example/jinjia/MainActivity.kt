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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var goldItemAdapter: GoldItemAdapter

    private var hasPromptedBatteryOptimization = false
    private var allItemsList = listOf<GoldItem>()
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initRecyclerView()
        initIntervalSpinner()
        initTabs()
        initViews()
        observeServiceState()
    }

    override fun onResume() {
        super.onResume()
        // 1. 读取本地持久化缓存直出界面
        val saved = GoldPriceService.getSavedState(this)
        updateUi(saved)

        if (saved.allItems.isNotEmpty()) {
            allItemsList = saved.allItems
            updateTargetSpinner()
            filterAndDisplayList()
        }

        // 2. 检查电池优化白名单
        checkBatteryOptimization()
    }

    private fun initRecyclerView() {
        goldItemAdapter = GoldItemAdapter { item ->
            // 点击设为监控标的
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

        // 默认选中 5 分钟
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

    private fun setTargetItem(item: GoldItem) {
        selectedTargetItem = item
        binding.tvCurrentTargetTitle.text = item.displayName
        binding.tvTargetPrice.text = "¥ %.2f /克".format(item.price)

        // 同步 Spinner 选中项
        val idx = allItemsList.indexOfFirst { it.id == item.id }
        if (idx >= 0 && binding.spTarget.adapter != null) {
            binding.spTarget.setSelection(idx)
        }

        // 若当前输入框为空或用户尚未设置，推荐预填当前价少 5 元作为默认参考阈值
        if (binding.etThreshold.text.isNullOrBlank()) {
            val suggested = (item.price - 5.0).coerceAtLeast(1.0)
            binding.etThreshold.setText("%.2f".format(suggested))
        }
    }

    private fun updateTargetSpinner() {
        if (allItemsList.isEmpty()) return

        val titles = allItemsList.map { "【${getCategoryName(it.category)}】${it.displayName} (¥%.2f)".format(it.price) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, titles).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spTarget.adapter = adapter

        // 尝试恢复之前选中的标的
        val saved = GoldPriceService.getSavedState(this)
        val selectedIdx = allItemsList.indexOfFirst { it.id == saved.targetId }.let {
            if (it >= 0) it else 0
        }
        binding.spTarget.setSelection(selectedIdx)

        binding.spTarget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in allItemsList.indices) {
                    val item = allItemsList[position]
                    selectedTargetItem = item
                    binding.tvCurrentTargetTitle.text = item.displayName
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

        val filtered = allItemsList.filter { it.category == catTag }
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
        val target = selectedTargetItem ?: allItemsList.firstOrNull()

        val intent = Intent(this, GoldPriceService::class.java).apply {
            action = GoldPriceService.ACTION_START
            putExtra(GoldPriceService.EXTRA_THRESHOLD, threshold)
            putExtra(GoldPriceService.EXTRA_INTERVAL_MINUTES, interval)
            putExtra(GoldPriceService.EXTRA_TARGET_ID, target?.id ?: "")
            putExtra(GoldPriceService.EXTRA_TARGET_NAME, target?.displayName ?: "今日金价")
        }

        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "已启动【${target?.displayName ?: "金价"}】实时监控", Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitoring() {
        val intent = Intent(this, GoldPriceService::class.java).apply {
            action = GoldPriceService.ACTION_STOP
        }
        startService(intent)
        Toast.makeText(this, "监控服务已停止", Toast.LENGTH_SHORT).show()
    }

    private fun observeServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                GoldPriceService.monitorState.collect { state ->
                    updateUi(state)
                    if (state.allItems.isNotEmpty() && state.allItems != allItemsList) {
                        allItemsList = state.allItems
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
            binding.tvServiceStatus.text = "监控中 (%.1fm)".format(state.intervalMinutes)
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            binding.tvServiceStatus.text = "已停止"
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))
        }

        // 3. 标的名称与单价
        if (state.targetTitle.isNotBlank()) {
            binding.tvCurrentTargetTitle.text = state.targetTitle
        }
        if (state.targetPrice != null) {
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
        } else {
            binding.tvUpdateTime.text = "尚未拉取"
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

    private fun getCategoryName(cat: String): String {
        return when (cat) {
            GoldDataParser.CAT_BANKS -> "银行"
            GoldDataParser.CAT_STORES -> "金店"
            GoldDataParser.CAT_METALS -> "大盘"
            GoldDataParser.CAT_RECYCLE -> "回收"
            else -> "行情"
        }
    }
}
