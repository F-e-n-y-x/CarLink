package com.carlink.core.connection

import android.content.Context
import android.content.Intent
import com.carlink.core.connection.aa.AaCommandReceiver
import com.carlink.core.connection.aa.AaProtocol
import com.carlink.core.core.Engine
import com.shilapi.xcertplay.CarPlayHostIntegration

/** Opens the phone's assistant in the running session, as a learned wheel voice button does. */
internal object StereoVoiceKey {
    fun press(context: Context, engine: Engine): Boolean = when (engine) {
        Engine.CARPLAY -> CarPlayHostIntegration.requestSiri()
        Engine.ANDROID_AUTO -> {
            // ":aa" toggles the assistant only while Android Auto's transport runs (CommManager).
            context.sendBroadcast(Intent(AaProtocol.ACTION_COMMAND).setClass(context, AaCommandReceiver::class.java)
                .putExtra(AaProtocol.EXTRA_COMMAND, AaProtocol.COMMAND_VOICE))
            true
        }
    }
}
