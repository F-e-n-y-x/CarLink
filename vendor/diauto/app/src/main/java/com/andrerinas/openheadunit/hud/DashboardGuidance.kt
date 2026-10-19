package com.andrerinas.openheadunit.hud

/**
 * The latest mapped guidance for the dashboard turn card: the same turn, distance and street the
 * HUD gets. Written by the navigation handler, read by the dashboard window.
 */
internal object DashboardGuidance {
    @Volatile var latest: BydGuidance? = null

    /** The guidance, unless the phone stopped describing the route a while ago. */
    fun current(): BydGuidance? = latest?.takeIf { System.nanoTime() - it.updatedNs < TTL_NS }

    /** Matches the BYD outputs: a route the phone stopped describing is not shown for long. */
    private const val TTL_NS = 30_000_000_000L
}
