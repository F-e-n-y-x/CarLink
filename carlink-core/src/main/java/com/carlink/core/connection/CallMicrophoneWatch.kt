package com.carlink.core.connection

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.HandlerThread
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.carlink.core.diagnostics.EventLog

/**
 * Watches calls during a session for something else taking the microphone (DiPlay #987: a head unit
 * whose own Bluetooth hands-free link answered the call, so the CarPlay call's microphone was
 * silent; #985: echo). Every recording change during a call is logged with the audio mode and the
 * other recordings' sources (DiPlay PR #995's diagnostics); on a likely conflict the host shows a
 * short notice once per call ([CarLinkHost.onCallMicrophoneConflict]). Main process, engine-agnostic.
 */
internal class CallMicrophoneWatch(context: Context) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val handler by lazy { Handler(HandlerThread("carlink-call-mic").apply { start() }.looper) }
    private var watching = false // main thread
    private var noticed = false // handler thread

    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) = check(configs)
    }

    fun setWatching(on: Boolean) {
        val manager = audio ?: return
        if (on == watching) return
        watching = on
        runCatching {
            if (on) manager.registerAudioRecordingCallback(callback, handler) else manager.unregisterAudioRecordingCallback(callback)
        }
        handler.post { noticed = false }
    }

    private fun check(configs: List<AudioRecordingConfiguration>) {
        val mode = runCatching { audio!!.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        if (!inCall(mode) || configs.isEmpty()) {
            noticed = false
            return
        }
        // Off the main thread: the gateway-role check waits for a profile proxy (BluetoothHelper).
        val handsFree = BluetoothHelper.handsFreeLinkState(app, DeviceEnvironment.lastPhoneAddress(app))
        val conflict = conflict(mode, handsFree, configs.size)
        EventLog.add("CarLink", "call: audioMode=$mode recordings=${configs.map { it.clientAudioSource }} " +
            "stereoHandsFree=${handsFree ?: "unknown"} conflict=$conflict")
        if (conflict && !noticed) {
            noticed = true
            runCatching { CarLinkCore.config.host.onCallMicrophoneConflict(app) }
        }
    }

    companion object {
        /** Both engines use [AudioManager.MODE_IN_COMMUNICATION] for a call; [AudioManager.MODE_IN_CALL] is the stereo's own phone. */
        fun inCall(mode: Int) = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION

        /**
         * Whether the microphone is likely taken during a call in [mode]: the stereo's own phone has
         * the call (MODE_IN_CALL, which neither engine sets), its hands-free link to a phone is up
         * ([handsFree]; null when unknown), or more than one recording runs.
         * ponytail: counts recordings, assuming the engine holds one; other apps' configurations are
         * anonymized, so telling ours apart needs the engine's session id (DiPlay PR #995).
         */
        fun conflict(mode: Int, handsFree: Boolean?, recordings: Int): Boolean =
            mode == AudioManager.MODE_IN_CALL || (inCall(mode) && (handsFree == true || recordings > 1))
    }
}
