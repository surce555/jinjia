package com.example.jinjia

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 金价后台轮询与前台通知服务
 */
class GoldPriceService : Service() {

    data class MonitorState(
        val isRunning: Boolean = false,
        val currentPrice: Double? = null,
        val targetThreshold: Double? = null,
        val updateTime: Long? = null,
        val statusMessage: String = "未运行"
    )

    companion object {
        const val ACTION_START = "com.example.jinjia.ACTION_START"
        const val ACTION_STOP = "com.example.jinjia.ACTION_STOP"
        const val EXTRA_THRESHOLD = "extra_threshold"

        const val CHANNEL_MONITOR_ID = "gold_monitor_channel"
        const val CHANNEL_ALERT_ID = "gold_alert_channel"

        const val NOTIFICATION_MONITOR_ID = 1001
        const val NOTIFICATION_ALERT_ID = 2001

        private const val POLL_INTERVAL_MS = 5 * 60 * 1000L // 5 分钟
        private const val API_URL = "https://api.jdjygold.com/gw2/generic/jrm/h5/m/stdLatestPrice?productSku=1961543816"

        private val _monitorState = MutableStateFlow(MonitorState())
        val monitorState = _monitorState.asStateFlow()
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var pollJob: Job? = null

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    // 告警状态机标志位（边缘触发）
    private var hasAlerted: Boolean = false
    private var targetThreshold: Double = 0.0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val threshold = intent.getDoubleExtra(EXTRA_THRESHOLD, 0.0)
                if (threshold > 0) {
                    targetThreshold = threshold
                    hasAlerted = false // 重新设定阈值时重置状态
                }
                startMonitorService()
            }
            ACTION_STOP -> {
                stopMonitorService()
            }
        }
        return START_NOT_STICKY
    }

    private fun startMonitorService() {
        val initialNotification = buildMonitorNotification(
            priceText = "正在获取实时金价...",
            detailText = "监控阈值: ¥%.2f /克".format(targetThreshold)
        )

        // 启动前台服务（适配 Android 14+ dataSync 类型）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_MONITOR_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_MONITOR_ID, initialNotification)
        }

        _monitorState.value = _monitorState.value.copy(
            isRunning = true,
            targetThreshold = targetThreshold,
            statusMessage = "正在监控中（每5分钟轮询）"
        )

        // 启动 5 分钟轮询协程
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            while (isActive) {
                fetchPriceAndEvaluate()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopMonitorService() {
        pollJob?.cancel()
        _monitorState.value = MonitorState(
            isRunning = false,
            currentPrice = _monitorState.value.currentPrice,
            targetThreshold = targetThreshold,
            updateTime = _monitorState.value.updateTime,
            statusMessage = "监控已停止"
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 抓取金价并根据告警状态机进行判定
     */
    private suspend fun fetchPriceAndEvaluate() {
        try {
            val request = Request.Builder()
                .url(API_URL)
                .header("User-Agent", "Mozilla/5.0 (Android Mobile; 金价盯盘)")
                .get()
                .build()

            val response = withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute()
            }

            val bodyString = response.body?.string()
            if (response.isSuccessful && !bodyString.isNullOrEmpty()) {
                val jsonObject = JSONObject(bodyString)
                val resultData = jsonObject.optJSONObject("resultData")
                val datas = resultData?.optJSONObject("datas")

                if (datas != null) {
                    val priceStr = datas.optString("price")
                    val timeVal = datas.optLong("time", System.currentTimeMillis())
                    val price = priceStr.toDoubleOrNull()

                    if (price != null) {
                        handleNewPrice(price, timeVal)
                        return
                    }
                }
            }

            // 数据解析异常处理
            updatePersistentNotification(
                priceText = "最新金价解析失败",
                detailText = "监控阈值: ¥%.2f /克".format(targetThreshold)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            val timeFormatted = formatTimestamp(System.currentTimeMillis())
            _monitorState.value = _monitorState.value.copy(
                statusMessage = "请求异常: ${e.localizedMessage ?: "网络错误"}"
            )
            updatePersistentNotification(
                priceText = "网络拉取金价失败",
                detailText = "重试等待中 ($timeFormatted)"
            )
        }
    }

    /**
     * 核心业务：边缘触发告警逻辑判定
     */
    private fun handleNewPrice(currentPrice: Double, timestamp: Long) {
        // 1. 边缘触发告警状态机
        if (targetThreshold > 0) {
            if (currentPrice < targetThreshold) {
                if (!hasAlerted) {
                    // 低于阈值且未告警过 -> 触发高优先级横幅告警
                    sendAlertNotification(currentPrice, targetThreshold)
                    hasAlerted = true
                }
                // 若 hasAlerted == true，则跳过不弹窗（防轰炸）
            } else {
                // 价格反弹回阈值及以上 -> 重置标志位，等待下一次下跌穿透
                hasAlerted = false
            }
        }

        // 2. 更新共享状态供 Activity 观察
        _monitorState.value = MonitorState(
            isRunning = true,
            currentPrice = currentPrice,
            targetThreshold = targetThreshold,
            updateTime = timestamp,
            statusMessage = "正常监控中（每5分钟更新）"
        )

        // 3. 刷新常驻前台通知
        val timeFormatted = formatTimestamp(timestamp)
        updatePersistentNotification(
            priceText = "实时金价: ¥%.2f /克".format(currentPrice),
            detailText = "目标: <¥%.2f | 更新: %s".format(targetThreshold, timeFormatted)
        )
    }

    /**
     * 发送高优先级横幅通知（带声音和振动）
     */
    private fun sendAlertNotification(price: Double, threshold: Double) {
        val pendingIntent = createContentPendingIntent()

        val alertNotification = NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
            .setSmallIcon(R.drawable.ic_gold)
            .setContentTitle("【低价告警】金价已下跌破位！")
            .setContentText("当前实时金价 ¥%.2f /克，已跌破监控阈值 ¥%.2f /克！".format(price, threshold))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ALERT_ID, alertNotification)
    }

    /**
     * 刷新前台常驻通知内容
     */
    private fun updatePersistentNotification(priceText: String, detailText: String) {
        val notification = buildMonitorNotification(priceText, detailText)
        notificationManager.notify(NOTIFICATION_MONITOR_ID, notification)
    }

    private fun buildMonitorNotification(priceText: String, detailText: String): Notification {
        val pendingIntent = createContentPendingIntent()

        return NotificationCompat.Builder(this, CHANNEL_MONITOR_ID)
            .setSmallIcon(R.drawable.ic_gold)
            .setContentTitle(priceText)
            .setContentText(detailText)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createContentPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 常驻监控渠道（Low 重要度，无声息）
            val monitorChannel = NotificationChannel(
                CHANNEL_MONITOR_ID,
                getString(R.string.notification_channel_monitor_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示金价常驻前台监控状态与最新行情"
                setShowBadge(false)
            }

            // 告警渠道（High 重要度，悬浮横幅、振动与铃声）
            val alertChannel = NotificationChannel(
                CHANNEL_ALERT_ID,
                getString(R.string.notification_channel_alert_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "当金价低于监控阈值时弹出高优先级横幅告警"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(monitorChannel)
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        return try {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            sdf.format(Date(timestamp))
        } catch (e: Exception) {
            timestamp.toString()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        _monitorState.value = MonitorState(
            isRunning = false,
            currentPrice = _monitorState.value.currentPrice,
            targetThreshold = targetThreshold,
            updateTime = _monitorState.value.updateTime,
            statusMessage = "服务已终止"
        )
    }
}
