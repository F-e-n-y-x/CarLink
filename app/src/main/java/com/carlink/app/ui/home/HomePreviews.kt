package com.carlink.app.ui.home

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkStage
import com.carlink.app.core.LinkState
import com.carlink.app.core.PhoneKind
import com.carlink.app.core.Transport
import com.carlink.app.detect.BluetoothAvailability
import com.carlink.app.detect.PairedPhone
import com.carlink.app.ui.ConnectionPrefs
import com.carlink.app.ui.HomeState
import com.carlink.app.ui.theme.CarLinkTheme
import com.carlink.app.ui.theme.ThemeMode
import com.carlink.app.ui.theme.UiProfile

/*
 * Car previews use pixel sizes because the CAR profile maps the panel's short side to 810dp:
 * 1920×1080px is the 1440×810dp landscape canvas, 1080×1920px the 810×1440dp portrait one.
 */

@Preview(name = "Car landscape 1440×810", device = "spec:width=1920px,height=1080px,dpi=160")
@Preview(name = "Car portrait 810×1440", device = "spec:width=1080px,height=1920px,dpi=160")
internal annotation class CarPreviews

@Preview(name = "Phone portrait 400×860", device = "spec:width=400dp,height=860dp,dpi=420")
@Preview(name = "Phone landscape 860×400", device = "spec:width=860dp,height=400dp,dpi=420")
@Preview(name = "Tablet portrait 800×1280", device = "spec:width=800dp,height=1280dp,dpi=240")
internal annotation class HandheldPreviews

@Composable
internal fun PreviewFrame(profile: UiProfile, dark: Boolean = true, content: @Composable () -> Unit) {
    CarLinkTheme(profile, if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
    }
}

private val sara = PairedPhone("AA:BB:CC:DD:EE:01", "Sara's iPhone", PhoneKind.IPHONE, connected = true)
private val omar = PairedPhone("AA:BB:CC:DD:EE:02", "Omar's Pixel 8", PhoneKind.ANDROID, connected = false)
private val galaxy = PairedPhone("AA:BB:CC:DD:EE:03", "Galaxy A54", PhoneKind.UNKNOWN, connected = false)
private val work = PairedPhone("AA:BB:CC:DD:EE:04", "Work phone", PhoneKind.ANDROID, connected = false)
private val phones = listOf(sara, omar, galaxy, work)

private val ready = HomeState(LinkState.Idle, phones, BluetoothAvailability.READY, sara, null)
private val live = HomeState(
    LinkState(LinkStage.CONNECTED, Engine.CARPLAY, Transport.WIRELESS, sara.name, 6, 6),
    phones, BluetoothAvailability.READY, sara, SystemClock.elapsedRealtime() - 754_000,
)
private val failed = HomeState(
    LinkState(LinkStage.FAILED, Engine.ANDROID_AUTO, Transport.WIRELESS, omar.name,
        detail = "The phone stopped the connection. Unlock it once, then try again."),
    phones, BluetoothAvailability.READY, sara, null,
)
private val bluetoothOff = HomeState(LinkState.Idle, emptyList(), BluetoothAvailability.OFF, null, null)
private val waiting = HomeState(LinkState.Idle, emptyList(), BluetoothAvailability.READY, null, null)
private val noPermission = HomeState(LinkState.Idle, emptyList(), BluetoothAvailability.NO_PERMISSION, null, null)

private val prefs = ConnectionPrefs(autoConnectUsb = true, autoConnectBluetooth = true, startOnBoot = true, autoConnectPaused = false)
private val paused = prefs.copy(autoConnectPaused = true)

@CarPreviews @Composable private fun HomeReadyCar() = PreviewFrame(UiProfile.CAR) { HomeContent(ready, prefs, HomeActions()) }
@HandheldPreviews @Composable private fun HomeReadyHandheld() = PreviewFrame(UiProfile.HANDHELD) { HomeContent(ready, prefs, HomeActions()) }

@CarPreviews @Composable private fun HomeLiveCar() = PreviewFrame(UiProfile.CAR) { HomeContent(live, prefs, HomeActions()) }
@HandheldPreviews @Composable private fun HomeLiveHandheld() = PreviewFrame(UiProfile.HANDHELD) { HomeContent(live, prefs, HomeActions()) }

@CarPreviews @Composable private fun HomeFailedCar() = PreviewFrame(UiProfile.CAR) { HomeContent(failed, paused, HomeActions()) }
@HandheldPreviews @Composable private fun HomeFailedHandheld() = PreviewFrame(UiProfile.HANDHELD) { HomeContent(failed, paused, HomeActions()) }

@CarPreviews @Composable private fun HomeBluetoothOffCar() = PreviewFrame(UiProfile.CAR) { HomeContent(bluetoothOff, prefs, HomeActions()) }
@HandheldPreviews @Composable private fun HomeBluetoothOffHandheld() =
    PreviewFrame(UiProfile.HANDHELD) { HomeContent(bluetoothOff, prefs, HomeActions()) }

@CarPreviews @Composable private fun HomeWaitingCar() = PreviewFrame(UiProfile.CAR) { HomeContent(waiting, paused, HomeActions()) }
@HandheldPreviews @Composable private fun HomeWaitingHandheld() = PreviewFrame(UiProfile.HANDHELD) { HomeContent(waiting, paused, HomeActions()) }

@CarPreviews @Composable private fun HomeNoPermissionCar() = PreviewFrame(UiProfile.CAR) { HomeContent(noPermission, prefs, HomeActions()) }
@HandheldPreviews @Composable private fun HomeNoPermissionHandheld() =
    PreviewFrame(UiProfile.HANDHELD) { HomeContent(noPermission, prefs, HomeActions()) }

@Preview(name = "Car landscape, light", device = "spec:width=1920px,height=1080px,dpi=160")
@Composable private fun HomeReadyCarLight() = PreviewFrame(UiProfile.CAR, dark = false) { HomeContent(ready, paused, HomeActions()) }

@Preview(name = "Phone portrait, 1.3× font", device = "spec:width=400dp,height=860dp,dpi=420", fontScale = 1.3f)
@Composable private fun HomeLiveHandheldLargeFont() = PreviewFrame(UiProfile.HANDHELD) { HomeContent(live, prefs, HomeActions()) }

@Preview(name = "Phone portrait, RTL", device = "spec:width=400dp,height=860dp,dpi=420", locale = "ar")
@Composable private fun HomeReadyHandheldRtl() = PreviewFrame(UiProfile.HANDHELD) { HomeContent(ready, paused, HomeActions()) }
