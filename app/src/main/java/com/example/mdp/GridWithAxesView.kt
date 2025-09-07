package com.example.mdp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class GridWithAxesView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val gridPaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 28f * resources.displayMetrics.density / 2f
        isAntiAlias = true
    }

    var rows = 20
    var cols = 20

    // computed layout metrics
    private var cellSizePx = 0f
    private var gridLeft = 0f
    private var gridTop = 0f
    private var gridSizePx = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        computeGridMetrics(w, h)
    }

    private fun computeGridMetrics(w: Int, h: Int) {
        // leave room for labels outside - x labels below, y labels on left
        val available = min(w, h)
        cellSizePx = available.toFloat() / (rows + 3) // +3 leaves more margin for bottom labels
        gridSizePx = cellSizePx * rows
        gridLeft = cellSizePx // one cell offset for left labels
        gridTop = cellSizePx  // one cell offset for top margin
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // ensure metrics
        computeGridMetrics(width, height)

        // vertical lines
        for (i in 0..cols) {
            val x = gridLeft + i * cellSizePx
            canvas.drawLine(x, gridTop, x, gridTop + gridSizePx, gridPaint)
        }

        // horizontal lines
        for (i in 0..rows) {
            val y = gridTop + i * cellSizePx
            canvas.drawLine(gridLeft, y, gridLeft + gridSizePx, y, gridPaint)
        }

        // X-axis labels (0-19) below the grid
        for (i in 0 until cols) {
            val centerX = gridLeft + (i + 0.5f) * cellSizePx
            val txt = i.toString()
            val tx = centerX - textPaint.measureText(txt) / 2f
            val ty = gridTop + gridSizePx + textPaint.textSize + 8f // below the grid
            canvas.drawText(txt, tx, ty, textPaint)
        }

        // Y-axis labels (0-19) on the left, from bottom to top
        for (i in 0 until rows) {
            val centerY = gridTop + gridSizePx - (i + 0.5f) * cellSizePx + (textPaint.textSize / 3f)
            val txt = i.toString() // 0 at bottom, 19 at top
            val tx = gridLeft - textPaint.measureText(txt) - 8f
            canvas.drawText(txt, tx, centerY, textPaint)
        }
    }

    /**
     * Convert a screen coordinate (global to this view) into a grid index (0..19, 0..19).
     * Returns Pair(x, y) or null if outside the grid.
     */
    fun pixelToCell(px: Float, py: Float): Pair<Int, Int>? {
        val localX = px
        val localY = py
        if (localX < gridLeft || localY < gridTop || localX > gridLeft + gridSizePx || localY > gridTop + gridSizePx) {
            return null
        }
        val x = ((localX - gridLeft) / cellSizePx).toInt() // 0-based column (0-19)
        val gridRow = ((localY - gridTop) / cellSizePx).toInt() // 0-based from top
        val y = rows - 1 - gridRow // convert to bottom-up (0 at bottom, 19 at top)
        if (x < 0 || x >= cols || y < 0 || y >= rows) return null
        return Pair(x, y)
    }

    fun cellCenterPixels(x: Int, y: Int): Pair<Float, Float> {
        val cx = gridLeft + (x + 0.5f) * cellSizePx
        val gridRow = rows - 1 - y // convert from bottom-up to top-down
        val cy = gridTop + (gridRow + 0.5f) * cellSizePx
        return Pair(cx, cy)
    }

    /**
     * Expose cell size so other code can size obstacles accordingly.
     */
    fun getCellSizePx(): Float = cellSizePx
}
