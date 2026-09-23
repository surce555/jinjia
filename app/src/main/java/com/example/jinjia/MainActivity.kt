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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.jinjia.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var hasPromptedBatteryOptimization = false

    // 申请 Android 13+ (API 33) 通知权限启动器
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

        initViews()
        observeServiceState()
    }

    override fun onResume() {
        super.onResume()
        // 1. 第一时间读取 SharedPreferences 中的最新价格、状态与更新时间刷新界面
        val savedState = GoldPriceService.getSavedState(this)
        updateUi(savedState)

        // 若输入框未填入数值且本地已存有阈值，则自动恢复
        if (binding.etThreshold.text.isNullOrEmpty() && savedState.targetThreshold != null) {
            binding.etThreshold.setText("%.2f".format(savedState.targetThreshold))
        }

        // 2. 检查电池优化白名单，避免熄屏/切后台后系统冻结闹钟与网络
        checkBatteryOptimization()
    }

    private fun initViews() {
        binding.btnStart.setOnClickListener {
            checkPermissionAndStart()
        }

        binding.btnStop.setOnClickListener {
            stopMonitoring()
        }
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

        val intent = Intent(this, GoldPriceService::class.java).apply {
            action = GoldPriceService.ACTION_START
            putExtra(GoldPriceService.EXTRA_THRESHOLD, threshold)
        }

        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "金价监控服务已启动", Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitoring() {
        val intent = Intent(this, GoldPriceService::class.java).apply {
            action = GoldPriceService.ACTION_STOP
        }
        startService(intent)
        Toast.makeText(this, "金价监控服务已停止", Toast.LENGTH_SHORT).show()
    }

    /**
     * 实时监听服务状态流（当界面处于前台 STARTED 状态时自动无缝刷新）
     */
    private fun observeServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                GoldPriceService.monitorState.collect { state ->
                    updateUi(state)
                }
            }
        }
    }

    /**
     * 检查并引导用户将应用加入电池优化白名单（无限制后台网络与唤醒）
     */
    private fun checkBatteryOptimization() {
        if (hasPromptedBatteryOptimization) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    hasPromptedBatteryOptimization = true
                    AlertDialog.Builder(this)
                        .setTitle("后台长效运行权限")
                        .setMessage("为了防止应用在熄屏休眠或切到后台时被系统掐断网络和定时更新，请允许本应用忽略电池优化（无限制后台运行）。")
                        .setPositiveButton("去设置") { _, _ ->
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

    private fun updateUi(state: GoldPriceService.MonitorState) {
        // 1. 按钮状态切换
        binding.btnStart.isEnabled = !state.isRunning
        binding.btnStop.isEnabled = state.isRunning
        binding.etThreshold.isEnabled = !state.isRunning

        // 2. 状态信息
        if (state.isRunning) {
            binding.tvServiceStatus.text = state.statusMessage
            if (state.statusMessage.contains("失败") || state.statusMessage.contains("异常")) {
                binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))
            } else {
                binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            }
        } else {
            binding.tvServiceStatus.text = state.statusMessage
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))
        }

        // 3. 最新金价展示
        if (state.currentPrice != null) {
            binding.tvLatestPrice.text = "¥ %.2f /克".format(state.currentPrice)
        } else {
            binding.tvLatestPrice.text = "--.-- 元/克"
        }

        // 4. 设定阈值
        if (state.targetThreshold != null && state.targetThreshold > 0) {
            binding.tvTargetThreshold.text = "¥ %.2f /克".format(state.targetThreshold)
        } else {
            binding.tvTargetThreshold.text = "未设置"
        }

        // 5. 更新时间
        if (state.updateTime != null && state.updateTime > 0) {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            binding.tvUpdateTime.text = sdf.format(Date(state.updateTime))
        } else {
            binding.tvUpdateTime.text = "尚未拉取"
        }
    }
}
