package com.carlink.core.connection

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import com.andrerinas.openheadunit.connection.carkey.fyt.ModuleCallback
import com.andrerinas.openheadunit.connection.carkey.fyt.RemoteModule
import com.andrerinas.openheadunit.connection.carkey.fyt.RemoteToolkit
import com.carlink.core.core.Engine
import com.carlink.core.detect.HeadUnitDetector
import com.carlink.core.diagnostics.EventLog

/**
 * What CarLink tells an FYT head unit (com.syu.ms) around a projection session. Static research of
 * FYT's UIS7862 firmware (re-headunit-apps/reports/fyt-system-apps.md, fyt-carlink.md):
 * - FYT's own projection app releases the phone on `com.syu.carlink.disconnect` (exported, no permission);
 * - toolkit module 0 cmd 0 [10] makes the "third-party player" (APPID 10) FYT's audio source, so FYT
 *   stops its radio/BT music and sends wheel media keys and the knob to the active MediaSession;
 * - FYT's floating button covers the projection; `android.fyt.action.HIDE`/`SHOW` toggles it.
 * Every call is best effort: on other firmware the targets are missing and nothing happens.
 */
internal class FytHeadUnit(context: Context) {
    private val app = context.applicationContext
    private var toolkit: ServiceConnection? = null

    fun engaged(engine: Engine?) {
        if (engine != null) start(engine) else stop()
    }

    private fun start(engine: Engine) {
        runCatching { app.startService(Intent(STOCK_DISCONNECT).setPackage(STOCK_APP)) }
        floatingButton(show = false)
        if (toolkit != null) return
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val ok = runCatching { mainCmd(binder, CMD_APP_ID, APP_ID_THIRD_PARTY_PLAYER) }
                    .onFailure { Log.w(TAG, "toolkit cmd failed", it) }.isSuccess
                EventLog.add("CarLink", "FYT: ${engine.name} set as the audio source (APPID 10): $ok")
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val bound = runCatching {
            app.bindService(Intent().setClassName(MS_PACKAGE, MS_TOOLKIT), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (bound) toolkit = connection else runCatching { app.unbindService(connection) }
    }

    private fun stop() {
        floatingButton(show = true)
        toolkit?.let { runCatching { app.unbindService(it) } }
        toolkit = null
    }

    /**
     * Reads the CAN driver side once (canbus module 7, update 1003 `U_DRIVER_ON_RIGHT`, replayed on
     * register; fyt-carlink.md §4b) with DiAuto's toolkit binder classes, then unbinds. [onResult]
     * runs on the main thread, at most once; never where the stereo has no CAN box answering.
     */
    fun readDriverSide(onResult: (right: Boolean) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        var module: RemoteModule? = null
        var done = false
        val callback = object : ModuleCallback.Stub() {
            override fun update(updateCode: Int, ints: IntArray?, floats: FloatArray?, strings: Array<String>?) {
                val value = ints?.firstOrNull()?.takeIf { updateCode == UPDATE_DRIVER_ON_RIGHT } ?: return
                main.post { if (!done) { done = true; onResult(value != 0) } }
            }
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                runCatching {
                    module = RemoteToolkit.Stub.asInterface(binder).getRemoteModule(MODULE_CANBUS)
                    module?.register(callback, UPDATE_DRIVER_ON_RIGHT, 1)
                }.onFailure { Log.w(TAG, "canbus register failed", it) }
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val bound = runCatching {
            app.bindService(Intent().setClassName(MS_PACKAGE, MS_TOOLKIT), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        main.postDelayed({
            done = true
            runCatching { module?.unregister(callback, UPDATE_DRIVER_ON_RIGHT) }
            runCatching { app.unbindService(connection) }
        }, if (bound) READ_WINDOW_MILLIS else 0L)
    }

    private fun floatingButton(show: Boolean) {
        runCatching { app.startService(Intent(if (show) BUTTON_SHOW else BUTTON_HIDE).setPackage(BUTTON_APP)) }
    }

    /** IRemoteToolkit.getRemoteModule(0).cmd(code, [value]) — the binder layout DiAuto's carkey/fyt client uses. */
    private fun mainCmd(toolkitBinder: IBinder, code: Int, value: Int) {
        val module = transact(toolkitBinder, TOOLKIT_DESCRIPTOR, 1) { writeInt(MODULE_MAIN) }.let { reply ->
            try { reply.readException(); reply.readStrongBinder() } finally { reply.recycle() }
        } ?: error("no main module")
        transact(module, MODULE_DESCRIPTOR, 1) {
            writeInt(code)
            writeIntArray(intArrayOf(value))
            writeFloatArray(null)
            writeStringArray(null)
        }.let { reply -> try { reply.readException() } finally { reply.recycle() } }
    }

    private inline fun transact(binder: IBinder, descriptor: String, code: Int, write: Parcel.() -> Unit): Parcel {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            data.write()
            binder.transact(code, data, reply, 0)
            return reply
        } catch (e: Exception) {
            reply.recycle()
            throw e
        } finally {
            data.recycle()
        }
    }

    companion object {
        private const val TAG = "CarLink-FYT"
        private const val STOCK_APP = "com.syu.carlink"
        private const val STOCK_DISCONNECT = "com.syu.carlink.disconnect"
        private const val BUTTON_APP = "com.fyt.screenbutton"
        private const val BUTTON_HIDE = "android.fyt.action.HIDE"
        private const val BUTTON_SHOW = "android.fyt.action.SHOW"
        private const val MS_PACKAGE = "com.syu.ms"
        private const val MS_TOOLKIT = "app.ToolkitService"
        private const val TOOLKIT_DESCRIPTOR = "com.syu.ipc.IRemoteToolkit"
        private const val MODULE_DESCRIPTOR = "com.syu.ipc.IRemoteModule"
        private const val MODULE_MAIN = 0
        private const val CMD_APP_ID = 0
        private const val APP_ID_THIRD_PARTY_PLAYER = 10
        private const val MODULE_CANBUS = 7
        private const val UPDATE_DRIVER_ON_RIGHT = 1003
        private const val READ_WINDOW_MILLIS = 5_000L

        /** Null off FYT firmware, so other head units never see these calls. */
        fun createIfFyt(context: Context): FytHeadUnit? = if (HeadUnitDetector.isFyt) FytHeadUnit(context) else null
    }
}
