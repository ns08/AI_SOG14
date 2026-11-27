package com.soni08.morseai.service

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.soni08.morseai.contacts.ContactPhoneLookup
import com.soni08.morseai.contacts.MatchedContact
import com.soni08.morseai.media.LocalMusicPlayer
import com.soni08.morseai.shopping.AmazonOrderHelper
import com.soni08.morseai.text.HiraganaNormalizer
import com.soni08.morseai.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

data class PendingContactSelection(
    val candidates: List<MatchedContact>
)

enum class MicInputMode(val wireValue: String) {
    SOG14_ONLY("sog14_only"),
    CONTROLLER_ONLY("controller_only");

    companion object {
        fun fromWire(value: String?): MicInputMode {
            return entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) } ?: SOG14_ONLY
        }
    }
}

class AssistantForegroundService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "morse_voice_ai_channel"
        const val NOTIF_ID = 1001

        const val ACTION_STOP = "com.soni08.morseai.STOP"
        const val ACTION_INCOMING_CALL = "com.soni08.morseai.INCOMING_CALL"
        const val ACTION_CALL_ENDED = "com.soni08.morseai.CALL_ENDED"
        const val ACTION_START_SCREEN_SHARE = "com.soni08.morseai.START_SCREEN_SHARE"
        const val ACTION_STOP_SCREEN_SHARE = "com.soni08.morseai.STOP_SCREEN_SHARE"
        const val ACTION_REQUEST_SCREEN_SHARE_UI = "com.soni08.morseai.REQUEST_SCREEN_SHARE_UI"
        const val ACTION_SET_MIC_MODE = "com.soni08.morseai.SET_MIC_MODE"

        const val EXTRA_INCOMING_NUMBER = "incoming_number"
        const val EXTRA_GEMINI_API_KEY = "gemini_api_key"
        const val EXTRA_GEMINI_MODEL = "gemini_model"
        const val EXTRA_RELAY_URL = "relay_url"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_MIC_MODE = "mic_mode"
        const val EXTRA_AUTO_SHARE_ON_START = "auto_share_on_start"
        const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"

        const val BROADCAST_LOG = "com.soni08.morseai.LOG"
        const val EXTRA_LOG_MSG = "log_msg"

        private const val PKG_LINE = "jp.naver.line.android"
        private const val PKG_TEAMS = "com.microsoft.teams"

        @Volatile
        private var instance: AssistantForegroundService? = null

        fun notifyIncomingCallEvent(appName: String, detail: String) {
            instance?.onIncomingAppCallDetected(appName, detail)
        }

        fun isScreenSharingActive(): Boolean {
            return instance?.mediaProjection != null
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val screenUploadExecutor = Executors.newSingleThreadExecutor()
    private val mediaIoExecutor = Executors.newSingleThreadExecutor()

    private var wakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null

    private var geminiApiKey: String = ""
    private var geminiModel: String = "gemini-2.5-flash"
    private var relayUrl: String = "https://soni08.website/remote/relay.php"
    private var deviceId: String = "sog14-main"
    private var token: String = "change-me-token"

    @Volatile
    private var micInputMode: MicInputMode = MicInputMode.SOG14_ONLY

    @Volatile
    private var autoShareOnStart: Boolean = true

    @Volatile
    private var aiChatEnabled: Boolean = true

    @Volatile
    private var mediaProjectionAuthorized: Boolean = false

    @Volatile
    private var phoneCallActive: Boolean = false

    @Volatile
    private var lastAutoSharePromptAt: Long = 0L

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    @Volatile
    private var isUploadingFrame: Boolean = false

    @Volatile
    private var lastFrameSentAt: Long = 0L

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var speakerOutTrack: AudioTrack? = null

    @Volatile
    private var isSpeaking = false
    @Volatile
    private var isStopping = false
    @Volatile
    private var relayPollingStarted = false
    @Volatile
    private var pendingContactSelection: PendingContactSelection? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        acquirePersistentWakeLock()
        createNotificationChannel()
        initSpeakerOutTrack()
        tts = TextToSpeech(this, this)
        AppNotificationListenerService.onNewNotificationCallback = { item ->
            if (AppNotificationListenerService.isRecordingSinceTrigger && !isSpeaking) {
                sendLog("つうちきろく: ${item.appName} / ${item.title} ${item.text}")
            }
        }
    }

    private fun acquirePersistentWakeLock() {
        try {
            if (wakeLock?.isHeld != true) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "MorseVoiceAI::SleepKeepAliveWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun wakeDeviceFromSleep() {
        acquirePersistentWakeLock()
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "MorseVoiceAI::WakeScreenForMirror"
            ).apply {
                setReferenceCounted(false)
                acquire(10_000L)
            }
            screenWakeLock = wl
        } catch (_: Exception) {
        }
    }

    private fun initSpeakerOutTrack() {
        try {
            val sampleRate = 16000
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = minBuf.coerceAtLeast(4096)
            speakerOutTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    android.media.AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                    AudioTrack.MODE_STREAM
                )
            }
            speakerOutTrack?.play()
        } catch (_: Exception) {
        }
    }

    private fun computeForegroundServiceType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return 0
        }
        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val hasMicPerm = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasMicPerm && micInputMode == MicInputMode.SOG14_ONLY) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hasPhonePerm = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
            if (phoneCallActive && hasPhonePerm) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            }
            if (mediaProjectionAuthorized && mediaProjection != null) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
        } else {
            if (mediaProjectionAuthorized && mediaProjection != null) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
        }
        return type
    }

    private fun startForegroundSafely(content: String) {
        val notification = buildNotification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val desiredType = computeForegroundServiceType()
            try {
                if (desiredType != 0) {
                    startForeground(NOTIF_ID, notification, desiredType)
                } else {
                    startForeground(NOTIF_ID, notification)
                }
                return
            } catch (_: SecurityException) {
                mediaProjectionAuthorized = false
                phoneCallActive = false
                val fallbackType = computeForegroundServiceType()
                try {
                    if (fallbackType != 0) {
                        startForeground(NOTIF_ID, notification, fallbackType)
                    } else {
                        startForeground(NOTIF_ID, notification)
                    }
                    return
                } catch (_: SecurityException) {
                    try {
                        startForeground(NOTIF_ID, notification)
                    } catch (_: Exception) {
                    }
                    return
                }
            }
        }
        try {
            startForeground(NOTIF_ID, notification)
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            phoneCallActive = false
            mediaProjectionAuthorized = false
            stopScreenCapture()
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_CALL_ENDED) {
            phoneCallActive = false
            startForegroundSafely("まいくもーど: ${micInputMode.wireValue}")
            return START_STICKY
        }

        if (intent?.action == ACTION_STOP_SCREEN_SHARE) {
            stopScreenCapture()
            mediaProjectionAuthorized = false
            startForegroundSafely("まいくもーど: ${micInputMode.wireValue}")
            sendLog("がめんみらーりんぐを ていししました")
            return START_STICKY
        }

        if (intent?.action == ACTION_INCOMING_CALL) {
            phoneCallActive = true
        }

        acquirePersistentWakeLock()

        intent?.getStringExtra(EXTRA_GEMINI_API_KEY)?.takeIf { it.isNotBlank() }?.let { geminiApiKey = it }
        intent?.getStringExtra(EXTRA_GEMINI_MODEL)?.takeIf { it.isNotBlank() }?.let { geminiModel = it }
        intent?.getStringExtra(EXTRA_RELAY_URL)?.takeIf { it.isNotBlank() }?.let { relayUrl = it }
        intent?.getStringExtra(EXTRA_DEVICE_ID)?.takeIf { it.isNotBlank() }?.let { deviceId = it }
        intent?.getStringExtra(EXTRA_TOKEN)?.takeIf { it.isNotBlank() }?.let { token = it }
        if (intent?.hasExtra(EXTRA_AUTO_SHARE_ON_START) == true) {
            autoShareOnStart = intent.getBooleanExtra(EXTRA_AUTO_SHARE_ON_START, true)
        }
        intent?.getStringExtra(EXTRA_MIC_MODE)?.takeIf { it.isNotBlank() }?.let {
            applyMicMode(MicInputMode.fromWire(it), announce = false)
        }

        if (intent?.action == ACTION_SET_MIC_MODE) {
            val mode = MicInputMode.fromWire(intent.getStringExtra(EXTRA_MIC_MODE))
            applyMicMode(mode, announce = true)
            startForegroundSafely("まいく: ${micInputMode.wireValue}")
            return START_STICKY
        }

        if (intent?.action == ACTION_START_SCREEN_SHARE) {
            val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, Activity.RESULT_CANCELED)
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
            }
            startForegroundSafely("がめんきょうゆうじゅんびちゅう・まいく: ${micInputMode.wireValue}")
            if (resultCode == Activity.RESULT_OK && data != null) {
                startScreenCapture(resultCode, data)
            } else {
                mediaProjectionAuthorized = false
                startForegroundSafely("まいく: ${micInputMode.wireValue}")
                sendLog("がめんきゃぷちゃの きょかが えられませんでした")
            }
        } else {
            startForegroundSafely("まいく: ${micInputMode.wireValue}")
            if (autoShareOnStart && mediaProjection == null) {
                triggerAutoScreenShareIfNeeded()
            }
        }

        autoStartLocalAiAtLaunch()
        applyMicMode(micInputMode, announce = false)

        if (!relayPollingStarted) {
            relayPollingStarted = true
            startRelayPollLoop()
        }

        if (intent?.action == ACTION_INCOMING_CALL) {
            val number = intent.getStringExtra(EXTRA_INCOMING_NUMBER).orEmpty()
            handleIncomingPhoneCall(number)
        }

        return START_STICKY
    }

    private fun applyMicMode(mode: MicInputMode, announce: Boolean) {
        micInputMode = mode
        mainHandler.post {
            when (mode) {
                MicInputMode.SOG14_ONLY -> {
                    startSpeechRecognitionLoop()
                    sendLog("まいくもーど: SOG14ほんたいのみ")
                    if (announce) speakHiragana("SOG14の ほんたいまいくのみを しようします")
                }
                MicInputMode.CONTROLLER_ONLY -> {
                    stopSpeechRecognitionLoop()
                    sendLog("まいくもーど: えんかくそうさがわまいくのみ")
                    if (announce) speakHiragana("えんかくそうさがわの まいくのみを しようします")
                }
            }
        }
    }

    private fun triggerAutoScreenShareIfNeeded() {
        if (mediaProjection != null) return
        val now = System.currentTimeMillis()
        if (now - lastAutoSharePromptAt < 6000L) return
        lastAutoSharePromptAt = now

        wakeDeviceFromSleep()
        sendLog("がめんきょうゆうを じどうかいしします")
        mainHandler.post {
            try {
                val uiIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    action = ACTION_REQUEST_SCREEN_SHARE_UI
                }
                startActivity(uiIntent)
            } catch (_: Exception) {
            }
        }
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        stopScreenCapture()
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            val projection = mpm?.getMediaProjection(resultCode, data)
            if (projection == null) {
                mediaProjectionAuthorized = false
                startForegroundSafely("まいく: ${micInputMode.wireValue}")
                sendLog("MediaProjection の しゅとくにしっぱいしました")
                return
            }

            mediaProjection = projection
            mediaProjectionAuthorized = true
            startForegroundSafely("がめんきょうゆう・まいく: ${micInputMode.wireValue}")

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post {
                        stopScreenCapture()
                        mediaProjectionAuthorized = false
                        if (!isStopping) {
                            startForegroundSafely("まいく: ${micInputMode.wireValue}")
                            sendLog("がめんみらーりんぐが しゅうりょうしました")
                        }
                    }
                }
            }, mainHandler)

            val metrics = resources.displayMetrics
            val rawW = metrics.widthPixels.coerceAtLeast(360)
            val rawH = metrics.heightPixels.coerceAtLeast(640)
            val scale = if (rawW > 540) 540f / rawW.toFloat() else 1f
            val captureW = ((rawW * scale).toInt() / 2) * 2
            val captureH = ((rawH * scale).toInt() / 2) * 2
            val density = metrics.densityDpi

            val reader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ r ->
                val image = try {
                    r.acquireLatestImage()
                } catch (_: Exception) {
                    null
                } ?: return@setOnImageAvailableListener

                val now = System.currentTimeMillis()
                if (isUploadingFrame || now - lastFrameSentAt < 450L) {
                    image.close()
                    return@setOnImageAvailableListener
                }

                try {
                    val planes = image.planes
                    if (planes.isEmpty()) {
                        image.close()
                        return@setOnImageAvailableListener
                    }
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * captureW

                    val paddedBitmap = Bitmap.createBitmap(
                        captureW + rowPadding / pixelStride,
                        captureH,
                        Bitmap.Config.ARGB_8888
                    )
                    paddedBitmap.copyPixelsFromBuffer(buffer)
                    image.close()

                    val croppedBitmap = if (rowPadding == 0) {
                        paddedBitmap
                    } else {
                        Bitmap.createBitmap(paddedBitmap, 0, 0, captureW, captureH).also {
                            paddedBitmap.recycle()
                        }
                    }

                    isUploadingFrame = true
                    lastFrameSentAt = now
                    screenUploadExecutor.execute {
                        try {
                            val baos = ByteArrayOutputStream()
                            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 55, baos)
                            val jpegBytes = baos.toByteArray()
                            val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                            uploadScreenFrame(base64Image, captureW, captureH)
                        } catch (_: Exception) {
                        } finally {
                            croppedBitmap.recycle()
                            isUploadingFrame = false
                        }
                    }
                } catch (_: Exception) {
                    try {
                        image.close()
                    } catch (_: Exception) {
                    }
                }
            }, mainHandler)

            val display = projection.createVirtualDisplay(
                "MorseVoiceAIScreenMirror",
                captureW,
                captureH,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                mainHandler
            )

            imageReader = reader
            virtualDisplay = display
            sendLog("がめんみらーりんぐを かいししました (${captureW}x${captureH})")
        } catch (e: SecurityException) {
            mediaProjectionAuthorized = false
            stopScreenCapture()
            startForegroundSafely("まいく: ${micInputMode.wireValue}")
            sendLog("がめんきゃぷちゃけんげんえらー: ${e.message}")
        } catch (e: Exception) {
            mediaProjectionAuthorized = false
            stopScreenCapture()
            startForegroundSafely("まいく: ${micInputMode.wireValue}")
            sendLog("がめんみらーりんぐかいしえらー: ${e.message}")
        }
    }

    private fun uploadScreenFrame(base64Jpeg: String, width: Int, height: Int) {
        if (relayUrl.isBlank() || isStopping) return
        val conn = (URL(relayUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4000
            readTimeout = 6000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            val payload = JSONObject().apply {
                put("role", "device_screen")
                put("action", "upload_frame")
                put("device_id", deviceId)
                put("token", token)
                put("width", width)
                put("height", height)
                put("mime", "image/jpeg")
                put("frame_base64", base64Jpeg)
                put("timestamp", System.currentTimeMillis())
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(payload.toString())
            }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun stopScreenCapture() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
        mediaProjectionAuthorized = false
        isUploadingFrame = false
    }

    private fun handleIncomingPhoneCall(rawNumber: String) {
        phoneCallActive = true
        startForegroundSafely("ちゃくしんたいおう・じぇみにれんけいちゅう")
        aiChatEnabled = true

        val cleanNumber = rawNumber.filter { it.isDigit() || it == '+' }
        val hasContactsPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        val matched = if (hasContactsPerm && cleanNumber.isNotEmpty()) {
            ContactPhoneLookup.lookupCallerByNumber(this, cleanNumber)
        } else {
            null
        }

        val spokenDigits = formatDigitsForSpeech(cleanNumber)
        val announcement = when {
            matched != null -> {
                val spokenName = HiraganaNormalizer.toHiragana(matched.name)
                "ちゃくしんが あります。はっしんもとは $spokenName さん、でんわばんごうは $spokenDigits です。"
            }
            spokenDigits.isNotEmpty() -> {
                "ちゃくしんが あります。でんわばんごうは $spokenDigits、れんらくさき みとうろくの ばんごうです。"
            }
            else -> {
                "ひつうち または ばんごうふめいの ちゃくしんが あります。"
            }
        }
        sendLog("ちゃくしんけんち: $announcement")
        mainHandler.postDelayed({ speakHiragana(announcement) }, 600L)
    }

    private fun onIncomingAppCallDetected(appName: String, detail: String) {
        if (isStopping) return
        phoneCallActive = true
        startForegroundSafely("ちゃくしんたいおう・じぇみにれんけいちゅう")
        aiChatEnabled = true
        val msg = "$appName から ちゃくしんが あります。$detail。"
        sendLog(msg)
        speakHiragana(msg)
    }

    private fun autoStartLocalAiAtLaunch() {
        if (geminiApiKey.isNotBlank()) {
            sendLog("Gemini せつぞくせってい じゅんびかんりょう (${geminiModel})")
        } else {
            sendLog("Gemini APIきーが みせっていです")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.JAPANESE
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                    mainHandler.post { speechRecognizer?.stopListening() }
                }

                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    if (!isStopping && micInputMode == MicInputMode.SOG14_ONLY) {
                        mainHandler.postDelayed({ restartSpeechRecognition() }, 350L)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    if (!isStopping && micInputMode == MicInputMode.SOG14_ONLY) {
                        mainHandler.postDelayed({ restartSpeechRecognition() }, 350L)
                    }
                }
            })
            ttsReady = true
        }
    }

    private fun stopSpeechRecognitionLoop() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
    }

    private fun startSpeechRecognitionLoop() {
        if (micInputMode != MicInputMode.SOG14_ONLY) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendLog("おんせいにんしきがつかえません")
            return
        }
        mainHandler.post {
            if (micInputMode != MicInputMode.SOG14_ONLY) return@post
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}

                        override fun onError(error: Int) {
                            if (!isStopping && !isSpeaking && micInputMode == MicInputMode.SOG14_ONLY) {
                                mainHandler.postDelayed({ restartSpeechRecognition() }, 700L)
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val rawRecognized = matches?.firstOrNull()?.trim()
                            if (!rawRecognized.isNullOrEmpty() && !isSpeaking && micInputMode == MicInputMode.SOG14_ONLY) {
                                val hiraText = HiraganaNormalizer.toHiragana(rawRecognized)
                                sendLog("おんせいにんしき(SOG14): $hiraText")
                                handleVoiceCommand(hiraText, rawRecognized)
                            }
                            if (!isStopping && !isSpeaking && micInputMode == MicInputMode.SOG14_ONLY) {
                                mainHandler.postDelayed({ restartSpeechRecognition() }, 350L)
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
            }
            restartSpeechRecognition()
        }
    }

    private fun handleVoiceCommand(hiraText: String, rawText: String) {
        val pendingContacts = pendingContactSelection
        if (pendingContacts != null) {
            if (HiraganaNormalizer.isCancellation(hiraText)) {
                pendingContactSelection = null
                speakHiragana("でんわを やめました")
                return
            }
            val chosenIndex = extractSelectionIndex(hiraText, pendingContacts.candidates.size)
            if (chosenIndex != null) {
                val chosen = pendingContacts.candidates[chosenIndex]
                pendingContactSelection = null
                placePhoneCall(chosen.phoneNumber, chosen.name)
                return
            }
            if (HiraganaNormalizer.isApproval(hiraText) && pendingContacts.candidates.isNotEmpty()) {
                val first = pendingContacts.candidates.first()
                pendingContactSelection = null
                placePhoneCall(first.phoneNumber, first.name)
                return
            }
            speakHiragana("なんばんめに かけますか。いちばんめ などで いってください")
            return
        }

        if (hiraText.contains("じぇみにまいく") || hiraText.contains("ほんたいまいくのみ")) {
            applyMicMode(MicInputMode.SOG14_ONLY, announce = true)
            return
        }
        if (hiraText.contains("えんかくまいくのみ") || hiraText.contains("そうさがわまいく")) {
            applyMicMode(MicInputMode.CONTROLLER_ONLY, announce = true)
            return
        }

        if (hiraText.contains("いままでのつうちないようをおしえて") || hiraText.contains("つうちをよみあげて")) {
            if (!AppNotificationListenerService.isRecordingSinceTrigger) {
                AppNotificationListenerService.startRecordingFromNow()
                speakHiragana("いまから つうちないようを きろくします。つぎに いわれたときに よみあげます")
                return
            }
            val items = AppNotificationListenerService.consumeRecordedNotifications()
            if (items.isEmpty()) {
                speakHiragana("きろくされた つうちは ありません")
                return
            }
            val sb = StringBuilder()
            items.forEachIndexed { idx, item ->
                val content = listOf(item.title, item.text).filter { it.isNotEmpty() }.joinToString("、")
                sb.append("${idx + 1}つめ、あぷりめいは ${item.appName}、つうちないようは $content。")
            }
            speakHiragana(HiraganaNormalizer.toHiragana(sb.toString()))
            return
        }

        if (hiraText.contains("あまぞん")) {
            val kw = rawText.replace(Regex("""(Amazon|アマゾン|あまぞん|で|を|注文して|注文|検索して|検索)"""), "").trim()
            AmazonOrderHelper.openAmazonSearch(this, kw)
            speakHiragana("あまぞんで $kw を ひらきました")
            return
        }

        if (hiraText.contains("おんがく") || hiraText.contains("きょく")) {
            val kw = rawText.replace(Regex("""(本体|ほんたい|の|音楽|おんがく|曲|きょく|を|再生して|再生)"""), "").trim()
            val ok = LocalMusicPlayer.playLocalMusic(this, kw)
            speakHiragana(if (ok) "ほんたいのおんがくを さいせいします" else "がいとうするきょくが みつかりませんでした")
            return
        }

        val callTargetMatch = Regex("""^(.+?)(?:にでんわして|でんわをかけて|でんわして)$""").find(hiraText)
        if (callTargetMatch != null) {
            val target = callTargetMatch.groupValues[1].trim()
            val digits = target.filter { it.isDigit() || it == '+' }
            if (digits.isNotEmpty() && digits.length >= 3) {
                placePhoneCall(digits, target)
            } else {
                resolveAndCallContact(target, rawText)
            }
            return
        }
        if (hiraText.contains("でんわにでる") || hiraText.contains("ちゃくしんにでる")) {
            acceptIncomingPhoneCall()
            return
        }

        if (hiraText.contains("らいんでつうわ") || hiraText.contains("らいんつうわ") || hiraText.contains("らいんででんわ")) {
            startLineVoiceCall()
            return
        }

        val teamsReplyMatch = Regex("""^(?:てぃーむずで|ちーむずで)?\s*(.+?)(?:にへんしんして|とへんしんして|へへんしんして|とおくって)$""").find(hiraText)
        if (teamsReplyMatch != null && (hiraText.contains("てぃーむず") || hiraText.contains("ちーむず") || hiraText.contains("へんしん"))) {
            replyToTeamsMessage(teamsReplyMatch.groupValues[1].trim())
            return
        }

        networkExecutor.execute {
            val reply = querySog14Gemini(rawText)
            if (!reply.isNullOrBlank()) {
                val spoken = HiraganaNormalizer.toHiragana(reply)
                sendLog("Gemini: $reply")
                speakHiragana(spoken)
            } else {
                speakHiragana("SOG14の じぇみにに せつぞくできませんでした")
            }
        }
    }

    private fun querySog14Gemini(promptText: String): String? {
        if (geminiApiKey.isBlank() || promptText.isBlank()) return null
        val modelName = geminiModel.trim().ifEmpty { "gemini-2.5-flash" }
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/${modelName}:generateContent?key=${geminiApiKey.trim()}"
        return try {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val systemInstruction = JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", "あなたはAndroid端末SOG14上の音声アシスタントです。短くわかりやすく日本語で返答してください。")))
            }
            val userContent = JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", promptText)))
            }
            val bodyJson = JSONObject().apply {
                put("system_instruction", systemInstruction)
                put("contents", JSONArray().put(userContent))
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(bodyJson.toString())
            }
            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }
            conn.disconnect()
            if (code !in 200..299) return null

            val root = JSONObject(text)
            val candidates = root.optJSONArray("candidates") ?: return null
            val first = candidates.optJSONObject(0) ?: return null
            val content = first.optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val t = parts.optJSONObject(i)?.optString("text").orEmpty()
                if (t.isNotBlank()) sb.append(t)
            }
            sb.toString().trim().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveAndCallContact(hiraTargetName: String, rawText: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            speakHiragana("れんらくさきの よみとりけんげんが ありません")
            return
        }
        val rawCandidate = rawText
            .replace(Regex("""(に電話して|にでんわして|電話をかけて|でんわをかけて|電話して|でんわして)$"""), "")
            .trim()
        val candidates = ContactPhoneLookup.findCandidatesByName(this, rawCandidate)
            .ifEmpty { ContactPhoneLookup.findCandidatesByName(this, hiraTargetName) }

        when (candidates.size) {
            0 -> speakHiragana("$hiraTargetName さんの れんらくさきが みつかりませんでした")
            1 -> placePhoneCall(candidates.first().phoneNumber, candidates.first().name)
            else -> {
                pendingContactSelection = PendingContactSelection(candidates)
                speakHiragana(buildCandidatesPrompt(candidates))
            }
        }
    }

    private fun buildCandidatesPrompt(candidates: List<MatchedContact>): String {
        val ordinalNames = listOf("いちばんめ", "にばんめ", "さんばんめ", "よんばんめ", "ごばんめ")
        val sb = StringBuilder("れんらくさきが ${candidates.size}けん みつかりました。")
        candidates.forEachIndexed { idx, item ->
            val label = ordinalNames.getOrElse(idx) { "${idx + 1}ばんめ" }
            val spokenName = HiraganaNormalizer.toHiragana(item.name)
            val last4Spoken = formatDigitsForSpeech(item.phoneNumber.filter { it.isDigit() }.takeLast(4))
            if (last4Spoken.isNotEmpty()) {
                sb.append("$label は $spokenName、ばんごうの しもよんけたは $last4Spoken。")
            } else {
                sb.append("$label は $spokenName。")
            }
        }
        sb.append("どれに かけますか")
        return HiraganaNormalizer.toHiragana(sb.toString())
    }

    private fun formatDigitsForSpeech(digits: String): String {
        val clean = digits.filter { it.isDigit() }
        if (clean.isEmpty()) return ""
        val map = mapOf(
            '0' to "ぜろ", '1' to "いち", '2' to "に", '3' to "さん", '4' to "よん",
            '5' to "ご", '6' to "ろく", '7' to "なな", '8' to "はち", '9' to "きゅう"
        )
        return clean.mapNotNull { map[it] }.joinToString(" ")
    }

    private fun extractSelectionIndex(hiraText: String, maxSize: Int): Int? {
        val index = when {
            hiraText.contains("いちばんめ") || hiraText == "いち" || hiraText == "1" -> 0
            hiraText.contains("にばんめ") || hiraText == "に" || hiraText == "2" -> 1
            hiraText.contains("さんばんめ") || hiraText == "さん" || hiraText == "3" -> 2
            hiraText.contains("よんばんめ") || hiraText == "よん" || hiraText == "し" || hiraText == "4" -> 3
            hiraText.contains("ごばんめ") || hiraText == "ご" || hiraText == "5" -> 4
            else -> null
        }
        return if (index != null && index in 0 until maxSize) index else null
    }

    private fun placePhoneCall(phoneNumber: String, label: String = phoneNumber) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            speakHiragana("でんわはっしんの けんげんが ありません")
            return
        }
        phoneCallActive = true
        startForegroundSafely("つうちゅう・じぇみにれんけいちゅう")
        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        speakHiragana("$label に でんわを かけます")
    }

    private fun acceptIncomingPhoneCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        ) {
            val tm = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            @Suppress("DEPRECATION")
            tm?.acceptRingingCall()
            speakHiragana("でんわに でました")
        } else {
            DeviceControlAccessibilityService.executeAction("ACTION_ACCEPT_CALL")
        }
    }

    private fun startLineVoiceCall() {
        val intent = packageManager.getLaunchIntentForPackage(PKG_LINE)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            DeviceControlAccessibilityService.executeAction("ACTION_LINE_VOICE_CALL")
        }
    }

    private fun replyToTeamsMessage(messageText: String) {
        val intent = packageManager.getLaunchIntentForPackage(PKG_TEAMS)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            DeviceControlAccessibilityService.executeAction("ACTION_TEAMS_REPLY:$messageText")
        }
    }

    private fun startRelayPollLoop() {
        networkExecutor.execute {
            while (!isStopping) {
                try {
                    if (relayUrl.isNotBlank()) {
                        val conn = (URL(relayUrl).openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            connectTimeout = 5000
                            readTimeout = 8000
                            doOutput = true
                            setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        }
                        val payload = JSONObject().apply {
                            put("role", "device")
                            put("device_id", deviceId)
                            put("token", token)
                            put("mic_mode", micInputMode.wireValue)
                        }
                        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
                        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        conn.disconnect()

                        val json = JSONObject(body)
                        val cmds: JSONArray? = json.optJSONArray("commands")
                        if (cmds != null) {
                            for (i in 0 until cmds.length()) {
                                val c = cmds.getJSONObject(i)
                                mainHandler.post { handleRemoteRelayCommand(c) }
                            }
                        }

                        val camFrame = json.optString("controller_camera_b64", "")
                        if (camFrame.isNotEmpty()) {
                            saveControllerCameraFrame(camFrame)
                        }
                        val micPcm = json.optString("controller_mic_pcm16_b64", "")
                        if (micPcm.isNotEmpty() && micInputMode == MicInputMode.CONTROLLER_ONLY) {
                            playControllerMicToSog14Speaker(micPcm)
                        }
                        val spkOut = json.optString("controller_speaker_pcm16_b64", "")
                        if (spkOut.isNotEmpty()) {
                            playControllerMicToSog14Speaker(spkOut)
                        }
                    }
                } catch (_: Exception) {
                }
                try {
                    Thread.sleep(400L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun saveControllerCameraFrame(base64Jpeg: String) {
        mediaIoExecutor.execute {
            try {
                val bytes = Base64.decode(base64Jpeg, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@execute
                val outFile = File(filesDir, "controller_camera_latest.jpg")
                FileOutputStream(outFile).use { fos ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 70, fos)
                }
                bmp.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun playControllerMicToSog14Speaker(base64Pcm16: String) {
        mediaIoExecutor.execute {
            try {
                val pcmBytes = Base64.decode(base64Pcm16, Base64.DEFAULT)
                if (pcmBytes.isNotEmpty()) {
                    speakerOutTrack?.write(pcmBytes, 0, pcmBytes.size)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun handleRemoteRelayCommand(cmd: JSONObject) {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        when (cmd.optString("type")) {
            "auto_start_screen_share" -> {
                triggerAutoScreenShareIfNeeded()
            }
            "set_mic_mode" -> {
                val mode = MicInputMode.fromWire(cmd.optString("mode"))
                applyMicMode(mode, announce = true)
            }
            "amazon_order" -> {
                val q = cmd.optString("query")
                AmazonOrderHelper.openAmazonSearch(this, q)
                speakHiragana("あまぞんで $q を ひらきました")
            }
            "play_local_music" -> {
                val q = cmd.optString("query")
                val ok = LocalMusicPlayer.playLocalMusic(this, q)
                speakHiragana(if (ok) "ほんたいのおんがくを さいせいします" else "きょくが みつかりません")
            }
            "voice_command" -> {
                val txt = cmd.optString("text")
                handleVoiceCommand(HiraganaNormalizer.toHiragana(txt), txt)
            }
            "pointer_up" -> {
                val sx = cmd.optDouble("start_nx", 0.5).toFloat()
                val sy = cmd.optDouble("start_ny", 0.5).toFloat()
                val ex = cmd.optDouble("end_nx", 0.5).toFloat()
                val ey = cmd.optDouble("end_ny", 0.5).toFloat()
                val dist = Math.hypot((ex - sx).toDouble(), (ey - sy).toDouble())
                if (dist < 0.02) {
                    DeviceControlAccessibilityService.tapNormalized(ex, ey, w, h)
                } else {
                    DeviceControlAccessibilityService.swipeNormalized(sx, sy, ex, ey, w, h)
                }
            }
            "keyboard_input" -> {
                val key = cmd.optString("key")
                if (key.length == 1) DeviceControlAccessibilityService.typeText(key)
                else if (key.equals("BackSpace", true)) DeviceControlAccessibilityService.executeAction("ACTION_BACK")
            }
            "key_event" -> {
                when (cmd.optString("key").uppercase()) {
                    "HOME" -> DeviceControlAccessibilityService.executeAction("ACTION_HOME")
                    "BACK" -> DeviceControlAccessibilityService.executeAction("ACTION_BACK")
                }
            }
        }
    }

    private fun restartSpeechRecognition() {
        if (isStopping || isSpeaking || micInputMode != MicInputMode.SOG14_ONLY) return
        val recognizer = speechRecognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            recognizer.startListening(intent)
        } catch (_: Exception) {
            mainHandler.postDelayed({ restartSpeechRecognition() }, 900L)
        }
    }

    private fun speakHiragana(text: String) {
        val cleanHiragana = HiraganaNormalizer.toHiragana(text)
        if (cleanHiragana.isBlank() || !ttsReady) return
        mainHandler.post {
            tts?.speak(cleanHiragana, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        }
    }

    private fun sendLog(msg: String) {
        val intent = Intent(BROADCAST_LOG).apply {
            setPackage(packageName)
            putExtra(EXTRA_LOG_MSG, msg)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Morse Voice AI Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MorseVoiceAI かどうちゅう")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isStopping = true
        phoneCallActive = false
        stopScreenCapture()
        stopSpeechRecognitionLoop()
        try {
            speakerOutTrack?.stop()
            speakerOutTrack?.release()
        } catch (_: Exception) {
        }
        speakerOutTrack = null
        if (instance === this) instance = null
        AppNotificationListenerService.onNewNotificationCallback = null
        LocalMusicPlayer.stop()
        tts?.stop()
        tts?.shutdown()
        tts = null
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        try {
            if (screenWakeLock?.isHeld == true) screenWakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        screenWakeLock = null
        networkExecutor.shutdownNow()
        screenUploadExecutor.shutdownNow()
        mediaIoExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
