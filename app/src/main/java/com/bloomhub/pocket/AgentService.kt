package com.bloomhub.pocket

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.bloomhub.pocket.agent.AgentLoop
import com.bloomhub.pocket.llm.CloudLlm
import com.bloomhub.pocket.llm.Llm
import com.bloomhub.pocket.llm.LocalLlm
import com.bloomhub.pocket.screen.ScreenAccess
import com.bloomhub.pocket.store.ChatStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue

/**
 * 前台服务：持有 LLM 与 Agent 循环，串行处理任务队列。
 * 任务来源：MainActivity 手动下发、QqListener 通知触发。
 */
class AgentService : Service() {

    companion object {
        @Volatile var running = false
            private set
        @Volatile var statusText = "未启动"
        private var llm: Llm? = null
        private val queue = LinkedBlockingQueue<Task>()
        private val mainHandler = Handler(Looper.getMainLooper())
        private var chatStore: ChatStore? = null

        /** 任务入队；qqContext 为触发任务的消息背景（可空） */
        fun enqueue(goal: String, qqContext: String? = null) {
            queue.add(Task(goal, qqContext))
            statusText = "队列中：${queue.size} 个任务"
        }

        fun setLlm(newLlm: Llm) {
            llm?.unload()
            llm = newLlm
            statusText = "模型就绪（${if (newLlm is LocalLlm) "本地" else "云端"}）"
        }

        fun hasLlm() = llm != null

        fun logToUi(line: String) {
            chatStore?.append("log", line)
        }

        data class Task(val goal: String, val qqContext: String?)
    }

    private lateinit var wakeLock: PowerManager.WakeLock
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        chatStore = ChatStore(this)
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocket:agent")
        startForeground(1, buildNotification())
        running = true
        statusText = "待命（${if (llm == null) "未加载模型" else "模型就绪"}）"
        scope.launch { loop() }
        Log.i("AgentService", "started")
    }

    private suspend fun loop() {
        while (true) {
            val task = try { queue.take() } catch (e: InterruptedException) { break }
            val current = llm
            if (current == null) {
                statusText = "未加载模型，任务被跳过"
                logToUi("跳过任务（未加载模型）：${task.goal}")
                continue
            }
            statusText = "执行中：${task.goal.take(30)}"
            logToUi("▶ ${task.goal}")
            wakeLock.acquire(10 * 60 * 1000L)
            try {
                val agent = AgentLoop(this, current) { line ->
                    logToUi(line)
                    statusText = "执行中：${line.take(40)}"
                }
                val r = agent.run(task.goal, task.qqContext)
                statusText = if (r.ok) "完成：${r.summary.take(40)}" else "失败：${r.summary.take(40)}"
                logToUi(if (r.ok) "✔ ${r.summary}" else "✘ ${r.summary}")
            } catch (e: Exception) {
                statusText = "异常：${e.message?.take(40)}"
                logToUi("异常：${e.message}")
                Log.e("AgentService", "task failed", e)
            } finally {
                wakeLock.release()
            }
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("pocket", getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, "pocket")
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }
}
