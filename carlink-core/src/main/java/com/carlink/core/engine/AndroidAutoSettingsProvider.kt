package com.carlink.core.engine

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.utils.Settings

/**
 * Runs in the ":aa" process and owns DiAuto's settings file there.
 *
 * SharedPreferences caches per process: if CarLink's main process wrote DiAuto's "settings" file
 * directly, the Android Auto engine would keep its stale copy and the next write from either side
 * would silently undo the other. Every read and write therefore goes through this provider.
 * Not exported; only CarLink's own processes can call it.
 */
class AndroidAutoSettingsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = context ?: return Bundle()
        val prefs = context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
        return when (method) {
            METHOD_GET -> Bundle().apply {
                val all = prefs.all
                (extras?.getStringArrayList(EXTRA_KEYS) ?: all.keys.toList()).forEach { key ->
                    when (val value = all[key]) {
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                        is Set<*> -> putStringArrayList(key, ArrayList(value.filterIsInstance<String>()))
                    }
                }
            }
            METHOD_PUT -> {
                val values = extras ?: return Bundle()
                val editor = prefs.edit()
                values.keySet().forEach { key ->
                    @Suppress("DEPRECATION")
                    when (val value = values.get(key)) {
                        null -> editor.remove(key)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is String -> editor.putString(key, value)
                        is ArrayList<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                        else -> Log.w(TAG, "ignoring $key of ${value.javaClass.simpleName}")
                    }
                }
                editor.commit()
                syncDeviceProtected(context, values)
                notifyEngine(context, values.keySet())
                Bundle()
            }
            else -> Bundle()
        }
    }

    /**
     * DiAuto keeps a device-protected copy of its auto-start switches for direct-boot receivers
     * (`Settings.sync*ToDeviceStorage`, DA§6.2); a value written only to the normal file would leave
     * the old one there (DA§11.1).
     */
    private fun syncDeviceProtected(context: Context, values: Bundle) {
        fun flag(key: String): Boolean? = if (values.containsKey(key)) values.getBoolean(key) else null
        flag("auto-start-on-boot")?.let { Settings.syncAutoStartOnBootToDeviceStorage(context, it) }
        flag("auto-start-on-screen-on")?.let { Settings.syncAutoStartOnScreenOnToDeviceStorage(context, it) }
        flag("auto-start-on-usb")?.let { Settings.syncAutoStartOnUsbToDeviceStorage(context, it) }
        flag("auto-start-on-wifi")?.let { Settings.syncAutoStartOnWifiToDeviceStorage(context, it) }
    }

    /**
     * Most Android Auto settings are read when a connection starts. Day/night is live: ask a
     * running AapService to re-evaluate it, as DiAuto's own screens do.
     */
    private fun notifyEngine(context: Context, keys: Set<String>) {
        if (keys.none { it.startsWith("night-mode") }) return
        // AapService listens for this package-local broadcast while it runs.
        context.sendBroadcast(Intent(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE).setPackage(context.packageName))
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "CarLink-AA-Settings"
        const val METHOD_GET = "get"
        const val METHOD_PUT = "put"
        const val EXTRA_KEYS = "keys"

        fun authority(context: Context) = "${context.packageName}.androidauto.settings"
    }
}

/** Main-process client for [AndroidAutoSettingsProvider]. Calls are synchronous IPC; keep them off the UI thread. */
object AndroidAutoSettings {
    fun read(context: Context, keys: List<String>? = null): Bundle {
        val extras = keys?.let { Bundle().apply { putStringArrayList(AndroidAutoSettingsProvider.EXTRA_KEYS, ArrayList(it)) } }
        return call(context, AndroidAutoSettingsProvider.METHOD_GET, extras) ?: Bundle()
    }

    fun write(context: Context, values: Bundle) {
        if (!values.isEmpty) call(context, AndroidAutoSettingsProvider.METHOD_PUT, values)
    }

    private fun call(context: Context, method: String, extras: Bundle?): Bundle? = runCatching {
        context.contentResolver.call(Uri.parse("content://${AndroidAutoSettingsProvider.authority(context)}"), method, null, extras)
    }.onFailure { Log.w("CarLink-AA-Settings", "$method failed: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()
}
