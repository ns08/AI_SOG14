package com.soni08.morseai.ui

import android.content.pm.PackageManager
import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.soni08.morseai.service.AssistantForegroundService
import com.soni08.morseai.service.MicInputMode

class MainActivity : ComponentActivity() {

    private lateinit var editGeminiApiKey: EditText
    private lateinit var editGeminiModel: EditText
    private lateinit var editRelayUrl: EditText
    private lateinit var editDeviceId: EditText
    private lateinit var editToken: EditText
    private lateinit var checkAutoShare: CheckBox
    private lateinit var radioMicGroup: RadioGroup
    private lateinit var radioSog14Only: RadioButton
    private lateinit var radioControllerOnly: RadioButton
    private lateinit var textLog: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startAssistantService(requestAutoShare = checkAutoShare.isChecked)
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
                putExtra(AssistantForegroundService.EXTRA_GEMINI_API_KEY, editGeminiApiKey.text.toString().trim())
                putExtra(AssistantForegroundService.EXTRA_GEMINI_MODEL, editGeminiModel.text.toString().trim())
                putExtra(AssistantForegroundService.EXTRA_RELAY_URL, editRelayUrl.text.toString().trim())
                putExtra(AssistantForegroundService.EXTRA_DEVICE_ID, editDeviceId.text.toString().trim())
                putExtra(AssistantForegroundService.EXTRA_TOKEN, editToken.text.toString().trim())
                putExtra(AssistantForegroundService.EXTRA_MIC_MODE, currentMicMode().wireValue)
                putExtra(AssistantForegroundService.EXTRA_AUTO_SHARE_ON_START, checkAutoShare.isChecked)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val prefs = getSharedPreferences("morse_voice_ai_prefs", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }

        editGeminiApiKey = EditText(this).apply {
            hint = "Gemini API Key (AIza...)"
            setText(prefs.getString("gemini_api_key", ""))
        }
        editGeminiModel = EditText(this).apply {
            hint = "Gemini モデル名 (例: gemini-2.5-flash)"
            setText(prefs.getString("gemini_model", "gemini-2.5-flash"))
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

        checkAutoShare = CheckBox(this).apply {
            text = "アプリ起動・復帰時に画面共有を自動開始"
            isChecked = prefs.getBoolean("auto_share_on_start", true)
        }

        val savedMode = MicInputMode.fromWire(prefs.getString("mic_mode", MicInputMode.SOG14_ONLY.wireValue))
        radioSog14Only = RadioButton(this).apply {
            id = 10001
            text = "マイク: SOG14 本体マイクのみ"
            isChecked = savedMode == MicInputMode.SOG14_ONLY
        }
        radioControllerOnly = RadioButton(this).apply {
            id = 10002
            text = "マイク: 遠隔操作側マイクのみ"
            isChecked = savedMode == MicInputMode.CONTROLLER_ONLY
        }
        radioMicGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(radioSog14Only)
            addView(radioControllerOnly)
            setOnCheckedChangeListener { _, _ ->
                savePrefs()
                pushMicModeToService()
            }
        }

        val btnStartService = Button(this).apply {
            text = "設定保存・サービス開始"
            setOnClickListener {
                savePrefs()
                requestPermissionsAndStart(triggerShare = checkAutoShare.isChecked)
            }
        }

        val btnStartMirror = Button(this).apply {
            text = "画面共有を今すぐ開始"
            setOnClickListener {
                savePrefs()
                startAssistantService(requestAutoShare = false)
                requestScreenCapturePermission()
            }
        }

        val btnStopMirror = Button(this).apply {
            text = "画面共有を停止"
            setOnClickListener {
                startService(Intent(this@MainActivity, AssistantForegroundService::class.java).apply {
                    action = AssistantForegroundService.ACTION_STOP_SCREEN_SHARE
                })
            }
        }

        val btnAccessibility = Button(this).apply {
            text = "ユーザー補助設定を開く"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val btnStopAll = Button(this).apply {
            text = "サービス完全停止"
            setOnClickListener {
                startService(Intent(this@MainActivity, AssistantForegroundService::class.java).apply {
                    action = AssistantForegroundService.ACTION_STOP
                })
            }
        }

        textLog = TextView(this).apply {
            text = "ログ待機中...\n"
            textSize = 13f
        }
        val scrollView = ScrollView(this).apply {
            addView(textLog)
        }

        root.addView(editGeminiApiKey)
        root.addView(editGeminiModel)
        root.addView(editRelayUrl)
        root.addView(editDeviceId)
        root.addView(editToken)
        root.addView(checkAutoShare)
        root.addView(radioMicGroup)
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
        handleIncomingIntentAction(intent)
        requestPermissionsAndStart(triggerShare = checkAutoShare.isChecked)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntentAction(intent)
    }

    private fun handleIncomingIntentAction(intent: Intent?) {
        if (intent?.action == AssistantForegroundService.ACTION_REQUEST_SCREEN_SHARE_UI) {
            if (!AssistantForegroundService.isScreenSharingActive()) {
                requestScreenCapturePermission()
            }
        }
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

    private fun currentMicMode(): MicInputMode {
        return if (radioControllerOnly.isChecked) MicInputMode.CONTROLLER_ONLY else MicInputMode.SOG14_ONLY
    }

    private fun savePrefs() {
        getSharedPreferences("morse_voice_ai_prefs", Context.MODE_PRIVATE).edit()
            .putString("gemini_api_key", editGeminiApiKey.text.toString().trim())
            .putString("gemini_model", editGeminiModel.text.toString().trim())
            .putString("relay_url", editRelayUrl.text.toString().trim())
            .putString("device_id", editDeviceId.text.toString().trim())
            .putString("token", editToken.text.toString().trim())
            .putBoolean("auto_share_on_start", checkAutoShare.isChecked)
            .putString("mic_mode", currentMicMode().wireValue)
            .apply()
    }

    private fun pushMicModeToService() {
        val intent = Intent(this, AssistantForegroundService::class.java).apply {
            action = AssistantForegroundService.ACTION_SET_MIC_MODE
            putExtra(AssistantForegroundService.EXTRA_MIC_MODE, currentMicMode().wireValue)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestPermissionsAndStart(triggerShare: Boolean) {
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
            startAssistantService(requestAutoShare = triggerShare)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startAssistantService(requestAutoShare: Boolean) {
        val intent = Intent(this, AssistantForegroundService::class.java).apply {
            putExtra(AssistantForegroundService.EXTRA_GEMINI_API_KEY, editGeminiApiKey.text.toString().trim())
            putExtra(AssistantForegroundService.EXTRA_GEMINI_MODEL, editGeminiModel.text.toString().trim())
            putExtra(AssistantForegroundService.EXTRA_RELAY_URL, editRelayUrl.text.toString().trim())
            putExtra(AssistantForegroundService.EXTRA_DEVICE_ID, editDeviceId.text.toString().trim())
            putExtra(AssistantForegroundService.EXTRA_TOKEN, editToken.text.toString().trim())
            putExtra(AssistantForegroundService.EXTRA_MIC_MODE, currentMicMode().wireValue)
            putExtra(AssistantForegroundService.EXTRA_AUTO_SHARE_ON_START, requestAutoShare)
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
