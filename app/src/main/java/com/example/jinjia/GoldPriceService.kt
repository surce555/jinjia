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
import android.graphics.Color
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 金价后台轮询与多品类精准监控服务
 * 支持双数据源定向高频轮询与 Doze 休眠准时唤醒，提供锁屏通知穿透与测试模式
 */
class GoldPriceService : Service() {

    data class MonitorState(
        val isRunning: Boolean = false,
        val targetId: String = "realtime_icbc",
        val targetTitle: String = "[实时] 工商银行",
        val targetPrice: Double? = 932.33,
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
        const val ACTION_ENTER_FOREGROUND = "com.example.jinjia.ACTION_ENTER_FOREGROUND"
        const val ACTION_ENTER_BACKGROUND = "com.example.jinjia.ACTION_ENTER_BACKGROUND"
        const val ACTION_TEST_NOTIFICATION = "com.example.jinjia.ACTION_TEST_NOTIFICATION"

        const val BACKGROUND_INTERVAL_MS = 5 * 60 * 1000L // 后台休眠固定 5 分钟 (300,000ms)
        const val FOREGROUND_INTERVAL_MS = 60 * 1000L      // 前台活跃固定 1 分钟 (60,000ms)

        // 隐藏测试阈值常数
        const val THRESHOLD_TEST_30S = 10030.0
        const val THRESHOLD_TEST_5M = 10300.0

        const val EXTRA_TARGET_ID = "extra_target_id"
        const val EXTRA_TARGET_NAME = "extra_target_name"
        const val EXTRA_THRESHOLD = "extra_threshold"
        const val EXTRA_INTERVAL_MINUTES = "extra_interval_minutes"
        const val EXTRA_TEST_MODE = "extra_test_mode"

        const val CHANNEL_MONITOR_ID = "gold_monitor_channel"
        const val CHANNEL_ALERT_ID = "gold_alert_channel_v3" // v3 纯强力振动通知，彻底静音关闭声音

        const val NOTIFICATION_MONITOR_ID = 1001
        const val NOTIFICATION_ALERT_ID = 2001
        const val NOTIFICATION_TEST_ID = 3001

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
            return try {
                val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val isRunning = sp.getBoolean(KEY_IS_RUNNING, false)
                val targetId = sp.getString(KEY_TARGET_ID, "realtime_icbc") ?: "realtime_icbc"
                val targetTitle = sp.getString(KEY_TARGET_TITLE, "[实时] 工商银行") ?: "[实时] 工商银行"
                val priceStr = sp.getString(KEY_TARGET_PRICE, null)
                val targetPrice = priceStr?.toDoubleOrNull() ?: 932.33
                val threshold = sp.getFloat(KEY_TARGET_THRESHOLD, 0f).toDouble()
                val intervalMinutes = sp.getFloat(KEY_INTERVAL_MINUTES, 5.0f).toDouble()
                val updateTime = sp.getLong(KEY_UPDATE_TIME, 0L)
                val statusMsg = sp.getString(KEY_STATUS_MESSAGE, if (isRunning) "正在监控中" else "未运行") ?: "未运行"
                val json = sp.getString(KEY_ALL_ITEMS_JSON, null) ?: ""
                val allItems = if (json.isNotBlank()) GoldDataParser.deserializeItems(json) else GoldDataParser.DEFAULT_TARGETS

                MonitorState(
                    isRunning = isRunning,
                    targetId = targetId,
                    targetTitle = targetTitle,
                    targetPrice = targetPrice,
                    targetThreshold = if (threshold > 0) threshold else null,
                    intervalMinutes = intervalMinutes.coerceAtLeast(0.5),
                    updateTime = if (updateTime > 0) updateTime else null,
                    statusMessage = statusMsg,
                    allItems = if (allItems.isNotEmpty()) allItems else GoldDataParser.DEFAULT_TARGETS
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to getSavedState: ${t.message}", t)
                MonitorState(allItems = GoldDataParser.DEFAULT_TARGETS)
            }
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var pollJob: Job? = null
    private var testJob: Job? = null

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
    private var targetId: String = "realtime_icbc"
    private var targetTitle: String = "[实时] 工商银行"
    private var targetThreshold: Double = 0.0
    private var intervalMinutes: Double = 5.0
    private var hasAlerted: Boolean = false
    private var lastAlertTime: Long = 0L
    private var lastAlertPrice: Double = 0.0
    private var isAppInForeground: Boolean = false
    private var alertCounter: Int = 0
    private var test30sWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannels()
            val saved = getSavedState(this)
            targetId = saved.targetId
            targetTitle = saved.targetTitle
            if (saved.targetThreshold != null) targetThreshold = saved.targetThreshold
            intervalMinutes = saved.intervalMinutes
            _monitorState.value = saved
        } catch (t: Throwable) {
            Log.e(TAG, "Error in onCreate: ${t.message}", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 第一行必须立即调用 startForeground 满足 Android 14 规范，杜绝闪退
        try {
            promoteToForeground()
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed: ${t.message}", t)
        }

        try {
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
                        lastAlertTime = 0L
                        lastAlertPrice = 0.0
                    }
                    intervalMinutes = interval.coerceAtLeast(0.5)

                    startMonitorService()
                }
                ACTION_ENTER_FOREGROUND -> {
                    isAppInForeground = true
                    Log.i(TAG, "App entered foreground: active UI sync")
                }
                ACTION_ENTER_BACKGROUND -> {
                    isAppInForeground = false
                    Log.i(TAG, "App entered background: switched to 5-minute background polling")
                    if (_monitorState.value.isRunning) {
                        scheduleSafeAlarm()
                    }
                }
                ACTION_POLL -> {
                    if (_monitorState.value.isRunning) {
                        Log.i(TAG, "Safe alarm triggered in background")
                        serviceScope.launch {
                            try {
                                fetchPriceAndEvaluate()
                            } catch (t: Throwable) {
                                Log.e(TAG, "ACTION_POLL error: ${t.message}", t)
                            } finally {
                                // 核心修复：后台每次闹钟拉取完毕后，必须无缝预定下一次闹钟，杜绝深睡断链
                                if (_monitorState.value.isRunning) {
                                    scheduleSafeAlarm()
                                }
                            }
                        }
                    }
                }
                ACTION_TEST_NOTIFICATION -> {
                    val mode = intent.getStringExtra(EXTRA_TEST_MODE) ?: "30秒"
                    sendTestNotification(mode)
                }
                ACTION_STOP -> {
                    stopMonitorService()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Exception in onStartCommand: ${t.message}", t)
            resetRunningStateOnFailure()
        }
        return START_STICKY
    }

    private fun promoteToForeground() {
        val modeDesc = if (isAppInForeground) "前台1m" else "后台5m"
        val notification = buildMonitorNotification(
            priceText = "【$targetTitle】正在监控...",
            detailText = "阈值: ¥%.2f | 频率: %s".format(targetThreshold, modeDesc)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_MONITOR_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_MONITOR_ID, notification)
        }
    }

