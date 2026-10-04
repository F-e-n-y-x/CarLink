package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.util.concurrent.ExecutorService

/**
 * Reaches the instrument cluster through the head unit's own adb daemon.
 *
 * Some firmware keeps its cluster projection display private to the OEM container service, so
 * DisplayManager never reports it to an ordinary app and a [android.app.Presentation] cannot be
 * created. A shell process may still place an activity on that display, which is how the stock map
 * and third-party launchers project onto the cluster. DiPlay already owns the same loopback adb
 * connection it uses for vehicle data, so it puts [ClusterMirrorActivity] there with `am start`.
 *
 * All of it is optional: without a trusted adbd the cluster map stays unavailable and CarPlay is
 * unchanged.
 */
internal object ClusterMirror {
    data class Target(val displayId: Int, val width: Int, val height: Int)

    /** The activity once shell has placed it on the cluster, otherwise null. Same process, direct reference. */
    @Volatile var activity: ClusterMirrorActivity? = null
        private set

    /** Whichever host owns the CarPlay session; surface delivery goes here. Set on the main thread. */
    @Volatile var listener: Listener? = null

    /** True from [launch] until the activity appears, so nobody else's launch survives. */
    @Volatile var expecting = false

    interface Listener {
        fun onClusterMirrorSurface(surface: Surface?)
        fun onClusterMirrorClosed()
    }

    private const val PREFS = "diplay_cluster_mirror"
    private const val KEY_ID = "displayId"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"
    private val DISPLAY_INFO =
        Regex("""DisplayInfo\{"([^"]+), displayId (\d+)", uniqueId "[^"]*", app (\d+) x (\d+)""")

    private var adb: LocalAdb? = null
    private var retryAtMillis = 0L
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Runs [command] over the same loopback adbd link the vehicle readouts use. Background use never
     * offers the ADB key, so the car's approval dialog cannot appear while driving, and a refused or
     * absent adbd is retried slowly instead of on every call.
     */
    @Synchronized
    private fun shell(context: Context, command: String): String? {
        val now = SystemClock.elapsedRealtime()
        if (now < retryAtMillis) return null
        val client = adb ?: LocalAdb(AdbKeys.load(context)).also { adb = it }
        if (client.connect(mayAsk = false) != LocalAdb.Access.READY) {
            retryAtMillis = now + RETRY_MILLIS
            Log.w(ClusterMapPresentation.TAG, "adb unavailable for the cluster mirror")
            return null
        }
        return client.shell(command)
    }

    /**
     * Finds the cluster display as adbd sees it and caches it, because every later decision — after
     * this battery of reconnects, in particular requesting the stream — needs it synchronously.
     */
    fun probe(
        context: Context,
        theme: DiLink51ClusterLayout.Theme = DiLink51ClusterLayout.theme(context),
    ): Target? {
        val output = shell(context, "dumpsys display") ?: return null
        val found = LinkedHashMap<String, Target>()
        DISPLAY_INFO.findAll(output).forEach { match ->
            val id = match.groupValues[2].toIntOrNull() ?: return@forEach
            val width = match.groupValues[3].toIntOrNull() ?: return@forEach
            val height = match.groupValues[4].toIntOrNull() ?: return@forEach
            if (id <= 0 || width <= 0 || height <= 0) return@forEach
            // Keep the first display for each name: the logical section repeats them.
            found.putIfAbsent(match.groupValues[1], Target(id, width, height))
        }
        val name = DiLink51ClusterLayout.displayName(found.keys.toList(), Build.FINGERPRINT, theme)
            ?: return null
        val target = found[name] ?: return null
        Log.i(ClusterMapPresentation.TAG, "cluster mirror target=$target name=$name")
        remember(context, target)
        return target
    }

    /** The last target this app resolved, or null before the first successful probe. */
    fun cached(context: Context): Target? {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val width = prefs.getInt(KEY_WIDTH, 0)
        val height = prefs.getInt(KEY_HEIGHT, 0)
        val id = prefs.getInt(KEY_ID, 0)
        return if (id > 0 && width > 0 && height > 0) Target(id, width, height) else null
    }

    fun remember(context: Context, target: Target?) {
        val edit = context.getSharedPreferences(PREFS, 0).edit()
        if (target == null) edit.clear() else {
            edit.putInt(KEY_ID, target.displayId)
                .putInt(KEY_WIDTH, target.width)
                .putInt(KEY_HEIGHT, target.height)
        }
        edit.apply()
    }

    /**
     * Asks adbd to put [ClusterMirrorActivity] on the cluster. adbd is a socket, and the caller sits
     * on the main thread, so the round trip always runs on [executor] and the result comes back on
     * the main thread. The surface itself arrives later through [listener].
     */
    fun launch(context: Context, target: Target, executor: ExecutorService, onResult: (Boolean) -> Unit) {
        if (activity != null || expecting) {
            handler.post { onResult(activity != null) }
            return
        }
        expecting = true
        val component = "${context.packageName}/${ClusterMirrorActivity::class.java.name}"
        executor.execute {
            val output = shell(context, "am start --display ${target.displayId} -n $component")
            val headline = output?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(160).orEmpty()
            val started = headline.isNotEmpty() && output?.contains("error", ignoreCase = true) != true
            Log.i(ClusterMapPresentation.TAG, "cluster mirror launch started=$started $headline")
            if (!started) expecting = false
            handler.post { onResult(started) }
        }
    }

    /** Dismisses the cluster window. The activity lives in this process, so it can simply finish. */
    fun stop() {
        expecting = false
        listener = null
        activity?.finish()
    }

    /**
     * Reports the window [ClusterMirror] opened, or false if it arrived from anywhere else. A true
     * value also covers the configuration change that recreates it.
     */
    internal fun admit(activity: ClusterMirrorActivity): Boolean {
        if (!expecting && this.activity == null) return false
        expecting = false
        this.activity = activity
        return true
    }

    internal fun detach(activity: ClusterMirrorActivity) {
        if (this.activity !== activity) return
        this.activity = null
        listener?.onClusterMirrorClosed()
    }

    private const val RETRY_MILLIS = 30_000L
}
