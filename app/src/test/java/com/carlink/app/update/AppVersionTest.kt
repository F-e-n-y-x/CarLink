package com.carlink.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    @Test fun parsesTagsWithAndWithoutV() {
        assertEquals(AppVersion(1, 2, 3), AppVersion.parseTag("v1.2.3"))
        assertEquals(AppVersion(1, 2, 3), AppVersion.parseTag("1.2.3"))
        assertEquals(AppVersion(10, 0, 0), AppVersion.parseTag(" V10.0.0 "))
    }

    @Test fun rejectsMalformedTags() {
        listOf(null, "", "v1", "v1.2", "v1.2.3.4", "v1.2.3-beta", "latest", "v1.x.3", "v1.200.0", "vv1.2.3", "v-1.2.3")
            .forEach { assertNull("'$it' must not parse", AppVersion.parseTag(it)) }
    }

    @Test fun versionNameIgnoresBuildSuffix() {
        assertEquals(AppVersion(1, 0, 0), AppVersion.parseVersionName("1.0.0-hud-test"))
        assertEquals(AppVersion(1, 0, 0), AppVersion.parseVersionName("1.0.0"))
        assertNull(AppVersion.parseVersionName("1.0"))
    }

    @Test fun comparesSemanticallyNotAsText() {
        assertTrue(AppVersion(1, 10, 0) > AppVersion(1, 9, 0))
        assertTrue(AppVersion(2, 0, 0) > AppVersion(1, 99, 99))
        assertTrue(AppVersion(1, 0, 10) > AppVersion(1, 0, 9))
        assertEquals(0, AppVersion(1, 2, 3).compareTo(AppVersion.parseTag("v1.2.3")!!))
        assertEquals(listOf("1.0.2", "1.0.10", "1.2.0", "10.0.0"),
            listOf("10.0.0", "1.0.10", "1.2.0", "1.0.2").map { AppVersion.parseTag(it)!! }.sorted().map { it.toString() })
    }

    @Test fun versionCodeScheme() {
        assertEquals(10000, AppVersion(1, 0, 0).versionCode)
        assertEquals(10203, AppVersion(1, 2, 3).versionCode)
        assertEquals(20000, AppVersion(2, 0, 0).versionCode)
        // The order of codes follows the order of versions.
        assertTrue(AppVersion(1, 99, 99).versionCode < AppVersion(2, 0, 0).versionCode)
        assertEquals("v1.2.3", AppVersion(1, 2, 3).tag)
    }
}
