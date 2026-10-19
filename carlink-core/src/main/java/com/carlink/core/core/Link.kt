package com.carlink.core.core

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
    /**
     * This stereo's Bluetooth is a separate module apps cannot use, so wireless CarPlay and
     * Android Auto cannot start (pre-flight or engine-reported); a USB cable works.
     */
    EXTERNAL_BLUETOOTH,
    /** Wireless through the stereo's Bluetooth module was tried (opt-in) and could not reach it; USB works. */
    EXTERNAL_MODULE,
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
    /** Why the attempt is taking long and what is being done about it; null while on time. */
    val notice: LinkNotice? = null,
) {
    val isActive: Boolean get() = stage != LinkStage.IDLE && stage != LinkStage.FAILED

    companion object {
        val Idle = LinkState()
    }
}

/**
 * Why an attempt waits, in terms the screens word for the driver (never an engine string):
 * the session layer's notice, or a retry it is running.
 */
data class LinkNotice(
    val kind: LinkNoticeKind,
    /** What is being done about it, when something is. */
    val recovery: LinkRecovery? = null,
    /** For [LinkNoticeKind.RETRYING]: what went wrong, when known. */
    val reason: LinkProblem? = null,
)

enum class LinkNoticeKind {
    /** The phone has been silent for a while (wireless or USB). */
    NOT_ANSWERING,
    /** A later step is slower than this phone usually is. */
    SLOW,
    /** A step passed its deadline and a recovery ([LinkNotice.recovery]) runs. */
    RECOVERING,
    /** The engine is trying again after [LinkNotice.reason]. */
    RETRYING,
    /**
     * CarPlay is late in the picture step: on iOS 27 the iPhone plays sound but never sends a frame
     * (DiPlay #249, #975). Offered only while DiPlay's Direct video output is off.
     */
    NO_PICTURE,
}

/** What CarLink does about a slow step, as the driver is told it. */
enum class LinkRecovery {
    WAKE_PHONE,
    RESTART_USB,
    RETRY,
    FRESH_WIFI_DIRECT,
    TRY_WIFI_DIRECT,
    TRY_CAR_HOTSPOT,
    TRY_SAME_WIFI,
    /** Some other connection (the next path is not known). */
    TRY_OTHER_PATH,
    REPLUG,
}
