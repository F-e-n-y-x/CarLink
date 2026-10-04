package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R

/**
 * The instrument-cluster window for firmware that keeps its cluster display private. [ClusterMirror]
 * has adbd place this activity on that display, because third-party apps cannot reach it through
 * DisplayManager. It draws the whole cluster stream, exactly as the non-verified branch of
 * [ClusterMapPresentation] does; leftover crop and theming belong to the cluster itself.
 */
internal class ClusterMirrorActivity : Activity() {
    var outputSurface: Surface? = null
        private set
    private var waitingLabel: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        if (!ClusterMirror.admit(this)) {
            // Anything other than ClusterMirror's own adb launch closes at once.
            Log.w(ClusterMapPresentation.TAG, "cluster mirror activity rejected unexpected launch")
            finish()
            return
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(ClusterMapPresentation.TAG, "cluster mirror surface created")
                outputSurface = holder.surface
                ClusterMirror.listener?.onClusterMirrorSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i(ClusterMapPresentation.TAG, "cluster mirror surface ${width}x$height")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(ClusterMapPresentation.TAG, "cluster mirror surface destroyed")
                ClusterMirror.listener?.onClusterMirrorSurface(null)
            }
        })
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        waitingLabel = TextView(this).apply {
            text = getString(R.string.cluster_waiting_for_map)
            setTextColor(Color.WHITE)
            textSize = 26f
            gravity = Gravity.CENTER
        }
        root.addView(waitingLabel, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        Log.i(ClusterMapPresentation.TAG, "cluster mirror activity created display=${display?.displayId}")
    }

    /** Hides the placeholder once the phone streams the cluster screen. */
    fun setStreamActive(active: Boolean) {
        waitingLabel?.visibility = if (active) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        outputSurface = null
        ClusterMirror.detach(this)
        super.onDestroy()
    }
}
