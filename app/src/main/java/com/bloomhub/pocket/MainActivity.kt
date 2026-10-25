package com.bloomhub.pocket

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.bloomhub.pocket.llm.CloudLlm
import com.bloomhub.pocket.llm.LocalLlm
import com.bloomhub.pocket.notify.QqListener
import com.bloomhub.pocket.screen.ScreenAccess
import com.bloomhub.pocket.store.ChatStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var chatStore: ChatStore
    private var modelFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        chatStore = ChatStore(this)

        findViewById<Button>(R.id.btnA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnNotif).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnShot).setOnClickListener {
            com.bloomhub.pocket.screen.Screenshot.requestPermission(this)
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            @Suppress("BatteryLife")
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        findViewById<Button>(R.id.btnWizard).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        findViewById<Button>(R.id.btnImportModel).setOnClickListener { importModel() }
        findViewById<Button>(R.id.btnCloud).setOnClickListener { useCloud() }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startService() }
        findViewById<Button>(R.id.btnRun).setOnClickListener { runTask() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // 记住上次导入的模型
        val prev = File(filesDir, "models/model.gguf")
        if (prev.exists()) modelFile = prev

        // 首次启动：未完成新手向导就自动进入
        if (!getSharedPreferences("pocket", MODE_PRIVATE).getBoolean("setupDone", false)) {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        refreshStatus()
        handler.post(object : Runnable {
            override fun run() {
                refreshStatus()
                handler.postDelayed(this, 2000)
            }
        })
    }

    private fun refreshStatus() {
        val tv = findViewById<TextView>(R.id.statusText)
        val a11y = if (ScreenAccess.available) "✓" else "✗"
        val llm = if (AgentService.hasLlm()) "✓" else "✗"
        tv.text = "无障碍:$a11y  模型:$llm  服务:${if (AgentService.running) "运行中" else "未启动"}\n${AgentService.statusText}"
        val log = findViewById<TextView>(R.id.logText)
        log.text = chatStore.tail(120).joinToString("\n").ifBlank { "（尚无日志）" }
    }

    /** SAF 选 .gguf → 复制到应用目录（llama.cpp 需要真实路径） */
    private fun importModel() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, 1001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001) {
            val ok = com.bloomhub.pocket.screen.Screenshot.init(this, resultCode, data)
            Toast.makeText(this, if (ok) "截屏授权成功 ✓" else "截屏授权失败", Toast.LENGTH_SHORT).show()
            return
        }
        if (requestCode == 1001 && resultCode == Activity.RESULT_OK) {
            val uri: Uri = data?.data ?: return
            Toast.makeText(this, "开始复制模型到应用目录（1~3GB，请稍候）", Toast.LENGTH_LONG).show()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val dir = File(filesDir, "models").apply { mkdirs() }
                    val out = File(dir, "model.gguf")
                    contentResolver.openInputStream(uri)!!.use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                    withContext(Dispatchers.Main) { toastAndLoad(out) }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "复制失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun toastAndLoad(f: File) {
        modelFile = f
        try {
            AgentService.setLlm(LocalLlm(f.absolutePath, nCtx = 4096, nThreads = 6))
            Toast.makeText(this, "本地模型已加载 ✓", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "加载失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun useCloud() {
        val base = findViewById<EditText>(R.id.edCloudBase).text.toString().trim()
        val key = findViewById<EditText>(R.id.edCloudKey).text.toString().trim()
        if (base.isBlank() || key.isBlank()) {
            Toast.makeText(this, "云端模式需要 Base 和 Key 都填写", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences("pocket", MODE_PRIVATE).edit()
            .putString("cloudBase", base).putString("cloudKey", key).apply()
        AgentService.setLlm(CloudLlm(base, key))
        Toast.makeText(this, "已切换云端模式（${base}）", Toast.LENGTH_SHORT).show()
    }

    private fun startService() {
        if (!ScreenAccess.available) {
            Toast.makeText(this, "先开启无障碍权限（千秋读屏）", Toast.LENGTH_LONG).show()
            return
        }
        if (!AgentService.hasLlm()) {
            Toast.makeText(this, "先导入模型或配置云端", Toast.LENGTH_LONG).show()
            return
        }
        startForegroundService(Intent(this, AgentService::class.java))
        Toast.makeText(this, "千秋已启动，在通知栏常驻", Toast.LENGTH_SHORT).show()
    }

    private fun runTask() {
        val goal = findViewById<EditText>(R.id.edTask).text.toString().trim()
        if (goal.isBlank()) return
        if (!AgentService.running) startService()
        AgentService.enqueue(goal)
        findViewById<EditText>(R.id.edTask).setText("")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
