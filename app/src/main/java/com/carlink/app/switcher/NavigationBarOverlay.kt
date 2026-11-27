package com.carlink.app.switcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import com.carlink.app.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The navigation bar switch (Settings › General › Navigation bar). Off by default: the bar needs
 * [NavigationBarService], which the driver has to turn on in Android's accessibility settings.
 */
internal object NavigationBarPreference {
    const val DEFAULT_ENABLED = false
    private const val FILE = "carlink_navigation_bar"
    private const val KEY_ENABLED = "enabled"

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = preferences(context).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}

/**
 * An on-screen navigation bar for head units that have none (or hide theirs): an 8 dp strip on
 * each screen edge. An inward swipe from an edge opens a bar on that edge with Back, Home,
 * CarLink and Android settings; the same swipe again closes it.
 *
 * Why strips and not a button that is always visible: nothing is drawn while the bar is closed, so
 * CarPlay and Android Auto keep the whole screen, and the strips stay up during a projection so
 * the bar can still be opened to go back.
 *
 * An overlay window cannot hand a touch it has received to the app below, so a touch on a closed
 * strip that is not an inward swipe (a tap, a long press, a vertical drag) is replayed to the app
 * underneath with [AccessibilityService.dispatchGesture] while the strip is briefly untouchable.
 * Back and Home are [AccessibilityService.performGlobalAction]s, which is why this runs in an
 * accessibility service ([NavigationBarService]): that is also what allows a
 * `TYPE_ACCESSIBILITY_OVERLAY` window, which needs no "Display over other apps" permission.
 */
