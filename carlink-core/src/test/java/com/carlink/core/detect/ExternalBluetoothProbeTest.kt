package com.carlink.core.detect

import com.carlink.core.core.PhoneKind
import com.carlink.core.detect.ExternalBluetoothProbe.Sources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ExternalBluetoothProbe]'s evidence and classification, one fake stereo per case. The markers are
 * the ones upstream open-headunit uses for its two module routes (design/EXTERNAL_BLUETOOTH.md).
 */
class ExternalBluetoothProbeTest {
    private class Stereo(
        val nodes: Set<String> = emptySet(),
        val props: Map<String, String> = emptyMap(),
        val port: PortState = PortState.CLOSED,
        val apps: List<InstalledApp> = emptyList(),
        val adapter: AdapterFacts = AdapterFacts(present = true, enabled = true, addressReal = false),
        val bonded: List<String>? = emptyList(),
        val known: Map<String, PhoneKind> = emptyMap(),
    ) {
        val portChecks = mutableListOf<String>()
        var packageScans = 0

        fun sources() = Sources(
            files = { it in nodes },
            properties = { props[it] },
            port = { host, port, timeout -> portChecks += "$host:$port/$timeout"; this.port },
            packages = { packageScans++; apps },
            bluetooth = object : ExternalBluetoothProbe.BluetoothSource {
                override fun adapter() = adapter
                override fun bondedAddresses() = bonded
            },
            knownPhones = { known },
            ownPackages = setOf("com.carlink.app.hudtest"),
        )

        fun probe(complete: Boolean = true) = ExternalBluetoothProbe.probe(sources(), complete)
    }

    private val phone = "AA:BB:CC:DD:EE:01"

    @Test fun anOrdinaryTabletIsNone() {
        val report = Stereo(bonded = listOf(phone), known = mapOf(phone to PhoneKind.ANDROID)).probe()
        assertEquals(StereoBluetooth.NONE, report.classification)
        assertTrue(report.signals.isEmpty())
        assertNull(report.diAutoEvidence)
        assertFalse(report.classification.blocksWireless)
        assertFalse(report.classification.hasSignals)
    }

    @Test fun aDaemonOnTheModulePortIsZlinkDaemon() {
        val stereo = Stereo(nodes = setOf("/dev/zj_bt_serial"), port = PortState.OPEN)
        val report = stereo.probe()
        assertEquals(StereoBluetooth.ZLINK_DAEMON, report.classification)
        assertEquals(StereoBluetoothSignal.MODULE_SERVICE_ANSWERS, report.signals.first())
        assertTrue(report.classification.blocksWireless)
        // Connect only, to loopback, with the short timeout.
        assertEquals(listOf("127.0.0.1:3152/300"), stereo.portChecks)
    }

    @Test fun aDaemonAloneStillCounts() {
        assertEquals(StereoBluetooth.ZLINK_DAEMON, Stereo(port = PortState.OPEN).probe().classification)
    }

    @Test fun fytSignalsAreFytModule() {
        val byProperty = Stereo(props = mapOf("sys.fyt.bluetooth_type" to "1")).probe()
        assertEquals(StereoBluetooth.FYT_MODULE, byProperty.classification)
        assertEquals(listOf(StereoBluetoothSignal.FYT_SETTING), byProperty.signals)
        val bySerial = Stereo(nodes = setOf("/dev/auto_serial")).probe()
        assertEquals(StereoBluetooth.FYT_MODULE, bySerial.classification)
        assertTrue(bySerial.classification.blocksWireless)
        // FYT wins over ZLink markers when both are present and no daemon answers.
        assertEquals(StereoBluetooth.FYT_MODULE,
            Stereo(nodes = setOf("/dev/rf_serial"), props = mapOf("sys.fyt.bluetooth_type" to "2")).probe().classification)
    }

    @Test fun aZeroOrUnreadableFytTypeIsNotASignal() {
        assertEquals(StereoBluetooth.NONE, Stereo(props = mapOf("sys.fyt.bluetooth_type" to "0")).probe().classification)
        assertEquals(StereoBluetooth.NONE, Stereo(props = mapOf("sys.fyt.bluetooth_type" to "abc")).probe().classification)
        assertEquals(0, ExternalBluetoothProbe.fytType(null))
        assertEquals(3, ExternalBluetoothProbe.fytType(" 3 "))
    }

    @Test fun zlinkMarkersWithoutADaemonAreExternalUnknown() {
        val bySerial = Stereo(nodes = setOf("/dev/rf_serial")).probe()
        assertEquals(StereoBluetooth.EXTERNAL_UNKNOWN, bySerial.classification)
        assertEquals(listOf(StereoBluetoothSignal.MODULE_SERIAL_PORT), bySerial.signals)
        // DiAuto's own answer is kept as-is.
        assertEquals("/dev/rf_serial exists", bySerial.diAutoEvidence)
        val bySetting = Stereo(props = mapOf("rw.zj.bt.type" to "Extra")).probe()
        assertEquals(StereoBluetooth.EXTERNAL_UNKNOWN, bySetting.classification)
        assertEquals(listOf(StereoBluetoothSignal.MODULE_SETTING), bySetting.signals)
        assertEquals("rw.zj.bt.type=Extra", bySetting.diAutoEvidence)
        assertTrue(bySetting.classification.blocksWireless)
    }

    @Test fun btSerialAloneDecidesNothing() {
        val report = Stereo(nodes = setOf("/dev/BT_serial")).probe()
        assertEquals(StereoBluetooth.NONE, report.classification)
        assertEquals(true, report.nodes["/dev/BT_serial"])
    }

