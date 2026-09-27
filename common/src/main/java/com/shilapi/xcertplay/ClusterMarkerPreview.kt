package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay

/**
 * A small drawing of the BYD cluster for the settings page: where BYD draws its own readouts
 * (grey) and where the CarPlay car marker will sit (dot), so the driver sees the effect of the
 * marker settings before CarPlay reconnects.
 */
internal class ClusterMarkerPreview(context: Context) : View(context) {
    private var horizontalStep = 0
    private var verticalStep = 0

    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(28, 52, 92) }
    private val overlay = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 150, 160, 175) }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 170, 255) }
    private val markerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    fun setMarker(horizontal: Int, vertical: Int) {
        horizontalStep = horizontal
        verticalStep = vertical
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // Keep the cluster's 8:3 shape inside whatever space the settings row gives.
        val w = minOf(width.toFloat(), height * 8f / 3f)
        val h = w * 3f / 8f
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        val radius = h * 0.12f
        canvas.drawRoundRect(RectF(left, top, left + w, top + h), radius, radius, panel)
        for (zone in BYD_OVERLAYS) {
            canvas.drawRect(left + w * zone.left, top + h * zone.top, left + w * zone.right, top + h * zone.bottom, overlay)
        }
        val (x, y) = CarPlayClusterDisplay.markerPercent(horizontalStep, verticalStep)
        val cx = left + w * (x / 100).toFloat()
        val cy = top + h * (y / 100).toFloat()
        canvas.drawCircle(cx, cy, h * 0.07f, marker)
        canvas.drawCircle(cx, cy, h * 0.07f, markerRing)
    }

    private companion object {
        // What BYD draws over the map in Full screen navi, as panel fractions (calibration grid).
        val BYD_OVERLAYS = listOf(
            RectF(0f, 0f, 1f, 0.11f), // status row: clock, gear, compass, temperature
            RectF(0.69f, 0.17f, 0.86f, 0.28f), // speed limit, lane assist and brake icons
            RectF(0.68f, 0.32f, 0.92f, 0.68f), // ADAS lane view
            RectF(0.71f, 0.71f, 0.78f, 0.83f), // speed
            RectF(0.29f, 0.76f, 0.32f, 0.82f), // power
            RectF(0f, 0.83f, 1f, 1f), // bottom band: drive mode, battery, range, odometer
        )
    }
}
