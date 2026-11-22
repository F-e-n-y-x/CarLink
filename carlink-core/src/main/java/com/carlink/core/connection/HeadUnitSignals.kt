package com.carlink.core.connection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.carlink.core.detect.HeadUnitDetector
import com.carlink.core.diagnostics.EventLog
import com.carlink.core.settings.UnifiedSettingsStore
import com.shilapi.xcertplay.CarPlayHostIntegration

/**
 * What an FYT or Joying stereo tells any app without root (re-headunit-apps/reports:
 * fyt-system-apps.md §5, joying-qcm6125.md §6), used only on those firmwares.
 *
 * Lights: both write the headlight (ILL) state to `Settings.System "car_mode"` (0 day, 1 night).
 * Under CarLink's Automatic day/night, CarPlay follows it through DiPlay's `stereoNight` hook and
 * Android Auto through DiAuto's own car-signal mode, which reads the same key
 * ([com.carlink.core.connection.aa.AaSessionSettings]).
 *
 * ACC on: Home's automatic start runs again ([Connection.onCarStarted]). Driver side: read once per
 * app start and kept as the driving-side default. Main process.
 */
internal object HeadUnitSignals {
    private const val CAR_MODE = "car_mode"

    /** FYT or Joying firmware. */
    fun isStereo(context: Context): Boolean = HeadUnitDetector.isFyt || QzdHeadUnit.present(context)

    /** The stereo's lights as night (true) or day; null off FYT/Joying or where the key is missing. */
    fun lightsNight(context: Context): Boolean? = if (!isStereo(context)) null
    else nightFromCarMode(runCatching { Settings.System.getString(context.contentResolver, CAR_MODE) }.getOrNull())

    fun nightFromCarMode(value: String?): Boolean? = value?.trim()?.toIntOrNull()?.let { it != 0 }

    /** Starts following the stereo's signals for this process's life; nothing off FYT/Joying. */
    fun install(context: Context) {
        val app = context.applicationContext
        if (!isStereo(app)) return
        followLights(app)
        followAcc(app)
        readDriverSide(app)
    }

    /**
     * ACC on as the car starting: FYT's `com.fyt.boot.ACCON` (com.syu.ms, fyt-system-apps.md §1) and
     * Joying's `com.zjinnova.zlink.action.POWER_ON` (CarService, MCU ACC). Both are implicit
     * broadcasts with no permission, so only a runtime receiver hears them; this one lives while the
     * main process does. A spoofed broadcast can only cause what showing Home causes.
     */
    private fun followAcc(app: Context) {
        val filter = IntentFilter()
        if (HeadUnitDetector.isFyt) filter.addAction(FYT_ACC_ON)
        if (QzdHeadUnit.present(app)) filter.addAction(JOYING_ACC_ON)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                EventLog.add("CarLink", "stereo: ACC on (${intent.action})")
                Connection.onCarStarted(app)
            }
        }
        runCatching { ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED) }
    }

    /** The stereo's driver side becomes the driving-side default until the driver picks one (UnifiedSettingsStore). */
    private fun readDriverSide(app: Context) {
        val store = { right: Boolean ->
            EventLog.add("CarLink", "stereo: driver on the ${if (right) "right" else "left"}")
            UnifiedSettingsStore(app).saveStereoDriveSide(right)
        }
        if (HeadUnitDetector.isFyt) FytHeadUnit(app).readDriverSide(store)
        else QzdHeadUnit.createIfPresent(app)?.readDriverSide(store)
    }

    private const val FYT_ACC_ON = "com.fyt.boot.ACCON"
    private const val JOYING_ACC_ON = "com.zjinnova.zlink.action.POWER_ON"

    private fun followLights(app: Context) {
        fun read() {
            val night = lightsNight(app)
            if (night != CarPlayHostIntegration.stereoNight) {
                EventLog.add("CarLink", "stereo lights: ${night?.let { if (it) "night" else "day" } ?: "no signal"}")
            }
            CarPlayHostIntegration.stereoNight = night
        }
        read()
        runCatching {
            app.contentResolver.registerContentObserver(Settings.System.getUriFor(CAR_MODE), false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) = read()
                })
        }
    }
}
