package com.carlink.app.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Test

class AllowAllTest {
    private fun p(item: SetupItem, granted: Boolean = false) = SetupPermission(item, listOf("x"), granted)
    private val all = listOf(p(SetupItem.BLUETOOTH), p(SetupItem.NOTIFICATIONS), p(SetupItem.OVERLAY), p(SetupItem.VPN), p(SetupItem.BATTERY))

    @Test fun walksRuntimeTogetherThenEachSpecialGrantInOrder() {
        assertEquals(listOf(SetupItem.BLUETOOTH, SetupItem.NOTIFICATIONS), SetupPermissions.nextToAllow(all, emptySet()).map { it.item })
        var asked = setOf(SetupItem.BLUETOOTH, SetupItem.NOTIFICATIONS)
        // Notifications refused: not asked again, the walk moves on to the overlay.
        assertEquals(listOf(SetupItem.OVERLAY), SetupPermissions.nextToAllow(all, asked).map { it.item })
        asked = asked + SetupItem.OVERLAY
        assertEquals(listOf(SetupItem.VPN), SetupPermissions.nextToAllow(all, asked).map { it.item })
        asked = asked + SetupItem.VPN
        assertEquals(listOf(SetupItem.BATTERY), SetupPermissions.nextToAllow(all, asked).map { it.item })
        assertEquals(emptyList<SetupPermission>(), SetupPermissions.nextToAllow(all, asked + SetupItem.BATTERY))
    }

    @Test fun grantedItemsAreSkipped() {
        val some = listOf(p(SetupItem.BLUETOOTH, granted = true), p(SetupItem.OVERLAY, granted = true), p(SetupItem.BATTERY))
        assertEquals(listOf(SetupItem.BATTERY), SetupPermissions.nextToAllow(some, emptySet()).map { it.item })
    }
}
