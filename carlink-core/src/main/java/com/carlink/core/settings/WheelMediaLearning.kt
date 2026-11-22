package com.carlink.core.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.andrerinas.openheadunit.HostIntegration
import java.io.Closeable
import java.util.UUID

/** Routes system media buttons to the Learn panel, including those delivered to DiAuto in :aa. */
object WheelMediaLearning {
    private const val REQUEST = "com.carlink.core.action.WHEEL_MEDIA_LEARN"
    private const val RESULT = "com.carlink.core.action.WHEEL_MEDIA_LEARNED"
    private const val TOKEN = "token"
    private const val DEADLINE = "deadline"
    private const val KEY_CODE = "key_code"

    /** Main thread. The temporary session requests neither audio focus nor playback. */
    @Suppress("DEPRECATION") // FLAG_HANDLES_MEDIA_BUTTONS matters on the older head units.
    fun listen(context: Context, durationMs: Long, onKey: (Int) -> Unit): Closeable {
        val app = context.applicationContext
        val token = UUID.randomUUID().toString()
        val deadline = SystemClock.uptimeMillis() + durationMs
        var finished = false
        fun accept(key: Int) {
            if (finished || SystemClock.uptimeMillis() >= deadline || !WheelKeys.canLearn(key)) return
            finished = true
            onKey(key)
        }
        val result = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra(TOKEN) == token) accept(intent.getIntExtra(KEY_CODE, KeyEvent.KEYCODE_UNKNOWN))
            }
        }
        ContextCompat.registerReceiver(app, result, IntentFilter(RESULT), ContextCompat.RECEIVER_NOT_EXPORTED)
        fun tellEngine(until: Long) {
            app.sendBroadcast(Intent(REQUEST).setPackage(app.packageName)
                .putExtra(TOKEN, token).putExtra(DEADLINE, until))
        }
        tellEngine(deadline)

        val press = MediaPress(::accept)
        val session = runCatching { MediaSession(app, "CarLink Wheel Learn") }.getOrNull()
        if (session != null) {
            runCatching {
                session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
                session.setPlaybackState(PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .setState(PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f).build())
                session.setCallback(object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                        val key = IntentCompat.getParcelableExtra(mediaButtonIntent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                        return !finished && SystemClock.uptimeMillis() < deadline && key != null && press.handle(key)
                    }
                }, Handler(Looper.getMainLooper()))
                session.isActive = true
            }.onFailure { runCatching { session.release() } }
        }
        return Closeable {
            finished = true
            tellEngine(0L)
            app.unregisterReceiver(result)
            runCatching { session?.release() }
        }
    }

    /** Installed once in :aa. A runtime receiver lets Learn avoid starting a stopped engine. */
    internal fun installAndroidAuto(context: Context) {
        val app = context.applicationContext
        var currentToken: String? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val token = intent.getStringExtra(TOKEN) ?: return
                val deadline = intent.getLongExtra(DEADLINE, 0L)
                if (deadline <= SystemClock.uptimeMillis()) {
                    if (currentToken == token) {
                        currentToken = null
                        HostIntegration.onLearnMediaButton = null
                    }
                    return
                }
                currentToken = token
                val press = MediaPress { key ->
                    app.sendBroadcast(Intent(RESULT).setPackage(app.packageName)
                        .putExtra(TOKEN, token).putExtra(KEY_CODE, key))
                }
                HostIntegration.onLearnMediaButton = { key ->
                    if (SystemClock.uptimeMillis() < deadline) press.handle(key)
                    else {
                        currentToken = null
                        HostIntegration.onLearnMediaButton = null
                        false
                    }
                }
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(REQUEST), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private class MediaPress(private val onKey: (Int) -> Unit) {
        private var pressed = KeyEvent.KEYCODE_UNKNOWN

        fun handle(key: KeyEvent): Boolean {
            val code = key.keyCode
            if (!WheelKeys.canLearn(code) || code == KeyEvent.KEYCODE_BACK) return false
            return when (key.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (key.repeatCount == 0) pressed = code
                    true
                }
                KeyEvent.ACTION_UP -> {
                    if (pressed != code) return false
                    pressed = KeyEvent.KEYCODE_UNKNOWN
                    if (!key.isCanceled) onKey(code)
                    true
                }
                else -> false
            }
        }
    }
}
