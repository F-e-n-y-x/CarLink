package com.carlink.app.gallery

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.carlink.app.core.Engine
import com.carlink.app.core.Transport
import com.carlink.app.settings.ConnectionMethod
import com.carlink.app.settings.WirelessPath
import com.carlink.app.switcher.Edge
import com.carlink.app.ui.switcher.SwitchPrompt
import com.carlink.app.ui.switcher.SwitcherActions
import com.carlink.app.ui.switcher.SwitcherBubble
import com.carlink.app.ui.switcher.SwitcherOverlay
import com.carlink.app.ui.switcher.SwitcherPage
import com.carlink.app.ui.switcher.SwitcherUi

private val switcherConfigs = setOf("car-landscape", "tablet-landscape", "tablet-portrait")

private object GallerySwitcherActions : SwitcherActions

/**
 * The floating switcher and the USB switch prompt, drawn over a stand-in for the projection
 * (the real windows are overlays above CarPlay / Android Auto).
 */
internal fun switcherEntries(): List<GalleryEntry> = with(HomeFakes) {
    fun entry(name: String, content: @Composable () -> Unit) = GalleryEntry("switcher", name, configs = switcherConfigs, content = content)
    fun ui(page: SwitcherPage = SwitcherPage.MAIN, edge: Edge = Edge.RIGHT, since: Long) = SwitcherUi(
        phoneName = iphone.name, engine = Engine.CARPLAY, transport = Transport.WIRELESS, path = WirelessPath.WIFI_DIRECT,
        connectedSince = since, otherPhones = listOf(pixel, iphone.copy(address = "AA:BB:CC:00:00:09", name = "Work iPhone")),
        method = ConnectionMethod.AUTOMATIC, page = page, edge = edge,
    )
    listOf(
        entry("bubble") {
            FakeProjection {
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).size(if (com.carlink.app.ui.theme.CarLinkTokens.profile ==
                    com.carlink.app.ui.theme.UiProfile.CAR) 64.dp else 56.dp)) { SwitcherBubble() }
            }
        },
        entry("panel") {
            val since = remember { SystemClock.elapsedRealtime() - 754_000 }
            FakeProjection { SwitcherOverlay(ui(since = since), GallerySwitcherActions) }
        },
        entry("panel-left-connection-type") {
            val since = remember { SystemClock.elapsedRealtime() - 754_000 }
            FakeProjection { SwitcherOverlay(ui(SwitcherPage.CONNECTION_TYPE, Edge.LEFT, since), GallerySwitcherActions) }
        },
        entry("panel-switching") {
            val since = remember { SystemClock.elapsedRealtime() - 61_000 }
            FakeProjection { SwitcherOverlay(ui(since = since).copy(switchingTo = pixel.address), GallerySwitcherActions) }
        },
        entry("switch-prompt") {
            FakeProjection {
                SwitchPrompt("Ayush’s S25 Ultra", newEngine = Engine.ANDROID_AUTO, currentEngine = Engine.CARPLAY,
                    onSwitch = {}, onDecline = {})
            }
        },
    )
}

/** A dark, map-like stand-in for the CarPlay picture: a sidebar and a few tiles. */
@Composable
private fun FakeProjection(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF26323D), Color(0xFF16202A))))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(0.09f).background(Color(0xFF0B0F14)))
        listOf(0.18f to 0.15f, 0.55f to 0.2f, 0.3f to 0.6f, 0.7f to 0.65f).forEach { (x, y) ->
            Box(Modifier.fillMaxSize().padding(start = (x * 1000).dp.coerceAtMost(900.dp), top = (y * 700).dp)) {
                Box(Modifier.size(120.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF3A4A58)))
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp).size(80.dp).clip(CircleShape).background(Color(0xFF4F87C9)))
        content()
    }
}
