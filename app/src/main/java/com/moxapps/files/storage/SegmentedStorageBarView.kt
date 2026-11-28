package com.moxapps.files.storage

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.annotation.ColorInt

class SegmentedStorageBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val clipPath = Path()
    private val boundsRect = RectF()

    private var segments: List<StorageSegment> = emptyList()
    private var totalCapacityBytes: Long = 0L

    @ColorInt
    private var trackColor: Int = Color.argb(50, 128, 128, 128)

    init {
        val typedValue = TypedValue()
        val resolved = context.theme.resolveAttribute(
            androidx.appcompat.R.attr.colorControlNormal, typedValue, true
        )
        val baseColor = if (resolved) typedValue.data else Color.GRAY
        trackColor = Color.argb(
            45, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)
        )
        trackPaint.color = trackColor
    }

    fun setData(segments: List<StorageSegment>, totalCapacityBytes: Long) {
        this.segments = segments
        this.totalCapacityBytes = totalCapacityBytes
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = h * 0.5f
        boundsRect.set(0f, 0f, w, h)
        clipPath.reset()
        clipPath.addRoundRect(boundsRect, radius, radius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(boundsRect, trackPaint)

        if (totalCapacityBytes > 0L && segments.isNotEmpty()) {
            var curX = 0f
            val minSegWidth = h * 0.4f
            val gap = (h * 0.25f).coerceAtLeast(1.5f)
            var drewAny = false
            for (seg in segments) {
                if (seg.usedBytes <= 0L) continue
                if (drewAny) {
                    curX += gap
                }
                if (curX >= w) break
                val rawWidth = (seg.usedBytes.toDouble() / totalCapacityBytes.toDouble()).toFloat() * w
                val segWidth = rawWidth.coerceAtLeast(minSegWidth).coerceAtMost(w - curX)
                if (segWidth <= 0f) break
                segmentPaint.color = seg.color
                canvas.drawRect(curX, 0f, curX + segWidth, h, segmentPaint)
                curX += segWidth
                drewAny = true
            }
        }
        canvas.restore()
    }
}
