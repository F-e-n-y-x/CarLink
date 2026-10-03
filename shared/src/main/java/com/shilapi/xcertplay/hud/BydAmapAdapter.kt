package com.shilapi.xcertplay.hud

/**
 * The stock AMap adapter that turns AUTONAVI_STANDARD_BROADCAST_SEND broadcasts into cluster guidance.
 * DiLink 3 (Android 10, Qualcomm 6125, "1for2" cluster) ships the same receiver as
 * com.example.amapservice, and its cluster shows the guidance card only in simple-navigation mode.
 */
internal enum class BydAmapAdapter(val packageName: String, val needsSimpleNavigationMode: Boolean) {
    BYD("com.byd.amapservice", needsSimpleNavigationMode = false),
    DILINK3("com.example.amapservice", needsSimpleNavigationMode = true);

    companion object {
        fun find(installed: (String) -> Boolean): BydAmapAdapter? = entries.firstOrNull { installed(it.packageName) }
    }
}

/**
 * The DiLink 3 cluster mode, switched through the AutoContainer binder as ClusterDebug does with
 * sendInfo(1000, command, ""). Apps would need a BYD signature; the adb shell may call it.
 * ClusterDebug labels 16 "full-screen projection on", 17 "half-screen projection on",
 * 18 "projection off" (the stock state) and 39 "simple navigation".
 */
internal object BydDiLink3ClusterMode {
    enum class Mode(val info: Int) {
        // Half screen keeps the cluster's own speed and status readouts beside the map.
        PROJECTION(17),
        SIMPLE_NAVIGATION(39),
        STOCK(18);

        val command: String get() = "service call AutoContainer 2 i32 1000 i32 $info s16 \"\""
    }

    /** The mode to request now, or null while DiPlay has never changed the stock mode. */
    fun desired(mapShown: Boolean, guidanceActive: Boolean, requested: Mode?): Mode? = when {
        mapShown -> Mode.PROJECTION
        guidanceActive -> Mode.SIMPLE_NAVIGATION
        requested != null -> Mode.STOCK
        else -> null
    }

    /** True when the binder call returned without an exception. */
    fun accepted(output: String?): Boolean = BydParcel.words(output).firstOrNull() == 0L
}
