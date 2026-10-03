package com.shilapi.xcertplay

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
class BydAdbSettingsUiTest {
    private lateinit var activity: DiPlayActivity
    private lateinit var controls: LinearLayout

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        for (name in listOf("diplay_byd_outputs", "diplay_car_hotspot", "xcertplay_airplay")) {
            app.getSharedPreferences(name, 0).edit().clear().commit()
        }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.byd.carsettings"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.byd.carsettings"
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        })
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.MANUAL)
        controls = LinearLayout(activity)
        assertTrue(CarHotspotSetup.isBydHeadUnit(activity))
        assertFalse(BydOutputSettings.available(activity))
    }

    @Test fun adbVehicleControlsAppearWithoutAmapOrSomeipNavigationServices() {
        render(LocalAdb.Access.READY)
        assertEquals(View.VISIBLE, controls.visibility)
        assertEquals(1, controls.childCount)
        val labels = labels(controls.getChildAt(0))
        for (id in listOf(R.string.byd_adb_features, R.string.car_battery_for_the_iphone,
            R.string.wheel_speed_for_tunnels, R.string.video_while_parked, R.string.cluster_song,
            R.string.check_adb_access, R.string.apply_and_reconnect, R.string.auto_car_hotspot_title)) {
            assertEquals(activity.getString(id), 1, labels.count { it == activity.getString(id) })
        }
        assertTrue(labels.any { it.startsWith(activity.getString(R.string.charging_connectors)) })
        assertTrue(labels.any { it.startsWith(activity.getString(R.string.low_charge_warning)) })
        assertFalse(labels.contains(activity.getString(R.string.navigation_on_hud_and_instrument_cluster)))
        assertFalse(labels.contains(activity.getString(R.string.dashboard_map_only_in_small_and_full_navi)))
        assertFalse(BydOutputSettings.batteryToIphone(activity))
        assertFalse(BydOutputSettings.wheelSpeedToIphone(activity))
        assertFalse(BydOutputSettings.videoWhileParked(activity))
        assertFalse(BydOutputSettings.clusterSong(activity))
        assertFalse(CarHotspotSettings.enabled(activity))
        assertEquals(4, buttons(controls).size)
        assertFalse(labels.contains(activity.getString(R.string.open_car_hotspot_settings)))
        assertFooterLast(R.string.adb_access_ready)
    }

    @Test fun unapprovedAdbStillShowsVehicleOptionsAndTheAuthorizationEntry() {
        render(LocalAdb.Access.NOT_APPROVED)
        assertEquals(View.VISIBLE, controls.visibility)
        assertEquals(1, controls.childCount)
        val labels = labels(controls.getChildAt(0))
        assertTrue(labels.contains(activity.getString(R.string.car_battery_for_the_iphone)))
        assertTrue(labels.contains(activity.getString(R.string.video_while_parked)))
        assertTrue(labels.contains(activity.getString(R.string.check_adb_access)))
        assertTrue(labels.contains(activity.getString(R.string.adb_not_approved)))
        assertTrue(labels.contains(activity.getString(R.string.auto_car_hotspot_title)))
        assertFooterLast(R.string.adb_not_approved)
    }

    @Test fun changingHotspotModeOnlyHidesHotspotControlsAndPreservesTheSavedChoice() {
        CarHotspotSettings.setEnabled(activity, true)
        AirPlayPersistence.saveAutoStartOnBoot(activity, true)
        ShadowSettings.setCanDrawOverlays(false)
        for (mode in listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.MANUAL)) {
            AirPlayPersistence.saveWirelessHotspotMode(activity, mode)
            render(LocalAdb.Access.READY)
            assertEquals(View.VISIBLE, controls.visibility)
            assertEquals(1, controls.childCount)
            val labels = labels(controls.getChildAt(0))
            for (id in listOf(R.string.car_battery_for_the_iphone, R.string.wheel_speed_for_tunnels,
                R.string.video_while_parked, R.string.cluster_song, R.string.check_adb_access)) {
                assertTrue(labels.contains(activity.getString(id)))
            }
            assertEquals(mode == WirelessHotspotMode.MANUAL, labels.contains(activity.getString(R.string.auto_car_hotspot_title)))
            assertFalse(labels.contains(activity.getString(R.string.open_car_hotspot_settings)))
            assertTrue(CarHotspotSettings.enabled(activity))
            assertFooterLast(R.string.adb_access_ready)
        }
    }

    @Test fun losingSupportedAdbHidesTheSectionAndDetachesThePreviousStatusView() {
        for (access in listOf(LocalAdb.Access.UNREACHABLE, LocalAdb.Access.UNSUPPORTED)) {
            render(LocalAdb.Access.READY)
            render(access)
            assertEquals(View.GONE, controls.visibility)
            assertEquals(0, controls.childCount)
            assertNull(DiPlayActivity::class.java.getDeclaredField("adbStatus").apply {
                isAccessible = true
            }.get(activity))
        }
    }

    private fun assertFooterLast(status: Int) {
        assertEquals(listOf(activity.getString(status), activity.getString(R.string.check_adb_access),
            activity.getString(R.string.apply_and_reconnect)), labels(controls).takeLast(3))
    }

    private fun render(access: LocalAdb.Access) {
        DiPlayActivity::class.java.getDeclaredMethod("renderBydAdbControls", LinearLayout::class.java,
            LocalAdb.Access::class.java).apply { isAccessible = true }.invoke(activity, controls, access)
    }

    private fun labels(view: View): List<String> = buildList {
        if (view is TextView) add(view.text.toString())
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(labels(view.getChildAt(index)))
    }

    private fun buttons(view: View): List<Button> = buildList {
        if (view is Button && view !is CompoundButton) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(buttons(view.getChildAt(index)))
    }
}
