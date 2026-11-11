package com.carlink.app.switcher

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowManager
import com.carlink.core.connection.Connection
import com.carlink.core.connection.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The switcher's screen dimmer: a black overlay window of the bubble's type over the projection,
 * which every touch passes through. It goes away when the session ends. Main thread only.
 *
 * The window's own alpha does the dimming and stays at or under 0.8: Android 12+ blocks touches
 * under an untrusted overlay that is more opaque than that.
 */
object ScreenDimmer {
    /** Off, then three steps darker; the panel's tile cycles through them. */
    val ALPHAS = listOf(0f, 0.3f, 0.5f, 0.7f)

    /** The current step of [ALPHAS]. */
    var level = 0
        private set

    private var view: View? = null
    private var watch: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun set(context: Context, newLevel: Int) {
        level = newLevel.coerceIn(0, ALPHAS.lastIndex)
        if (level == 0) return remove(context)
        val app = context.applicationContext
        val windows = app.getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            @Suppress("DEPRECATION") if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            alpha = ALPHAS[level]
            title = "CarLink dimmer"
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val current = view
        runCatching {
            if (current == null) windows.addView(View(app).apply { setBackgroundColor(Color.BLACK) }.also { view = it }, params)
            else windows.updateViewLayout(current, params)
        }.onFailure {
            Log.w("CarLink-Switcher", "cannot show the dimmer", it)
            view = null
            level = 0
            return
        }
        if (watch == null) watch = scope.launch {
            Connection.get(app).state.first { it !is SessionState.Active }
            remove(app)
        }
    }

    fun remove(context: Context) {
        level = 0
        watch?.cancel()
        watch = null
        val current = view ?: return
        view = null
        runCatching { context.applicationContext.getSystemService(WindowManager::class.java).removeViewImmediate(current) }
    }
}
