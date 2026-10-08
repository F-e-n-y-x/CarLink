package com.carlink.app.engine

import android.content.Context
import android.content.Intent
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkState
import com.carlink.app.core.Transport
import com.carlink.app.entry.UsbRouterActivity

/**
 * What [EngineCoordinator] needs from an engine. Production uses [defaultEngineFactory], which
 * wraps [CarPlayEngine] and [AndroidAutoEngine] unchanged; the seam exists so the coordinator's
 * rules (one engine at a time, no restart on USB re-enumeration, ...) are unit tested.
 */
internal interface EngineHandle {
    fun start(transport: Transport, address: String?, phoneName: String?, usbAttach: Intent?)

    fun stop()

    /** The engine's state when the coordinator is created (a session it did not start). */
    fun current(): LinkState = LinkState.Idle

    /** Re-syncs with an engine that may already be running. */
    fun refresh() {}
}

/** Creates the handle for an engine; `onState` receives every state the engine reports. */
internal typealias EngineFactory = (context: Context, engine: Engine, onState: (LinkState) -> Unit) -> EngineHandle

internal val defaultEngineFactory: EngineFactory = { context, engine, onState ->
    when (engine) {
        Engine.CARPLAY -> {
            val carPlay = CarPlayEngine(context, onState)
            object : EngineHandle {
                override fun start(transport: Transport, address: String?, phoneName: String?, usbAttach: Intent?) =
                    carPlay.start(transport, address, phoneName, usbAttach)
                override fun stop() = carPlay.stop()
                override fun current() = carPlay.current()
            }
        }
        Engine.ANDROID_AUTO -> {
            val androidAuto = AndroidAutoEngine(context, onState)
            object : EngineHandle {
                override fun start(transport: Transport, address: String?, phoneName: String?, usbAttach: Intent?) =
                    androidAuto.start(transport, address, phoneName, usbAttach?.let(UsbRouterActivity::usbDevice))
                override fun stop() = androidAuto.stop()
                override fun refresh() = androidAuto.refresh()
            }
        }
    }
}
