package com.carlink.core.connection

import android.app.Application
import com.carlink.core.connection.aa.AaProtocol
import com.carlink.core.core.Engine
import com.carlink.core.settings.UnifiedSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class HeadUnitSignalsTest {
    private val app: Application = org.robolectric.RuntimeEnvironment.getApplication()

    @Test fun carModeReadsAsNightOrDay() {
        assertEquals(true, HeadUnitSignals.nightFromCarMode("1"))
        assertEquals(false, HeadUnitSignals.nightFromCarMode(" 0"))
        assertNull(HeadUnitSignals.nightFromCarMode(null))
        assertNull(HeadUnitSignals.nightFromCarMode("dark"))
    }

    @Test fun noFytOrJoyingFirmwareMeansNoLightsSignal() {
        android.provider.Settings.System.putInt(app.contentResolver, "car_mode", 1)
        assertNull("an ordinary device keeps its own day/night", HeadUnitSignals.lightsNight(app))
    }

    @Test fun joyingCallbackGivesTheDriverSideOnlyForItsId() {
        assertEquals(true, QzdHeadUnit.driverOnRight("canbus.driverOnRight", 1))
        assertEquals(false, QzdHeadUnit.driverOnRight("canbus.driverOnRight", 0))
        assertNull(QzdHeadUnit.driverOnRight("main.accState", 1))
        assertNull(QzdHeadUnit.driverOnRight("canbus.driverOnRight", null))
    }

    @Test fun theStereoDriverSideIsOnlyADefault() {
        assertTrue("stereo says right, never chosen", UnifiedSettingsStore.driveSide(false, false, true))
        assertFalse("no stereo signal", UnifiedSettingsStore.driveSide(false, false, null))
        assertFalse("the driver chose left", UnifiedSettingsStore.driveSide(true, false, true))
        assertTrue("right stored before the marker was the driver's", UnifiedSettingsStore.driveSide(false, true, false))
    }

    @Test fun theStoreFollowsTheStereoUntilTheDriverChangesTheSide() {
        val store = UnifiedSettingsStore(app)
        store.save(store.load().copy(sendCarLocation = false)) // an earlier save wrote right_hand_drive=false
        store.saveStereoDriveSide(true)
        assertTrue(store.load().rightHandDrive)
        store.save(store.load().copy(resumeMusic = true)) // saving another setting keeps it a default
        store.saveStereoDriveSide(false)
        assertFalse(store.load().rightHandDrive)
        store.saveStereoDriveSide(true)
        store.save(store.load().copy(rightHandDrive = false)) // the driver picks left
        assertFalse(store.load().rightHandDrive)
    }

    @Test fun androidAutoVoiceKeyGoesToTheEngineProcess() {
        assertTrue(StereoVoiceKey.press(app, Engine.ANDROID_AUTO))
        val sent = shadowOf(app).broadcastIntents.last()
        assertEquals(AaProtocol.ACTION_COMMAND, sent.action)
        assertEquals(AaProtocol.COMMAND_VOICE, sent.getStringExtra(AaProtocol.EXTRA_COMMAND))
        assertFalse("no CarPlay session: no Siri", StereoVoiceKey.press(app, Engine.CARPLAY))
    }
}
