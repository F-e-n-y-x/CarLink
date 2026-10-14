package com.carlink.core.connection

import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Learns the GPU's name (`GL_RENDERER`) once per firmware, from a 1×1 off-screen EGL context on a
 * background thread, and remembers it, so lite rendering can tell a weak GPU (PowerVR GE8322,
 * Mali-T720) from a capable one ([GpuClass]). Android has no API that names the GPU without a
 * GL context. [cached] is a preference read, safe on the main thread; until the first probe has
 * finished (the first start after an install or a firmware update) it is null and the GPU is not
 * a signal, so a GPU-only decision applies from the next start.
 */
object GpuProbe {
    private const val TAG = "CarLink-Capabilities"
    private const val PREFS = "carlink_device"
    private const val KEY_RENDERER = "gl_renderer"
    private const val KEY_FINGERPRINT = "gl_renderer_fingerprint"
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    fun cached(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_FINGERPRINT, null) != Build.FINGERPRINT) return null
        return prefs.getString(KEY_RENDERER, null)?.takeIf { it.isNotBlank() }
    }

    /** Probes on a low-priority thread unless this firmware's GPU is already known. */
    fun ensure(context: Context) {
        val app = context.applicationContext
        if (cached(app) != null || !running.compareAndSet(false, true)) return
        Thread({
            try {
                val started = SystemClock.uptimeMillis()
                val renderer = runCatching { readRenderer() }.onFailure { Log.w(TAG, "GL renderer probe failed", it) }.getOrNull()
                Log.i(TAG, "GL renderer '$renderer' in ${SystemClock.uptimeMillis() - started} ms")
                if (!renderer.isNullOrBlank()) {
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY_RENDERER, renderer).putString(KEY_FINGERPRINT, Build.FINGERPRINT).apply()
                    DeviceCapabilities.invalidateSignals()
                }
            } finally {
                running.set(false)
            }
        }, "carlink-gpu-probe").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun readRenderer(): String? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
        try {
            val attributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
            val config = configs[0] ?: return null
            val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            if (context == EGL14.EGL_NO_CONTEXT) return null
            val surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            try {
                if (surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, context)) return null
                return GLES20.glGetString(GLES20.GL_RENDERER)
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, context)
            }
        } finally {
            // Not eglTerminate: the display is shared with the app's own renderer (HWUI) in this process.
            EGL14.eglReleaseThread()
        }
    }
}
