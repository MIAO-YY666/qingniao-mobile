package com.bloomhub.pocket

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 全局崩溃捕获：未捕获异常的堆栈写入
 *   1) 应用私有目录 crash/last.txt
 *   2) 公共 Download/crash-<时间>.txt（方便直接用文件管理器看）
 * 并正常交给系统默认处理（崩溃）。
 * MainActivity 下次启动会把 last.txt 显示在运行日志区，方便定位闪退原因。
 */
class App : Application() {

    companion object {
        @Volatile var lastCrash: String? = null
        const val CRASH_FILE = "crash/last.txt"
    }

    override fun onCreate() {
        super.onCreate()
        val file = File(filesDir, CRASH_FILE)
        if (file.exists()) lastCrash = file.readText().take(4000)

        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val text = "time: ${System.currentTimeMillis()}\nthread: ${t.name}\n$sw"
                // 私有目录
                File(filesDir, "crash").apply { mkdirs() }
                File(filesDir, CRASH_FILE).writeText(text)
                // 公共 Download（无需权限，应用媒体目录）
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, "qingniao-crash-${System.currentTimeMillis()}.txt")
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    uri?.let { contentResolver.openOutputStream(it)?.use { o -> o.write(text.toByteArray()) } }
                }
            } catch (_: Exception) {
            }
            // 交给系统默认处理（真正崩溃）
            android.os.Process.killProcess(android.os.Process.myPid())
            Runtime.getRuntime().exit(10)
        }
    }
}
