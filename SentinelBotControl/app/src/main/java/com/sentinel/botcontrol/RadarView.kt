package com.sentinel.botcontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Sweeping radar display matching the robot's ultrasonic servo scan.
 * Firmware angle convention: 0°=right, 90°=straight ahead, 180°=left
 * (RADAR_MIN_ANGLE=20 .. RADAR_MAX_ANGLE=160).
 * On-screen we relabel that as a heading: -90° (left) .. 0° (ahead) .. +90° (right),
 * with a center "hub" showing the live distance reading, matching the AHA UI design.
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var angleDeg: Int = 90
    private var distanceCm: Int = -1 // -1 = no reading yet
    private var hasData: Boolean = false
    private val maxRangeCm = 120

    private data class Blip(val angle: Int, val dist: Int, var alpha: Int)
    private val trail = ArrayDeque<Blip>()

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = themeColor("grid_line")
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = themeColor("accent_cyan")
    }
    private val sweepPaintIdle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = themeColor("accent_red")
    }
    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = themeColor("accent_green")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor("text_secondary")
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }
    private val baseLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = themeColor("divider")
    }
    private val hubFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = themeColor("bg_card_elevated")
    }
    private val hubStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = themeColor("accent_cyan")
    }
    private val hubLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor("text_secondary")
        textSize = 20f
        textAlign = Paint.Align.CENTER
    }
    private val hubValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor("text_primary")
        textSize = 40f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val hubUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor("accent_cyan")
        textSize = 18f
        textAlign = Paint.Align.CENTER
    }

    private fun themeColor(name: String): Int {
        val resId = resources.getIdentifier(name, "color", context.packageName)
        return if (resId != 0) resources.getColor(resId, null) else Color.CYAN
    }

    fun updateReading(angle: Int, distCm: Int) {
        angleDeg = angle.coerceIn(0, 180)
        distanceCm = distCm
        hasData = true
        if (distCm in 1 until 400) {
            trail.addLast(Blip(angleDeg, distCm, 255))
            if (trail.size > 40) trail.removeFirst()
        }
        invalidate()
    }

    /** Called when the connection drops or before any reading has arrived. */
    fun clearData() {
        hasData = false
        distanceCm = -1
        trail.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height - 14f
        val radius = min(width / 2f, height - 26f) - 8f

        for (i in 1..4) {
            val r = radius * i / 4f
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 180f, 180f, false, arcPaint)
        }
        canvas.drawLine(cx - radius, cy, cx + radius, cy, baseLinePaint)

        for (a in 0..180 step 30) {
            val rad = Math.toRadians(a.toDouble())
            val x = cx + (cos(rad) * radius).toFloat()
            val y = cy - (sin(rad) * radius).toFloat()
            canvas.drawLine(cx, cy, x, y, arcPaint)
        }

        val it = trail.iterator()
        while (it.hasNext()) {
            val b = it.next()
            val rad = Math.toRadians(b.angle.toDouble())
            val r = (min(b.dist, maxRangeCm).toFloat() / maxRangeCm) * radius
            val x = cx + (cos(rad) * r).toFloat()
            val y = cy - (sin(rad) * r).toFloat()
            blipPaint.alpha = b.alpha
            canvas.drawCircle(x, y, 6f, blipPaint)
            b.alpha = (b.alpha - 12).coerceAtLeast(0)
        }

        // sweep line — cyan while data is flowing, dim red when idle/no data
        val rad = Math.toRadians(angleDeg.toDouble())
        val sx = cx + (cos(rad) * radius).toFloat()
        val sy = cy - (sin(rad) * radius).toFloat()
        canvas.drawLine(cx, cy, sx, sy, if (hasData) sweepPaint else sweepPaintIdle)

        // heading labels: -90 (left) .. 0 (ahead) .. +90 (right)
        canvas.drawText("-90°", cx - radius, cy + 22f, labelPaint)
        canvas.drawText("0°", cx, cy - radius - 12f, labelPaint)
        canvas.drawText("90°", cx + radius, cy + 22f, labelPaint)

        // center hub with live distance readout
        val hubRadius = 56f
        canvas.drawCircle(cx, cy, hubRadius, hubFillPaint)
        canvas.drawCircle(cx, cy, hubRadius, hubStrokePaint)
        canvas.drawText("فاصله", cx, cy - 16f, hubLabelPaint)
        val valueText = if (hasData && distanceCm in 1 until 400) "$distanceCm" else "--"
        canvas.drawText(valueText, cx, cy + 12f, hubValuePaint)
        canvas.drawText("cm", cx, cy + 30f, hubUnitPaint)
    }
}
