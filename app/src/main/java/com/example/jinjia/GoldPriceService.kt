package com.example.jinjia

import android.app.AlarmManager
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
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 金价后台轮询与多品类精准监控服务
 */
class GoldPriceService : Service() {

    data class MonitorState(
        val isRunning: Boolean = false,
        val targetId: String = "",
        val targetTitle: String = "未选择标的",
        val targetPrice: Double? = null,
        val targetThreshold: Double? = null,
        val intervalMinutes: Double = 5.0,
        val updateTime: Long? = null,
        val statusMessage: String = "未运行",
        val allItems: List<GoldItem> = emptyList()
    )

    companion object {
        private const val TAG = "GoldPriceService"

        const val ACTION_START = "com.example.jinjia.ACTION_START"
        const val ACTION_POLL = "com.example.jinjia.ACTION_POLL"
        const val ACTION_STOP = "com.example.jinjia.ACTION_STOP"

        const val EXTRA_TARGET_ID = "extra_target_id"
        const val EXTRA_TARGET_NAME = "extra_target_name"
        const val EXTRA_THRESHOLD = "extra_threshold"
        const val EXTRA_INTERVAL_MINUTES = "extra_interval_minutes"

        const val CHANNEL_MONITOR_ID = "gold_monitor_channel"
        const val CHANNEL_ALERT_ID = "gold_alert_channel"

        const val NOTIFICATION_MONITOR_ID = 1001
        const val NOTIFICATION_ALERT_ID = 2001

        // 数据持久化常量
        const val PREFS_NAME = "gold_price_prefs"
        const val KEY_IS_RUNNING = "is_running"
        const val KEY_TARGET_ID = "target_id"
        const val KEY_TARGET_TITLE = "target_title"
        const val KEY_TARGET_PRICE = "target_price"
        const val KEY_TARGET_THRESHOLD = "target_threshold"
        const val KEY_INTERVAL_MINUTES = "interval_minutes"
        const val KEY_UPDATE_TIME = "update_time"
        const val KEY_STATUS_MESSAGE = "status_message"
        const val KEY_ALL_ITEMS_JSON = "all_items_json"

        private val _monitorState = MutableStateFlow(MonitorState())
        val monitorState = _monitorState.asStateFlow()

        /**
         * 供外部（如 MainActivity.onResume）读取本地缓存状态
         */
        fun getSavedState(context: Context): MonitorState {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val isRunning = sp.getBoolean(KEY_IS_RUNNING, false)
            val targetId = sp.getString(KEY_TARGET_ID, "") ?: ""
            val targetTitle = sp.getString(KEY_TARGET_TITLE, "今日金价") ?: "今日金价"
            val priceStr = sp.getString(KEY_TARGET_PRICE, null)
            val targetPrice = priceStr?.toDoubleOrNull()
            val threshold = sp.getFloat(KEY_TARGET_THRESHOLD, 0f).toDouble()
            val intervalMinutes = sp.getFloat(KEY_INTERVAL_MINUTES, 5.0f).toDouble()
            val updateTime = sp.getLong(KEY_UPDATE_TIME, 0L)
            val statusMsg = sp.getString(KEY_STATUS_MESSAGE, if (isRunning) "正在监控中" else "未运行") ?: "未运行"
            val json = sp.getString(KEY_ALL_ITEMS_JSON, null) ?: ""
            val allItems = GoldDataParser.parseJson(json)

            return MonitorState(
                isRunning = isRunning,
                targetId = targetId,
                targetTitle = targetTitle,
                targetPrice = targetPrice,
                targetThreshold = if (threshold > 0) threshold else null,
                intervalMinutes = intervalMinutes.coerceAtLeast(0.5),
                updateTime = if (updateTime > 0) updateTime else null,
                statusMessage = statusMsg,
                allItems = allItems
            )
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var pollJob: Job? = null

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val powerManager by lazy {
        getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    private val alarmManager by lazy {
        getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    // 监控与告警状态机参数
    private var targetId: String = ""
    private var targetTitle: String = "今日金价"
    private var targetThreshold: Double = 0.0
    private var intervalMinutes: Double = 5.0
    private var hasAlerted: Boolean = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

        // 还原本地持久化参数
        val saved = getSavedState(this)
        targetId = saved.targetId
        targetTitle = saved.targetTitle
        if (saved.targetThreshold != null) targetThreshold = saved.targetThreshold
        intervalMinutes = saved.intervalMinutes
        _monitorState.value = saved
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val newTargetId = intent.getStringExtra(EXTRA_TARGET_ID) ?: ""
                val newTargetName = intent.getStringExtra(EXTRA_TARGET_NAME) ?: ""
                val threshold = intent.getDoubleExtra(EXTRA_THRESHOLD, 0.0)
                val interval = intent.getDoubleExtra(EXTRA_INTERVAL_MINUTES, 5.0)

                if (newTargetId.isNotBlank()) targetId = newTargetId
                if (newTargetName.isNotBlank()) targetTitle = newTargetName
                if (threshold > 0) {
                    targetThreshold = threshold
                    hasAlerted = false // 重新设置阈值时复位告警状态机
                }
                intervalMinutes = interval.coerceAtLeast(0.5)

                startMonitorService()
            }
            ACTION_POLL -> {
                if (_monitorState.value.isRunning) {
                    Log.i(TAG, "Alarm triggered: executing scheduled poll in Doze mode")
                    triggerPoll()
                }
            }
            ACTION_STOP -> {
                stopMonitorService()
            }
        }
        return START_STICKY
    }

    private fun startMonitorService() {
        val initialNotification = buildMonitorNotification(
            priceText = "【$targetTitle】正在拉取最新金价...",
            detailText = "监控阈值: ¥%.2f /克 | 间隔: %.1f分钟".format(targetThreshold, intervalMinutes)
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

        val updatedState = _monitorState.value.copy(
            isRunning = true,
            targetId = targetId,
            targetTitle = targetTitle,
            targetThreshold = targetThreshold,
            intervalMinutes = intervalMinutes,
            statusMessage = "正在初始化全网金价..."
        )
        _monitorState.value = updatedState
        saveStateToPrefs(updatedState, null)

        // 即刻触发首轮异步拉取，后续由 AlarmManager 精准续期
        triggerPoll()
    }

    private fun stopMonitorService() {
        cancelAlarm()
        pollJob?.cancel()

        val stoppedState = _monitorState.value.copy(
            isRunning = false,
            targetId = targetId,
            targetTitle = targetTitle,
            targetThreshold = targetThreshold,
            intervalMinutes = intervalMinutes,
            statusMessage = "监控已停止"
        )
        _monitorState.value = stoppedState
        saveStateToPrefs(stoppedState, null)

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun triggerPoll() {
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            try {
                fetchPriceAndEvaluate()
            } catch (t: Throwable) {
                Log.e(TAG, "Exception during poll execution: ${t.message}", t)
            } finally {
                if (_monitorState.value.isRunning) {
                    scheduleNextAlarm()
                }
            }
        }
    }

    /**
     * 动态设置下一次 AlarmManager 唤醒闹钟（根据 intervalMinutes 计算，最小 30 秒）
     */
    private fun scheduleNextAlarm() {
        try {
            val intervalMs = (intervalMinutes * 60 * 1000L).toLong().coerceAtLeast(30_000L)
            val triggerAtMillis = SystemClock.elapsedRealtime() + intervalMs
            val pendingIntent = getPollPendingIntent()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
            Log.i(TAG, "Scheduled next Alarm in ${intervalMs / 1000}s")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to schedule AlarmManager: ${t.message}", t)
        }
    }

    private fun cancelAlarm() {
        try {
            alarmManager.cancel(getPollPendingIntent())
            Log.i(TAG, "Alarm cancelled")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to cancel Alarm: ${t.message}", t)
        }
    }

    private fun getPollPendingIntent(): PendingIntent {
        val intent = Intent(this, GoldPriceService::class.java).apply {
            action = ACTION_POLL
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, 1002, intent, flags)
        } else {
            PendingIntent.getService(this, 1002, intent, flags)
        }
    }

    /**
     * 核心网络拉取与指定标的预警比对（复用 GoldRepository）
     */
    private suspend fun fetchPriceAndEvaluate() {
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Jinjia:NetworkWakeLock"
        )
        try {
            wakeLock.acquire(30_000L) // 30 秒超时保护

            val (allItems, jsonString) = GoldRepository.fetchGoldData()
            if (allItems.isNotEmpty()) {
                handleParsedData(allItems, jsonString)
            } else {
                handleFetchError("解析后无有效行情条目")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Fetch failed: ${t.message}", t)
            handleFetchError(t.message ?: t.javaClass.simpleName)
        } finally {
            try {
                if (wakeLock.isHeld) wakeLock.release()
            } catch (t: Throwable) {
                Log.w(TAG, "Error releasing wake lock: ${t.message}")
            }
        }
    }

    private fun handleParsedData(allItems: List<GoldItem>, rawJson: String) {
        val timestamp = System.currentTimeMillis()

        // 匹配用户指定的监控标的，若未指定则默认选第一个
        val targetItem = if (targetId.isNotBlank()) {
            allItems.find { it.id == targetId } ?: allItems.find { it.title.contains(targetTitle) } ?: allItems.first()
        } else {
            allItems.first()
        }

        val currentPrice = targetItem.price
        val currentTitle = targetItem.displayName
        targetId = targetItem.id
        targetTitle = currentTitle

        // 1. 边缘触发告警状态机
        if (targetThreshold > 0) {
            if (currentPrice < targetThreshold) {
                if (!hasAlerted) {
                    sendAlertNotification(currentTitle, currentPrice, targetThreshold)
                    hasAlerted = true
                }
            } else {
                hasAlerted = false
            }
        }

        // 2. 更新状态并持久化
        val newState = MonitorState(
            isRunning = true,
            targetId = targetId,
            targetTitle = targetTitle,
            targetPrice = currentPrice,
            targetThreshold = targetThreshold,
            intervalMinutes = intervalMinutes,
            updateTime = timestamp,
            statusMessage = "监控中（已收录 ${allItems.size} 项行情）",
            allItems = allItems
        )
        _monitorState.value = newState
        saveStateToPrefs(newState, rawJson)

        // 3. 刷新前台常驻通知
        val timeFormatted = formatTimestamp(timestamp)
        updatePersistentNotification(
            priceText = "【$targetTitle】¥%.2f /克".format(currentPrice),
            detailText = "阈值: <¥%.2f | 间隔: %.1fm (%s)".format(targetThreshold, intervalMinutes, timeFormatted)
        )
    }

    private fun handleFetchError(errorMsg: String) {
        val timeFormatted = formatTimestamp(System.currentTimeMillis())
        val errState = _monitorState.value.copy(
            statusMessage = "拉取异常: $errorMsg"
        )
        _monitorState.value = errState
        saveStateToPrefs(errState, null)

        updatePersistentNotification(
            priceText = "【$targetTitle】金价拉取失败",
            detailText = "$errorMsg (重试等待中 $timeFormatted)"
        )
    }

    private fun saveStateToPrefs(state: MonitorState, rawJson: String?) {
        try {
            val sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = sp.edit()
                .putBoolean(KEY_IS_RUNNING, state.isRunning)
                .putString(KEY_TARGET_ID, state.targetId)
                .putString(KEY_TARGET_TITLE, state.targetTitle)
                .putString(KEY_TARGET_PRICE, state.targetPrice?.toString())
                .putFloat(KEY_TARGET_THRESHOLD, (state.targetThreshold ?: 0.0).toFloat())
                .putFloat(KEY_INTERVAL_MINUTES, state.intervalMinutes.toFloat())
                .putLong(KEY_UPDATE_TIME, state.updateTime ?: 0L)
                .putString(KEY_STATUS_MESSAGE, state.statusMessage)

            if (!rawJson.isNullOrBlank()) {
                editor.putString(KEY_ALL_ITEMS_JSON, rawJson)
            }
            editor.apply()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to save state to SharedPreferences: ${t.message}", t)
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeUpScreen() {
        try {
            val screenWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "Jinjia:AlertScreenWakeLock"
            )
            screenWakeLock.acquire(5_000L)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to wake up screen: ${t.message}", t)
        }
    }

    private fun sendAlertNotification(title: String, price: Double, threshold: Double) {
        wakeUpScreen()

        val pendingIntent = createContentPendingIntent()

        val alertNotification = NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
            .setSmallIcon(R.drawable.ic_gold)
            .setContentTitle("【低价告警】$title 跌破阈值！")
            .setContentText("【$title】当前实时金价 ¥%.2f /克，已跌破监控阈值 ¥%.2f /克！".format(price, threshold))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ALERT_ID, alertNotification)
    }

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
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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
            val monitorChannel = NotificationChannel(
                CHANNEL_MONITOR_ID,
                getString(R.string.notification_channel_monitor_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示金价常驻前台监控状态与最新行情"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val alertChannel = NotificationChannel(
                CHANNEL_ALERT_ID,
                getString(R.string.notification_channel_alert_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "当金价低于监控阈值时弹出高优先级横幅告警"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            notificationManager.createNotificationChannel(monitorChannel)
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        return try {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            sdf.format(Date(timestamp))
        } catch (t: Throwable) {
            timestamp.toString()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        cancelAlarm()
        serviceScope.cancel()
        val stoppedState = _monitorState.value.copy(
            isRunning = false,
            statusMessage = "服务已终止"
        )
        _monitorState.value = stoppedState
        saveStateToPrefs(stoppedState, null)
    }
}
