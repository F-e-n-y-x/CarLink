package com.carlink.core.connection.aa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Asks the running Android Auto session to take a new interface size without reconnecting
 * ([AaEngineHooks.setSize]). [done] gets true when it applied live, false when the session must
 * reconnect (no answer within [TIMEOUT_MILLIS] counts as false). Main thread.
 */
object AaLiveSize {
    private const val TIMEOUT_MILLIS = 1_500L

    fun request(context: Context, percent: Int, done: (live: Boolean) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        var answered = false
        lateinit var receiver: BroadcastReceiver
        fun finish(live: Boolean) {
            if (answered) return
            answered = true
            runCatching { app.unregisterReceiver(receiver) }
            done(live)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getStringExtra(AaProtocol.EXTRA_KIND) != AaProtocol.KIND_SIZE) return
                finish(intent.getStringExtra(AaProtocol.EXTRA_DETAIL) == "live=true")
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(AaProtocol.ACTION_EVENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        main.postDelayed({ finish(false) }, TIMEOUT_MILLIS)
        app.sendBroadcast(Intent(AaProtocol.ACTION_COMMAND).setClass(app, AaCommandReceiver::class.java)
            .putExtra(AaProtocol.EXTRA_COMMAND, AaProtocol.COMMAND_SET_SIZE)
            .putExtra(AaProtocol.EXTRA_SIZE_PERCENT, percent))
    }
}
