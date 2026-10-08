package com.andrerinas.openheadunit.connection

import org.junit.Assert.*
import org.junit.Test

class P2pSessionOwnershipTest {
    @Test fun `system owner broadcast cannot adopt CarPlay group`() {
        val state = P2pSessionOwnership()
        state.start()
        state.observeCreatedGroup(true, "DIRECT-dp-carplay")
        assertFalse(state.owns(true, "DIRECT-dp-carplay"))
    }

    @Test fun `replacement group cannot be removed by old session`() {
        val state = P2pSessionOwnership()
        state.start()
        state.expect("DIRECT-aa-session")
        assertTrue(state.created(state.generation))
        assertTrue(state.owns(true, "DIRECT-aa-session"))
        assertFalse(state.owns(true, "DIRECT-dp-replacement"))
        assertFalse(state.owns(false, "DIRECT-aa-session"))
    }

    @Test fun `disabled P2P cancels pending retries until reenabled`() {
        val state = P2pSessionOwnership()
        state.start()
        val old = state.generation
        state.setEnabled(false)
        assertFalse(state.current(old))
        assertFalse(state.current(state.generation))
        state.setEnabled(true)
        assertTrue(state.current(state.generation))
        assertFalse(state.created(old))
    }

    @Test fun `exit cannot be undone by late callback or enable broadcast`() {
        val state = P2pSessionOwnership()
        state.start()
        val old = state.generation
        state.stop()
        state.setEnabled(true)
        assertFalse(state.current(old))
        assertFalse(state.current(state.generation))
        state.start()
        assertFalse(state.current(old))
        assertTrue(state.current(state.generation))
    }

    @Test fun `legacy group requires successful current create before adoption`() {
        val state = P2pSessionOwnership()
        state.start()
        state.expectSystemName()
        state.observeCreatedGroup(true, "DIRECT-system")
        assertFalse(state.owns(true, "DIRECT-system"))
        state.created(state.generation)
        state.observeCreatedGroup(true, "DIRECT-system")
        assertTrue(state.owns(true, "DIRECT-system"))
    }
}
