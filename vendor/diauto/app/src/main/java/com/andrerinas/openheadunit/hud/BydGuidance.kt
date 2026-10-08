package com.andrerinas.openheadunit.hud

/** Complete immutable snapshot shared by the Android Auto input and BYD outputs. */
internal data class BydGuidance(
    val clusterIcon: Int,
    val gaodeIcon: Int,
    val hudArrow: Int,
    val roundaboutExit: Int,
    val distanceMeters: Int,
    val road: String,
    val remainingMeters: Int = -1,
    val remainingSeconds: Int = -1,
    val updatedNs: Long = System.nanoTime(),
)