    @Test fun markersWithThePhoneBondedToARealAndroidAdapterAreLikelyAFalsePositive() {
        val real = AdapterFacts(present = true, enabled = true, addressReal = true)
        val report = Stereo(nodes = setOf("/dev/rf_serial"), adapter = real, bonded = listOf(phone.lowercase()),
            known = mapOf(phone to PhoneKind.IPHONE)).probe()
        assertEquals(StereoBluetooth.LIKELY_FALSE_POSITIVE, report.classification)
        assertTrue(report.classification.hasSignals)
        assertFalse(report.classification.blocksWireless)
        assertEquals(listOf(KnownPhoneBond(PhoneKind.IPHONE, true)), report.knownPhones)
        // Either half alone is not enough.
        assertEquals(StereoBluetooth.EXTERNAL_UNKNOWN, Stereo(nodes = setOf("/dev/rf_serial"), adapter = real, bonded = emptyList(),
            known = mapOf(phone to PhoneKind.IPHONE)).probe().classification)
        assertEquals(StereoBluetooth.EXTERNAL_UNKNOWN, Stereo(nodes = setOf("/dev/rf_serial"), bonded = listOf(phone),
            known = mapOf(phone to PhoneKind.IPHONE)).probe().classification)
        // The rule applies to the daemon and FYT routes too.
        assertEquals(StereoBluetooth.LIKELY_FALSE_POSITIVE, Stereo(port = PortState.OPEN, adapter = real, bonded = listOf(phone),
            known = mapOf(phone to PhoneKind.ANDROID)).probe().classification)
    }

    @Test fun theQuickProbeSkipsTheNetworkAndThePackageScan() {
        val stereo = Stereo(nodes = setOf("/dev/rf_serial"), port = PortState.OPEN,
            apps = listOf(InstalledApp("com.syu.carlink", "1.0", 1)))
        val report = stereo.probe(complete = false)
        assertTrue(stereo.portChecks.isEmpty())
        assertEquals(0, stereo.packageScans)
        assertEquals(PortState.NOT_CHECKED, report.daemonPort)
        assertFalse(report.complete)
        // The block decision is the same without the port: the markers alone block.
        assertTrue(report.classification.blocksWireless)
    }

    @Test fun onlyRelevantPackagesAreNamedWithTheirVersions() {
        val apps = listOf(
            InstalledApp("com.syu.carlink", "2.1", 21),
            InstalledApp("com.zjinnova.zlink", "5.3.1", 531),
            InstalledApp("net.easyconn", "4.0", 40),
            InstalledApp("com.example.zlinkhelper", null, 1),
            InstalledApp("com.android.settings", "10", 29),
            InstalledApp("com.carlink.app.hudtest", "1.0", 1),
            InstalledApp("com.carlink.app", "1.0", 1),
        )
        val report = Stereo(apps = apps).probe()
        assertEquals(listOf("com.example.zlinkhelper", "com.syu.carlink", "com.zjinnova.zlink", "net.easyconn"),
            report.packages.map { it.packageName })
    }

    @Test fun unreadableBondsAreUnknownNotFalse() {
        val report = Stereo(nodes = setOf("/dev/rf_serial"), bonded = null, known = mapOf(phone to PhoneKind.ANDROID)).probe()
        assertNull(report.bondedCount)
        assertEquals(listOf(KnownPhoneBond(PhoneKind.ANDROID, null)), report.knownPhones)
    }

    @Test fun failingSourcesNeverThrow() {
        val broken = Sources(
            files = { error("no") }, properties = { error("no") }, port = { _, _, _ -> error("no") }, packages = { error("no") },
            bluetooth = object : ExternalBluetoothProbe.BluetoothSource {
                override fun adapter() = error("no")
                override fun bondedAddresses() = error("no")
            },
            knownPhones = { error("no") },
        )
        val report = ExternalBluetoothProbe.probe(broken)
        assertEquals(StereoBluetooth.NONE, report.classification)
        assertEquals(PortState.ERROR, report.daemonPort)
    }

    @Test fun theBlockNamesEveryFieldAndNoAddress() {
        val report = Stereo(nodes = setOf("/dev/rf_serial"), props = mapOf("sys.fyt.bluetooth_type" to "1"),
            apps = listOf(InstalledApp("com.syu.carlink", "2.1", 21)), bonded = listOf(phone),
            known = mapOf(phone to PhoneKind.ANDROID)).probe()
        val block = report.format()
        for (part in listOf("classification=FYT_MODULE", "signals=FYT_SETTING,MODULE_SERIAL_PORT", "diauto.evidence=/dev/rf_serial exists",
            "prop sys.fyt.bluetooth_type=1", "prop rw.zlink.bt.type=<unset>", "node /dev/auto_serial=absent",
            "node /dev/rf_serial=exists", "port 127.0.0.1:3152=CLOSED", "com.syu.carlink 2.1 (21)", "realAddress=false",
            "bonded=1", "phone1(ANDROID) bondedToAndroid=true")) {
            assertTrue("missing '$part' in\n$block", part in block)
        }
        assertFalse(phone in block)
    }

    @Test fun realAddressesAreRecognised() {
        assertFalse(ExternalBluetoothProbe.isRealAddress("02:00:00:00:00:00"))
        assertFalse(ExternalBluetoothProbe.isRealAddress("00:00:00:00:00:00"))
        assertFalse(ExternalBluetoothProbe.isRealAddress(null))
        assertFalse(ExternalBluetoothProbe.isRealAddress("nonsense"))
        assertTrue(ExternalBluetoothProbe.isRealAddress("a4:5e:60:12:34:56"))
    }
}
