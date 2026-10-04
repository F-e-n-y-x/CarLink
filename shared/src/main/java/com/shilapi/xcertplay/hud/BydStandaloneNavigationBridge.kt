package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object BydStandaloneNavigationBridge {
    private val lock = Any()
    private val route = BydHudRouteState()
    private var context: Context? = null
    private var output: BydStandaloneHudOutput? = null
    private var started = false

    fun initialize(appContext: Context) = synchronized(lock) {
        context = appContext.applicationContext
        if (output == null) output = BydStandaloneHudOutput.create(appContext)
        if (output != null && !started) {
            started = true
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "diplay-standalone-navi").apply { isDaemon = true }
            // 250ms, not 500ms: a lyrics line can be replaced between two slower ticks, which reads
            // as lag against the music. The session still collapses unchanged packets to one send
            // per second, so sampling faster costs nothing on the wire.
            }.scheduleWithFixedDelay(::tick, 0, 250, TimeUnit.MILLISECONDS)
        }
    }

    fun onFrame(frame: Iap2Frame) = synchronized(lock) {
        route.accept(frame.messageId, frame.payload)
        Unit
    }

    fun clear() = synchronized(lock) {
        route.clear() // A keepalive must never resurrect ended guidance.
        output?.clear()
        Unit
    }

    private fun tick() = synchronized(lock) {
        try {
            val app = context
            val frame = if (app != null && BydOutputSettings.enabled(app))
                route.currentApple()?.let(BydClusterFrame::from) else null
            // The line is looked up untouched whatever it says, because the setting only decides
            // whether it reaches the HUD, not whether it is followed.
            // The title alone, not "Title — Artist": the HUD row is a handful of fixed cells, and
            // a Latin artist name would take most of them away from the line being read.
            val line = app?.takeIf { BydOutputSettings.hudSong(it) }
                ?.let { BydClusterSong.current() }?.takeIf { it.playing }?.line
            // The line goes to the cluster whole and the firmware marquees it, the same scroll that
            // already carries any road name past the visible cells. Sliding it here as well put two
            // independent motions on one row -- ours by a cell every 500 ms, the cluster's on its own
            // cadence -- so they overlapped and read as a stutter. One scroller only: the cluster's.
            if (frame == null) {
                // Without guidance the HUD would blank every other second; keep the line instead.
                if (line == null) output?.clear() else output?.showText(line)
            } else {
                // Guidance and the line share the HUD's one text row: with the line on, it takes the
                // row over, so the arrows and distance keep showing with the lyrics where the road
                // name would be. Both travel in the same packet, which is what keeps them in step --
                // a separate text packet would race the arrows and lag behind them.
                output?.update(frame.icon, frame.roundaboutExit, frame.distanceMeters, line ?: frame.road)
            }
        } catch (error: Exception) {
            Log.w("DiPlay-Standalone", "HUD update/cleanup will retry", error)
        }
    }
}
