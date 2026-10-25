package com.bloomhub.pocket

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bloomhub.pocket.llm.DeviceProfile
import com.bloomhub.pocket.llm.LocalLlm
import com.bloomhub.pocket.llm.ModelCatalog
import com.bloomhub.pocket.screen.ScreenAccess
import com.bloomhub.pocket.screen.Screenshot
import java.io.File

/**
 * 新手向导：检测配置 → 推荐模型 → 一键下载并自动配置 → 权限教学 → 完成。
 * 下载走系统 DownloadManager（有通知进度、系统级网络处理），完成即自动加载。
 */
class SetupActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: android.content.SharedPreferences
    private var downloadId: Long = -1
    private var poll: Runnable? = null

    private val completeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            checkDownload()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        prefs = getSharedPreferences("pocket", MODE_PRIVATE)
        downloadId = prefs.getLong("modelDownloadId", -1)

        // ---- 第 1 步：设备检测 + 模型推荐 ----
        val dev = DeviceProfile.detect(this)
        findViewById<TextView>(R.id.deviceInfo).text =
            "机型：${dev.model}\n芯片：${dev.socName}　核心：${dev.cores} 核\n内存：${dev.ramGb}GB"

        val rec = ModelCatalog.recommend(dev.ramGb)
        val group = findViewById<RadioGroup>(R.id.modelGroup)
        for (m in ModelCatalog.MODELS) {
            val rb = RadioButton(this).apply {
                text = "${m.name}　${m.sizeMb}MB\n聪明 ${"★".repeat(m.quality)}${"☆".repeat(5 - m.quality)}　" +
                    "速度 ${"★".repeat(m.speed)}${"☆".repeat(5 - m.speed)}\n${m.desc}" +
                    if (m.id == rec.id) "\n👉 根据你的 ${dev.ramGb}GB 内存，推荐这个" else
                        if (m.minRamGb > dev.ramGb) "\n⚠ 内存偏小，不建议" else ""
                tag = m.id
                textSize = 13f
                setPadding(0, 10, 0, 10)
            }
            group.addView(rb)
            if (m.id == rec.id) group.check(rb.id)
        }

        // 已有模型：直接显示就绪
        val existing = File(filesDir, "models/model.gguf")
        if (existing.exists()) {
            findViewById<TextView>(R.id.downloadStatus).text = "✓ 已有模型（${existing.length() / 1048576}MB），无需重复下载，直接下一步"
        }

        findViewById<Button>(R.id.btnDownload).setOnClickListener { startDownload(group) }

        // ---- 第 2 步：权限教学 ----
        findViewById<Button>(R.id.btnWizA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnWizNotif).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnWizShot).setOnClickListener {
            Screenshot.requestPermission(this)
        }
        findViewById<Button>(R.id.btnWizBat).setOnClickListener {
            @Suppress("BatteryLife")
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        // ---- 第 3 步：完成 ----
        findViewById<Button>(R.id.btnFinish).setOnClickListener {
            if (!AgentService.hasLlm() && existing.exists()) {
                try { AgentService.setLlm(LocalLlm(existing.absolutePath)) } catch (_: Exception) {}
            }
            prefs.edit().putBoolean("setupDone", true).apply()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        // 顶部步骤指示随滚动不重要，简化为固定文案
        findViewById<TextView>(R.id.stepIndicator).text =
            "按顺序做完①~⑤就能用；已经做过的不用重复。"

        refreshPermStatus()
    }

    private fun selectedModel(): ModelCatalog.ModelSpec? {
        val group = findViewById<RadioGroup>(R.id.modelGroup)
        val rb = group.findViewById<RadioButton>(group.checkedRadioButtonId) ?: return null
        val id = rb.tag as? String ?: return null
        return ModelCatalog.MODELS.firstOrNull { it.id == id }
    }

    private fun startDownload(group: RadioGroup) {
        val m = selectedModel() ?: run { toast("先在上面选一个模型"); return }
        // 勾选任何选项后已存在模型也允许重新下载（换模型）
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(android.net.Uri.parse(m.url)).apply {
            setTitle("哦鲸鲸的脑子：${m.name}")
            setDescription("下载完成会自动配置，不用管它")
            setDestinationInExternalFilesDir(this@SetupActivity, Environment.DIRECTORY_DOWNLOADS, "model.gguf.part")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
        }
        // 清掉旧的半成品
        File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "model.gguf.part").delete()
        downloadId = dm.enqueue(req)
        prefs.edit().putLong("modelDownloadId", downloadId)
            .putString("pendingModelName", m.name).apply()
        findViewById<TextView>(R.id.downloadStatus).text =
            "⏬ 已开始下载 ${m.name}（约 ${m.sizeMb}MB）。通知栏能看到进度；下完这一页会自动变成「✓ 模型就绪」。"
        toast("开始下载，去通知栏看进度")
        startPolling()
    }

    private fun startPolling() {
        stopPolling()
        poll = object : Runnable {
            override fun run() {
                checkDownload()
                handler.postDelayed(this, 3000)
            }
        }
        handler.post(poll!!)
    }

    private fun stopPolling() {
        poll?.let { handler.removeCallbacks(it) }
        poll = null
    }

    /** 检查下载状态；完成则改名 + 自动加载 + 更新 UI */
    private fun checkDownload() {
        if (downloadId <= 0) return
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val q = DownloadManager.Query().setFilterById(downloadId)
        val c: Cursor = dm.query(q) ?: return
        try {
            if (!c.moveToFirst()) return
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val tv = findViewById<TextView>(R.id.downloadStatus)
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    // 下载落在外部私有目录，搬进应用内部 models/
                    val part = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "model.gguf.part")
                    val target = File(filesDir, "models/model.gguf")
                    if (part.exists() && part.length() > 50L * 1024 * 1024) {
                        target.parentFile?.mkdirs()
                        target.delete()
                        part.renameTo(target)
                        // 自动配置：直接加载
                        try {
                            AgentService.setLlm(LocalLlm(target.absolutePath))
                            tv.text = "✓ 模型就绪（${target.length() / 1048576}MB，已自动配置完成）！继续第④步开权限吧"
                            toast("大脑装好了 ✓")
                        } catch (e: Exception) {
                            tv.text = "✓ 下载完成，但加载失败：${e.message}（回主界面重新导入试试）"
                        }
                        prefs.edit().putLong("modelDownloadId", -1).apply()
                        downloadId = -1
                        stopPolling()
                    }
                }
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> {
                    val sofar = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    if (total > 0) {
                        tv.text = "⏬ 下载中 ${(sofar / 1048576)}/${total / 1048576}MB（${sofar * 100 / total}%）——保持这个页面或去看通知栏，完成后自动配置"
                    }
                }
                DownloadManager.STATUS_FAILED -> {
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    tv.text = "✗ 下载失败（原因码 $reason）。点上面的下载按钮重试；多次失败就用电脑下载 .gguf 放进手机 Download 再「导入本地 GGUF 模型」"
                    prefs.edit().putLong("modelDownloadId", -1).apply()
                    downloadId = -1
                    stopPolling()
                }
            }
        } catch (_: Exception) {
        } finally {
            c.close()
        }
    }

    private fun refreshPermStatus() {
        val sb = StringBuilder()
        sb.append(if (ScreenAccess.available) "✓ 无障碍已开" else "○ 无障碍还没开").append('\n')
        sb.append(if (AgentService.hasLlm()) "✓ 模型已就绪" else "○ 模型还没配置").append('\n')
        findViewById<TextView>(R.id.permStatus).text = sb
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(completeReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        refreshPermStatus()
        if (downloadId > 0) startPolling()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(completeReceiver) } catch (_: Exception) {}
        stopPolling()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001) {
            toast(if (Screenshot.init(this, resultCode, data)) "截屏授权成功 ✓" else "截屏授权未完成")
        }
    }

    private fun toast(s: String) {
        val t = Toast.makeText(this, s, Toast.LENGTH_LONG)
        t.setGravity(Gravity.CENTER, 0, 0)
        t.show()
    }
}
