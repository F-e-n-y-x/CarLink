package com.carlink.app.switcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

/**
 * Hosts the navigation bar ([NavigationBarOverlay]). An accessibility service is the one
 * permission Android gives an app to press Back and Home for the driver
 * ([performGlobalAction]), to replay a touch under an edge strip ([dispatchGesture]) and to
 * add a window above every app ([android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]).
 *
 * It asks for none of the accessibility events and for no window content (see
 * `res/xml/carlink_navigation_bar_service.xml`), so it cannot read what is on the screen. The
 * strips are added when Android connects the service and removed when it unbinds; the switch in
 * Settings only decides whether they are drawn ([NavigationBarPreference]).
 */
class NavigationBarService : AccessibilityService() {

    private val overlay by lazy { NavigationBarOverlay(this) }

    override fun onServiceConnected() = overlay.start()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        overlay.stop()
        return super.onUnbind(intent)
    }

    companion object {
        /** Whether the driver has turned the service on in Android's accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, NavigationBarService::class.java)
            val manager = context.getSystemService(AccessibilityManager::class.java)
            val enabled = manager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            if (enabled != null && enabled.any { ComponentName.unflattenFromString(it.id) == expected }) return true
            val raw = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val want = expected.flattenToString()
            return raw.split(':').any { it.equals(want, ignoreCase = true) }
        }
    }
}
