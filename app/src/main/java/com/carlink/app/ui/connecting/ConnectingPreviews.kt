package com.carlink.app.ui.connecting

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.carlink.app.R
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkStage
import com.carlink.app.core.LinkState
import com.carlink.app.core.Transport
import com.carlink.app.ui.home.CarPreviews
import com.carlink.app.ui.home.HandheldPreviews
import com.carlink.app.ui.home.PreviewFrame
import com.carlink.app.ui.theme.UiProfile

/** Builds the rows the tracker would produce for [link] at [current], with sample durations. */
@Composable
private fun sampleSteps(link: LinkState, current: Int): List<TimelineStep> {
    val count = DEFAULT_STEP_COUNT
    val durations = listOf(400L, 1_200L, 6_800L, 3_100L, 1_900L)
    return (1..count).map { n ->
        val state = when {
            link.stage == LinkStage.CONNECTED -> StepState.DONE
            n < current -> StepState.DONE
            n == current -> if (link.stage == LinkStage.FAILED) StepState.FAILED else StepState.ACTIVE
            else -> StepState.PENDING
        }
        val hint = if (state == StepState.ACTIVE && link.transport == Transport.USB && n in 2..3) {
            stringResource(R.string.carlink_connecting_hint_allow)
        } else null
        TimelineStep(n, stringResource(stepLabel(link.engine, link.transport, n, count)), state,
            if (state == StepState.DONE) durations.getOrNull(n - 1) else null, hint)
    }
}

private val usbCarPlay = LinkState(LinkStage.CONNECTING, Engine.CARPLAY, Transport.USB, "Sara's iPhone", 3, 6)
private val wirelessAa = LinkState(LinkStage.WAITING, Engine.ANDROID_AUTO, Transport.WIRELESS, "Omar's Pixel 8", 1, 6)
private val failedAa = LinkState(LinkStage.FAILED, Engine.ANDROID_AUTO, Transport.WIRELESS, "Omar's Pixel 8",
    detail = "The phone stopped the connection. Unlock it once, then try again.")
private val connected = LinkState(LinkStage.CONNECTED, Engine.CARPLAY, Transport.USB, "Sara's iPhone", 6, 6)

@Composable
private fun Sample(link: LinkState, current: Int, elapsed: Long) =
    ConnectingContent(link, sampleSteps(link, current), current, elapsed, ConnectingActions())

@CarPreviews @Composable private fun ConnectingUsbCar() = PreviewFrame(UiProfile.CAR) { Sample(usbCarPlay, 3, 7_000) }
@HandheldPreviews @Composable private fun ConnectingUsbHandheld() = PreviewFrame(UiProfile.HANDHELD) { Sample(usbCarPlay, 3, 7_000) }

@CarPreviews @Composable private fun ConnectingWaitingCar() = PreviewFrame(UiProfile.CAR) { Sample(wirelessAa, 1, 23_000) }
@HandheldPreviews @Composable private fun ConnectingWaitingHandheld() = PreviewFrame(UiProfile.HANDHELD) { Sample(wirelessAa, 1, 23_000) }

@CarPreviews @Composable private fun ConnectingFailedCar() = PreviewFrame(UiProfile.CAR) { Sample(failedAa, 4, 14_000) }
@HandheldPreviews @Composable private fun ConnectingFailedHandheld() = PreviewFrame(UiProfile.HANDHELD) { Sample(failedAa, 4, 14_000) }

@CarPreviews @Composable private fun ConnectingConnectedCar() = PreviewFrame(UiProfile.CAR) { Sample(connected, 6, 16_000) }
@HandheldPreviews @Composable private fun ConnectingConnectedHandheld() = PreviewFrame(UiProfile.HANDHELD) { Sample(connected, 6, 16_000) }

@Preview(name = "Car landscape, light", device = "spec:width=1920px,height=1080px,dpi=160")
@Composable private fun ConnectingUsbCarLight() = PreviewFrame(UiProfile.CAR, dark = false) { Sample(usbCarPlay, 3, 7_000) }
