package com.shilapi.xcertplay.hud

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.util.Log
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors

/** What the car shows for the CarPlay call: the phase and the caller, and since when it is connected. */
data class CarPlayCallCard(val phase: Phase, val name: String, val activeSinceMillis: Long? = null) {
    enum class Phase { RINGING, DIALING, ACTIVE }
}

/**
 * The iPhone's calls from iAP2 CallStateUpdate (0x4155): remote id (0), display name (1), status (2)
 * and call UUID (4). Status: 0 disconnected, 1 sending, 2 ringing, 3 connecting, 4 active, 5 held,
 * 6 disconnecting. Several calls can exist at once (call waiting); the card shows a ringing call
 * first, then a connected (active or held) one, then an outgoing one.
 */
class CarPlayCallState(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Call(val status: Int, val name: String, val activeSince: Long?)

    private val calls = LinkedHashMap<String, Call>()
    private var last: CarPlayCallCard? = null

    /** Returns true when the card changed; read it with [current]. */
    fun accept(frame: Iap2Frame): Boolean {
        if (frame.messageId != CALL_STATE_UPDATE) return false
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull() ?: return false
        val status = runCatching { body.optionalU8(STATUS) }.getOrNull() ?: return false
        val remote = runCatching { body.optionalString(REMOTE_ID) }.getOrNull()?.trim().orEmpty()
        val display = runCatching { body.optionalString(DISPLAY_NAME) }.getOrNull()?.trim().orEmpty()
        val id = runCatching { body.optionalString(CALL_UUID) }.getOrNull()?.takeIf { it.isNotBlank() } ?: remote
        if (status == DISCONNECTED || status == DISCONNECTING) {
            calls.remove(id)
        } else {
            val previous = calls[id]
            val connected = status == ACTIVE || status == HELD
            calls[id] = Call(
                status = status,
                name = display.ifEmpty { remote }.ifEmpty { previous?.name.orEmpty() },
                activeSince = previous?.activeSince ?: if (connected) clock() else null,
            )
        }
        val next = card()
        if (next == last) return false
        last = next
        return true
    }

    fun current(): CarPlayCallCard? = last

    fun clear() {
        calls.clear()
        last = null
    }

    private fun card(): CarPlayCallCard? {
        calls.values.firstOrNull { it.status == RINGING }?.let { return CarPlayCallCard(CarPlayCallCard.Phase.RINGING, it.name) }
        (calls.values.firstOrNull { it.status == ACTIVE } ?: calls.values.firstOrNull { it.status == HELD })?.let {
            return CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, it.name, it.activeSince)
        }
        calls.values.firstOrNull { it.status == SENDING || it.status == CONNECTING }?.let {
            return CarPlayCallCard(CarPlayCallCard.Phase.DIALING, it.name)
        }
        return null
    }

    companion object {
        const val CALL_STATE_UPDATE = 0x4155
        private const val REMOTE_ID = 0
        private const val DISPLAY_NAME = 1
        private const val STATUS = 2
        private const val CALL_UUID = 4
        private const val DISCONNECTED = 0
        private const val SENDING = 1
        private const val RINGING = 2
        private const val CONNECTING = 3
        private const val ACTIVE = 4
        private const val HELD = 5
        private const val DISCONNECTING = 6

        /** BYD's call card takes at most 60 bytes of UTF-16LE, like BYD's own CarPlay app sends. */
        const val MAX_NAME_BYTES = 60

        fun nameBytes(name: String): ByteArray {
            var end = name.length
            while (name.substring(0, end).toByteArray(Charsets.UTF_16LE).size > MAX_NAME_BYTES) {
                end--
                if (end > 0 && Character.isLowSurrogate(name[end])) end--
            }
            return name.substring(0, end).toByteArray(Charsets.UTF_16LE)
        }
    }
}

