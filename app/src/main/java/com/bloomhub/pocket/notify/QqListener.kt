package com.bloomhub.pocket.notify

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.bloomhub.pocket.AgentService

/**
 * QQ 新消息监听：主人（可配置昵称/QQ号）发来消息时，
 * 点亮屏幕并把「回复任务」塞进 Agent 队列。
 * 锁屏状态下无法操作屏幕——需要手机无锁屏或已解锁（本机无敏感资料的设定下通常可接受）。
 */
class QqListener : NotificationListenerService() {

    /** 冷启动兜底：服务没跑就拉起（否则任务只入队没人执行） */
    private fun startAgentService() {
        if (!AgentService.running) {
            try {
                applicationContext.startForegroundService(android.content.Intent(applicationContext, AgentService::class.java))
            } catch (e: Exception) {
                // 后台启动受限时忽略：下次打开 App 会补跑队列
            }
        }
    }

    companion object {
        const val QQ_PKG = "com.tencent.mobileqq"
        /** 任务来源（QQ 昵称）：改成你的名字/你信任的人 */
        val ownerNames = setOf("主人")
        private val seen = HashSet<String>()


    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn?.notification ?: return
        if (sbn.packageName != QQ_PKG) return
        val extras = n.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: return
        val text = (
            extras.getCharSequence("android.text")
                ?: extras.getCharSequence("android.bigText")
            )?.toString() ?: return
        // 去重（QQ 通知会重复发）
        val key = "$title|$text"
        synchronized(seen) {
            if (seen.contains(key)) return
            seen.add(key)
            if (seen.size > 400) seen.clear()
        }
        if (ownerNames.none { title.contains(it) }) return
        if (text.contains("[图片]") || text.contains("[动画表情]")) {
            // v1 无视觉；提示收到但暂不能看
            AgentService.enqueue("QQ 收到来自 $title 的媒体消息（口袋版暂看不了图片），回复一句并说明", "来自 $title 的消息：$text")
            return
        }
        startAgentService()
        AgentService.enqueue("处理 $title 发来的 QQ 消息", "他刚发来的消息：$text")
    }
}