    private fun startMonitorService() {
        promoteToForeground()

        val updatedState = _monitorState.value.copy(
            isRunning = true,
            targetId = targetId,
            targetTitle = targetTitle,
            targetThreshold = targetThreshold,
            intervalMinutes = intervalMinutes,
            statusMessage = "正在监控中"
        )
        _monitorState.value = updatedState
        saveStateToPrefs(updatedState, null)

        // 隐藏测试功能：输入 10030 触发 30 秒测试，输入 10300 触发 5 分钟测试
        if (targetThreshold == THRESHOLD_TEST_30S) {
            scheduleTestAlarm("30秒", 30_000L)
        } else if (targetThreshold == THRESHOLD_TEST_5M) {
            scheduleTestAlarm("5分钟", 300_000L)
        }

        // 启动安全协程循环轮询，辅以精准闹钟唤醒
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            while (isActive) {
                try {
                    fetchPriceAndEvaluate()
                } catch (t: Throwable) {
                    Log.e(TAG, "Coroutine loop error: ${t.message}", t)
                }
                scheduleSafeAlarm()
                val delayMs = if (isAppInForeground) FOREGROUND_INTERVAL_MS else BACKGROUND_INTERVAL_MS
                delay(delayMs)
            }
        }
    }

    private fun stopMonitorService() {
        cancelAlarm()
        cancelTestAlarm()
        pollJob?.cancel()
        testJob?.cancel()

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

        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {}
        stopSelf()
    }

    /**
     * 针对锁屏测试模式的闹钟与协程双通道准时推送调度
     */
    private fun scheduleTestAlarm(modeDesc: String, delayMs: Long) {
        cancelTestAlarm()
        testJob?.cancel()

        // 针对 30 秒短时间测试持有 PARTIAL_WAKE_LOCK，彻底阻止小米 HyperOS 锁屏后瞬间冻结 CPU
        if (delayMs <= 60_000L) {
            try {
                test30sWakeLock?.let { if (it.isHeld) it.release() }
                test30sWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Jinjia:Test30sWakeLock"
                ).apply {
                    acquire(delayMs + 8_000L)
                }
                Log.i(TAG, "Acquired 30s test WakeLock for ${delayMs + 8000}ms")
            } catch (t: Throwable) {
                Log.w(TAG, "WakeLock acquire error: ${t.message}")
            }
        }

        // 1. AlarmManager 定时精准唤醒（使用系统最高优先级 setAlarmClock 穿透 Doze 休眠）
        try {
            val intent = Intent(this, GoldPriceService::class.java).apply {
                action = ACTION_TEST_NOTIFICATION
                putExtra(EXTRA_TEST_MODE, modeDesc)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(this, 1003, intent, flags)
            } else {
                PendingIntent.getService(this, 1003, intent, flags)
            }

            scheduleAlarmWithAlarmClock(delayMs, pendingIntent)
            Log.i(TAG, "Test notification alarm scheduled after $delayMs ms ($modeDesc)")
        } catch (t: Throwable) {
            Log.w(TAG, "scheduleTestAlarm error: ${t.message}")
        }

        // 2. 协程并发双保险（在持有 WakeLock 下，即使熄屏 CPU 也不休眠，准时触发）
        testJob = serviceScope.launch {
            delay(delayMs)
            sendTestNotification(modeDesc)
        }
    }

    private fun cancelTestAlarm() {
        try {
            test30sWakeLock?.let { if (it.isHeld) it.release() }
            test30sWakeLock = null
        } catch (_: Throwable) {}

        try {
            val intent = Intent(this, GoldPriceService::class.java).apply {
                action = ACTION_TEST_NOTIFICATION
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(this, 1003, intent, flags)
            } else {
                PendingIntent.getService(this, 1003, intent, flags)
            }
            alarmManager.cancel(pendingIntent)
        } catch (_: Throwable) {}
    }

    /**
     * 发送隐藏测试通知（支持硬件级强力振动直出，彻底静音关闭声音，穿透小米 HyperOS 锁屏）
     */
    private fun sendTestNotification(modeDesc: String) {
        // 释放 30s 唤醒锁
        try {
            test30sWakeLock?.let { if (it.isHeld) it.release() }
            test30sWakeLock = null
        } catch (_: Throwable) {}

        // 1. 唤醒屏幕
        wakeUpScreen()

        // 2. 触发系统级硬件强力振动直出（关闭声音，纯振动穿透）
        playAlertVibrationOnly()

        // 3. 构建最高优先级通知 (纯振动，静音)
        val pendingIntent = createContentPendingIntent()

        val testNotification = NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
            .setSmallIcon(R.drawable.ic_gold)
            .setContentTitle("【金价盯盘】锁屏测试通知到达！")
            .setContentText("触发模式: $modeDesc 延迟推送。手机锁屏与后台休眠唤醒测试成功！(纯振动)")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("【金价盯盘】锁屏通知测试成功！\n触发模式: $modeDesc 延迟推送（纯振动模式）。\n当前应用在手机锁屏/Doze 休眠状态下成功唤醒 CPU 并送达通知！\n\n提示：若屏幕未亮或通知被折叠，请在小米系统设置中开启本应用的【锁屏通知】与【后台弹出界面】权限。")
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setSound(null)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS)
            .setVibrate(longArrayOf(0, 800, 300, 800, 300, 800))
            .setFullScreenIntent(pendingIntent, true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_TEST_ID, testNotification)
        Log.i(TAG, "Test notification dispatched ($modeDesc, vibration only)")
    }

    /**
     * 适配 Android 6+ 至 14+ 的系统级 AlarmClock 精准闹钟调度
     * AlarmClockInfo 享有系统最高优先级，不受 Doze 深度休眠限制，到点准时唤醒 CPU
     */
    private fun scheduleSafeAlarm() {
        try {
            val intervalMs = if (isAppInForeground) FOREGROUND_INTERVAL_MS else BACKGROUND_INTERVAL_MS
            val pendingIntent = getPollPendingIntent()

            scheduleAlarmWithAlarmClock(intervalMs, pendingIntent)
            Log.i(TAG, "Safe alarm scheduled after ${intervalMs / 1000}s (foreground=$isAppInForeground)")
        } catch (t: Throwable) {
            Log.w(TAG, "scheduleSafeAlarm skipped: ${t.message}")
        }
    }

    private fun scheduleAlarmWithAlarmClock(delayMs: Long, pendingIntent: PendingIntent) {
        val showIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )
        val triggerAtWallClock = System.currentTimeMillis() + delayMs
        val triggerAtElapsed = SystemClock.elapsedRealtime() + delayMs

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val clockInfo = AlarmManager.AlarmClockInfo(triggerAtWallClock, showIntent)
                alarmManager.setAlarmClock(clockInfo, pendingIntent)
                Log.i(TAG, "setAlarmClock scheduled successfully: delay=${delayMs}ms")
                return
            } catch (t: Throwable) {
                Log.w(TAG, "setAlarmClock failed, falling back to exact idle: ${t.message}")
            }
        }

        scheduleExactOrAllowWhileIdle(triggerAtElapsed, pendingIntent)
    }

    private fun scheduleExactOrAllowWhileIdle(triggerAtMillis: Long, pendingIntent: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
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
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        } else {
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }
    }

    private fun cancelAlarm() {
        try {
            alarmManager.cancel(getPollPendingIntent())
        } catch (t: Throwable) {
            Log.w(TAG, "cancelAlarm error: ${t.message}")
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
     * 核心网络拉取与指定标的预警比对
     * 针对锁屏深睡唤醒提供网络重试机制（防止基带休眠未就绪抛出异常）
     */
    private suspend fun fetchPriceAndEvaluate() {
        val wakeLock = try {
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Jinjia:NetworkWakeLock"
            ).apply { acquire(45_000L) }
        } catch (_: Throwable) {
            null
        }

        try {
            var retryCount = 0
            var success = false
            while (retryCount < 3 && !success) {
                try {
                    if (targetId.startsWith("realtime_")) {
                        val bankCode = targetId.removePrefix("realtime_")
                        val item = GoldRepository.fetchRealtimeBank(bankCode)
                        if (item != null) {
                            handleSingleItemUpdate(item)
                            success = true
                        } else {
                            retryCount++
                            if (retryCount < 3) delay(1500L)
                        }
                    } else {
                        val (allItems, jsonString) = GoldRepository.fetchGoldData()
                        if (allItems.isNotEmpty()) {
                            handleParsedData(allItems, jsonString)
                            success = true
                        } else {
                            retryCount++
                            if (retryCount < 3) delay(1500L)
                        }
                    }
                } catch (e: Exception) {
                    retryCount++
                    Log.w(TAG, "Network attempt $retryCount failed: ${e.message}")
                    if (retryCount < 3) {
                        delay(1500L)
                    } else {
                        throw e
                    }
                }
            }

            if (!success) {
                handleFetchError("机构【$targetTitle】响应超时")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Fetch failed after retries: ${t.message}", t)
            handleFetchError(t.message ?: t.javaClass.simpleName)
        } finally {
            try {
                if (wakeLock?.isHeld == true) wakeLock.release()
            } catch (_: Throwable) {}
        }
    }

    /**
     * 评估是否触发跌破预警（支持首次跌破、深跌追加告警与长周期防漏看）
     */
    private fun shouldTriggerAlert(currentPrice: Double, threshold: Double): Boolean {
        if (threshold <= 0 || currentPrice <= 0) return false
        if (threshold == THRESHOLD_TEST_30S || threshold == THRESHOLD_TEST_5M) return false
        if (currentPrice >= threshold) {
            hasAlerted = false
            return false
        }
        val now = System.currentTimeMillis()
        // 首次跌破阈值，立即告警
        if (!hasAlerted) {
            hasAlerted = true
            lastAlertTime = now
            lastAlertPrice = currentPrice
            return true
        }
        // 已告警过，若价格进一步深跌 >= 1.0 元，再次触发强力振动告警
        if (currentPrice <= lastAlertPrice - 1.0) {
            lastAlertTime = now
            lastAlertPrice = currentPrice
            return true
        }
        // 或者持续处于低位且距离上次告警超过 30 分钟，再次振动提醒防漏看
        if (now - lastAlertTime >= 30 * 60 * 1000L) {
            lastAlertTime = now
            lastAlertPrice = currentPrice
            return true
        }
        return false
    }

    /**
     * 单项高频实时数据更新处理
     */
    private fun handleSingleItemUpdate(item: GoldItem) {
        val timestamp = System.currentTimeMillis()
        val currentPrice = item.price
        val currentTitle = item.displayName
        targetId = item.id
        targetTitle = currentTitle

        // 1. 边缘触发告警状态机（低于阈值推送通知）
        if (shouldTriggerAlert(currentPrice, targetThreshold)) {
            sendAlertNotification(currentTitle, currentPrice, targetThreshold, item.unit)
        }

        // 2. 合并更新本地全量列表
        val currentList = _monitorState.value.allItems.toMutableList()
        val idx = currentList.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            currentList[idx] = item
        } else {
            currentList.add(0, item)
        }

        // 3. 更新状态并持久化
        val newState = MonitorState(
            isRunning = true,
            targetId = targetId,
            targetTitle = targetTitle,
            targetPrice = currentPrice,
            targetThreshold = targetThreshold,
            intervalMinutes = intervalMinutes,
            updateTime = timestamp,
            statusMessage = "正在监控",
            allItems = currentList
        )
        _monitorState.value = newState
        val json = GoldDataParser.serializeItems(currentList)
        saveStateToPrefs(newState, json)

        // 4. 刷新前台常驻通知
        val timeFormatted = formatTimestamp(timestamp)
        val symbol = if (item.unit.contains("美元") || item.id == "realtime_gj") "$" else "¥"
        updatePersistentNotification(
            priceText = "【$targetTitle】$symbol%.2f %s".format(currentPrice, item.unit),
            detailText = "阈值: <$symbol%.2f | 间隔: %.1fm (%s)".format(targetThreshold, intervalMinutes, timeFormatted)
        )
    }

    /**
     * 综合多品类全量数据更新处理
     */
    private fun handleParsedData(allItems: List<GoldItem>, rawJson: String) {
        val timestamp = System.currentTimeMillis()

        // 精准匹配当前监控的目标标的
        val targetItem = allItems.find { it.id == targetId }
            ?: allItems.find { it.title == targetTitle }
            ?: allItems.first()

        val currentPrice = targetItem.price
        val currentTitle = targetItem.displayName
        targetId = targetItem.id
        targetTitle = currentTitle

        // 1. 边缘触发告警状态机
        if (shouldTriggerAlert(currentPrice, targetThreshold)) {
            sendAlertNotification(currentTitle, currentPrice, targetThreshold, targetItem.unit)
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
            statusMessage = "正在监控",
            allItems = allItems
        )
        _monitorState.value = newState
        saveStateToPrefs(newState, rawJson)

        // 3. 刷新前台常驻通知
        val timeFormatted = formatTimestamp(timestamp)
        val symbol = if (targetItem.unit.contains("美元") || targetItem.id == "realtime_gj") "$" else "¥"
        updatePersistentNotification(
            priceText = "【$targetTitle】$symbol%.2f %s".format(currentPrice, targetItem.unit),
            detailText = "阈值: <$symbol%.2f | 间隔: %.1fm (%s)".format(targetThreshold, intervalMinutes, timeFormatted)
        )
    }

    private fun handleFetchError(errorMsg: String) {
        val timeFormatted = formatTimestamp(System.currentTimeMillis())
        val errState = _monitorState.value.copy(
            statusMessage = "拉取等待: $errorMsg"
        )
        _monitorState.value = errState
        saveStateToPrefs(errState, null)

        updatePersistentNotification(
            priceText = "【$targetTitle】更新等待中",
            detailText = "$errorMsg ($timeFormatted)"
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
            Log.e(TAG, "saveStateToPrefs error: ${t.message}", t)
        }
    }

    private fun resetRunningStateOnFailure() {
        try {
            val sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sp.edit().putBoolean(KEY_IS_RUNNING, false).apply()
            _monitorState.value = _monitorState.value.copy(isRunning = false, statusMessage = "已重置未运行")
        } catch (_: Throwable) {}
    }

    /**
     * 强力系统级振动直出（关闭声音，纯振动穿透锁屏与部分系统的静音策略）
     */
    private fun playAlertVibrationOnly() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vm?.defaultVibrator ?: (getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }

            val pattern = longArrayOf(0, 800, 300, 800, 300, 800)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    android.os.VibrationEffect.createWaveform(
                        pattern,
                        -1
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, -1)
            }
            Log.i(TAG, "Direct vibration executed successfully (sound disabled)")
        } catch (t: Throwable) {
            Log.e(TAG, "Direct vibration error: ${t.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeUpScreen() {
        try {
            val screenWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "Jinjia:AlertScreenWakeLock"
            )
            screenWakeLock.acquire(10_000L)
        } catch (t: Throwable) {
            Log.e(TAG, "wakeUpScreen error: ${t.message}", t)
        }
    }

    private fun sendAlertNotification(title: String, price: Double, threshold: Double, unit: String) {
        wakeUpScreen()
        playAlertVibrationOnly()

        val pendingIntent = createContentPendingIntent()
        val symbol = if (unit.contains("美元") || unit.contains("$") || title.contains("伦敦金")) "$" else "¥"

        val alertNotification = NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
            .setSmallIcon(R.drawable.ic_gold)
            .setContentTitle("【$title】跌破预警阈值！")
            .setContentText("【$title】跌破阈值，当前价格为 $symbol%.2f %s（监控阈值: $symbol%.2f %s）".format(price, unit, threshold, unit))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("【$title】跌破监控阈值！\n当前最新价格: $symbol%.2f %s\n设定的预警阈值: $symbol%.2f %s\n请及时关注实盘行情变动。".format(price, unit, threshold, unit))
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setSound(null)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS)
            .setVibrate(longArrayOf(0, 800, 300, 800, 300, 800))
            .setFullScreenIntent(pendingIntent, true)
            .setContentIntent(pendingIntent)
            .build()

        alertCounter++
        val notificationId = NOTIFICATION_ALERT_ID + (alertCounter % 5)
        notificationManager.notify(notificationId, alertNotification)
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
            // 清理历史旧通道，确保通道静音配置即时生效
            try {
                notificationManager.deleteNotificationChannel("gold_alert_channel")
                notificationManager.deleteNotificationChannel("gold_alert_channel_v2")
            } catch (_: Throwable) {}

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
                description = "当金价低于监控阈值或测试时弹出高优先级横幅告警并强力振动（无声音）"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 300, 800, 300, 800)
                enableLights(true)
                lightColor = Color.CYAN
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null) // 彻底静音，仅保留振动
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
        try {
            cancelAlarm()
            cancelTestAlarm()
            serviceScope.cancel()
            val stoppedState = _monitorState.value.copy(
                isRunning = false,
                statusMessage = "服务已终止"
            )
            _monitorState.value = stoppedState
            saveStateToPrefs(stoppedState, null)
        } catch (_: Throwable) {}
    }
}
