package com.carlink.core.connection

import android.media.AudioManager.MODE_IN_CALL
import android.media.AudioManager.MODE_IN_COMMUNICATION
import android.media.AudioManager.MODE_NORMAL
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallMicrophoneWatchTest {
    @Test fun aCallConflictsWhenTheStereosPhoneOrAnotherRecordingMayHoldTheMicrophone() {
        assertFalse(CallMicrophoneWatch.conflict(MODE_IN_COMMUNICATION, handsFree = false, recordings = 1))
        assertFalse(CallMicrophoneWatch.conflict(MODE_IN_COMMUNICATION, handsFree = null, recordings = 1))
        assertTrue(CallMicrophoneWatch.conflict(MODE_IN_COMMUNICATION, handsFree = true, recordings = 1))
        assertTrue(CallMicrophoneWatch.conflict(MODE_IN_COMMUNICATION, handsFree = false, recordings = 2))
        assertTrue(CallMicrophoneWatch.conflict(MODE_IN_CALL, handsFree = false, recordings = 1))
        // Not a call (Siri, the Assistant): never a notice.
        assertFalse(CallMicrophoneWatch.conflict(MODE_NORMAL, handsFree = true, recordings = 3))
    }
}
