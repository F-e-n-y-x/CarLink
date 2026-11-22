package com.carlink.core.entry

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.carlink.core.connection.Connection
import com.carlink.core.connection.SessionState
import com.carlink.core.connection.StereoVoiceKey
import com.carlink.core.diagnostics.EventLog

/**
 * The stereo's voice key as `android.intent.action.VOICE_COMMAND` (Joying's CarService sends it,
 * joying-qcm6125.md §5; once the driver picks CarLink for it). During a session it opens the
 * phone's assistant (Siri or Google Assistant); otherwise it hands the press to the system's
 * assistant. No window: it finishes at once, in its own task, so the projection stays in front.
 */
class VoiceCommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = (Connection.get(this).state.value as? SessionState.Active)?.request?.engine
        val sent = engine != null && StereoVoiceKey.press(this, engine)
        EventLog.add("CarLink", "stereo voice key: ${engine?.let { "$it assistant, sent=$sent" } ?: "no session, system assistant"}")
        if (engine == null) {
            runCatching { startActivity(Intent(VOICE_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        finish()
    }

    private companion object {
        /** Intent.ACTION_VOICE_ASSIST is hidden from apps; the system's assistant answers this action. */
        const val VOICE_ASSIST = "android.intent.action.VOICE_ASSIST"
    }
}
