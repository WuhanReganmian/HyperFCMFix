package org.hermes.hyperfcmfix

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.ViewGroup
import android.widget.*
import java.io.File

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

        val title = TextView(this).apply {
            text = "HyperOS FCM Fix"
            textSize = 24f
            setTextColor(Color.BLACK)
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))

        val subtitle = TextView(this).apply {
            text = "FCM 应用动态识别 · Greezer/Stopped bypass · AOSP 电池优化校正"
            textSize = 13f
            setPadding(0, 8, 0, 20)
        }
        root.addView(subtitle, LinearLayout.LayoutParams(-1, -2))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val refresh = Button(this).apply { text = "刷新" }
        val clear = Button(this).apply { text = "清空日志" }
        buttons.addView(refresh, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(clear, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(buttons)

        refresh.setOnClickListener { refresh() }
        clear.setOnClickListener {
            File(filesDir, "hyperfcmfix.log").delete()
            refresh()
        }

        appsView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 20, 0, 20)
        }
        root.addView(appsView, LinearLayout.LayoutParams(-1, -2))

        val logTitle = TextView(this).apply {
            text = "最近日志"
            textSize = 18f
            setPadding(0, 8, 0, 8)
        }
        root.addView(logTitle, LinearLayout.LayoutParams(-1, -2))

        logsView = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this)
        scroll.addView(logsView, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
    }

    private fun refresh() {
        val apps = File(filesDir, "fcm_apps.txt")
            .takeIf { it.exists() }
            ?.readLines()
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        appsView.text = buildString {
            append("当前识别到的 FCM 应用：${apps.size}\n")
            append("AOSP 电池策略：周期校正为「优化」\n")
            append("校正周期：开机后 5 分钟，之后每 12 小时\n")
            append("HyperOS 自启动：不由本模块强制开启\n\n")
            if (apps.isEmpty()) {
                append("等待 system_server 执行首次扫描。")
            } else {
                val pm = packageManager
                apps.forEach { pkg ->
                    val label = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    } catch (_: Throwable) { pkg }
                    append("✓ $label\n  $pkg\n")
                }
            }
        }

        val logFile = File(filesDir, "hyperfcmfix.log")
        logsView.text = if (logFile.exists()) {
            logFile.readLines().takeLast(300).joinToString("\n")
        } else {
            "暂无日志。首次 system_server 修复任务运行后会出现在这里。"
        }
    }
}
