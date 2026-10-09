package com.carlink.app.switcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.abs

/**
 * The owners Compose needs in a window that no activity hosts. Resumed while the window is
 * attached, destroyed when it is removed.
 */
internal class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    init {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun attach(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}

@Suppress("DEPRECATION")
private val overlayType: Int
    get() = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else WindowManager.LayoutParams.TYPE_PHONE

/**
 * The bubble's window: [size] × [size] px, never focusable, so the projection keeps key focus and
 * every touch outside the bubble goes straight to it. The container handles taps and drags itself
 * with raw screen coordinates (the window moves under the finger, so view-relative coordinates
 * would jitter); the Compose content only draws.
 */
@SuppressLint("ViewConstructor")
internal class BubbleWindow(
    context: Context,
    private val windowManager: WindowManager,
    size: Int,
    placement: BubblePlacement,
    private val callbacks: Callbacks,
    content: @Composable () -> Unit,
) : FrameLayout(context) {
    interface Callbacks {
        fun onTap()
        fun onTouchStart()
        fun onDrag(x: Int, y: Int): Pair<Int, Int>
        fun onRelease(x: Int, y: Int)
    }

    private val owner = OverlayOwner()
    val params = WindowManager.LayoutParams(
        size, size, overlayType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = placement.x
        y = placement.y
        title = "CarLink switcher"
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    init {
        val compose = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent(content)
        }
        owner.attach(this)
        addView(compose, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        isClickable = true
        isFocusable = false
    }

    fun show() = windowManager.addView(this, params)

    fun remove() {
        runCatching { windowManager.removeViewImmediate(this) }
        owner.destroy()
    }

    fun moveTo(x: Int, y: Int) {
        if (params.x == x && params.y == y) return
        params.x = x
        params.y = y
        if (isAttachedToWindow) runCatching { windowManager.updateViewLayout(this, params) }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    @SuppressLint("ClickableViewAccessibility") // performClick() is called for taps below.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX; downRawY = event.rawY
                startX = params.x; startY = params.y
                dragging = false
                callbacks.onTouchStart()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                if (dragging) {
                    val (x, y) = callbacks.onDrag(startX + dx.toInt(), startY + dy.toInt())
                    moveTo(x, y)
                }
            }
            MotionEvent.ACTION_UP -> if (dragging) callbacks.onRelease(params.x, params.y) else performClick()
            MotionEvent.ACTION_CANCEL -> callbacks.onRelease(params.x, params.y)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        callbacks.onTap()
        return true
    }
}

/**
 * The panel's window: full screen, focusable, so Back reaches it, with a scrim that closes it
 * when touched. It holds no text field, so it never needs the keyboard (no IME flags).
 */
@SuppressLint("ViewConstructor")
internal class PanelWindow(
    context: Context,
    private val windowManager: WindowManager,
    private val onBack: () -> Unit,
    content: @Composable () -> Unit,
) : FrameLayout(context) {
    private val owner = OverlayOwner()
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "CarLink switcher panel"
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    init {
        val compose = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent(content)
        }
        owner.attach(this)
        addView(compose, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        isFocusableInTouchMode = true
    }

    fun show() {
        windowManager.addView(this, params)
        requestFocus()
    }

    fun remove() {
        runCatching { windowManager.removeViewImmediate(this) }
        owner.destroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
