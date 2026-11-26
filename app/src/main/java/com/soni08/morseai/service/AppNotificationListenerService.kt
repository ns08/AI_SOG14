package com.soni08.morseai.service

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Collections

data class RecordedNotification(
    val timestampMs: Long,
    val appName: String,
    val packageName: String,
    val title: String,
    val text: String
)

class AppNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val PKG_LINE = "jp.naver.line.android"
        private const val PKG_TEAMS = "com.microsoft.teams"

        @Volatile
        var isRecordingSinceTrigger: Boolean = false
            private set

        @Volatile
        var triggerStartedAtMs: Long = 0L
            private set

        private val recordedList: MutableList<RecordedNotification> =
            Collections.synchronizedList(mutableListOf())

        var onNewNotificationCallback: ((RecordedNotification) -> Unit)? = null

        fun startRecordingFromNow() {
            recordedList.clear()
            triggerStartedAtMs = System.currentTimeMillis()
            isRecordingSinceTrigger = true
        }

        fun consumeRecordedNotifications(): List<RecordedNotification> {
            synchronized(recordedList) {
                val copy = recordedList.toList()
                recordedList.clear()
                return copy
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val item = sbn ?: return
        val extras = item.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()

        if (title.isEmpty() && text.isEmpty()) return

        val appName = resolveAppName(item.packageName)
        val combinedContent = listOf(title, text).filter { it.isNotEmpty() }.joinToString("、")

        if (title.contains("着信") || text.contains("着信") || text.contains("音声通話")) {
            AssistantForegroundService.notifyIncomingCallEvent(appName, combinedContent)
        }

        if (isRecordingSinceTrigger && item.postTime >= triggerStartedAtMs) {
            val entry = RecordedNotification(
                timestampMs = item.postTime,
                appName = appName,
                packageName = item.packageName,
                title = title,
                text = text
            )
            recordedList.add(entry)
            onNewNotificationCallback?.invoke(entry)
        }
    }

    private fun resolveAppName(packageName: String): String {
        return when (packageName) {
            PKG_LINE -> "LINE"
            PKG_TEAMS -> "Teams"
            else -> try {
                val pm = packageManager
                val info = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(info).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                packageName
            }
        }
    }
}
