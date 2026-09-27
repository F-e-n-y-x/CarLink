package com.andrerinas.openheadunit.hud

import java.util.concurrent.atomic.AtomicReference

/** A one-slot mailbox: slow outputs skip obsolete complete snapshots without blocking the input. */
internal class LatestNavigationOutput(private val nanoTime: () -> Long = System::nanoTime) {
    private val latest = AtomicReference<BydGuidance?>()
    fun update(value: BydGuidance?) { latest.set(value) }
    fun current(): BydGuidance? = latest.get()?.takeIf { nanoTime() - it.updatedNs < 30_000_000_000L }
}