internal class NavigationBarOverlay(private val service: AccessibilityService) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private val preferences = NavigationBarPreference.preferences(service)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
    private val roots = mutableListOf<View>()
    private var expandedRight: Boolean? = null

    fun start() {
        preferences.registerOnSharedPreferenceChangeListener(listener)
        refresh()
    }

    fun stop() {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
        remove()
        expandedRight = null
    }

    private fun refresh() {
        expandedRight = null
        render()
    }

    private fun render() {
        remove()
        if (!NavigationBarPreference.enabled(service)) return
        addEdge(onRight = false)
        addEdge(onRight = true)
    }

    private fun addEdge(onRight: Boolean) {
        val manager = windows ?: return
        val expanded = expandedRight == onRight
        lateinit var params: WindowManager.LayoutParams
        lateinit var view: GestureColumn
        view = GestureColumn(
            service,
            onRight,
            passThrough = !expanded,
            onSwipe = {
                expandedRight = if (expandedRight == onRight) null else onRight
                render()
            },
            onReplay = { stroke, duration -> replay(manager, view, params, stroke, duration) },
        )
        if (expanded) {
            view.background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(12).toFloat()
            }
            view.setPadding(0, dp(4), 0, dp(4))
            addNavigationButtons(view)
        } else {
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        params = WindowManager.LayoutParams(
            if (expanded) (service.resources.displayMetrics.heightPixels * 0.115f).roundToInt() else dp(8),
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or if (onRight) Gravity.RIGHT else Gravity.LEFT
            title = service.getString(R.string.carlink_nav_bar_title)
        }
        attach(manager, view, params)
    }

    /**
     * Sends a touch the closed strip received to the app below: the strip stops taking touches,
     * the same stroke is dispatched, and the strip takes touches again when it ends.
     */
    private fun replay(
        manager: WindowManager,
        view: View,
        params: WindowManager.LayoutParams,
        stroke: Path,
        durationMillis: Long,
    ) {
        if (view !in roots) return
        fun setTouchable(touchable: Boolean) {
            if (view !in roots) return
            params.flags = if (touchable) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try {
                manager.updateViewLayout(view, params)
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "navigation strip already removed", error)
            }
        }
        setTouchable(false)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(stroke, 0, durationMillis.coerceIn(1L, MAX_REPLAY_MILLIS)))
            .build()
        val restore = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = setTouchable(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = setTouchable(true)
        }
        val sent = try {
            service.dispatchGesture(gesture, restore, null)
        } catch (error: IllegalStateException) {
            Log.w(TAG, "touch replay unavailable", error)
            false
        }
        if (!sent) setTouchable(true)
    }

    internal fun addNavigationButtons(view: LinearLayout) {
        view.button(R.drawable.carlink_ic_arrow_back, R.string.carlink_nav_bar_back) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
        view.button(R.drawable.carlink_ic_home, R.string.carlink_nav_bar_home) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
        view.button(R.mipmap.ic_launcher, R.string.app_name, tintWhite = false) {
            val launcher = service.packageManager.getLaunchIntentForPackage(service.packageName)
            if (launcher != null) openDestination(launcher)
        }
        view.button(R.drawable.carlink_ic_settings, R.string.carlink_nav_bar_settings) {
            openDestination(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun attach(windows: WindowManager, view: View, params: WindowManager.LayoutParams) {
        try {
            windows.addView(view, params)
            roots.add(view)
        } catch (error: WindowManager.BadTokenException) {
            Log.w(TAG, "Accessibility overlay unavailable", error)
        }
    }

    private fun openDestination(intent: Intent): Boolean {
        try {
            service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: android.content.ActivityNotFoundException) {
            return false
        } catch (_: SecurityException) {
            return false
        }
        expandedRight = null
        render()
        return true
    }

    private fun remove() {
        for (view in roots) {
            try {
                windows?.removeViewImmediate(view)
            } catch (error: IllegalArgumentException) {
                // The system already dropped the window (for example when the service disconnected).
                Log.w(TAG, "navigation overlay already removed", error)
            }
        }
        roots.clear()
    }

    private fun LinearLayout.button(icon: Int, label: Int, tintWhite: Boolean = true, action: () -> Unit) {
        val button = object : ImageButton(service) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                val iconSize = minOf(dp(32), w, h)
                val left = (w - iconSize) / 2
                val top = (h - iconSize) / 2
                setPadding(left, top, w - iconSize - left, h - iconSize - top)
            }
        }.apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setImageResource(icon)
            if (tintWhite) setColorFilter(Color.WHITE)
            contentDescription = service.getString(label)
            background = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), null,
                GradientDrawable().apply { setColor(Color.WHITE) },
            )
            setOnClickListener { action() }
        }
        addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).roundToInt()

    /**
     * One edge. Closed ([passThrough]): an inward horizontal swipe toggles the bar and anything
     * else is handed to [onReplay]. Open: the buttons get their clicks and an inward swipe on the
     * bar closes it.
     */
    private class GestureColumn(
        context: Context,
        private val onRight: Boolean,
        private val passThrough: Boolean,
        private val onSwipe: () -> Unit,
        private val onReplay: (stroke: Path, durationMillis: Long) -> Unit,
    ) : LinearLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val threshold = maxOf(2 * slop, (24 * resources.displayMetrics.density).roundToInt())
        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L
        private var intercepting = false

        init { orientation = VERTICAL }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    intercepting = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val x = abs(event.rawX - downX)
                    val y = abs(event.rawY - downY)
                    if (x > slop && x > y) intercepting = true
                }
                MotionEvent.ACTION_CANCEL -> intercepting = false
            }
            return intercepting
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downTime = event.eventTime
                }
                MotionEvent.ACTION_UP -> {
                    val x = event.rawX - downX
                    val y = event.rawY - downY
                    val towardCenter = if (onRight) -x else x
                    if (towardCenter >= threshold && abs(x) > abs(y)) {
                        post { onSwipe() }
                    } else if (passThrough) {
                        val stroke = Path().apply {
                            moveTo(downX.coerceAtLeast(0f), downY.coerceAtLeast(0f))
                            lineTo(event.rawX.coerceAtLeast(0f), event.rawY.coerceAtLeast(0f))
                        }
                        val duration = event.eventTime - downTime
                        post { onReplay(stroke, duration) }
                    }
                    intercepting = false
                }
                MotionEvent.ACTION_CANCEL -> intercepting = false
            }
            return true
        }
    }

    private companion object {
        const val TAG = "NavigationBar"
        const val MAX_REPLAY_MILLIS = 10_000L
    }
}
