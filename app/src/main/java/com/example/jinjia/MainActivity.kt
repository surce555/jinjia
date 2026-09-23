package com.example.jinjia

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

    private fun observeServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                GoldPriceService.monitorState.collect { state ->
                    updateUi(state)
                }
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
            binding.tvServiceStatus.text = "正在监控中 (5分钟轮询)"
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            binding.tvServiceStatus.text = "已停止"
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
