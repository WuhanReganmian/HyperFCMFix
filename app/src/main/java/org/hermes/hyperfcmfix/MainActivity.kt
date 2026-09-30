package org.hermes.hyperfcmfix

import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private lateinit var appsView: TextView
    private lateinit var logsView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "HyperOS FCM Fix"
        buildUi()
        refresh()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        root.addView(TextView(this).apply {
            text = "HyperOS FCM Fix"
            textSize = 24f
            setTextColor(Color.BLACK)
        }, LinearLayout.LayoutParams(-1, -2))

        root.addView(TextView(this).apply {
            text = "FCM 应用动态识别 · Greezer/Stopped bypass · AOSP 电池优化校正"
            textSize = 13f
            setPadding(0, 8, 0, 20)
        }, LinearLayout.LayoutParams(-1, -2))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val refresh = Button(this).apply { text = "刷新" }
        val retry = Button(this).apply { text = "重试" }
        val clear = Button(this).apply { text = "清空日志" }
        buttons.addView(refresh, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(retry, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(clear, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(buttons)
        refresh.setOnClickListener { refresh() }
        retry.setOnClickListener {
            try {
                val intent = android.content.Intent("org.hermes.hyperfcmfix.action.RETRY_SCAN")
                    .setPackage("android")
                sendBroadcast(intent)
                Toast.makeText(this, "已触发立即扫描和电池策略校正", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Toast.makeText(this, "触发失败：${t.message}", Toast.LENGTH_LONG).show()
            }
        }
        clear.setOnClickListener {
            getPreferences(MODE_PRIVATE).edit()
                .putLong("log_clear_at", System.currentTimeMillis())
                .apply()
            refresh()
        }

        appsView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 20, 0, 20)
        }
        root.addView(appsView, LinearLayout.LayoutParams(-1, -2))

        root.addView(TextView(this).apply {
            text = "最近日志"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 8, 0, 8)
        }, LinearLayout.LayoutParams(-1, -2))

        logsView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this)
        scroll.addView(logsView, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun refresh() {
        val apps = Settings.Global.getString(contentResolver, "hyperfcmfix_fcm_apps")
            .orEmpty()
            .split('\n')
            .filter { it.isNotBlank() }

        appsView.text = buildString {
            append("当前识别到的 FCM 应用：${apps.size}\n")
            append("筛选条件：FCM Receiver + 已授予通知权限\n")
            append("AOSP 电池策略：Optimized（不在 Doze whitelist）\n")
            append("校正周期：开机后 5 分钟，之后每 12 小时\n")
            append("HyperOS 自启动：不由本模块强制开启\n\n")
            if (apps.isEmpty()) {
                append("暂无符合条件的 FCM 应用。")
            } else {
                apps.forEach { pkg ->
                    val label = try {
                        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                    } catch (_: Throwable) { pkg }
                    append("✓ $label\n  $pkg\n\n")
                }
            }
        }

        val clearAt = getPreferences(MODE_PRIVATE).getLong("log_clear_at", 0L)
        val logs = Settings.Global.getString(contentResolver, "hyperfcmfix_logs")
            .orEmpty()
            .split('\n')
            .filter { it.isNotBlank() }
            .filter { line ->
                if (clearAt == 0L) true else parseTimestamp(line) > clearAt
            }
        logsView.text = if (logs.isEmpty()) {
            "暂无日志。"
        } else {
            logs.takeLast(300).joinToString("\n")
        }
    }

    private fun parseTimestamp(line: String): Long {
        return try {
            val value = line.take(23)
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .parse(value)?.time ?: 0L
        } catch (_: Throwable) { 0L }
    }
}
