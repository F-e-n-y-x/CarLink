package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Optional, needs ADB over network: the iPhone draws and streams the cluster map for the whole
 * session, but the cluster shows it only in Small and Full screen navi. DiPlay reads the mode the
 * driver picked on the wheel every second and asks the iPhone to stop drawing the map while the
 * cluster hides it (Off, Turn on by navi, where it shows arrows only), and to draw it again when the
 * driver picks Small or Full. Without ADB access, or when the mode cannot be read, the map streams
 * as before.
 */
internal object BydClusterMapPause {
    private const val TAG = "DiPlay-BYD-ClusterMap"
    private const val READ_MILLIS = 1_000L
    private const val ADB_RETRY_MILLIS = 30_000L

    private val lock = Any()
    private var context: Context? = null
    private var adb: LocalAdb? = null
    private var tickerStarted = false
    private var unavailableLogged = false
    private var adbRetryMillis = 0L
    private var lastMode: BydClusterNaviMode? = null

    /** Whether DiPlay's map window is on the cluster. */
    @Volatile var clusterMapShown = false

    /** The running CarPlay session, told every second whether the iPhone should draw the cluster map. */
    @Volatile var streamControl: ((Boolean) -> Unit)? = null

    fun initialize(appContext: Context) = synchronized(lock) {
        context = appContext.applicationContext
        if (!tickerStarted) {
            tickerStarted = true
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "diplay-cluster-map").apply { isDaemon = true }
            }.scheduleAtFixedRate(::tick, READ_MILLIS, READ_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private fun tick() = synchronized(lock) {
        val app = context ?: return@synchronized
        val control = streamControl
        if (control == null || !clusterMapShown || !BydOutputSettings.clusterStreamPause(app)) {
            control?.invoke(true)
            close()
            return@synchronized
        }
        val mode = shell(app)?.let { BydClusterNaviMode.parseRead(it.shell(BydClusterNaviMode.READ_COMMAND)) }
        if (mode != lastMode) {
            lastMode = mode
            Log.i(TAG, "cluster mode ${mode?.label ?: "unknown"}")
        }
        // An unknown mode keeps the map streaming, as without ADB.
        control(mode?.showsMap != false)
    }

    // Never asks for approval here: the car's dialog must not appear while driving. A refused or
    // missing adbd is retried only every 30 s.
    private fun shell(app: Context): LocalAdb? {
        val now = SystemClock.elapsedRealtime()
        if (now < adbRetryMillis) return null
        val client = adb ?: LocalAdb(AdbKeys.load(app)).also { adb = it }
        val access = client.connect(mayAsk = false)
        if (access == LocalAdb.Access.READY) {
            unavailableLogged = false
            return client
        }
        adbRetryMillis = now + ADB_RETRY_MILLIS
        if (!unavailableLogged) {
            unavailableLogged = true
            Log.w(TAG, "ADB access $access: the cluster map keeps streaming")
        }
        return null
    }

    private fun close() {
        adb?.close()
        adb = null
        lastMode = null
    }
}

/** What the settings page shows about the ADB link that the cluster map pause needs. */
object BydClusterModeAccess {
    enum class State { READY, NOT_APPROVED, ADB_OFF, PAIRING_ONLY }

    class Status(val state: State, val modeLabel: String?, val showsMap: Boolean)

    /** Blocking: run off the main thread. [mayAsk] lets the car show its approval dialog for DiPlay's key. */
    fun check(context: Context, mayAsk: Boolean): Status {
        LocalAdb(AdbKeys.load(context)).use { adb ->
            val state = when (adb.connect(mayAsk)) {
                LocalAdb.Access.READY -> State.READY
                LocalAdb.Access.NOT_APPROVED -> State.NOT_APPROVED
                LocalAdb.Access.UNREACHABLE -> State.ADB_OFF
                LocalAdb.Access.UNSUPPORTED -> State.PAIRING_ONLY
            }
            if (state != State.READY) return Status(state, null, true)
            val mode = BydClusterNaviMode.parseRead(adb.shell(BydClusterNaviMode.READ_COMMAND))
            return Status(state, mode?.label, mode?.showsMap != false)
        }
    }
}
