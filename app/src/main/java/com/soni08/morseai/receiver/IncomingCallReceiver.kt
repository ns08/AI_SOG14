package com.soni08.morseai.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import com.soni08.morseai.service.AssistantForegroundService

class IncomingCallReceiver : BroadcastReceiver() {

    companion object {
        @Volatile
        private var lastNumber: String? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION")
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.trim().orEmpty()

        if (state == TelephonyManager.EXTRA_STATE_RINGING) {
            if (incomingNumber.isNotEmpty()) lastNumber = incomingNumber
            val resolvedNumber = incomingNumber.ifEmpty { lastNumber.orEmpty() }

            val serviceIntent = Intent(context, AssistantForegroundService::class.java).apply {
                action = AssistantForegroundService.ACTION_INCOMING_CALL
                putExtra(AssistantForegroundService.EXTRA_INCOMING_NUMBER, resolvedNumber)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } else if (state == TelephonyManager.EXTRA_STATE_IDLE) {
            lastNumber = null
        }
    }
}
