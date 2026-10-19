package com.andrerinas.openheadunit.hud

import android.content.Context
import android.content.Intent
import java.text.Normalizer

/**
 * AutoNavi standard broadcast to the stock map service that feeds the cluster/HUD.
 * DiLink 5 ships it as com.byd.amapservice; DiLink 3.0 (Android 10) ships the same role as
 * com.example.amapservice. On a DiLink 3.0 unit the same extras sent to it (together with
 * com.byd.automap) drew the turn arrow and countdown on the HUD.
 */
internal class BydClusterOutput(private val context: Context, private val targetPackage: String = PACKAGES.first()) {
    companion object {
        const val DILINK3_PACKAGE = "com.example.amapservice"
        val PACKAGES = listOf("com.byd.amapservice", DILINK3_PACKAGE)
        /** Shortest distance DiLink 3.0's HUD draws as a number (on a test unit 1 m and 5 m showed "现在", 10 m did not). */
        const val DILINK3_MIN_DISTANCE_METERS = 10

        /**
         * DiLink 3.0's HUD font has no accented letters: "Ferretería" was drawn as "Ferreter a".
         * Drop the diacritics (á→a, ñ→n, ü→u) so the street name stays readable.
         */
        fun plainLetters(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    }

    private var showing = false

    /**
     * DiLink 3.0's com.example.amapservice renders a short distance (below 10 m) as a Chinese
     * "now" caption on the HUD, and it has no other locale. Report 10 m there instead; the arrow
     * and the countdown are unchanged. A negative (unknown) distance is passed through.
     */
    private fun segmentDistance(meters: Int): Int =
        if (targetPackage == DILINK3_PACKAGE && meters in 0 until DILINK3_MIN_DISTANCE_METERS) DILINK3_MIN_DISTANCE_METERS
        else meters

    private fun roadName(road: String): String =
        if (targetPackage == DILINK3_PACKAGE) plainLetters(road) else road

    fun update(frame: BydGuidance?) {
        if (frame == null && !showing) return
        val intent = Intent("AUTONAVI_STANDARD_BROADCAST_SEND").setPackage(targetPackage)
            .addFlags(0x01000000)
            .putExtra("IS_BYD_MAP", true).putExtra("IS_BYD_BAIDU_MAP", false)
        if (frame != null) {
            intent.putExtra("KEY_TYPE", 10001).putExtra("TYPE", 0).putExtra("EXTRA_STATE", 0)
                .putExtra("EXTRA_IS_FOREGROUND", 0).putExtra("NEW_ICON", frame.clusterIcon)
                .putExtra("ROUNG_ABOUT_NUM", frame.roundaboutExit).putExtra("SEG_REMAIN_DIS", segmentDistance(frame.distanceMeters))
                .putExtra("NEXT_ROAD_NAME", roadName(frame.road)).putExtra("ROUTE_REMAIN_DIS", frame.remainingMeters)
                .putExtra("ROUTE_REMAIN_TIME", frame.remainingSeconds)
        } else {
            intent.putExtra("KEY_TYPE", 10019).putExtra("EXTRA_STATE", 9).putExtra("EXTRA_IS_FOREGROUND", 1)
                .putExtra("NEW_ICON", -1).putExtra("SEG_REMAIN_DIS", -1).putExtra("NEXT_ROAD_NAME", "")
                .putExtra("ROUTE_REMAIN_DIS", -1).putExtra("ROUTE_REMAIN_TIME", -1)
        }
        context.sendBroadcast(intent)
        showing = frame != null
    }
}
