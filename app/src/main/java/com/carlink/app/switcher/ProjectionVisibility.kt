package com.carlink.app.switcher

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Process
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.carlink.core.core.Engine
import com.shilapi.xcertplay.CarPlayHostActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What is in front, as far as the floating switcher cares. */
data class FrontState(
    /** Engines whose projection activity is resumed. */
    val projections: Set<Engine> = emptySet(),
    /** Resumed CarLink screens in the main process (Home and anything it opens). */
    val carLinkScreens: Int = 0,
) {
    val carLinkScreenInFront: Boolean get() = carLinkScreens > 0
}

/**
 * Tracks whether a projection is on screen, for [SwitcherController] (main process).
 *
 * CarPlay's [CarPlayHostActivity] lives in the main process, so an [Application.ActivityLifecycleCallbacks]
 * sees it directly. Android Auto's [AapProjectionActivity] lives in ":aa"; that process runs
 * [installInEngineProcess], whose callbacks send one explicit package broadcast per resume and
 * pause (the same pattern as AaEngineHooks' state broadcasts). Resumed/paused rather than
 * started/stopped: a paused projection (split screen partner focused, picture-in-picture, a system
 * dialog on top) is not where the driver is looking.
 */
object ProjectionVisibility {
    private const val ACTION = "com.carlink.app.action.SWITCHER_PROJECTION"
    private const val EXTRA_VISIBLE = "visible"
    private const val EXTRA_PID = "pid"

    private val _front = MutableStateFlow(FrontState())
    val front: StateFlow<FrontState> = _front.asStateFlow()

    /** ":aa"'s pid while it reports Android Auto in front; checked so a killed ":aa" does not stay "visible". */
    @Volatile private var aaPid = 0
    private var installed = false

    /** Main process: watches CarLink's own activities and listens for ":aa"'s reports. */
    fun installInMainProcess(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : LifecycleAdapter() {
            override fun onActivityResumed(activity: Activity) = update(activity, resumed = true)
            override fun onActivityPaused(activity: Activity) = update(activity, resumed = false)
        })
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val visible = intent.getBooleanExtra(EXTRA_VISIBLE, false)
                aaPid = if (visible) intent.getIntExtra(EXTRA_PID, 0) else 0
                setProjection(Engine.ANDROID_AUTO, visible)
            }
        }, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** ":aa" process: reports AapProjectionActivity's resume and pause to the main process. */
    fun installInEngineProcess(app: Application) {
        app.registerActivityLifecycleCallbacks(object : LifecycleAdapter() {
            override fun onActivityResumed(activity: Activity) = report(activity, true)
            override fun onActivityPaused(activity: Activity) = report(activity, false)

            private fun report(activity: Activity, visible: Boolean) {
                if (activity !is AapProjectionActivity) return
                app.sendBroadcast(Intent(ACTION).setPackage(app.packageName)
                    .putExtra(EXTRA_VISIBLE, visible)
                    .putExtra(EXTRA_PID, Process.myPid()))
            }
        })
    }

    /**
     * Drops Android Auto from the front set when the ":aa" process that reported it is gone (a
     * process killed while resumed never sends its pause).
     */
    fun forgetDeadEngineProcess(context: Context) {
        val pid = aaPid
        if (pid == 0 || Engine.ANDROID_AUTO !in _front.value.projections) return
        val alive = runCatching {
            context.getSystemService(ActivityManager::class.java)?.runningAppProcesses?.any { it.pid == pid }
        }.getOrNull() ?: true
        if (!alive) {
            aaPid = 0
            setProjection(Engine.ANDROID_AUTO, false)
        }
    }

    private fun update(activity: Activity, resumed: Boolean) {
        when {
            activity is CarPlayHostActivity -> setProjection(Engine.CARPLAY, resumed)
            // Activities of the main process other than the projection: CarLink's own screens.
            isCarLinkScreen(activity) -> _front.value = _front.value.let {
                it.copy(carLinkScreens = (it.carLinkScreens + if (resumed) 1 else -1).coerceAtLeast(0))
            }
        }
    }

    private fun isCarLinkScreen(activity: Activity) = activity.javaClass.name.startsWith("com.carlink.app.")

    private fun setProjection(engine: Engine, visible: Boolean) {
        _front.value = _front.value.let { it.copy(projections = if (visible) it.projections + engine else it.projections - engine) }
    }

    private abstract class LifecycleAdapter : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
