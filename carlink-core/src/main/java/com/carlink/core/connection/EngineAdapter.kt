package com.carlink.core.connection

import android.content.Intent
import com.carlink.core.core.Engine
import com.carlink.core.core.Transport
import com.carlink.core.settings.WirelessPath

/**
 * The contract between [SessionManager] (one session, one engine at a time, path policy,
 * pre-flight) and the engine adapters ([CarPlayAdapter], [AndroidAutoAdapter]). Main process.
 * See design/REBUILD_V2.md §4.
 *
 * An adapter starts exactly the session it is handed, reports what happens in [AdapterEvent]s on
 * the main thread, and never decides paths or retries across paths: it reports what failed and
 * whether a different path could help, and [SessionManager] decides.
 */
interface EngineAdapter {
    val engine: Engine

    /**
     * Starts [plan]. Settings for the session were already written by [SessionManager]. Reports
     * through [listener] until [AdapterEvent.Ended] or a terminal [AdapterEvent.Failed].
     * Only called when [hasSession] is false (the previous session's stop completed).
     */
    fun start(plan: SessionPlan, listener: (AdapterEvent) -> Unit)

    /**
     * Ends the current session (any stage) and calls [onStopped] on the main thread once the
     * engine has fully released its resources (service, Wi-Fi Direct group, sockets), so the next
     * session can start safely. Calls [onStopped] at once when nothing runs.
     */
    fun stop(onStopped: () -> Unit)

    /** True from [start] until the engine's teardown completes, including internal restarts. */
    fun hasSession(): Boolean

    /**
     * Applies [action] to the running session in place, when the engine has a way to
     * ([RecoveryAction.NUDGE], [RecoveryAction.RESWITCH_USB]). Returns false when it has none;
     * [SessionManager] then only shows why it waits, or restarts the attempt itself.
     */
    fun recover(action: RecoveryAction): Boolean = false
}

/**
 * What [SessionManager]'s stall watchdog does when a phase takes longer than its deadline
 * ([StallPlaybook] says which, in which order).
 */
enum class RecoveryAction {
    /** Wake the phone again (Android Auto: one more Bluetooth poke). Nothing is torn down. */
    NUDGE,
    /** Android Auto over USB: send the accessory switch again so the phone re-enumerates. */
    RESWITCH_USB,
    /** Stop the engine and start the same path again. */
    RETRY_STEP,
    /** Same as [RETRY_STEP]; the stop releases the Wi-Fi Direct group, so the phone gets a fresh one. */
    RESET_WIFI_DIRECT,
    /** Give up on this wireless path and try the next one. */
    NEXT_PATH,
    /** Fail with "unplug the phone and plug it in again". */
    ASK_REPLUG,
    /** Fail with the phase's reason. */
    GIVE_UP,
}

/** One session to start. */
data class SessionPlan(
    val engine: Engine,
    val transport: Transport,
    /** Wireless only: the path [SessionManager] chose and wrote into the engine's settings. */
    val path: WirelessPath?,
    /** Bluetooth address of the phone, when known (wireless always; USB when remembered). */
    val phoneAddress: String?,
    val phoneName: String?,
    /** USB only: the attach intent that identifies the device. */
    val usbAttach: Intent? = null,
    /** 1 for the first attempt of a request, 2+ for a retry on another path. */
    val attempt: Int = 1,
)

/** What an engine reports during a session. */
sealed interface AdapterEvent {
    /** The engine is working through its stages; [step] of [stepCount] for Home's timeline. */
    data class Progress(val step: Int, val stepCount: Int, val stage: ProgressStage) : AdapterEvent

    /**
     * The engine is retrying on its own after [reason]; not a failure yet. [detail] says what
     * happened in the driver's terms ("the iPhone did not answer over Bluetooth").
     */
    data class Retrying(val attempt: Int, val reason: FailureReason, val detail: String? = null) : AdapterEvent

    /** Projection is up (first frame shown). */
    data object Connected : AdapterEvent

    /** The session ended without an error (phone left, driver exited, engine closed). */
    data class Ended(val byUser: Boolean) : AdapterEvent

    /** Terminal for this session: the engine stopped trying. */
    data class Failed(val reason: FailureReason, val detail: String? = null) : AdapterEvent
}

/** Driver-facing progress, the same words for both engines. */
enum class ProgressStage {
    /** Waiting for the phone to answer (Bluetooth wake, USB permission, phone locked). */
    WAITING_FOR_PHONE,
    /** Bluetooth handshake / iAP2 / authentication. */
    HANDSHAKE,
    /** The phone is joining the car's Wi-Fi link. */
    JOINING_WIFI,
    /** Network link up; projection protocol and video starting. */
    STARTING_PROJECTION,
    /** Still [WAITING_FOR_PHONE] after [SessionManager.NOT_ANSWERING_MILLIS]: tell the driver what to check. */
    PHONE_NOT_ANSWERING,
}

/**
 * Why something failed, precise enough for [SessionManager] to pick a fix or another path.
 * [pathRelated] is true only when a different wireless path could succeed.
 */
enum class FailureReason(val pathRelated: Boolean) {
    /** Android Auto: the phone refused the network it was handed (wrong/missing password, BSSID). */
    PHONE_REFUSED_WIFI(true),
    /** CarPlay: the iPhone authenticated but never reached the car over Wi-Fi (no AirPlay TCP). */
    PHONE_NEVER_REACHED_CAR(true),
    /** Video stalled or never arrived on an established link. */
    LINK_TOO_WEAK(true),
    /** The Wi-Fi Direct group or hotspot could not be created or is owned by another app. */
    WIFI_SETUP_FAILED(true),
    /** Bluetooth: phone not reachable / not paired / RFCOMM failed. */
    BLUETOOTH(false),
    /**
     * This head unit's Bluetooth is an external module the app cannot reach
     * (detect/ExternalBluetoothProbe); no wireless path can work until a module route is ported.
     */
    EXTERNAL_BLUETOOTH(false),
    /**
     * Wireless through the stereo's Bluetooth module (the driver's experimental opt-in) could not
     * reach the module: no daemon, the serial port refused, or the stereo's own Car Link app holds it.
     */
    EXTERNAL_MODULE(false),
    /** The phone never answered within the engine's time budget. */
    PHONE_DID_NOT_ANSWER(false),
    /** USB permission denied, interface claim failed, accessory switch failed. */
    USB(false),
    /** A permission, consent or system switch is missing (engine-side detection). */
    BLOCKED(false),
    /** CarPlay authentication (MFi) failed. */
    AUTHENTICATION(false),
    /** Anything else the engine reported. */
    ENGINE(false),
}
