package com.carlink.app.engine

import android.content.Context
import android.content.Intent
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.CommManager.ConnectionState
import com.carlink.app.engine.AndroidAutoProtocol as P
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Runs in the ":aa" process: mirrors DiAuto's CommManager connection state to the main process.
 */
internal object AndroidAutoStateReporter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            App.provide(app).commManager.connectionState.collectLatest { report(app, it) }
        }
    }

    fun report(context: Context) {
        report(context.applicationContext, App.provide(context).commManager.connectionState.value)
    }

    private fun report(context: Context, state: ConnectionState) {
        val (name, message) = when (state) {
            is ConnectionState.Disconnected -> P.CONNECTION_DISCONNECTED to null
            ConnectionState.Connecting -> P.CONNECTION_CONNECTING to null
            ConnectionState.Connected -> P.CONNECTION_CONNECTED to null
            ConnectionState.StartingTransport -> P.CONNECTION_STARTING_TRANSPORT to null
            ConnectionState.HandshakeComplete -> P.CONNECTION_HANDSHAKE_COMPLETE to null
            ConnectionState.TransportStarted -> P.CONNECTION_TRANSPORT_STARTED to null
            is ConnectionState.Error -> P.CONNECTION_ERROR to state.message
        }
        context.sendBroadcast(Intent(P.ACTION_STATE).setPackage(context.packageName)
            .putExtra(P.EXTRA_CONNECTION, name)
            .putExtra(P.EXTRA_MESSAGE, message)
            .putExtra(P.EXTRA_SERVICE_RUNNING, AndroidAutoCommandReceiver.isServiceRunning(context)))
    }
}
