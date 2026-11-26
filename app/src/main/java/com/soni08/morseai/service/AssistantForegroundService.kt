package com.soni08.morseai.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.soni08.morseai.ai.LocalAiClient
import com.soni08.morseai.contacts.ContactPhoneLookup
import com.soni08.morseai.contacts.MatchedContact
import com.soni08.morseai.media.LocalMusicPlayer
import com.soni08.morseai.shopping.AmazonOrderHelper
import com.soni08.morseai.text.HiraganaNormalizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

data class PendingContactSelection(
    val candidates: List<MatchedContact>
)

class AssistantForegroundService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "morse_voice_ai_channel"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.soni08.morseai.STOP"
        const val ACTION_INCOMING_CALL = "com.soni08.morseai.INCOMING_CALL"
        const val EXTRA_INCOMING_NUMBER = "incoming_number"
        const val EXTRA_LOCAL_AI_URL = "local_ai_url"
        const val EXTRA_LOCAL_AI_MODEL = "local_ai_model"
        const val EXTRA_RELAY_URL = "relay_url"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_TOKEN = "token"
        const val BROADCAST_LOG = "com.soni08.morseai.LOG"
        const val EXTRA_LOG_MSG = "log_msg"

        private const val PKG_TERMUX = "com.termux"
        private const val PKG_LINE = "jp.naver.line.android"
        private const val PKG_TEAMS = "com.microsoft.teams"

        @Volatile
        private var instance: AssistantForegroundService? = null

        fun notifyIncomingCallEvent(appName: String, detail: String) {
            instance?.onIncomingAppCallDetected(appName, detail)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkExecutor = Executors.newSingleThreadExecutor()

    private var wakeLock: PowerManager.WakeLock? = null
    private var localAiUrl: String = "http://127.0.0.1:8080"
    private var localAiModel: String = "local-model"
    private var relayUrl: String = "https://soni08.website/remote/relay.php"
    private var deviceId: String = "sog14-main"
    private var token: String = "change-me-token"

    @Volatile
    private var aiChatEnabled: Boolean = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null

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
        tts = TextToSpeech(this, this)
        AppNotificationListenerService.onNewNotificationCallback = { item ->
            if (AppNotificationListenerService.isRecordingSinceTrigger && !isSpeaking) {
                sendLog("つうちきろく: ${item.appName} / ${item.title} ${item.text}")
            }
        }
    }

    private fun acquirePersistentWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MorseVoiceAI::SleepKeepAliveWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        acquirePersistentWakeLock()
        intent?.getStringExtra(EXTRA_LOCAL_AI_URL)?.takeIf { it.isNotBlank() }?.let { localAiUrl = it }
        intent?.getStringExtra(EXTRA_LOCAL_AI_MODEL)?.takeIf { it.isNotBlank() }?.let { localAiModel = it }
        intent?.getStringExtra(EXTRA_RELAY_URL)?.takeIf { it.isNotBlank() }?.let { relayUrl = it }
        intent?.getStringExtra(EXTRA_DEVICE_ID)?.takeIf { it.isNotBlank() }?.let { deviceId = it }
        intent?.getStringExtra(EXTRA_TOKEN)?.takeIf { it.isNotBlank() }?.let { token = it }

        startForeground(NOTIF_ID, buildNotification("おんせいにんしき・すりーぷたいおう かどうちゅう"))
        autoStartLocalAiAtLaunch()
        startSpeechRecognitionLoop()

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

    private fun handleIncomingPhoneCall(rawNumber: String) {
        aiChatEnabled = true
        autoStartLocalAiAtLaunch()

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
                "ちゃくしんが あります。はっしんもとは $spokenName さん、でんわばんごうは $spokenDigits です。えーあいを じどうきどう しました。"
            }
            spokenDigits.isNotEmpty() -> {
                "ちゃくしんが あります。でんわばんごうは $spokenDigits、れんらくさき みとうろくの ばんごうです。えーあいを じどうきどう しました。"
            }
            else -> {
                "ひつうち または ばんごうふめいの ちゃくしんが あります。えーあいを じどうきどう しました。"
            }
        }
        sendLog("ちゃくしんけんち: $announcement")
        mainHandler.postDelayed({ speakHiragana(announcement) }, 600L)
    }

    private fun onIncomingAppCallDetected(appName: String, detail: String) {
        if (isStopping) return
        aiChatEnabled = true
        autoStartLocalAiAtLaunch()
        val msg = "$appName から ちゃくしんが あります。$detail。えーあいを じどうきどう しました。"
        sendLog(msg)
        speakHiragana(msg)
    }

    private fun autoStartLocalAiAtLaunch(attempt: Int = 1) {
        networkExecutor.execute {
            if (isLocalAiReachable()) {
                sendLog("ろーかるえーあい じどうれんけいかんりょう")
                return@execute
            }
            sendLog("Termux ろーかるえーあい じどうきどうちゅう (${attempt}かいめ)")
            try {
                val runIntent = Intent("com.termux.RUN_COMMAND").apply {
                    setClassName(PKG_TERMUX, "com.termux.app.RunCommandService")
                    putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                    putExtra(
                        "com.termux.RUN_COMMAND_ARGUMENTS",
                        arrayOf("-lc", "/data/data/com.termux/files/home/start_llama.sh")
                    )
                    putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(runIntent)
                } else {
                    startService(runIntent)
                }
            } catch (_: Exception) {
            }

            if (attempt < 5 && !isStopping) {
                mainHandler.postDelayed({ autoStartLocalAiAtLaunch(attempt + 1) }, 3000L)
            }
        }
    }

    private fun isLocalAiReachable(): Boolean {
        val base = localAiUrl.trim().trimEnd('/').removeSuffix("/v1/chat/completions")
        if (base.isBlank()) return false
        return try {
            val conn = (URL("$base/v1/models").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1500
                readTimeout = 1500
            }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
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
                    if (!isStopping) {
                        mainHandler.postDelayed({ restartSpeechRecognition() }, 350L)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    if (!isStopping) {
                        mainHandler.postDelayed({ restartSpeechRecognition() }, 350L)
                    }
                }
            })
            ttsReady = true
        }
    }

    private fun startSpeechRecognitionLoop() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendLog("おんせいにんしきがつかえません")
            return
        }
        mainHandler.post {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}

                        override fun onError(error: Int) {
                            if (!isStopping && !isSpeaking) {
                                mainHandler.postDelayed({ restartSpeechRecognition() }, 700L)
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val rawRecognized = matches?.firstOrNull()?.trim()
                            if (!rawRecognized.isNullOrEmpty() && !isSpeaking) {
                                val hiraText = HiraganaNormalizer.toHiragana(rawRecognized)
                                sendLog("おんせいにんしき: $hiraText")
                                handleVoiceCommand(hiraText, rawRecognized)
                            }
                            if (!isStopping && !isSpeaking) {
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

        if (hiraText.contains("えーあいとはなしたい") || hiraText.contains("えーあいもーど")) {
            aiChatEnabled = true
            autoStartLocalAiAtLaunch()
            speakHiragana("えーあいと はなす もーどに しました")
            return
        }
        if (hiraText.contains("えーあいをやめる") || hiraText.contains("つうじょうもーど")) {
            aiChatEnabled = false
            speakHiragana("えーあいもーどを しゅうりょうしました")
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

        if (aiChatEnabled) {
            networkExecutor.execute {
                val reply = LocalAiClient.generateHiraganaReply(localAiUrl, localAiModel, hiraText)
                if (!reply.isNullOrBlank()) {
                    speakHiragana(HiraganaNormalizer.toHiragana(reply))
                } else {
                    speakHiragana("ろーかるえーあいに せつぞくできませんでした")
                }
            }
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
                        }
                        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
                        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        conn.disconnect()

                        val cmds: JSONArray? = JSONObject(body).optJSONArray("commands")
                        if (cmds != null) {
                            for (i in 0 until cmds.length()) {
                                val c = cmds.getJSONObject(i)
                                mainHandler.post { handleRemoteRelayCommand(c) }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
                try {
                    Thread.sleep(500L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun handleRemoteRelayCommand(cmd: JSONObject) {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        when (cmd.optString("type")) {
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
        if (isStopping || isSpeaking) return
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
        if (instance === this) instance = null
        AppNotificationListenerService.onNewNotificationCallback = null
        mainHandler.post {
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
        LocalMusicPlayer.stop()
        tts?.stop()
        tts?.shutdown()
        tts = null
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        networkExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
