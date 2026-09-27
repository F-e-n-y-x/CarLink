package com.shilapi.xcertplay

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Shows CarPlay's instrument-cluster stream on a BYD cluster projection display.
 *
 * BYD exposes the cluster's projection area as public presentation displays owned by
 * com.byd.containerservice; the stock map (com.byd.launchermap) draws there the same way. The
 * cluster only shows this display while its projection mode is on, which DiPlay cannot switch.
 */
internal class ClusterMapPresentation(
    context: Context,
    display: Display,
    private val onSurface: (Surface?) -> Unit,
) : Presentation(context, display) {
    private var waitingLabel: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val surfaceView = SurfaceView(context)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "cluster surface created")
                onSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i(TAG, "cluster surface ${width}x$height")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "cluster surface destroyed")
                onSurface(null)
            }
        })
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        waitingLabel = TextView(context).apply {
            text = "DiPlay · waiting for the CarPlay map"
            setTextColor(Color.WHITE)
            textSize = 26f
            gravity = Gravity.CENTER
        }
        root.addView(waitingLabel, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    /** Hides the placeholder once the phone streams the cluster screen. */
    fun setStreamActive(active: Boolean) {
        waitingLabel?.visibility = if (active) View.GONE else View.VISIBLE
    }

    companion object {
        const val TAG = "DiPlay-Cluster"
        private const val BYD_PROJECTION_DISPLAY = "fission_bg_XDJAScreenProjection"

        /** The cluster projection display, preferring the one the stock map uses. */
        /**
         * The cluster projection display. BYD hides the stock map's display (`fission_bg_…`) from
         * third-party apps; its `shared_…_0` sibling is composited on top of it, so that one works.
         */
        fun findDisplay(context: Context): Display? {
            val displays = context.getSystemService(DisplayManager::class.java)
                ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION) ?: return null
            return displays.firstOrNull { it.name == BYD_PROJECTION_DISPLAY }
                ?: displays.firstOrNull { it.name.contains(BYD_PROJECTION_DISPLAY) && it.name.endsWith("_0") }
                ?: displays.firstOrNull { it.name.contains(BYD_PROJECTION_DISPLAY) }
        }

        fun describeDisplays(context: Context): String =
            context.getSystemService(DisplayManager::class.java)?.displays
                ?.joinToString { "${it.displayId}:${it.name}" }.orEmpty()

        fun sizeOf(display: Display): Point = Point().also {
            @Suppress("DEPRECATION")
            display.getRealSize(it)
        }
    }
}
