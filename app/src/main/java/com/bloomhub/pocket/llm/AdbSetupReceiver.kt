package com.bloomhub.pocket.llm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bloomhub.pocket.store.ChatStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * ADB 配置通道（开发/自用，免点屏幕）：
 *   am broadcast -a com.bloomhub.pocket.ADB_DOWNLOAD --es model_id qwen3-1.7b
 *   am broadcast -a com.bloomhub.pocket.ADB_IMPORT  --es path /sdcard/.../model.gguf
 *   am broadcast -a com.bloomhub.pocket.ADB_WIZARD_DONE
 */
class AdbSetupReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val go = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_DOWNLOAD -> {
                        val spec = ModelCatalog.byId(intent.getStringExtra("model_id"))
                        if (spec == null) {
                            ChatStore(context).append("adb", "未知 model_id；可选：${ModelCatalog.MODELS.joinToString { it.id }}")
                        } else {
                            ChatStore(context).append("adb", "ADB 触发下载：${spec.name}")
                            ModelSetup.enqueue(context, spec)
                            // 前台服务盯完成（广播进程活不过 10 秒）
                            context.startForegroundService(android.content.Intent(context, com.bloomhub.pocket.AgentService::class.java).putExtra("watchDownload", true))
                        }
                    }
                    ACTION_IMPORT -> {
                        val path = intent.getStringExtra("path")
                        if (path.isNullOrBlank()) {
                            ChatStore(context).append("adb", "ADB_IMPORT 需要 path 参数")
                        } else {
                            // 拷贝 1GB+ 文件远超广播 10s 窗口：交给前台服务做
                            context.startForegroundService(android.content.Intent(context, com.bloomhub.pocket.AgentService::class.java).putExtra("importPath", path))
                            ChatStore(context).append("adb", "ADB 导入已转交服务处理：$path")
                        }
                    }
                    ACTION_TASK -> {
                        val goal = intent.getStringExtra("goal")
                        if (!goal.isNullOrBlank()) {
                            ChatStore(context).append("adb", "ADB 派任务：$goal")
                            if (!com.bloomhub.pocket.AgentService.running) {
                                context.startForegroundService(android.content.Intent(context, com.bloomhub.pocket.AgentService::class.java))
                            }
                            com.bloomhub.pocket.AgentService.enqueue(goal)
                        }
                    }
                    ACTION_WIZARD_DONE -> {
                        context.getSharedPreferences("pocket", Context.MODE_PRIVATE)
                            .edit().putBoolean("setupDone", true).apply()
                        ChatStore(context).append("adb", "已标记新手向导完成")
                    }
                }
            } catch (e: Exception) {
                ChatStore(context).append("adb", "ADB 命令异常：${e.message}")
            } finally {
                go.finish()
            }
        }
    }

    companion object {
        const val ACTION_DOWNLOAD = "com.bloomhub.pocket.ADB_DOWNLOAD"
        const val ACTION_IMPORT = "com.bloomhub.pocket.ADB_IMPORT"
        const val ACTION_WIZARD_DONE = "com.bloomhub.pocket.ADB_WIZARD_DONE"
        const val ACTION_TASK = "com.bloomhub.pocket.ADB_TASK"
    }
}
