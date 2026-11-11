package com.carlink.core.connection

import android.app.Application
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ResumeMusicTest {
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("resume_test", Context.MODE_PRIVATE)
    private fun fresh() = ResumeMusic(prefs, "playing") // a new process: the car switched off and on

    private fun endedWhilePlaying(): ResumeMusic = fresh().apply {
        onSessionShown(true).let(::check)
        onPhonePlaying(true)
    }

    @Test fun pressesPlayWhenTheLastSessionEndedPlayingAndThePhoneStaysPaused() {
        endedWhilePlaying()
        val resume = fresh()
        resume.onPhonePlaying(false) // the phone's report as it connects, before the picture
        assertTrue(resume.check(resume.onSessionShown(true)))
    }

    @Test fun alsoAfterAReconnectInTheSameProcess() {
        val resume = endedWhilePlaying()
        resume.onSessionEnded()
        resume.onPhonePlaying(false)
        assertTrue(resume.check(resume.onSessionShown(true)))
    }

    @Test fun nothingWhenOff() {
        endedWhilePlaying()
        val resume = fresh()
        assertFalse(resume.check(resume.onSessionShown(false)))
    }

    @Test fun nothingWhenThePhoneResumedByItself() {
        endedWhilePlaying()
        val resume = fresh()
        val token = resume.onSessionShown(true)
        resume.onPhonePlaying(true)
        assertFalse(resume.check(token))
    }

    @Test fun nothingWhenTheDriverPlayedAndPausedBeforeTheCheck() {
        endedWhilePlaying()
        val resume = fresh()
        val token = resume.onSessionShown(true)
        resume.onPhonePlaying(true)
        resume.onPhonePlaying(false)
        assertFalse(resume.check(token))
    }

    @Test fun nothingWhenTheMusicWasPausedBeforeTheSessionEnded() {
        endedWhilePlaying().onPhonePlaying(false)
        val resume = fresh()
        assertFalse(resume.check(resume.onSessionShown(true)))
    }

    @Test fun aSessionWithoutMusicClearsTheMarkAfterItsCheck() {
        endedWhilePlaying()
        fresh().apply { check(onSessionShown(false)) } // feature off: the check still settles the state
        val resume = fresh()
        assertFalse(resume.check(resume.onSessionShown(true)))
    }

    @Test fun aCheckFromAnEndedSessionDoesNothing() {
        endedWhilePlaying()
        val resume = fresh()
        val stale = resume.onSessionShown(true)
        resume.onSessionEnded()
        assertFalse(resume.check(stale))
        assertTrue("the next session still resumes", resume.check(resume.onSessionShown(true)))
    }
}
