package com.carlink.app.entry

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.carlink.app.R
import com.carlink.app.core.PhoneKind
import com.carlink.app.ui.HomeActivity

/**
 * Brings CarLink's Home to the front from the background. Android 10+ blocks background
 * activity starts unless the app may draw over other apps (both engines ask for it); without
 * it, a full-screen notification opens Home instead, as DiAuto's auto-start receivers do.
 */
object HomeLauncher {
    enum class Reason { BOOT, PHONE }

    fun open(context: Context, reason: Reason, address: String? = null, kind: PhoneKind? = null, name: String? = null) {
        val intent = HomeActivity.intent(context).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (reason == Reason.PHONE && kind != null) {
                putExtra(HomeActivity.EXTRA_AUTO_PHONE_ADDRESS, address)
                putExtra(HomeActivity.EXTRA_AUTO_PHONE_KIND, kind.name)
                putExtra(HomeActivity.EXTRA_AUTO_PHONE_NAME, name)
            }
        }
        val canStartDirectly = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(context)
        if (canStartDirectly) {
            runCatching { context.startActivity(intent) }
                .onSuccess { return }
                .onFailure { Log.w(TAG, "Direct launch failed: ${it.javaClass.simpleName}") }
        }
        notify(context, intent, name)
    }

    private fun notify(context: Context, intent: Intent, phoneName: String?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.notification_channel_launch),
                NotificationManager.IMPORTANCE_HIGH).apply { setShowBadge(false) })
        }
        val pending = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_carlink)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(phoneName?.let { context.getString(R.string.notification_phone_ready, it) }
                ?: context.getString(R.string.notification_open))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private const val TAG = "CarLink-Launch"
    private const val CHANNEL = "carlink_launch"
    private const val NOTIFICATION_ID = 7001
}
