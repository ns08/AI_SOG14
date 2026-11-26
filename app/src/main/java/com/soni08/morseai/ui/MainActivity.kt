package com.soni08.morseai.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.soni08.morseai.service.AssistantForegroundService

class MainActivity : Activity() {

    private lateinit var logView: TextView

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(AssistantForegroundService.EXTRA_LOG_MSG) ?: return
            logView.append("\n$msg")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val btnStart = Button(this).apply {
            text = "音声AIサービス開始（スリープ対応）"
            setOnClickListener {
                requestAllRuntimePermissions()
                requestIgnoreBatteryOptimizations()
                startAssistantService()
            }
        }

        val btnNotifyPerm = Button(this).apply {
            text = "通知アクセス設定を開く（LINE/Teams読取）"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        val btnAccessPerm = Button(this).apply {
            text = "ユーザー補助設定を開く（全アプリ/通話/返信操作）"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        logView = TextView(this).apply {
            text = "待機ログ:"
        }
        val scroll = ScrollView(this).apply {
            addView(logView)
        }

        root.addView(btnStart)
        root.addView(btnNotifyPerm)
        root.addView(btnAccessPerm)
        root.addView(scroll)
        setContentView(root)

        requestAllRuntimePermissions()
        requestIgnoreBatteryOptimizations()
        startAssistantService()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(AssistantForegroundService.BROADCAST_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(logReceiver)
        } catch (_: Exception) {
        }
    }

    private fun startAssistantService() {
        val intent = Intent(this, AssistantForegroundService::class.java).apply {
            putExtra(AssistantForegroundService.EXTRA_LOCAL_AI_URL, "http://127.0.0.1:8080")
            putExtra(AssistantForegroundService.EXTRA_LOCAL_AI_MODEL, "local-model")
            putExtra(AssistantForegroundService.EXTRA_RELAY_URL, "https://soni08.website/remote/relay.php")
            putExtra(AssistantForegroundService.EXTRA_DEVICE_ID, "sog14-main")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun requestAllRuntimePermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            "com.termux.permission.RUN_COMMAND"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            perms.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 2001)
        }
    }
}
