package com.shilapi.xcertplay

import android.content.Context
import android.provider.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UsbAutoConfirmServiceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun isEnabledReturnsFalseByDefault() {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            null,
        )
        assertFalse(UsbAutoConfirmService.isEnabled(context))
    }

    @Test
    fun isEnabledReturnsTrueWhenConfiguredInSecureSettings() {
        val serviceName = "${context.packageName}/${UsbAutoConfirmService::class.java.name}"
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "other.package/other.Service:$serviceName",
        )
        assertTrue(UsbAutoConfirmService.isEnabled(context))
    }
}
