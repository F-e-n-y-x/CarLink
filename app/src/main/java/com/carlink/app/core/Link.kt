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
 * Why an attempt could not start or failed, so the UI can offer the matching fix
 * (DESIGN_SPEC §4.2). [ENGINE] means the engine reported a failure; see [LinkState.detail].
 */
enum class LinkProblem {
    CARPLAY_AUTH_MISSING,
    NO_IPHONE_SELECTED,
    CAR_HOTSPOT_DETAILS_MISSING,
    CAR_HOTSPOT_OFF,
    SAME_WIFI_DETAILS_MISSING,
    // Pre-flight (connection v2): each has a one-tap fix.
    BLUETOOTH_OFF,
    PHONE_NOT_PAIRED,
    WIFI_OFF,
    LOCATION_OFF,
    PERMISSIONS,
    OVERLAY,
    VPN_CONSENT,
    // Session failures (connection v2), precise enough to say what to do.
    PHONE_REFUSED_WIFI,
    PHONE_NEVER_REACHED_CAR,
    LINK_TOO_WEAK,
    PHONE_DID_NOT_ANSWER,
    BLUETOOTH,
    USB,
    ENGINE,
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
    val problem: LinkProblem? = null,
) {
    val isActive: Boolean get() = stage != LinkStage.IDLE && stage != LinkStage.FAILED

    companion object {
        val Idle = LinkState()
    }
}
