package com.simplechat.app.service

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
import androidx.core.app.NotificationCompat
import com.simplechat.app.MainActivity
import com.simplechat.app.R

/**
 * 「后台继续生成」的前台服务。
 *
 * ### 为什么必须有它
 *
 * 生成过程活在 `ChatViewModel.viewModelScope` 的协程里 —— 应用退到后台后，
 * 进程只是个普通后台进程：**系统随时可以把它回收**，屏幕一关更是几分钟就可能
 * 被判定为空闲。表现是回复写到一半突然停住（落库为 STOPPED），用户回来只看到半截。
 *
 * 前台服务把进程提到「用户可感知」的优先级，Doze 的网络限制也不作用于它 ——
 * 这是 Android 上唯一被官方支持的做法（后台普通 `Service` 在 8.0 起会被直接掐断）。
 *
 * ### 代价与边界
 *
 * - 通知栏会常驻一条「正在生成回复」，这是前台服务的强制要求，不能隐藏；
 * - 生成结束立即 `stopForeground`，通知随之消失，**不会**留下常驻通知；
 * - 只持有 `PARTIAL_WAKE_LOCK`（屏幕关闭时保住 CPU），不做 `FULL_WAKE_LOCK`
 *   —— 点亮屏幕不是我们的权利。
 */
class GenerationService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 5 秒内必须把通知挂上去，否则系统会以 ANR 级错误杀掉服务
        startForegroundCompat()
        acquireWakeLock()
        // 不要 START_STICKY：生成任务只属于当前进程的这一次请求，进程被回收后
        // 重启一个空服务毫无意义（只会留下一条假通知）
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        // stopForeground 后通知一起撤掉；REMOVE 让系统立刻清掉，而不是等下一次刷新
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            runCatching { acquire(WAKE_LOCK_TIMEOUT_MS) }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.generation_running_title))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.generation_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "generation"
        const val NOTIFICATION_ID = 0x5C01

        /** 唤醒锁的保险时长：生成结束后立刻释放，这个上限只防"忘了释放"。 */
        private const val WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1000L
        private const val WAKE_LOCK_TAG = "SimpleChat:generation"

        /**
         * 开始 / 结束前台服务。
         *
         * 由 `BackgroundGeneration` 统一调用（它拿着 application context），
         * 失败一律静默：这是"尽量别被杀"，不是功能的前置条件 ——
         * 服务起不来时生成照常进行，只是回到系统默认的优先级。
         */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, GenerationService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, GenerationService::class.java)) }
        }
    }
}
