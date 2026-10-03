package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydAdbAccess
import com.shilapi.xcertplay.hud.BydOutputSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE, shadows = [BydSettingsReconnectTest.AdbCheck::class,
    BydSettingsReconnectTest.UnavailableAdb::class])
class BydSettingsReconnectTest {
    private lateinit var activity: DiPlayActivity
    private var stopCount = 0

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        for (name in listOf("diplay_byd_outputs", "diplay_car_hotspot", "xcertplay_airplay", "diplay")) {
            app.getSharedPreferences(name, 0).edit().clear().commit()
        }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.byd.amapservice"
            applicationInfo = ApplicationInfo().apply { packageName = "com.byd.amapservice" }
        })
        CarPlayBackgroundSession.clear()
        AdbCheck.entered = CountDownLatch(1)
        AdbCheck.release = CountDownLatch(1)
        AdbCheck.worker = null
        AdbCheck.calls = 0
        AdbCheck.result = BydAdbAccess.Status(BydAdbAccess.State.READY, batteryPercent = 51.0, rangeKm = 100)
        stopCount = 0
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        AirPlayPersistence.saveWirelessEnabled(activity, false)
    }

    @After fun tearDown() {
        AdbCheck.release.countDown()
        AdbCheck.worker?.join(3_000)
        shadowOf(Looper.getMainLooper()).idle()
        CarPlayBackgroundSession.clear()
    }

    @Test fun navigationApplyWaitsForBatteryEvenBeforeTheAdbSectionAppears() {
        beginBatteryReconnect(navigationApply())
        completeCheck()
        assertReconnected()
    }

    @Test fun adbSectionApplyUsesTheSameBatteryPreflight() {
        beginBatteryReconnect(applyButton(renderAdbSection()))
        completeCheck()
        assertReconnected()
    }

    @Test fun adbFailureKeepsTheSessionAndReportsTheFailureWithoutAStatusView() {
        AdbCheck.result = BydAdbAccess.Status(BydAdbAccess.State.ADB_OFF)
        beginBatteryReconnect(navigationApply())
        completeCheck()
        assertEquals(0, stopCount)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(activity.getString(R.string.adb_off), ShadowToast.getTextOfLatestToast())
    }

    @Test fun unreadableBatteryKeepsTheSessionEvenWhenAdbIsReady() {
        AdbCheck.result = BydAdbAccess.Status(BydAdbAccess.State.READY)
        beginBatteryReconnect(navigationApply())
        completeCheck()
        assertEquals(0, stopCount)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(ShadowToast.getTextOfLatestToast().contains(activity.getString(R.string.adb_battery_unreadable)))
    }

    @Test fun lateAdbSectionDoesNotReplaceThePendingReconnectWithAQuietCheck() {
        beginBatteryReconnect(navigationApply())
        renderAdbSection()
        completeCheck()
        assertEquals(1, AdbCheck.calls)
        assertReconnected()
    }

    @Test fun leavingSettingsDiscardsThePendingReconnect() {
        beginBatteryReconnect(navigationApply())
        DiPlayActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
        completeCheck()
        assertEquals(0, stopCount)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun navigationApplyWithoutBatteryReportingConnectsDirectly() {
        val apply = navigationApply()
        existingSession()
        apply.performClick()
        assertEquals(0, AdbCheck.calls)
        assertReconnected()
    }

    private fun beginBatteryReconnect(apply: Button) {
        BydOutputSettings.setBatteryToIphone(activity, true)
        existingSession()
        apply.performClick()
        assertTrue("ADB preflight did not start", AdbCheck.entered.await(3, TimeUnit.SECONDS))
        assertEquals(0, stopCount)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    private fun completeCheck() {
        AdbCheck.release.countDown()
        AdbCheck.worker!!.join(3_000)
        assertFalse(AdbCheck.worker!!.isAlive)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertReconnected() {
        assertEquals(1, stopCount)
        assertEquals(CarPlayHostActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
    }

    private fun existingSession() {
        val stop: ((() -> Unit) -> Unit) = { done -> stopCount++; done() }
        CarPlayBackgroundSession::class.java.getDeclaredField("stopAction").apply {
            isAccessible = true
        }.set(CarPlayBackgroundSession, stop)
    }

    private fun navigationApply(): Button {
        val content = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("settings", LinearLayout::class.java).apply {
            isAccessible = true
        }.invoke(activity, content)
        val navigation = descendants(content).filterIsInstance<TextView>()
            .first { it.text.toString() == activity.getString(R.string.byd_navigation) }.parent.parent as ViewGroup
        return applyButton(navigation)
    }

    private fun renderAdbSection(): LinearLayout = LinearLayout(activity).also { controls ->
        DiPlayActivity::class.java.getDeclaredMethod("renderBydAdbControls", LinearLayout::class.java,
            LocalAdb.Access::class.java).apply { isAccessible = true }.invoke(activity, controls, LocalAdb.Access.READY)
    }

    private fun applyButton(view: View): Button = descendants(view).filterIsInstance<Button>()
        .single { it.text.toString() == activity.getString(R.string.apply_and_reconnect) }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(descendants(view.getChildAt(i)))
    }

    @Implements(LocalAdb::class, isInAndroidSdk = false)
    class UnavailableAdb {
        @Implementation fun connect(mayAsk: Boolean): LocalAdb.Access = LocalAdb.Access.UNREACHABLE
    }

    @Implements(BydAdbAccess::class, isInAndroidSdk = false)
    class AdbCheck {
        @Implementation fun check(context: Context, mayAsk: Boolean): BydAdbAccess.Status {
            calls++
            worker = Thread.currentThread()
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            return result
        }

        companion object {
            lateinit var entered: CountDownLatch
            lateinit var release: CountDownLatch
            lateinit var result: BydAdbAccess.Status
            @Volatile var worker: Thread? = null
            @Volatile var calls = 0
        }
    }
}
