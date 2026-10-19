package com.carlink.core.connection.aa

import android.app.Application
import android.hardware.usb.UsbDevice
import com.andrerinas.openheadunit.aap.AapService
import com.carlink.core.settings.VehicleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

/** The ":aa" command logic against a fake DiAuto. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AaCommandHandlerTest {
    private class FakeEngine : AaEngineAccess {
        var running = false
        var busy = false
        var stopsWork = true
        var config = WirelessConfig(3, 0, "", "", "", "0")
        var group: P2pGroupReading = P2pGroupReading.NONE
        /** What the group becomes once the service stops (the engine's own teardown). */
        var groupAfterServiceStop: P2pGroupReading? = null
        var kept: String? = "DIRECT-K7-CarLink"
        var recorded: String? = "DIRECT-K7-CarLink"
        val started = mutableListOf<Pair<String?, String?>>()
        var targets: String? = null
        var removals = 0
        var staticBssidReset = false
        var claim: Boolean? = null
        var permission = true
        var permissionRequested = false
        var switchWorks = true
        var switched = 0
        var resets = 0
        var accessory = false
        var refuseStarts = false
        /** For each fresh AapService start: whether CarLink was in front (while-in-use). */
        val startedInFront = mutableListOf<Boolean>()
        private var clock = 0L

        var front = true
        /** What Home opening does to [front]; null: it never comes to the front. */
        var frontAfterOpen: Boolean? = true
        var broughtToFront = 0
        override fun inFront() = front
        override fun bringToFront() { broughtToFront++; frontAfterOpen?.let { front = it } }
        override fun isServiceRunning() = running
        override fun isBusy() = busy
        override fun startService(action: String?, mac: String?): Boolean {
            if (refuseStarts) return false
            started += action to mac
            if (action != AapService.ACTION_STOP_SERVICE && !running) startedInFront += front
            if (action == AapService.ACTION_STOP_SERVICE) {
                if (stopsWork) {
                    running = false
                    groupAfterServiceStop?.let { group = it }
                }
            } else running = true
            return true
        }
        override fun wirelessConfig() = config
        override fun setPokeTargets(mac: String) { targets = mac }
        override fun keptGroupName() = kept
        override fun recordedGroupName() = recorded
        override fun currentGroup() = group
        override fun removeGroup() { removals++; group = P2pGroupReading.NONE }
        override fun resetStaticBssid() { staticBssidReset = true }
        override fun setClaimHiddenBottomStrip(claim: Boolean) { this.claim = claim }
        override fun isAccessory(device: UsbDevice) = accessory
        override fun hasUsbPermission(device: UsbDevice) = permission
        override fun requestUsbPermission(device: UsbDevice) { permissionRequested = true }
        override fun switchToAccessory(device: UsbDevice): Boolean { switched++; return switchWorks }
        override fun resetUsb(device: UsbDevice): Boolean { resets++; return true }
        override fun deviceName(device: UsbDevice) = "Pixel"
        override fun currentDevice(previous: UsbDevice): UsbDevice? = previous
        var accessoryOnBus: UsbDevice? = null
        override fun accessoryDevice(): UsbDevice? = accessoryOnBus
        var usbOnly: Boolean? = null
        override fun setUsbOnlySession(usbOnly: Boolean) { this.usbOnly = usbOnly }
        override fun sleep(millis: Long) { clock += millis }
        override fun now() = clock
    }

    private val engine = FakeEngine()
    private val monitor = AaEngineMonitor()
    private val events = mutableListOf<Pair<String, String?>>()
    private val acks = mutableListOf<Triple<Long, Boolean, Boolean?>>()
    private var reports = 0
    private val handler = AaCommandHandler(engine, monitor, { k, d -> events += k to d },
        { t, s, g -> acks += Triple(t, s, g) }, { reports++ })

    private val phone = "aa:bb:cc:dd:ee:ff"
    private fun actions() = engine.started.map { it.first }
    private fun device(): UsbDevice = Shadow.newInstanceOf(UsbDevice::class.java)

    @Test fun aFreshWirelessStartArmsThenPokesExactlyTheChosenPhone() {
        handler.startWireless(phone, VehicleProfile.GENERIC)
        assertEquals(listOf(AapService.ACTION_START_WIRELESS, AapService.ACTION_NATIVE_AA_POKE), actions())
        assertEquals(phone, engine.started.last().second)
        assertEquals(phone, engine.targets)
        assertEquals(false, engine.claim)
        assertEquals(engine.config, monitor.armedWith)
        assertTrue(reports > 0)
    }

    @Test fun aStartInTheBackgroundBringsCarLinkToTheFrontFirst() {
        engine.front = false
        handler.startWireless(phone, VehicleProfile.GENERIC)
        assertEquals(1, engine.broughtToFront)
        assertEquals(listOf(true), engine.startedInFront)
        assertEquals("inFront=true", events.single { it.first == AaProtocol.KIND_BROUGHT_TO_FRONT }.second)
    }

    @Test fun aUsbStartInTheBackgroundStillStartsWhenHomeNeverComesUp() {
        engine.front = false
        engine.frontAfterOpen = null
        engine.accessory = true
        handler.startUsb(null, null, VehicleProfile.GENERIC)
        assertEquals(listOf(AapService.ACTION_CHECK_USB), actions())
        assertEquals(listOf(false), engine.startedInFront)
        assertEquals("inFront=false", events.single { it.first == AaProtocol.KIND_BROUGHT_TO_FRONT }.second)
    }

    @Test fun aRunningServiceOrAVisibleCarLinkOpensNothing() {
        handler.startWireless(phone, VehicleProfile.GENERIC)
        engine.front = false
        handler.poke(phone)
        assertEquals(0, engine.broughtToFront)
    }

    @Test fun aBydProfileLetsTheProjectionClaimTheHiddenStrip() {
        handler.startWireless(phone, VehicleProfile.BYD_DILINK)
        assertEquals(true, engine.claim)
    }

    @Test fun anIdleArmedServiceIsNotRestarted() {
        engine.running = true
        monitor.armed(engine.config)
        handler.startWireless(phone, VehicleProfile.GENERIC)
        assertEquals(listOf(AapService.ACTION_START_WIRELESS, AapService.ACTION_NATIVE_AA_POKE), actions())
    }

    @Test fun aBusyServiceIsLeftAlone() {
        engine.running = true
        engine.busy = true
        handler.startWireless(phone, VehicleProfile.GENERIC)
        assertEquals(emptyList<String?>(), actions())
        assertEquals("decision=ALREADY_ACTIVE", events.single { it.first == AaProtocol.KIND_START_DECISION }.second)
    }

    @Test fun changedWirelessSettingsForceARearmWithoutARestart() {
        engine.running = true
        monitor.armed(engine.config)
        engine.config = engine.config.copy(transport = 1, hotspotInterface = "wlan0")
        handler.startWireless(phone, VehicleProfile.GENERIC)
        assertEquals(listOf(AapService.ACTION_START_WIRELESS_SCAN, AapService.ACTION_NATIVE_AA_POKE), actions())
    }

    @Test fun aServiceThatStoodDownForAnotherP2pOwnerIsRestarted() {
        engine.running = true
        monitor.armed(engine.config)
        monitor.onEngineEvent("P2P_OWNED_BY_OTHER")
        handler.startWireless(phone, VehicleProfile.GENERIC)
        assertEquals(listOf(AapService.ACTION_STOP_SERVICE, AapService.ACTION_START_WIRELESS, AapService.ACTION_NATIVE_AA_POKE), actions())
        assertFalse(monitor.p2pOwnedByOther)
    }

    @Test fun carPlaysLeftoverGroupIsRemovedButAndroidAutosIsKept() {
        engine.group = P2pGroupReading(true, "DIRECT-dp0123456789ab-Ab12", true)
        handler.startWireless(phone, VehicleProfile.GENERIC)
        assertEquals(1, engine.removals)

        val kept = FakeEngine().apply { group = P2pGroupReading(true, "DIRECT-K7-CarLink", true) }
        AaCommandHandler(kept, AaEngineMonitor(), { _, _ -> }, { _, _, _ -> }, {}).startWireless(phone, VehicleProfile.GENERIC)
        assertEquals("PR #1006: no removal on start", 0, kept.removals)

        val foreign = FakeEngine().apply { group = P2pGroupReading(true, "DIRECT-zz-Mirror", true) }
        AaCommandHandler(foreign, AaEngineMonitor(), { _, _ -> }, { _, _, _ -> }, {}).startWireless(phone, VehicleProfile.GENERIC)
        assertEquals(0, foreign.removals)
    }

    @Test fun aRefusedServiceStartIsReported() {
        engine.refuseStarts = true
        handler.startWireless(phone, VehicleProfile.GENERIC)
        assertTrue(events.any { it.first == AaProtocol.KIND_START_REFUSED })
        assertNull(monitor.armedWith)
    }

    @Test fun stopWaitsForTheServiceAndTheEnginesGroupRelease() {
        engine.running = true
        engine.group = P2pGroupReading(true, "DIRECT-K7-CarLink", true)
        engine.groupAfterServiceStop = P2pGroupReading.NONE
        handler.stop(42L)
        assertEquals(listOf(AapService.ACTION_STOP_SERVICE), actions())
        assertEquals(listOf(Triple(42L, true, true as Boolean?)), acks)
        assertEquals(0, engine.removals)
        assertTrue(engine.staticBssidReset)
        assertEquals(false, engine.claim)
    }

    @Test fun stopRemovesAnAndroidAutoGroupTheEngineLeftBehind() {
        engine.group = P2pGroupReading(true, "DIRECT-K7-CarLink", true) // a process that died without its teardown
        handler.stop(7L)
        assertEquals("nothing to stop", emptyList<String?>(), actions())
        assertEquals(1, engine.removals)
        assertEquals(Triple(7L, true, true as Boolean?), acks.single())
    }

    @Test fun stopNeverTouchesAnotherAppsGroupAndSaysWhenItCannotTell() {
        engine.group = P2pGroupReading(true, "DIRECT-zz-Mirror", true)
        handler.stop(1L)
        assertEquals(0, engine.removals)
        assertEquals(true, acks.single().third)

        val blind = FakeEngine().apply { group = P2pGroupReading.UNKNOWN }
        val blindAcks = mutableListOf<Boolean?>()
        AaCommandHandler(blind, AaEngineMonitor(), { _, _ -> }, { _, _, g -> blindAcks += g }, {}).stop(2L)
        assertEquals(listOf<Boolean?>(null), blindAcks)
    }

    @Test fun aServiceThatWillNotStopIsReported() {
        engine.running = true
        engine.stopsWork = false
        handler.stop(3L)
        assertFalse(acks.single().second)
    }

    @Test fun usbPerformsTheAccessorySwitchItself() {
        handler.startUsb(device(), phone, VehicleProfile.GENERIC)
        assertEquals("service up first, with no action", listOf<String?>(null), actions())
        assertEquals(1, engine.switched)
        assertEquals(phone, engine.targets)
    }

    @Test fun usbAsksForPermissionAndSwitchesOnlyWhenGranted() {
        engine.permission = false
        val d = device()
        handler.startUsb(d, null, VehicleProfile.GENERIC)
        assertTrue(engine.permissionRequested)
        assertEquals(0, engine.switched)
        handler.onUsbPermission(d, granted = false)
        assertEquals("USB_PERMISSION_DENIED", events.last().first)
        handler.onUsbPermission(d, granted = true)
        assertEquals(1, engine.switched)
    }

    @Test fun aFailedSwitchIsReported() {
        engine.switchWorks = false
        handler.startUsb(device(), null, VehicleProfile.GENERIC)
        assertEquals(AaProtocol.KIND_AOA_SWITCH_FAILED, events.last().first)
    }

    @Test fun anAccessoryOrUnknownDeviceIsLeftToTheEnginesCheck() {
        engine.accessory = true
        handler.startUsb(device(), null, VehicleProfile.GENERIC)
        handler.startUsb(null, null, VehicleProfile.GENERIC)
        assertEquals(listOf(AapService.ACTION_CHECK_USB, AapService.ACTION_CHECK_USB), actions())
        assertEquals(0, engine.switched)
    }

    @Test fun pokeOnlyAnIdleRunningEngine() {
        handler.poke(phone)
        engine.running = true
        engine.busy = true
        handler.poke(phone)
        engine.busy = false
        handler.poke(phone)
        assertEquals(listOf(AapService.ACTION_NATIVE_AA_POKE), actions())
    }

    // ---- RELIABILITY_AUDIT K2 / K10 ----

    @Test fun aStaleAccessoryIsResetUnlessASessionIsAlreadyUnderWay() {
        handler.reswitchStaleAccessory()
        assertEquals("result=no-accessory", events.last().second)
        engine.accessoryOnBus = device()
        handler.reswitchStaleAccessory()
        assertEquals(AaProtocol.KIND_AOA_RESWITCH, events.last().first)
        assertTrue(events.last().second!!.startsWith("result=reset=true"))
        assertEquals(1, engine.resets)
        assertEquals(0, engine.switched)
        engine.busy = true
        handler.reswitchStaleAccessory()
        assertTrue(events.last().second!!.startsWith("result=busy"))
        assertEquals(1, engine.resets)
    }

    @Test fun usbSessionsArmNoWirelessAndTheFlagIsClearedAfterwards() {
        handler.startUsb(device(), phone, VehicleProfile.GENERIC)
        assertEquals(true, engine.usbOnly)
        handler.stop(1L)
        assertEquals(false, engine.usbOnly)
        handler.startUsb(device(), phone, VehicleProfile.GENERIC)
        handler.startWireless(phone, VehicleProfile.GENERIC)
        assertEquals("a wireless start arms wireless again", false, engine.usbOnly)
    }
}
