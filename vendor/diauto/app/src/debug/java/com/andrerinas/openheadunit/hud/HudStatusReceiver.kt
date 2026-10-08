package com.andrerinas.openheadunit.hud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcel
import android.util.Log
import com.byd.spi.ipc.cursor.BinderCursor

/** Shell-triggered, debug-only status probe. The transaction allowlist contains getters only. */
class HudStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "byd.hud.STARTER_CONFIG") {
            BydStarterBridge.configure(context, intent.getStringExtra("token") ?: return)
            resultData = "Starter configured"
            return
        }
        if (intent.action == "com.andrerinas.openheadunit.HUD_BRIDGE_DEMO") {
            BydStarterBridge.demonstrate(context)
            resultData = "Bridge demo scheduled: left 500m / right 800m / clear"
            return
        }
        if (intent.action == "com.andrerinas.openheadunit.HUD_KEY_PROBE") {
            val pending = goAsync()
            Thread({
                try {
                    // Per-key write/readback: proves which keys the property layer truly
                    // accepts and whether the write reaches its value store.
                    val before = BydCarServerTransport.read(context, "0x43F01018")
                    Log.i("DiAuto-HUD-Keys", "distance before: $before")
                    for ((key, label, value) in listOf(
                        Triple("0x43E0003A", "state", 2),
                        Triple("0x43F01010", "simple", 1),
                        Triple("0x43F01030", "roadahead", 1),
                        Triple("0x43F01018", "distance", 777),
                    )) {
                        val code = BydCarServerTransport.write(context, key, value)
                        val back = BydCarServerTransport.read(context, key)
                        Log.i("DiAuto-HUD-Keys", "$label write=$code readback: $back")
                    }
                    Thread.sleep(2_000)
                    // Restore neutral values so nothing latches on the display.
                    BydCarServerTransport.write(context, "0x43F01018", 0)
                    BydCarServerTransport.write(context, "0x43F01030", 11)
                    Log.i("DiAuto-HUD-Keys", "probe finished, neutralized")
                } catch (e: Exception) {
                    Log.w("DiAuto-HUD-Keys", "key probe failed: ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    pending.finish()
                }
            }, "hud-key-probe").start()
            return
        }
        if (intent.action == "com.andrerinas.openheadunit.HUD_SEND_TEST") {
            val pending = goAsync()
            Thread({
                try {
                    val output = BydCarServerNavigationOutput(context)
                    BydNavigationOutputs.syntheticHold = true
                    output.update(2, 0, 500) // cluster icon 2 -> factory turn 1 (left)
                    Thread.sleep(15_000)
                    output.update(3, 0, 800) // cluster icon 3 -> factory turn 2 (right)
                    Thread.sleep(15_000)
                    output.clear()
                    Log.i("DiAuto-HUD-Send", "app-path synthetic sequence finished and cleared")
                } catch (e: Exception) {
                    Log.w("DiAuto-HUD-Send", "app-path synthetic sequence failed: ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    BydNavigationOutputs.syntheticHold = false
                    pending.finish()
                }
            }, "hud-send-test").start()
            return
        }
        if (intent.action == "com.andrerinas.openheadunit.HUD_SNAPSHOT") {
            val frame = BydNavigationOutputs.currentForDiagnostic(context)
            val turn = frame?.let { BydFactoryTurnCode.map(it.clusterIcon, it.roundaboutExit) }
            resultData = if (frame != null && turn != null) "$turn ${frame.distanceMeters}" else "clear"
            return
        }
        val pending = goAsync()
        Thread({
            try {
                val token = "com.byd.car.feature.vision.ICarHudService"
                context.contentResolver.query(
                    Uri.parse("content://com.byd.car.server.provider.CarServiceProvider/sync_binder"),
                    null, null, arrayOf(token), null,
                ).use { cursor ->
                    val extras = cursor?.extras ?: error("HUD provider returned no cursor")
                    extras.classLoader = BinderCursor.BinderParcelable::class.java.classLoader
                    @Suppress("DEPRECATION")
                    val wrapper = extras.getParcelable<BinderCursor.BinderParcelable>("binder")
                    val binder = wrapper?.binder ?: error("HUD provider returned no binder")
                    check(binder.interfaceDescriptor == token) { "Unexpected HUD interface" }
                    for ((code, label) in listOf(3 to "hudEnabled", 13 to "mode", 19 to "dynamicNavigation", 21 to "navigationFusion", 23 to "navigationMap", 30 to "arrowType")) {
                        val request = Parcel.obtain()
                        val reply = Parcel.obtain()
                        try {
                            request.writeInterfaceToken(token)
                            check(binder.transact(code, request, reply, 0)) { "Unsupported getter" }
                            reply.readException()
                            check(reply.readInt() != 0) { "Null result" }
                            val result = reply.readInt()
                            val message = reply.readString()
                            val type = reply.readString()
                            val value = when (type) {
                                "java.lang.Integer", "int" -> reply.readInt().toString()
                                "java.lang.Boolean", "boolean" -> (reply.readInt() != 0).toString()
                                null -> "null"
                                else -> "unparsed:$type"
                            }
                            Log.i("DiAuto-HUD-Status", "$label code=$result value=$value message=$message")
                        } catch (e: Exception) {
                            Log.w("DiAuto-HUD-Status", "$label failed: ${e.javaClass.simpleName}: ${e.message}")
                        } finally { request.recycle(); reply.recycle() }
                    }
                }
            } catch (e: Exception) {
                Log.w("DiAuto-HUD-Status", "HUD status unavailable: ${e.javaClass.simpleName}: ${e.message}")
            } finally { pending.finish() }
        }, "hud-status-read").start()
    }
}
