package com.carlink.app.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompetingAppsTest {
    @Test fun vendorProjectionAppsAreNamed() {
        listOf("com.zjinnova.zlink", "com.syu.carlink", "cn.manstep.autokit", "net.easyconn.carman", "com.carbit.http")
            .forEach { assertTrue(it, Checks.isCompeting(it)) }
    }

    @Test fun ordinaryAppsAndAndroidAutoAreNot() {
        listOf("com.google.android.projection.gearhead", "com.carlink.app", "com.spotify.music", "com.android.settings")
            .forEach { assertFalse(it, Checks.isCompeting(it)) }
    }
}
