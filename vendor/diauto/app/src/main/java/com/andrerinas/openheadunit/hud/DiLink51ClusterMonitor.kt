package com.andrerinas.openheadunit.hud

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresApi
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Follows the stock cluster screens through the owner's Usage access grant, never a privileged BYD
 * API. Ported from DiPlay. Polls every 500 ms and reports a [ClusterActivityState.Snapshot] on the
 * main thread.
 */
@RequiresApi(Build.VERSION_CODES.P)
internal class DiLink51ClusterMonitor(context: Context, private val onState: (ClusterActivityState.Snapshot) -> Unit) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "diauto-cluster-monitor").apply { isDaemon = true } }
    private var state = ClusterActivityState()
    private var since = bootTime()
    private val seen = linkedMapOf<EventKey, Long>()
    // This firmware includes the system getter. If Android hides it, single-instance stock
    // activities still work, but an overlapping recreation conservatively hides the map.
    private val instanceIdMethod = runCatching { UsageEvents.Event::class.java.getMethod("getInstanceId") }.getOrNull()
    @Volatile private var stopped = false
    private data class EventKey(val pkg: String?, val name: String?, val id: Int, val type: Int, val time: Long)

    fun start() {
        executor.scheduleWithFixedDelay({ poll() }, 0, 500, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        stopped = true
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }

    private fun bootTime() = (System.currentTimeMillis() - SystemClock.elapsedRealtime()).coerceAtLeast(0)

    private fun poll() {
        if (stopped) return
        val now = System.currentTimeMillis()
        val snapshot = try {
            if (!hasAccess(context)) {
                state = ClusterActivityState()
                seen.clear()
                since = bootTime()
            } else {
                if (now < since) { // A vehicle clock correction must not leave an old overlay visible.
                    state = ClusterActivityState()
                    seen.clear()
                    since = bootTime()
                }
                val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(since, now)
                    ?: throw IllegalStateException("Usage events unavailable")
                val event = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    val reset = event.eventType == UsageEvents.Event.DEVICE_SHUTDOWN || event.eventType == UsageEvents.Event.DEVICE_STARTUP
                    if (!reset && !ClusterActivityState.accepted(event.packageName, event.className)) continue
                    val instance = runCatching { instanceIdMethod?.invoke(event) as? Int }.getOrNull() ?: 0
                    val key = EventKey(event.packageName, event.className, instance, event.eventType, event.timeStamp)
                    if (seen.put(key, event.timeStamp) == null) {
                        state.event(event.packageName, event.className, instance, event.eventType, event.timeStamp)
                    }
                }
                // Overlap handles asynchronously delivered events; deduplicate only the allowed cluster events.
                since = (now - 2_000).coerceAtLeast(bootTime())
                seen.entries.removeAll { it.value < since }
            }
            state.snapshot()
        } catch (_: RuntimeException) {
            state = ClusterActivityState()
            seen.clear()
            since = bootTime()
            state.snapshot()
        }
        handler.post { if (!stopped) onState(snapshot) }
    }

    companion object {
        /** The cluster right now, from the usage events since boot; null without Usage access. */
        fun snapshotNow(context: Context): ClusterActivityState.Snapshot? {
            if (!hasAccess(context)) return null
            return runCatching {
                val now = System.currentTimeMillis()
                val since = (now - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(since, now)
                    ?: return@runCatching null
                val instanceId = runCatching { UsageEvents.Event::class.java.getMethod("getInstanceId") }.getOrNull()
                val state = ClusterActivityState()
                val event = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    val reset = event.eventType == UsageEvents.Event.DEVICE_SHUTDOWN || event.eventType == UsageEvents.Event.DEVICE_STARTUP
                    if (!reset && !ClusterActivityState.accepted(event.packageName, event.className)) continue
                    val instance = runCatching { instanceId?.invoke(event) as? Int }.getOrNull() ?: 0
                    state.event(event.packageName, event.className, instance, event.eventType, event.timeStamp)
                }
                state.snapshot()
            }.getOrNull()
        }

        fun hasAccess(context: Context): Boolean = context.getSystemService(AppOpsManager::class.java)
            .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }
}
