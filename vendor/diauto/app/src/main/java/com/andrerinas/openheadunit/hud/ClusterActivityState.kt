package com.andrerinas.openheadunit.hud

/**
 * Which stock BYD cluster screens are on, from Android's usage events, ported from DiPlay. The
 * cluster theme follows from the theme's own activity, and the side map is on screen only while
 * its card (MeterSmallScreenActivity) is open: Scenario keeps the projection layer up on every
 * card, so DiAuto hides its map itself when another card is open. Head-unit apps are irrelevant.
 */
internal class ClusterActivityState {
    data class Snapshot(
        val theme: ClusterMapConfig.Theme?,
        val mapVisible: Boolean,
        /** The small navi window (not the full map) is the newest visible map activity. */
        val smallWindow: Boolean = false,
    )
    private data class Activity(val name: String, val instance: Int)
    private val visible = mutableMapOf<Activity, Long>()

    fun event(pkg: String?, name: String?, instance: Int, type: Int, time: Long) {
        if (type == 26 || type == 27) { // UsageEvents DEVICE_SHUTDOWN / DEVICE_STARTUP
            visible.clear()
            return
        }
        if (!accepted(pkg, name)) return
        val key = Activity(name!!, instance)
        when (type) {
            1 -> visible[key] = time // ACTIVITY_RESUMED
            23 -> visible.remove(key) // ACTIVITY_STOPPED; paused activities can still be visible.
        }
    }

    fun snapshot(): Snapshot {
        val theme = visible.entries.filter { themeOf(it.key.name) != null }
            .maxByOrNull { it.value }?.key?.name?.let(::themeOf)
        val latestMap = visible.entries
            .filter { it.key.name == FULL_MAP || it.key.name == MINI_MAP }
            .maxByOrNull { it.value }?.key?.name
        val miniMap = visible.keys.any { it.name == MINI_MAP }
        return Snapshot(theme, theme == ClusterMapConfig.Theme.MAP || miniMap, latestMap == MINI_MAP)
    }

    private fun themeOf(name: String) = when (name) {
        FULL_MAP -> ClusterMapConfig.Theme.MAP
        SCENARIO -> ClusterMapConfig.Theme.SCENARIO
        SIMPLE -> ClusterMapConfig.Theme.SIMPLE
        else -> null
    }

    companion object {
        const val FULL_MAP = "com.byd.automap.meter.MeterActivity"
        const val MINI_MAP = "com.byd.automap.meter.MeterSmallScreenActivity"
        const val SCENARIO = "com.byd.sr.cluster.ClusterActivity"
        const val SIMPLE = "com.byd.cluster.SimpleClusterDynastyActivity"
        fun accepted(pkg: String?, name: String?): Boolean = when (pkg) {
            "com.byd.launchermap", "com.byd.automap" -> name == FULL_MAP || name == MINI_MAP
            "com.byd.sr" -> name == SCENARIO
            "com.byd.cluster" -> name == SIMPLE
            else -> false
        }
    }
}
