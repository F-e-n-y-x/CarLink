package com.shilapi.xcertplay.hud

/** Next-turn instruction for the dashboard overlay. Icons use the AMap NEW_ICON vocabulary. */
data class ClusterTurnGuidance(
    val icon: Int,
    val roundaboutExit: Int,
    val distanceMeters: Int,
    val road: String,
) {
    companion object {
        fun from(frame: BydClusterFrame): ClusterTurnGuidance {
            val icon = if (frame.icon == 0) 9 else frame.icon
            return ClusterTurnGuidance(icon, frame.roundaboutExit, frame.distanceMeters, frame.road)
        }
    }
}
