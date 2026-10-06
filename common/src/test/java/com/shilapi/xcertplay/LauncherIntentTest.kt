package com.shilapi.xcertplay

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class LauncherIntentTest {
    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    @Test
    fun onlyAPlainLauncherIntentReturnsToCarPlay() {
        assertTrue(DiPlayActivity.isLauncherIntent(launcher()))
        assertFalse(DiPlayActivity.isLauncherIntent(launcher().putExtra("page", "settings")))
        assertFalse(DiPlayActivity.isLauncherIntent(Intent(Intent.ACTION_MAIN)))
        assertFalse(DiPlayActivity.isLauncherIntent(Intent().putExtra("page", "connection")))
    }
}
