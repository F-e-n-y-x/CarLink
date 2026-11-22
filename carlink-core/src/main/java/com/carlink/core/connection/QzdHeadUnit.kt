package com.carlink.core.connection

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.carlink.core.diagnostics.EventLog

/**
 * Joying/QZD head units (com.car.service, e.g. QCM6125 Android 13): CarLink takes the stereo's audio
 * source while a session runs and gives it back after. Static research of the firmware
 * (re-headunit-apps/reports/joying-qcm6125.md): CarService's exported `app.RemoteService` hands out
 * `com.car.ipc.IRemote` with no permission; `cmd("main.appId", {requestAppId:10})` makes the
 * "third-party player" the source (radio/BT music stop, wheel media keys go to the active
 * MediaSession, the OOM adjuster protects the source app) and `requestAppId:-1` restores the
 * previous source. Best effort: elsewhere the service does not exist and nothing happens.
 */
internal class QzdHeadUnit(context: Context) {
    private val app = context.applicationContext
    private var binder: IBinder? = null
    private var engaged = false
    private var connection: ServiceConnection? = null

    fun engaged(on: Boolean) {
        engaged = on
        if (on && connection == null) bind()
        binder?.let { requestSource(it, if (on) APP_ID_THIRD_PARTY_PLAYER else APP_ID_PREVIOUS) }
        if (!on) unbind()
    }

    private fun bind() {
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service
                if (engaged) requestSource(service, APP_ID_THIRD_PARTY_PLAYER)
            }
            override fun onServiceDisconnected(name: ComponentName) { binder = null }
        }
        val bound = runCatching {
            app.bindService(Intent(ACTION).setPackage(PACKAGE), c, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (bound) connection = c else runCatching { app.unbindService(c) }
    }

    private fun unbind() {
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
        binder = null
    }

    /**
     * Reads the stereo's driver side once: IRemote.register(["canbus.driverOnRight"], callback, pushNow)
     * replays it to `com.car.ipc.ICallback.update(Bundle{id, value})` (CS/module/canbus/ModuleCanbus).
     * [onResult] runs on the main thread, at most once; the binding ends after [READ_WINDOW_MILLIS].
     */
    fun readDriverSide(onResult: (right: Boolean) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var done = false
        val callback = object : android.os.Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != 1) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(CALLBACK_DESCRIPTOR)
                val bundle = if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else null
                driverOnRight(bundle?.getString("id"), bundle?.takeIf { it.containsKey("value") }?.getInt("value"))
                    ?.let { right -> main.post { if (!done) { done = true; onResult(right) } } }
                return true
            }
        }
        var service: IBinder? = null
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = binder
                remoteCall(binder, TXN_REGISTER) { writeStringArray(arrayOf(ID_DRIVER_ON_RIGHT)); writeStrongBinder(callback); writeInt(1) }
            }
            override fun onServiceDisconnected(name: ComponentName) { service = null }
        }
        val bound = runCatching { app.bindService(Intent(ACTION).setPackage(PACKAGE), c, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        main.postDelayed({
            done = true
            service?.let { remoteCall(it, TXN_UNREGISTER) { writeStringArray(arrayOf(ID_DRIVER_ON_RIGHT)); writeStrongBinder(callback) } }
            runCatching { app.unbindService(c) }
        }, if (bound) READ_WINDOW_MILLIS else 0L)
    }

    /** A oneway IRemote call (register 3, unregister 4) in CarService's own proxy layout. */
    private inline fun remoteCall(service: IBinder, txn: Int, write: Parcel.() -> Unit) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.write()
            service.transact(txn, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: Exception) {
            Log.w(TAG, "IRemote call $txn failed", e)
        } finally {
            data.recycle()
        }
    }

    /** IRemote.cmd(String, Bundle), transaction 1, oneway — the layout of CarService's own proxy. */
    private fun requestSource(service: IBinder, appId: Int) {
        val data = Parcel.obtain()
        val ok = try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(CMD_APP_ID)
            data.writeInt(1)
            Bundle().apply { putInt("requestAppId", appId) }.writeToParcel(data, 0)
            service.transact(1, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: Exception) {
            Log.w(TAG, "main.appId failed", e)
            false
        } finally {
            data.recycle()
        }
        EventLog.add("CarLink", "Joying: audio source request $appId sent: $ok")
    }

    companion object {
        private const val TAG = "CarLink-QZD"
        private const val PACKAGE = "com.car.service"
        private const val ACTION = "com.car.service"
        private const val DESCRIPTOR = "com.car.ipc.IRemote"
        private const val CMD_APP_ID = "main.appId"
        private const val APP_ID_THIRD_PARTY_PLAYER = 10
        private const val APP_ID_PREVIOUS = -1
        private const val CALLBACK_DESCRIPTOR = "com.car.ipc.ICallback"
        private const val TXN_REGISTER = 3
        private const val TXN_UNREGISTER = 4
        private const val ID_DRIVER_ON_RIGHT = "canbus.driverOnRight"
        private const val READ_WINDOW_MILLIS = 5_000L

        /** The driver side from one ICallback update: 1 right, 0 left; null for any other id. */
        fun driverOnRight(id: String?, value: Int?): Boolean? = if (id == ID_DRIVER_ON_RIGHT) value?.let { it != 0 } else null

        /**
         * The stream type CarService treats as each CarPlay audio channel (DiPlay `AudioChannel` names), empty
         * without Joying's CarService (joying-qcm6125.md §4, `AudioKit`): 4 is navigation and 7 the voice
         * assistant; any other stream plays as the "player" and is ducked or muted with music. The unit's own
         * ZLink settings (`rw.zlink.audiostream.*`, readable by apps) win; calls only from them, because the call
         * stream differs by chip (2 on QCM6125, where 0 is the ring).
         */
        fun audioStreams(context: Context): Map<String, Int> =
            if (createIfPresent(context) == null) emptyMap() else audioStreams(com.carlink.core.detect.SystemProps::get)

        internal fun audioStreams(read: (String) -> String?): Map<String, Int> {
            fun prop(name: String) = read("rw.zlink.audiostream.$name")?.trim()?.toIntOrNull()?.takeIf { it in 1..10 }
            return buildMap {
                put("NAVIGATION", prop("tts") ?: STREAM_NAVIGATION)
                put("ASSISTANT", prop("speech") ?: STREAM_ASSISTANT)
                prop("phonecall")?.let { put("PHONE", it) }
            }
        }

        private const val STREAM_NAVIGATION = 4 // STREAM_ALARM
        private const val STREAM_ASSISTANT = 7 // STREAM_SYSTEM_ENFORCED

        /** Null unless Joying's CarService is installed, so other head units never see these calls. */
        fun createIfPresent(context: Context): QzdHeadUnit? = if (present(context)) QzdHeadUnit(context) else null

        /** Joying's CarService is installed. */
        fun present(context: Context): Boolean = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess
    }
}
