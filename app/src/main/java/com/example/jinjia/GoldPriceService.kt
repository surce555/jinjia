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
 * 集成 AlarmManager 深度休眠唤醒、双接口容错、SharedPreferences 持久化及边缘触发锁屏告警
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
        private const val TAG = "GoldPriceService"

        const val ACTION_START = "com.example.jinjia.ACTION_START"
        const val ACTION_POLL = "com.example.jinjia.ACTION_POLL"
        const val ACTION_STOP = "com.example.jinjia.ACTION_STOP"
        const val EXTRA_THRESHOLD = "extra_threshold"

        const val CHANNEL_MONITOR_ID = "gold_monitor_channel"
        const val CHANNEL_ALERT_ID = "gold_alert_channel"

        const val NOTIFICATION_MONITOR_ID = 1001
        const val NOTIFICATION_ALERT_ID = 2001

        private const val POLL_INTERVAL_MS = 5 * 60 * 1000L // 5 分钟

        // 数据持久化常量
        private const val PREFS_NAME = "gold_price_prefs"
        private const val KEY_IS_RUNNING = "is_running"
        private const val KEY_CURRENT_PRICE = "current_price"
        private const val KEY_TARGET_THRESHOLD = "target_threshold"
        private const val KEY_UPDATE_TIME = "update_time"
        private const val KEY_STATUS_MESSAGE = "status_message"

        // 数据源配置
        private const val JD_API_URL = "https://api.jdjygold.com/gw2/generic/jrm/h5/m/stdLatestPrice?productSku=1961543816"
        private const val SINA_API_URL = "https://hq.sinajs.cn/list=gds_au9999,gds_AUTD"

        private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private val _monitorState = MutableStateFlow(MonitorState())
        val monitorState = _monitorState.asStateFlow()

        /**
         * 供外部（如 MainActivity.onResume）直接读取已持久化的最新数据
         */
        fun getSavedState(context: Context): MonitorState {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val isRunning = sp.getBoolean(KEY_IS_RUNNING, false)
            val priceStr = sp.getString(KEY_CURRENT_PRICE, null)
            val currentPrice = priceStr?.toDoubleOrNull()
            val threshold = sp.getFloat(KEY_TARGET_THRESHOLD, 0f).toDouble()
            val updateTime = sp.getLong(KEY_UPDATE_TIME, 0L)
            val statusMsg = sp.getString(KEY_STATUS_MESSAGE, if (isRunning) "正在监控中" else "未运行") ?: "未运行"

            return MonitorState(
                isRunning = isRunning,
                currentPrice = currentPrice,
                targetThreshold = if (threshold > 0) threshold else null,
                updateTime = if (updateTime > 0) updateTime else null,
                statusMessage = statusMsg
            )
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var pollJob: Job? = null

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val powerManager by lazy {
        getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    private val alarmManager by lazy {
        getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    // 告警状态机标志位（边缘触发）
    private var hasAlerted: Boolean = false
    private var targetThreshold: Double = 0.0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

        // 从持久化存储还原状态
        val saved = getSavedState(this)
        if (saved.targetThreshold != null) {
            targetThreshold = saved.targetThreshold
        }
        _monitorState.value = saved
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
            ACTION_POLL -> {
                // 由 AlarmManager 定时闹钟触发深度唤醒轮询
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
            priceText = "正在拉取最新金价...",
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

        val updatedState = _monitorState.value.copy(
            isRunning = true,
            targetThreshold = targetThreshold,
            statusMessage = "正在初始化抓取数据..."
        )
        _monitorState.value = updatedState
        saveStateToPrefs(updatedState)

        // 触发即时首轮抓取（完成后通过 AlarmManager 自动挂起下一次精准闹钟）
        triggerPoll()
    }

    private fun stopMonitorService() {
        cancelAlarm()
        pollJob?.cancel()

        val stoppedState = MonitorState(
            isRunning = false,
            currentPrice = _monitorState.value.currentPrice,
            targetThreshold = targetThreshold,
            updateTime = _monitorState.value.updateTime,
            statusMessage = "监控已停止"
        )
        _monitorState.value = stoppedState
        saveStateToPrefs(stoppedState)

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 触发异步轮询并编排下一次闹钟唤醒
     */
    private fun triggerPoll() {
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            try {
                fetchPriceAndEvaluate()
            } catch (t: Throwable) {
                Log.e(TAG, "Unhandled exception in triggerPoll: ${t.message}", t)
            } finally {
                // 无论成功还是失败，只要服务处于运行中，均设置下一次精确闹钟（防止轮询断流）
                if (_monitorState.value.isRunning) {
                    scheduleNextAlarm()
                }
            }
        }
    }

    /**
     * 使用 AlarmManager 设定休眠唤醒闹钟（ELAPSED_REALTIME_WAKEUP）
     */
    private fun scheduleNextAlarm() {
        try {
            val triggerAtMillis = SystemClock.elapsedRealtime() + POLL_INTERVAL_MS
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
            Log.i(TAG, "Scheduled next AlarmManager wake-up at $triggerAtMillis (in 5 minutes)")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to schedule AlarmManager: ${t.message}", t)
        }
    }

    private fun cancelAlarm() {
        try {
            alarmManager.cancel(getPollPendingIntent())
            Log.i(TAG, "AlarmManager schedule cancelled")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to cancel AlarmManager: ${t.message}", t)
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
     * 核心业务：拉取金价并进行双接口主备容错与判定
     * 外层包含严密 Throwable 捕获与 30 秒 WakeLock 超时保护
     */
    private suspend fun fetchPriceAndEvaluate() {
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Jinjia:NetworkWakeLock"
        )
        try {
            wakeLock.acquire(30_000L) // 30 秒超时保护，防止异常永久持有

            var fetchedPrice: Double? = null
            var fetchedTime: Long = System.currentTimeMillis()
            var sourceName = "京东金融"
            var lastError: String? = null

            // 1. 首选尝试京东金融接口
            try {
                val jdRequest = Request.Builder()
                    .url(JD_API_URL)
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Referer", "https://m.jdjygold.com/")
                    .get()
                    .build()

                val response = withContext(Dispatchers.IO) {
                    okHttpClient.newCall(jdRequest).execute()
                }

                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrEmpty()) {
                        val parsed = parseJdResponse(body)
                        if (parsed != null) {
                            fetchedPrice = parsed.first
                            fetchedTime = parsed.second
                            sourceName = "京东金融"
                            Log.i(TAG, "Successfully fetched price from JD: $fetchedPrice")
                        } else {
                            lastError = "京东数据字段解析为空"
                        }
                    } else {
                        lastError = "京东返回空内容"
                    }
                } else {
                    lastError = "京东返回 HTTP ${response.code}"
                }
            } catch (t: Throwable) {
                val err = formatThrowable(t)
                Log.e(TAG, "JD API failed: $err", t)
                lastError = "京东失败($err)"
            }

            // 2. 若京东接口失败，自动切换新浪黄金现货备用接口
            if (fetchedPrice == null) {
                try {
                    Log.w(TAG, "Switching to fallback Sina gold API...")
                    val sinaRequest = Request.Builder()
                        .url(SINA_API_URL)
                        .header("User-Agent", BROWSER_UA)
                        .header("Accept", "*/*")
                        .header("Referer", "https://finance.sina.com.cn")
                        .get()
                        .build()

                    val response = withContext(Dispatchers.IO) {
                        okHttpClient.newCall(sinaRequest).execute()
                    }

                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrEmpty()) {
                            val parsed = parseSinaResponse(body)
                            if (parsed != null) {
                                fetchedPrice = parsed.first
                                fetchedTime = parsed.second
                                sourceName = "新浪黄金现货(备用)"
                                lastError = null // 备用接口拉取成功，清除错误提示
                                Log.i(TAG, "Successfully fetched price from Sina: $fetchedPrice")
                            } else {
                                lastError = "${lastError ?: ""}; 新浪行情解析为空"
                            }
                        } else {
                            lastError = "${lastError ?: ""}; 新浪返回空内容"
                        }
                    } else {
                        lastError = "${lastError ?: ""}; 新浪返回 HTTP ${response.code}"
                    }
                } catch (t: Throwable) {
                    val err = formatThrowable(t)
                    Log.e(TAG, "Sina API failed: $err", t)
                    lastError = "${lastError ?: ""}; 新浪失败($err)"
                }
            }

            // 3. 结果调度与 UI / 通知 / 本地缓存刷新
            if (fetchedPrice != null) {
                handleNewPrice(fetchedPrice, fetchedTime, sourceName)
            } else {
                val finalErrMsg = lastError ?: "网络拉取金价失败"
                Log.e(TAG, "All sources failed: $finalErrMsg")
                val timeFormatted = formatTimestamp(System.currentTimeMillis())
                val errState = _monitorState.value.copy(
                    statusMessage = "拉取异常: $finalErrMsg"
                )
                _monitorState.value = errState
                saveStateToPrefs(errState)

                updatePersistentNotification(
                    priceText = "金价拉取失败",
                    detailText = "$finalErrMsg (等待重试 $timeFormatted)"
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Outer throwable in fetchPriceAndEvaluate: ${t.message}", t)
        } finally {
            try {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error releasing wake lock: ${t.message}")
            }
        }
    }

    private fun parseJdResponse(body: String): Pair<Double, Long>? {
        return try {
            val jsonObject = JSONObject(body)
            val resultData = jsonObject.optJSONObject("resultData")
            val datas = resultData?.optJSONObject("datas") ?: return null
            val priceStr = datas.optString("price")
            val price = priceStr.toDoubleOrNull() ?: return null
            val time = datas.optLong("time", System.currentTimeMillis())
            Pair(price, time)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse JD JSON: ${t.message}", t)
            null
        }
    }

    private fun parseSinaResponse(body: String): Pair<Double, Long>? {
        return try {
            // 解析格式 var hq_str_xxx="...";
            val pattern = Regex("\"([^\"]+)\"")
            val matches = pattern.findAll(body)
            for (match in matches) {
                val content = match.groupValues[1]
                if (content.isBlank()) continue
                val parts = content.split(",")
                if (parts.isNotEmpty()) {
                    val price = parts[0].trim().toDoubleOrNull()
                    if (price != null && price > 0) {
                        var time = System.currentTimeMillis()
                        try {
                            if (parts.size >= 13) {
                                val dateStr = parts[12].trim()
                                val timeStr = parts[6].trim()
                                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                                time = sdf.parse("$dateStr $timeStr")?.time ?: time
                            }
                        } catch (_: Throwable) {}
                        return Pair(price, time)
                    }
                }
            }
            null
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse Sina quote: ${t.message}", t)
            null
        }
    }

    private fun formatThrowable(t: Throwable): String {
        return when {
            t is java.net.SocketTimeoutException -> "网络超时"
            t is java.net.UnknownHostException -> "域名解析失败(无网络/DNS错误)"
            t is java.net.ConnectException -> "连接被拒绝"
            t is SecurityException -> "系统权限拦截(SecurityException)"
            else -> t.localizedMessage ?: t.javaClass.simpleName
        }
    }

    /**
     * 核心业务：边缘触发告警逻辑判定及数据持久化
     */
    private fun handleNewPrice(currentPrice: Double, timestamp: Long, sourceName: String) {
        // 1. 边缘触发告警状态机
        if (targetThreshold > 0) {
            if (currentPrice < targetThreshold) {
                if (!hasAlerted) {
                    // 低于阈值且未告警过 -> 触发高优先级横幅告警并唤醒点亮屏幕
                    sendAlertNotification(currentPrice, targetThreshold)
                    hasAlerted = true
                }
                // 若 hasAlerted == true，则跳过不弹窗（防轰炸）
            } else {
                // 价格反弹回阈值及以上 -> 重置标志位，等待下一次下跌穿透
                hasAlerted = false
            }
        }

        // 2. 更新共享状态并持久化写入 SharedPreferences
        val newState = MonitorState(
            isRunning = true,
            currentPrice = currentPrice,
            targetThreshold = targetThreshold,
            updateTime = timestamp,
            statusMessage = "监控中（数据源: $sourceName）"
        )
        _monitorState.value = newState
        saveStateToPrefs(newState)

        // 3. 刷新常驻前台通知
        val timeFormatted = formatTimestamp(timestamp)
        updatePersistentNotification(
            priceText = "实时金价: ¥%.2f /克".format(currentPrice),
            detailText = "目标: <¥%.2f | 来源: %s (%s)".format(targetThreshold, sourceName, timeFormatted)
        )
    }

    private fun saveStateToPrefs(state: MonitorState) {
        try {
            val sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sp.edit()
                .putBoolean(KEY_IS_RUNNING, state.isRunning)
                .putString(KEY_CURRENT_PRICE, state.currentPrice?.toString())
                .putFloat(KEY_TARGET_THRESHOLD, (state.targetThreshold ?: 0.0).toFloat())
                .putLong(KEY_UPDATE_TIME, state.updateTime ?: 0L)
                .putString(KEY_STATUS_MESSAGE, state.statusMessage)
                .apply()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to save state to SharedPreferences: ${t.message}", t)
        }
    }

    /**
     * 点亮屏幕（持续 5 秒），使手机在锁屏黑屏状态下能够直接亮屏展示通知
     */
    @Suppress("DEPRECATION")
    private fun wakeUpScreen() {
        try {
            val screenWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "Jinjia:AlertScreenWakeLock"
            )
            screenWakeLock.acquire(5_000L) // 5 秒后自动释放
            Log.i(TAG, "Screen wake lock acquired for 5 seconds to show alert")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to wake up screen: ${t.message}", t)
        }
    }

    /**
     * 发送高优先级横幅通知（带声音和振动，锁屏完全可见并唤醒点亮屏幕）
     */
    private fun sendAlertNotification(price: Double, threshold: Double) {
        // 1. 锁屏点亮屏幕
        wakeUpScreen()

        // 2. 构建并弹出通知
        val pendingIntent = createContentPendingIntent()

        val alertNotification = NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
            .setSmallIcon(R.drawable.ic_gold)
            .setContentTitle("【低价告警】金价已下跌破位！")
            .setContentText("当前实时金价 ¥%.2f /克，已跌破监控阈值 ¥%.2f /克！".format(price, threshold))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // 锁屏完全公开展示
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
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // 锁屏公开展示
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
            // 常驻监控渠道（Low 重要度，无声息，锁屏完全可见）
            val monitorChannel = NotificationChannel(
                CHANNEL_MONITOR_ID,
                getString(R.string.notification_channel_monitor_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示金价常驻前台监控状态与最新行情"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            // 告警渠道（High 重要度，悬浮横幅、振动与铃声，锁屏完全可见）
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
        val stoppedState = MonitorState(
            isRunning = false,
            currentPrice = _monitorState.value.currentPrice,
            targetThreshold = targetThreshold,
            updateTime = _monitorState.value.updateTime,
            statusMessage = "服务已终止"
        )
        _monitorState.value = stoppedState
        saveStateToPrefs(stoppedState)
    }
}
