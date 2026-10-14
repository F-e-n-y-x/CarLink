package com.carlink.core.connection

import com.carlink.core.core.LinkNotice
import com.carlink.core.core.LinkNoticeKind
import com.carlink.core.core.LinkProblem
import com.carlink.core.core.LinkRecovery
import com.carlink.core.core.LinkStage
import com.carlink.core.core.LinkState
import com.carlink.core.settings.WirelessPath

/** [SessionState] in the terms the current screens use. */
fun SessionState.toLinkState(): LinkState = when (this) {
    SessionState.Idle -> LinkState.Idle
    is SessionState.Blocked -> LinkState(LinkStage.FAILED, request.engine, request.transport, request.phoneName,
        problem = problems.first().toLinkProblem())
    is SessionState.Failed -> LinkState(LinkStage.FAILED, request.engine, request.transport, request.phoneName,
        detail = detail, problem = reason.toLinkProblem())
    is SessionState.Active -> activeLinkState().copy(notice = linkNotice())
}

private fun SessionState.Active.activeLinkState(): LinkState = when (val p = phase) {
    SessionState.Phase.Starting -> LinkState(LinkStage.WAITING, request.engine, request.transport, request.phoneName, 1, STEPS,
        detail = notice?.message)
    is SessionState.Phase.Progress -> {
        val silent = p.stage == ProgressStage.PHONE_NOT_ANSWERING
        LinkState(
            if (silent || p.stage == ProgressStage.WAITING_FOR_PHONE) LinkStage.WAITING else LinkStage.CONNECTING,
            request.engine, request.transport, request.phoneName, stepOf(p.stage), STEPS,
            detail = if (silent) DETAIL_PHONE_NOT_ANSWERING else notice?.message)
    }
    // A retry stays on the step it happened in, with its reason (RELIABILITY_AUDIT K4): a dial
    // the iPhone never answered is not "phone answered". A phone that stays silent across
    // retries is still "not answering".
    is SessionState.Phase.Retrying -> {
        val silent = notice?.kind == Notice.Kind.PHONE_NOT_ANSWERING && stepOf(p.stage) == 1
        LinkState(if (silent) LinkStage.WAITING else LinkStage.CONNECTING, request.engine, request.transport, request.phoneName,
            stepOf(p.stage), STEPS,
            detail = if (silent) DETAIL_PHONE_NOT_ANSWERING else notice?.message ?: p.detail?.let { "Retrying: $it" } ?: "retrying")
    }
    SessionState.Phase.Connected -> LinkState(LinkStage.CONNECTED, request.engine, request.transport, request.phoneName, STEPS, STEPS)
}

/**
 * [SessionState.Active.notice] (or a retry under way) as [LinkNotice], which the screens word for
 * the driver. A retry that moved to the next wireless path reads as "trying <path> instead".
 */
private fun SessionState.Active.linkNotice(): LinkNotice? {
    if (phase == SessionState.Phase.Connected) return null
    notice?.let { n ->
        return when (n.kind) {
            Notice.Kind.PHONE_NOT_ANSWERING -> LinkNotice(LinkNoticeKind.NOT_ANSWERING)
            Notice.Kind.SLOW -> LinkNotice(LinkNoticeKind.SLOW)
            Notice.Kind.RECOVERING -> LinkNotice(LinkNoticeKind.RECOVERING, n.action?.let { recoveryOf(it, path) })
        }
    }
    val p = phase as? SessionState.Phase.Retrying ?: return null
    val movedPath = p.reason.pathRelated && attempt > 1 && p.attempt == attempt && path != null
    return if (movedPath) LinkNotice(LinkNoticeKind.RECOVERING, tryPath(path))
    else LinkNotice(LinkNoticeKind.RETRYING, reason = p.reason.toLinkProblem())
}

