package com.carlink.app.core

/** The projection engine that serves a phone. */
enum class Engine {
    /** Apple CarPlay, provided by the DiPlay engine (main process). */
    CARPLAY,

    /** Android Auto, provided by the DiAuto engine (":aa" process). */
    ANDROID_AUTO,
}

/** How the phone reaches the head unit. */
enum class Transport { USB, WIRELESS }

/** What CarLink concluded about a phone. */
enum class PhoneKind {
    IPHONE,
    ANDROID,

    /** Not enough evidence yet; never start an engine for this. */
    UNKNOWN,
    ;

    val engine: Engine?
        get() = when (this) {
            IPHONE -> Engine.CARPLAY
            ANDROID -> Engine.ANDROID_AUTO
            UNKNOWN -> null
        }
}

/** Where a session is in its life, in the driver's terms. */
enum class LinkStage {
    /** No engine is running. */
    IDLE,

    /** An engine is armed and waiting for the phone (wireless listener, USB permission). */
    WAITING,

    /** The phone answered; authentication, network and video are being set up. */
    CONNECTING,

    /** Projection is running. */
    CONNECTED,

    /** The last attempt failed; [LinkState.detail] says why. */
    FAILED,
}

/**
 * The single status CarLink shows for both engines.
 *
 * [step] and [stepCount] place [LinkStage.CONNECTING] on the stage timeline; [detail] is the
 * engine's own, already localized or technical, description of the current step or failure.
 */
data class LinkState(
    val stage: LinkStage = LinkStage.IDLE,
    val engine: Engine? = null,
    val transport: Transport? = null,
    val phoneName: String? = null,
    val step: Int = 0,
    val stepCount: Int = 0,
    val detail: String? = null,
) {
    val isActive: Boolean get() = stage != LinkStage.IDLE && stage != LinkStage.FAILED

    companion object {
        val Idle = LinkState()
    }
}
