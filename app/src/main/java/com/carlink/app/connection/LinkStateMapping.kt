package com.carlink.app.connection

import com.carlink.app.core.LinkProblem
import com.carlink.app.core.LinkStage
import com.carlink.app.core.LinkState

/** [SessionState] in the terms the current screens use. */
fun SessionState.toLinkState(): LinkState = when (this) {
    SessionState.Idle -> LinkState.Idle
    is SessionState.Blocked -> LinkState(LinkStage.FAILED, request.engine, request.transport, request.phoneName,
        problem = problems.first().toLinkProblem())
    is SessionState.Failed -> LinkState(LinkStage.FAILED, request.engine, request.transport, request.phoneName,
        detail = detail, problem = reason.toLinkProblem())
    is SessionState.Active -> when (val p = phase) {
        SessionState.Phase.Starting -> LinkState(LinkStage.WAITING, request.engine, request.transport, request.phoneName, 1, STEPS)
        is SessionState.Phase.Progress -> LinkState(
            if (p.stage == ProgressStage.WAITING_FOR_PHONE) LinkStage.WAITING else LinkStage.CONNECTING,
            request.engine, request.transport, request.phoneName, stepOf(p.stage), STEPS)
        is SessionState.Phase.Retrying -> LinkState(LinkStage.CONNECTING, request.engine, request.transport, request.phoneName,
            2, STEPS, detail = "retrying")
        SessionState.Phase.Connected -> LinkState(LinkStage.CONNECTED, request.engine, request.transport, request.phoneName, STEPS, STEPS)
    }
}

/** Home's timeline: waiting, handshake, joining Wi-Fi, starting the picture. */
private const val STEPS = 4

private fun stepOf(stage: ProgressStage) = when (stage) {
    ProgressStage.WAITING_FOR_PHONE -> 1
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
}

fun FailureReason.toLinkProblem(): LinkProblem = when (this) {
    FailureReason.PHONE_REFUSED_WIFI -> LinkProblem.PHONE_REFUSED_WIFI
    FailureReason.PHONE_NEVER_REACHED_CAR -> LinkProblem.PHONE_NEVER_REACHED_CAR
    FailureReason.LINK_TOO_WEAK -> LinkProblem.LINK_TOO_WEAK
    FailureReason.WIFI_SETUP_FAILED -> LinkProblem.ENGINE
    FailureReason.BLUETOOTH -> LinkProblem.BLUETOOTH
    FailureReason.PHONE_DID_NOT_ANSWER -> LinkProblem.PHONE_DID_NOT_ANSWER
    FailureReason.USB -> LinkProblem.USB
    FailureReason.BLOCKED -> LinkProblem.PERMISSIONS
    FailureReason.AUTHENTICATION -> LinkProblem.CARPLAY_AUTH_MISSING
    FailureReason.ENGINE -> LinkProblem.ENGINE
}
