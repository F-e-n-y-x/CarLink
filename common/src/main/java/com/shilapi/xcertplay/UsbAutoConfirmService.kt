package com.shilapi.xcertplay

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * An accessibility service to automatically check "Always allow / Use by default"
 * and click "OK / Confirm" when the Android system USB permission dialog appears for DiPlay.
 */
class UsbAutoConfirmService : AccessibilityService() {

    private var lastClickTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val now = SystemClock.uptimeMillis()
        if (now - lastClickTime < DEBOUNCE_MILLIS) return

        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        try {
            if (isUsbPermissionDialog(root)) {
                checkAlwaysCheckbox(root)
                if (clickConfirmButton(root)) {
                    lastClickTime = now
                    Log.i(TAG, "Successfully auto-confirmed USB permission dialog")
                }
            }
        } finally {
            runCatching { root.recycle() }
        }
    }

    override fun onInterrupt() {}

    private fun isUsbPermissionDialog(root: AccessibilityNodeInfo): Boolean {
        val texts = mutableListOf<String>()
        collectAllText(root, texts)
        val combined = texts.joinToString(" ")
        val isTargetApp = combined.contains("DiPlay", ignoreCase = true) ||
            combined.contains("CarPlay", ignoreCase = true)
        val isUsbPrompt = combined.contains("iPhone", ignoreCase = true) ||
            combined.contains("USB", ignoreCase = true) ||
            combined.contains("访问") ||
            combined.contains("access", ignoreCase = true)
        return isTargetApp && isUsbPrompt
    }

    private fun collectAllText(node: AccessibilityNodeInfo, outList: MutableList<String>) {
        node.text?.toString()?.let { outList.add(it) }
        node.contentDescription?.toString()?.let { outList.add(it) }
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collectAllText(child, outList)
            runCatching { child.recycle() }
        }
    }

    private fun checkAlwaysCheckbox(node: AccessibilityNodeInfo): Boolean {
        if (node.isCheckable && !node.isChecked) {
            val text = (node.text?.toString() ?: "") + (node.contentDescription?.toString() ?: "")
            if (text.contains("默认") || text.contains("一律") || text.contains("always", ignoreCase = true) || text.isEmpty()) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val checked = checkAlwaysCheckbox(child)
            runCatching { child.recycle() }
            if (checked) return true
        }
        return false
    }

    private fun clickConfirmButton(node: AccessibilityNodeInfo): Boolean {
        // 1. Check standard Android Alert positive button ID
        val button1Nodes = runCatching { node.findAccessibilityNodeInfosByViewId("android:id/button1") }.getOrNull()
        if (!button1Nodes.isNullOrEmpty()) {
            for (btn in button1Nodes) {
                if (btn.isClickable) {
                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }
        }

        // 2. Fall back to matching common positive button labels
        val targets = listOf("确定", "允许", "OK", "Allow", "Confirm")
        for (target in targets) {
            val matches = runCatching { node.findAccessibilityNodeInfosByText(target) }.getOrNull()
            if (!matches.isNullOrEmpty()) {
                for (n in matches) {
                    if (n.isClickable) {
                        n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                    val parent = n.parent
                    if (parent != null && parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                }
            }
        }
        return false
    }

    companion object {
        private const val TAG = "UsbAutoConfirm"
        private const val DEBOUNCE_MILLIS = 800L

        fun isEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            val myService = ComponentName(context, UsbAutoConfirmService::class.java).flattenToString()
            val myShortService = ComponentName(context, UsbAutoConfirmService::class.java).flattenToShortString()
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(myService, ignoreCase = true) ||
                    componentName.equals(myShortService, ignoreCase = true)
                ) {
                    return true
                }
            }
            return false
        }

        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
