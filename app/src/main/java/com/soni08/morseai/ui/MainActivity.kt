package com.soni08.morseai.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.soni08.morseai.service.AssistantForegroundService

class MainActivity : ComponentActivity() {

    private lateinit var editLocalAiUrl: EditText
    private lateinit var editLocalAiModel: EditText
    private lateinit var editRelayUrl: EditText
    private lateinit var editDeviceId: EditText
    private lateinit var editToken: EditText
    private lateinit var textLog: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startAssistantService()
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            appendLog("がめんきゃぷちゃの きょかを しゅとくしました")
            val intent = Intent(this, AssistantForegroundService::class.java).apply {
                action = AssistantForegroundService.ACTION_START_SCREEN_SHARE
                putExtra(AssistantForegroundService.EXTRA_PROJECTION_RESULT_CODE, result.resultCode)
                putExtra(AssistantForegroundService.EXTRA_PROJECTION_DATA, result.data)
                putExtra(AssistantForegroundService.EXTRA_LOCAL_AI_URL, editLocalAiUrl.text.toString().trim())
                putExtra(AssistantForegroundService.EXTRA_LOCAL_AI_MODEL, editLocalAiModel.text.toString().trim())
                putExtra(AssistantForegroundService.EXTRA_RELAY_URL, editRelayUrl.text.toString().trim())
                putExtra(AssistantForegroundService.EXTRA_DEVICE_ID, editDeviceId.text.toString().trim())
                putExtra(AssistantForegroundService.EXTRA_TOKEN, editToken.text.toString().trim())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            appendLog("がめんきゃぷちゃの きょかが きゃんせるされました")
        }
    }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(AssistantForegroundService.EXTRA_LOG_MSG) ?: return
            appendLog(msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("morse_voice_ai_prefs", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        editLocalAiUrl = EditText(this).apply {
            hint = "ローカルAI URL"
            setText(prefs.getString("local_ai_url", "http://127.0.0.1:8080"))
        }
        editLocalAiModel = EditText(this).apply {
            hint = "ローカルAI モデル名"
            setText(prefs.getString("local_ai_model", "local-model"))
        }
        editRelayUrl = EditText(this).apply {
            hint = "リレーサーバー URL"
            setText(prefs.getString("relay_url", "https://soni08.website/remote/relay.php"))
        }
        editDeviceId = EditText(this).apply {
            hint = "デバイスID"
            setText(prefs.getString("device_id", "sog14-main"))
        }
        editToken = EditText(this).apply {
            hint = "認証トークン"
            setText(prefs.getString("token", "change-me-token"))
        }

        val btnStartService = Button(this).apply {
            text = "音声AI・リレー開始（設定保存）"
            setOnClickListener {
                savePrefs()
                requestPermissionsAndStart()
            }
        }

        val btnStartMirror = Button(this).apply {
            text = "Web画面ミラーリング開始"
            setOnClickListener {
                savePrefs()
                startAssistantService()
                requestScreenCapturePermission()
            }
        }

        val btnStopMirror = Button(this).apply {
            text = "Web画面ミラーリング停止"
            setOnClickListener {
                val stopIntent = Intent(this@MainActivity, AssistantForegroundService::class.java).apply {
                    action = AssistantForegroundService.ACTION_STOP_SCREEN_SHARE
                }
                startService(stopIntent)
            }
        }

        val btnAccessibility = Button(this).apply {
            text = "ユーザー補助設定を開く（遠隔操作用）"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val btnStopAll = Button(this).apply {
            text = "サービス完全停止"
            setOnClickListener {
                val stopIntent = Intent(this@MainActivity, AssistantForegroundService::class.java).apply {
                    action = AssistantForegroundService.ACTION_STOP
                }
                startService(stopIntent)
            }
        }

        textLog = TextView(this).apply {
            text = "ログ待機中...\n"
            textSize = 13f
        }
        val scrollView = ScrollView(this).apply {
            addView(textLog)
        }

        root.addView(editLocalAiUrl)
        root.addView(editLocalAiModel)
        root.addView(editRelayUrl)
        root.addView(editDeviceId)
        root.addView(editToken)
        root.addView(btnStartService)
        root.addView(btnStartMirror)
        root.addView(btnStopMirror)
        root.addView(btnAccessibility)
        root.addView(btnStopAll)
        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        setContentView(root)
        requestPermissionsAndStart()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(AssistantForegroundService.BROADCAST_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onStop() {
        try {
            unregisterReceiver(logReceiver)
        } catch (_: Exception) {
        }
        super.onStop()
    }

    private fun savePrefs() {
        getSharedPreferences("morse_voice_ai_prefs", Context.MODE_PRIVATE).edit()
            .putString("local_ai_url", editLocalAiUrl.text.toString().trim())
            .putString("local_ai_model", editLocalAiModel.text.toString().trim())
            .putString("relay_url", editRelayUrl.text.toString().trim())
            .putString("device_id", editDeviceId.text.toString().trim())
            .putString("token", editToken.text.toString().trim())
            .apply()
    }

    private fun requestPermissionsAndStart() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
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
        if (missing.isEmpty()) {
            startAssistantService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startAssistantService() {
        val intent = Intent(this, AssistantForegroundService::class.java).apply {
            putExtra(AssistantForegroundService.EXTRA_LOCAL_AI_URL, editLocalAiUrl.text.toString().trim())
            putExtra(AssistantForegroundService.EXTRA_LOCAL_AI_MODEL, editLocalAiModel.text.toString().trim())
            putExtra(AssistantForegroundService.EXTRA_RELAY_URL, editRelayUrl.text.toString().trim())
            putExtra(AssistantForegroundService.EXTRA_DEVICE_ID, editDeviceId.text.toString().trim())
            putExtra(AssistantForegroundService.EXTRA_TOKEN, editToken.text.toString().trim())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestScreenCapturePermission() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager ?: return
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            textLog.append("• $msg\n")
        }
    }
}