/**
 * Optional, needs ADB over network: shows CarPlay calls on the dashboard and the windshield HUD the way
 * BYD's own CarPlay app does (com.byd.carplay.ui, BinderCarplayServer.CarplayNotifyInstrumentCallState):
 * the instrument's call state, caller and call time, the car's call state (which also turns the fan
 * down) and the audio system's CarPlay call status. Apps cannot write these, autoservice accepts the
 * adb shell, so DiPlay runs [BydCarPlayCallTool] from its APK under the shell. While a call lasts a
 * small watcher under the shell sends the call time every second and ends the call on the car if
 * DiPlay goes away mid-call.
 */
object BydCarPlayCall {
    private const val TAG = "DiPlay-BYD-Call"

    private val shell = BydAdbShell(TAG)
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "diplay-carplay-call").apply { isDaemon = true } }
    private val state = CarPlayCallState()
    @Volatile private var context: Context? = null
    private var shown: CarPlayCallCard? = null // writer thread
    private var watcherRunning = false // writer thread
    private var watcherToken: String? = null // writer thread; unique for each displayed call lifetime

    fun attach(appContext: Context) {
        context = appContext.applicationContext
    }

    /** The current call, followed even while the setting is off so the wheel's call keys can act on it. */
    fun current(): CarPlayCallCard? = synchronized(state) { state.current() }

    fun onFrame(frame: Iap2Frame) {
        val card = synchronized(state) {
            if (!state.accept(frame)) return
            state.current()
        }
        Log.i(TAG, "CarPlay call ${card?.phase ?: "ended"}")
        val app = context ?: return
        if (BydOutputSettings.carPlayCalls(app)) writer.execute { apply(app, card) }
    }

    /** The setting changed: show the current call now, or end the one DiPlay showed. */
    fun settingChanged(enabled: Boolean) {
        val app = context ?: return
        val card = if (enabled) current() else null
        writer.execute { apply(app, card) }
    }

    /** The session ended: forget the calls and end the one DiPlay showed. */
    fun end() {
        synchronized(state) { state.clear() }
        val app = context ?: return
        writer.execute { apply(app, null) }
    }

    private fun apply(app: Context, wanted: CarPlayCallCard?) {
        if (wanted != null && !BydOutputSettings.carPlayCalls(app)) return
        // Only the newest state matters; older queued ones are skipped.
        if (wanted != current() && !(wanted == null && !BydOutputSettings.carPlayCalls(app))) return
        if (wanted == shown) return
        if (wanted == null) {
            if (shown == null) return
            val token = watcherToken ?: return
            if (run(app, "end - $token")) {
                shown = null
                watcherToken = null
                watcherRunning = false
            }
            return
        }
        val name = Base64.encodeToString(wanted.name.toByteArray(Charsets.UTF_8), Base64.NO_WRAP).ifEmpty { "-" }
        val phase = wanted.phase.name.lowercase()
        val token = watcherToken ?: CarPlayCallWatchOwnership.newToken(app.packageName)
        val since = wanted.activeSinceMillis?.let { it / 1000 } ?: 0
        if (!run(app, "$phase $name $token $since")) return
        watcherToken = token
        shown = wanted
        if (!watcherRunning) {
            val apk = app.applicationInfo.sourceDir
            val tool = "CLASSPATH=$apk app_process /system/bin ${BydCarPlayCallTool::class.java.name} watch $token ${app.packageName} ${android.os.Process.myPid()}"
            watcherRunning = shell.run(app, "nohup sh -c '$tool' >/dev/null 2>&1 </dev/null &") != null
        }
    }

    private fun run(app: Context, args: String): Boolean {
        val apk = app.applicationInfo.sourceDir
        val output = shell.run(app, "CLASSPATH=$apk app_process /system/bin ${BydCarPlayCallTool::class.java.name} $args")
            ?: return false
        // A firmware without one of the features still shows the rest; only a tool that did not run is retried.
        val failed = output.lineSequence().map { it.trim() }.filter { it.contains('=') }
            .filter { line -> line.substringAfter('=').trim().toIntOrNull() != 0 }.toList()
        if (failed.isNotEmpty()) Log.w(TAG, "call writes not accepted: ${failed.joinToString().take(200)}")
        return failed.none { it.startsWith("write=") }
    }
}

