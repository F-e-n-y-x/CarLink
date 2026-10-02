package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import kotlin.math.min

/** Instruction card drawn by DiPlay on top of the dashboard map stream. */
internal class ClusterTurnCardView(context: Context) : View(context) {
    private var guidance: ClusterTurnGuidance? = null
    private var xPercent = ClusterTurnCardOverlay.DEFAULT_X_PERCENT
    private var yPercent = ClusterTurnCardOverlay.DEFAULT_Y_PERCENT
    private var size = CarPlayClusterDisplay.OverlaySize.MEDIUM
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 22, 26, 34) }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 168, 255) }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(90, 168, 255)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(90, 168, 255)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 186, 198, 210)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val arrowPath = Path()
    private val cardRect = RectF()
    private val barRect = RectF()

    fun setLayout(xPercent: Int, yPercent: Int, size: CarPlayClusterDisplay.OverlaySize) {
        this.xPercent = xPercent
        this.yPercent = yPercent
        this.size = size
        invalidate()
    }

    fun setGuidance(next: ClusterTurnGuidance?) {
        if (guidance == next) {
            visibility = if (next == null) GONE else VISIBLE
            return
        }
        guidance = next
        visibility = if (next == null) GONE else VISIBLE
        bringToFront()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val panelWidth = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val panelHeight = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
        setMeasuredDimension(panelWidth, panelHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val next = guidance ?: return
        val card = ClusterTurnCardOverlay.card(width, height, xPercent, yPercent, size)
        val radius = card.height * 0.18f
        cardRect.set(card.left.toFloat(), card.top.toFloat(), (card.left + card.width).toFloat(), (card.top + card.height).toFloat())
        canvas.drawRoundRect(cardRect, radius, radius, cardPaint)
        barRect.set(card.left.toFloat(), card.top.toFloat(), card.left + card.height * 0.08f, (card.top + card.height).toFloat())
        canvas.drawRoundRect(barRect, radius * 0.4f, radius * 0.4f, barPaint)

        val padding = card.height * 0.12f
        val arrowBox = card.height - padding * 2f
        val arrowLeft = card.left + card.height * 0.12f
        val arrowTop = card.top + padding
        drawManeuver(canvas, next, arrowLeft, arrowTop, arrowBox)

        val textLeft = arrowLeft + arrowBox + padding * 0.6f
        val textWidth = card.left + card.width - padding - textLeft
        if (textWidth <= 0f) return
        textPaint.textSize = card.height * 0.32f
        mutedPaint.textSize = card.height * 0.18f
        val distanceY = card.top + card.height * 0.42f
        canvas.drawText(ellipsize(distanceLabel(next.distanceMeters), textWidth, textPaint), textLeft, distanceY, textPaint)
        val road = roadLabel(next)
        if (road.isNotEmpty()) {
            canvas.drawText(
                ellipsize(road, textWidth, mutedPaint),
                textLeft,
                card.top + card.height * 0.72f,
                mutedPaint,
            )
        }
    }

    private fun drawManeuver(canvas: Canvas, next: ClusterTurnGuidance, left: Float, top: Float, box: Float) {
        val stroke = box * 0.12f
        accentPaint.strokeWidth = stroke
        val cx = left + box / 2f
        val cy = top + box / 2f
        val m = box * 0.16f
        val head = box * 0.20f
        arrowPath.reset()
        when (next.icon) {
            2 -> drawTurn(canvas, cx, cy, box, m, head, leftTurn = true) // left
            3 -> drawTurn(canvas, cx, cy, box, m, head, leftTurn = false) // right
            4 -> drawSlight(canvas, cx, cy, box, m, head, leftTurn = true)
            5 -> drawSlight(canvas, cx, cy, box, m, head, leftTurn = false)
            6 -> drawSharp(canvas, cx, cy, box, m, head, leftTurn = true)
            7 -> drawSharp(canvas, cx, cy, box, m, head, leftTurn = false)
            8 -> drawUTurn(canvas, cx, cy, box, m, head, leftTurn = true)
            19 -> drawUTurn(canvas, cx, cy, box, m, head, leftTurn = false)
            11, 12, 17, 18 -> drawRoundabout(canvas, cx, cy, box, next.roundaboutExit)
            15 -> drawDestination(canvas, cx, cy, box, m)
            else -> drawStraight(canvas, cx, cy, box, m, head)
        }
    }

    private fun drawStraight(canvas: Canvas, cx: Float, cy: Float, box: Float, m: Float, head: Float) {
        val top = cy - box / 2f + m
        val bottom = cy + box / 2f - m
        canvas.drawLine(cx, bottom, cx, top + head * 0.35f, accentPaint)
        triangle(canvas, cx, top, cx - head, top + head * 1.1f, cx + head, top + head * 1.1f)
    }

    private fun drawTurn(canvas: Canvas, cx: Float, cy: Float, box: Float, m: Float, head: Float, leftTurn: Boolean) {
        val stemX = if (leftTurn) cx + box * 0.18f else cx - box * 0.18f
        val bottom = cy + box / 2f - m
        val tipX = if (leftTurn) cx - box / 2f + m else cx + box / 2f - m
        canvas.drawLine(stemX, bottom, stemX, cy, accentPaint)
        canvas.drawLine(stemX, cy, tipX + if (leftTurn) head * 0.35f else -head * 0.35f, cy, accentPaint)
        if (leftTurn) {
            triangle(canvas, tipX, cy, tipX + head * 1.15f, cy - head, tipX + head * 1.15f, cy + head)
        } else {
            triangle(canvas, tipX, cy, tipX - head * 1.15f, cy - head, tipX - head * 1.15f, cy + head)
        }
    }

    private fun drawSlight(canvas: Canvas, cx: Float, cy: Float, box: Float, m: Float, head: Float, leftTurn: Boolean) {
        val bottom = cy + box / 2f - m
        val tipX = cx + if (leftTurn) -box * 0.28f else box * 0.28f
        val tipY = cy - box / 2f + m
        val midX = cx + if (leftTurn) -box * 0.06f else box * 0.06f
        canvas.drawLine(cx, bottom, midX, cy + box * 0.02f, accentPaint)
        canvas.drawLine(midX, cy + box * 0.02f, tipX, tipY + head * 0.45f, accentPaint)
        val dx = if (leftTurn) -1f else 1f
        triangle(
            canvas,
            tipX, tipY,
            tipX - dx * head * 0.95f, tipY + head * 1.05f,
            tipX + dx * head * 0.35f, tipY + head * 1.15f,
        )
    }

    private fun drawSharp(canvas: Canvas, cx: Float, cy: Float, box: Float, m: Float, head: Float, leftTurn: Boolean) {
        val bottom = cy + box / 2f - m
        val tipX = if (leftTurn) cx - box / 2f + m else cx + box / 2f - m
        val tipY = cy - box * 0.18f
        canvas.drawLine(cx, bottom, cx, cy + box * 0.08f, accentPaint)
        canvas.drawLine(cx, cy + box * 0.08f, tipX + if (leftTurn) head * 0.3f else -head * 0.3f, tipY, accentPaint)
        if (leftTurn) {
            triangle(canvas, tipX, tipY, tipX + head * 1.05f, tipY - head * 0.55f, tipX + head * 0.75f, tipY + head * 0.85f)
        } else {
            triangle(canvas, tipX, tipY, tipX - head * 1.05f, tipY - head * 0.55f, tipX - head * 0.75f, tipY + head * 0.85f)
        }
    }

    private fun drawUTurn(canvas: Canvas, cx: Float, cy: Float, box: Float, m: Float, head: Float, leftTurn: Boolean) {
        val stem = if (leftTurn) cx + box * 0.14f else cx - box * 0.14f
        val other = if (leftTurn) cx - box * 0.14f else cx + box * 0.14f
        val bottom = cy + box / 2f - m
        val top = cy - box * 0.16f
        val radius = kotlin.math.abs(stem - other) / 2f
        canvas.drawLine(stem, bottom, stem, top + radius, accentPaint)
        val arc = RectF(min(stem, other), top, min(stem, other) + radius * 2f, top + radius * 2f)
        canvas.drawArc(arc, 180f, 180f, false, accentPaint)
        canvas.drawLine(other, top + radius, other, bottom - head * 0.15f, accentPaint)
        triangle(canvas, other, bottom, other - head, bottom - head * 1.05f, other + head, bottom - head * 1.05f)
    }

    private fun drawRoundabout(canvas: Canvas, cx: Float, cy: Float, box: Float, exit: Int) {
        val radius = box * 0.28f
        canvas.drawArc(RectF(cx - radius, cy - radius, cx + radius, cy + radius), 40f, 280f, false, accentPaint)
        val head = box * 0.16f
        triangle(canvas, cx + radius + head * 0.2f, cy, cx + radius - head * 0.4f, cy - head, cx + radius - head * 0.15f, cy + head * 0.55f)
        if (exit in 1..9) {
            textPaint.textSize = box * 0.28f
            val label = exit.toString()
            canvas.drawText(label, cx - textPaint.measureText(label) / 2f, cy + textPaint.textSize * 0.35f, textPaint)
        }
    }

    private fun drawDestination(canvas: Canvas, cx: Float, cy: Float, box: Float, m: Float) {
        val pole = cx - box * 0.08f
        canvas.drawLine(pole, cy + box / 2f - m, pole, cy - box / 2f + m, accentPaint)
        arrowPath.reset()
        arrowPath.moveTo(pole, cy - box / 2f + m)
        arrowPath.lineTo(pole + box * 0.36f, cy - box * 0.08f)
        arrowPath.lineTo(pole, cy + box * 0.02f)
        arrowPath.close()
        canvas.drawPath(arrowPath, fillPaint)
    }

    private fun triangle(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        arrowPath.reset()
        arrowPath.moveTo(x1, y1)
        arrowPath.lineTo(x2, y2)
        arrowPath.lineTo(x3, y3)
        arrowPath.close()
        canvas.drawPath(arrowPath, fillPaint)
    }

    private fun distanceLabel(meters: Int): String = when {
        meters <= 20 -> context.getString(com.shilapi.xcertplay.host.R.string.turn_card_now)
        meters < 1000 -> context.getString(com.shilapi.xcertplay.host.R.string.turn_card_distance_m, meters)
        else -> {
            val km = meters / 100 / 10f
            context.getString(com.shilapi.xcertplay.host.R.string.turn_card_distance_km, km)
        }
    }

    private fun roadLabel(next: ClusterTurnGuidance): String =
        if (next.roundaboutExit in 1..9) {
            context.getString(com.shilapi.xcertplay.host.R.string.turn_card_exit, next.roundaboutExit)
        } else {
            next.road
        }

    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 0 && paint.measureText(text.take(end) + ellipsis) > maxWidth) end--
        return if (end == 0) ellipsis else text.take(end) + ellipsis
    }
}
