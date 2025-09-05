package com.example.mdp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

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
        // leave room for labels outside if needed
        // we'll use (rows + 2) to leave a margin so labels (1..n) can be outside/left/top
        val available = min(w, h)
        cellSizePx = available.toFloat() / (rows + 2) // +2 leaves margin
        gridSizePx = cellSizePx * rows
        gridLeft = cellSizePx // one cell offset for left labels
        gridTop = cellSizePx  // one cell offset for top labels
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

        // labels start at 1 and are centered on each cell
        for (i in 1..cols) {
            val centerX = gridLeft + (i - 0.5f) * cellSizePx
            // draw x labels above the grid (adjust as desired)
            val txt = i.toString()
            val tx = centerX - textPaint.measureText(txt) / 2f
            val ty = gridTop - 8f
            canvas.drawText(txt, tx, ty, textPaint)
        }

        for (i in 1..rows) {
            val centerY = gridTop + (i - 0.5f) * cellSizePx + (textPaint.textSize / 3f)
            val txt = i.toString()
            val tx = gridLeft - textPaint.measureText(txt) - 8f
            canvas.drawText(txt, tx, centerY, textPaint)
        }
    }

    /**
     * Convert a screen coordinate (global to this view) into a grid index (1..rows, 1..cols).
     * Returns Pair(colIndex, rowIndex) or null if outside the grid.
     */
    fun pixelToCell(px: Float, py: Float): Pair<Int, Int>? {
        // px,py are coords relative to THIS view (getX/getY of event mapped properly)
        val localX = px
        val localY = py
        if (localX < gridLeft || localY < gridTop || localX > gridLeft + gridSizePx || localY > gridTop + gridSizePx) {
            return null
        }
        val col = ((localX - gridLeft) / cellSizePx).toInt() + 1
        val row = ((localY - gridTop) / cellSizePx).toInt() + 1
        if (col < 1 || col > cols || row < 1 || row > rows) return null
        return Pair(col, row)
    }

    /**
     * Given grid indices (col,row) 1..n, return center pixel coordinate relative to this view.
     */
    fun cellCenterPixels(col: Int, row: Int): Pair<Float, Float> {
        val cx = gridLeft + (col - 0.5f) * cellSizePx
        val cy = gridTop + (row - 0.5f) * cellSizePx
        return Pair(cx, cy)
    }

    /**
     * Returns true if the given pixel coords (relative to this view) are inside the grid area.
     */
    fun isInsideGrid(px: Float, py: Float): Boolean {
        return !(px < gridLeft || py < gridTop || px > gridLeft + gridSizePx || py > gridTop + gridSizePx)
    }

    /**
     * Expose cell size so other code can size obstacles accordingly.
     */
    fun getCellSizePx(): Float = cellSizePx
}