private fun recoveryOf(action: RecoveryAction, path: WirelessPath?): LinkRecovery? = when (action) {
    RecoveryAction.NUDGE -> LinkRecovery.WAKE_PHONE
    RecoveryAction.RESWITCH_USB -> LinkRecovery.RESTART_USB
    RecoveryAction.RETRY_STEP -> LinkRecovery.RETRY
    RecoveryAction.RESET_WIFI_DIRECT -> LinkRecovery.FRESH_WIFI_DIRECT
    // The state already holds the next path when the notice is shown.
    RecoveryAction.NEXT_PATH -> tryPath(path)
    RecoveryAction.ASK_REPLUG -> LinkRecovery.REPLUG
    RecoveryAction.GIVE_UP -> null
}

private fun tryPath(path: WirelessPath?): LinkRecovery = when (path) {
    WirelessPath.WIFI_DIRECT -> LinkRecovery.TRY_WIFI_DIRECT
    WirelessPath.CAR_HOTSPOT -> LinkRecovery.TRY_CAR_HOTSPOT
    WirelessPath.SAME_WIFI -> LinkRecovery.TRY_SAME_WIFI
    null -> LinkRecovery.TRY_OTHER_PATH
}

/** [LinkState.detail] while a phone has not answered for a while; Home explains what to check. */
const val DETAIL_PHONE_NOT_ANSWERING = "phone_not_answering"

/** Home's timeline: waiting, handshake, joining Wi-Fi, starting the picture. */
private const val STEPS = 4

private fun stepOf(stage: ProgressStage) = when (stage) {
    ProgressStage.WAITING_FOR_PHONE, ProgressStage.PHONE_NOT_ANSWERING -> 1
    ProgressStage.HANDSHAKE -> 2
    ProgressStage.JOINING_WIFI -> 3
    ProgressStage.STARTING_PROJECTION -> 4
}

fun Problem.toLinkProblem(): LinkProblem = when (this) {
    Problem.BLUETOOTH_OFF -> LinkProblem.BLUETOOTH_OFF
    Problem.PHONE_NOT_PAIRED -> LinkProblem.PHONE_NOT_PAIRED
    Problem.WIFI_OFF -> LinkProblem.WIFI_OFF
    Problem.LOCATION_OFF -> LinkProblem.LOCATION_OFF
    Problem.PERMISSIONS -> LinkProblem.PERMISSIONS
    Problem.OVERLAY -> LinkProblem.OVERLAY
    Problem.VPN_CONSENT -> LinkProblem.VPN_CONSENT
    Problem.CARPLAY_AUTH_MISSING -> LinkProblem.CARPLAY_AUTH_MISSING
    Problem.NO_IPHONE_SELECTED -> LinkProblem.NO_IPHONE_SELECTED
    Problem.SAME_WIFI_DETAILS -> LinkProblem.SAME_WIFI_DETAILS_MISSING
    Problem.CAR_HOTSPOT_DETAILS -> LinkProblem.CAR_HOTSPOT_DETAILS_MISSING
    Problem.CAR_HOTSPOT_OFF -> LinkProblem.CAR_HOTSPOT_OFF
    Problem.EXTERNAL_BLUETOOTH -> LinkProblem.EXTERNAL_BLUETOOTH
}

fun FailureReason.toLinkProblem(): LinkProblem = when (this) {
    FailureReason.PHONE_REFUSED_WIFI -> LinkProblem.PHONE_REFUSED_WIFI
    FailureReason.PHONE_NEVER_REACHED_CAR -> LinkProblem.PHONE_NEVER_REACHED_CAR
    FailureReason.LINK_TOO_WEAK -> LinkProblem.LINK_TOO_WEAK
    FailureReason.WIFI_SETUP_FAILED -> LinkProblem.ENGINE
    FailureReason.BLUETOOTH -> LinkProblem.BLUETOOTH
    FailureReason.EXTERNAL_BLUETOOTH -> LinkProblem.EXTERNAL_BLUETOOTH
    FailureReason.PHONE_DID_NOT_ANSWER -> LinkProblem.PHONE_DID_NOT_ANSWER
    FailureReason.USB -> LinkProblem.USB
    FailureReason.BLOCKED -> LinkProblem.PERMISSIONS
    FailureReason.AUTHENTICATION -> LinkProblem.CARPLAY_AUTH_MISSING
    FailureReason.ENGINE -> LinkProblem.ENGINE
}