/**
 * Runs under the head unit's adb shell through app_process, not in DiPlay. Writes what BYD's CarPlay app
 * writes for a call, resolving the feature ids on the car (they differ between CAN and CAN FD cars):
 * `ringing|dialing|active <base64 name>` and `end -`. `watch <token> <package>` sends the call time
 * every second while the token says `active <start seconds>`, and ends the call on the car when DiPlay's
 * process is gone; it exits once the token is removed. Prints "name=result" per write; 0 is success.
 */
object BydCarPlayCallTool {
    private val ownership = CarPlayCallWatchOwnership()
    private const val INSTRUMENT = 1007
    private const val AUDIO = 1002
    private const val SETTING = 1023
    private const val IDS = "android.hardware.bydauto.BYDAutoFeatureIds"
    private const val INSTRUMENT_IN_CALL = 1
    private const val INSTRUMENT_ENDED = 2
    private const val BT_DIALING = 1
    private const val BT_RINGING = 2
    private const val BT_ACTIVE = 3
    private const val BT_IDLE = 5
    private const val AUDIO_IN_CALL = 0
    private const val AUDIO_IDLE = 1
    private const val MAX_WATCH_MILLIS = 6L * 60 * 60 * 1000

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val device = device()
            when (args.getOrNull(0)) {
                "watch" -> watch(device, args.getOrNull(1) ?: return,
                    args.getOrNull(2) ?: return, args.getOrNull(3) ?: return)
                "end" -> ownership.retire(args.getOrNull(2) ?: return) { end(device) }
                else -> ownership.claim(args.getOrNull(2) ?: return) { token ->
                    call(device, args[0], args.getOrNull(1))
                    val since = args.getOrNull(3)?.toLongOrNull() ?: 0
                    token.writeText("${args[0]} $since")
                }
            }
        } catch (error: Throwable) {
            println("write=ERR ${describe(error)}")
        } finally {
            // ActivityThread leaves threads behind; without this the shell command would not return.
            System.exit(0)
        }
    }

    private fun call(device: Device, phase: String, encodedName: String?) {
        val bt = when (phase) {
            "ringing" -> BT_RINGING
            "dialing" -> BT_DIALING
            "active" -> BT_ACTIVE
            else -> throw IllegalArgumentException("unknown phase $phase")
        }
        val name = encodedName?.takeIf { it != "-" }
            ?.let { String(java.util.Base64.getDecoder().decode(it), Charsets.UTF_8) }.orEmpty()
        device.set("audio", AUDIO, "$IDS\$Audio", "AUDIO_CARPLAY_CALL_STATUS", AUDIO_IN_CALL)
        device.set("car", SETTING, IDS, "SET_CALL_STATE_SET", 1)
        device.set("bt", SETTING, IDS, "SET_CMD_BTCALL_STATE_SET", bt)
        device.set("state", INSTRUMENT, IDS, "INSTRUMENT_CALL_STATE_SET", INSTRUMENT_IN_CALL)
        device.setBytes("name", INSTRUMENT, IDS, "INSTRUMENT_CALL_INFO_SET", CarPlayCallState.nameBytes(name))
    }

    private fun end(device: Device) {
        device.set("car", SETTING, IDS, "SET_CALL_STATE_SET", 0)
        device.set("bt", SETTING, IDS, "SET_CMD_BTCALL_STATE_SET", BT_IDLE)
        device.set("state", INSTRUMENT, IDS, "INSTRUMENT_CALL_STATE_SET", INSTRUMENT_ENDED)
        device.set("audio", AUDIO, "$IDS\$Audio", "AUDIO_CARPLAY_CALL_STATUS", AUDIO_IDLE)
    }

    private fun watch(device: Device, tokenPath: String, packageName: String, processId: String) {
        val token = java.io.File(tokenPath)
        val started = System.currentTimeMillis()
        while (token.exists() && System.currentTimeMillis() - started < MAX_WATCH_MILLIS) {
            if (!running(packageName, processId)) {
                ownership.retire(tokenPath) { end(device) }
                return
            }
            val current = ownership.update(tokenPath) {
                val parts = runCatching { it.readText().trim().split(' ') }.getOrDefault(emptyList())
                val since = parts.getOrNull(1)?.toLongOrNull() ?: 0
                if (parts.firstOrNull() == "active" && since > 0) {
                    val seconds = (System.currentTimeMillis() / 1000 - since).coerceIn(0, 99L * 3600 + 3599)
                    device.quiet(INSTRUMENT, IDS, "INSTRUMENT_CALL_TIME_HOUR_SET", (seconds / 3600).toInt())
                    device.quiet(INSTRUMENT, IDS, "INSTRUMENT_CALL_TIME_MINUTE_SET", (seconds / 60 % 60).toInt())
                    device.quiet(INSTRUMENT, IDS, "INSTRUMENT_CALL_TIME_SECOND_SET", (seconds % 60).toInt())
                }
            }
            if (!current) { ownership.retire(tokenPath) {}; return }
            Thread.sleep(1000)
        }
        ownership.retire(tokenPath) { end(device) }
    }

    private fun running(packageName: String, processId: String): Boolean = runCatching {
        val process = ProcessBuilder("pidof", packageName).redirectErrorStream(true).start()
        val pid = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        pid.split(Regex("\\s+")).contains(processId)
    }.getOrDefault(true)

    @SuppressLint("PrivateApi")
    private fun device(): Device {
        runCatching { android.os.Looper.prepareMainLooper() }
        val thread = Class.forName("android.app.ActivityThread")
        val main = thread.getMethod("systemMain").invoke(null)
        val context = thread.getMethod("getSystemContext").invoke(main)
        val deviceClass = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice")
        // getInstance checks a BYD permission on the caller's side only; autoservice accepts the shell
        // user, so build the device the way getInstance does. Its setMediaState/setMediaInfo write any
        // device's feature.
        val instance = try {
            deviceClass.getMethod("getInstance", Context::class.java).invoke(null, context)
        } catch (_: InvocationTargetException) {
            deviceClass.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
        }
        return Device(instance, deviceClass)
    }

    private class Device(private val instance: Any, deviceClass: Class<*>) {
        private val setInt = deviceClass.getMethod("setMediaState", Int::class.java, Int::class.java, Int::class.java)
        private val setBuffer = deviceClass.getMethod("setMediaInfo", Int::class.java, Int::class.java, ByteArray::class.java)

        fun set(label: String, device: Int, idClass: String, idName: String, value: Int) {
            val id = featureId(idClass, idName) ?: return println("$label=ERR no $idName")
            println("$label=${setInt.invoke(instance, device, id, value)}")
        }

        fun setBytes(label: String, device: Int, idClass: String, idName: String, value: ByteArray) {
            val id = featureId(idClass, idName) ?: return println("$label=ERR no $idName")
            println("$label=${setBuffer.invoke(instance, device, id, value)}")
        }

        fun quiet(device: Int, idClass: String, idName: String, value: Int) {
            val id = featureId(idClass, idName) ?: return
            runCatching { setInt.invoke(instance, device, id, value) }
        }

        private fun featureId(idClass: String, idName: String): Int? =
            runCatching { Class.forName(idClass).getField(idName).getInt(null) }.getOrNull()
    }

    private fun describe(error: Throwable): String {
        val cause = error.cause ?: error
        return cause.javaClass.name + (cause.message?.let { ": $it" } ?: "")
    }
}
