package com.soni08.morseai.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class DeviceControlAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: DeviceControlAccessibilityService? = null

        fun executeAction(action: String): Boolean {
            val svc = instance ?: return false
            return svc.performCustomAction(action)
        }

        fun tapNormalized(nx: Float, ny: Float, screenW: Int, screenH: Int): Boolean {
            val svc = instance ?: return false
            val x = (nx * screenW).coerceIn(1f, (screenW - 1).toFloat())
            val y = (ny * screenH).coerceIn(1f, (screenH - 1).toFloat())
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
            return svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }

        fun swipeNormalized(x1: Float, y1: Float, x2: Float, y2: Float, screenW: Int, screenH: Int): Boolean {
            val svc = instance ?: return false
            val sx = (x1 * screenW).coerceIn(1f, (screenW - 1).toFloat())
            val sy = (y1 * screenH).coerceIn(1f, (screenH - 1).toFloat())
            val ex = (x2 * screenW).coerceIn(1f, (screenW - 1).toFloat())
            val ey = (y2 * screenH).coerceIn(1f, (screenH - 1).toFloat())
            val path = Path().apply {
                moveTo(sx, sy)
                lineTo(ex, ey)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 220L)
            return svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }

        fun typeText(text: String): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            val target = findEditableNode(root) ?: return false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        private fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isEditable) return node
            for (i in 0 until node.childCount) {
                val found = findEditableNode(node.getChild(i))
                if (found != null) return found
            }
            return null
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun performCustomAction(action: String): Boolean {
        return when {
            action == "ACTION_BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            action == "ACTION_HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            action == "ACTION_NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            action == "ACTION_RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            action == "ACTION_ACCEPT_CALL" -> {
                handler.postDelayed({
                    clickNodeByKeywords(listOf("応答", "出る", "電話に出る", "Answer", "Accept"))
                }, 400L)
                true
            }
            action == "ACTION_LINE_VOICE_CALL" -> {
                handler.postDelayed({
                    clickNodeByKeywords(listOf("無料通話", "音声通話", "通話", "Voice call", "Call"))
                }, 1200L)
                true
            }
            action.startsWith("ACTION_TEAMS_REPLY:") -> {
                val replyText = action.removePrefix("ACTION_TEAMS_REPLY:")
                handler.postDelayed({
                    inputTextAndSend(replyText)
                }, 1200L)
                true
            }
            else -> false
        }
    }

    private fun clickNodeByKeywords(keywords: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        for (kw in keywords) {
            val nodes = root.findAccessibilityNodeInfosByText(kw)
            for (node in nodes) {
                var target: AccessibilityNodeInfo? = node
                while (target != null) {
                    if (target.isClickable) {
                        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    target = target.parent
                }
            }
        }
        return false
    }

    private fun inputTextAndSend(message: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val editNode = findEditableNode(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        }
        editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        handler.postDelayed({
            clickNodeByKeywords(listOf("送信", "Send"))
        }, 400L)
        return true
    }
}
