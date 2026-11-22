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

        /** Null unless Joying's CarService is installed, so other head units never see these calls. */
        fun createIfPresent(context: Context): QzdHeadUnit? = runCatching {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            QzdHeadUnit(context)
        }.getOrNull()
    }
}
