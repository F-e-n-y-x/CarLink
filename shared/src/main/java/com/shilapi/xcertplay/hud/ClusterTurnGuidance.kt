package com.shilapi.xcertplay.hud

/** Next-turn instruction for the dashboard overlay. Icons use the AMap NEW_ICON vocabulary. */
data class ClusterTurnGuidance(
    val icon: Int,
    val roundaboutExit: Int,
    val distanceMeters: Int,
    val road: String,
    val remainingMeters: Int = -1,
    val remainingSeconds: Int = -1,
    val arrivalEpochSeconds: Long? = null,
) {
    companion object {
        internal fun from(frame: BydClusterFrame, arrivalEpochSeconds: Long? = null): ClusterTurnGuidance {
            return ClusterTurnGuidance(frame.icon, frame.roundaboutExit, frame.distanceMeters, frame.road,
                frame.routeRemainingMeters, frame.routeRemainingSeconds, arrivalEpochSeconds)
        }
    }
}
