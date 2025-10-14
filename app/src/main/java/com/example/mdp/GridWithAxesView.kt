package com.example.mdp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Shader
import androidx.core.content.res.ResourcesCompat
import kotlin.text.toFloat
import kotlin.text.toInt

class GridWithAxesView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val gridPaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val customTypeface: Typeface? = ResourcesCompat.getFont(context, R.font.minecraftbold)

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 23f * resources.displayMetrics.density / 2f
        isAntiAlias = true
        typeface = customTypeface
    }

    var rows = 20
    var cols = 20

    // computed layout metrics
    private var cellSizePx = 0f
    private var gridLeft = 0f
    private var gridTop = 0f
    private var gridSizePx = 0f
    private var grassBitmap: Bitmap? = null
    private val grassPaint: Paint

    init {
        val grassBitmap = BitmapFactory.decodeResource(resources, R.drawable.grass_block)
        val shader = BitmapShader(grassBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        grassPaint = Paint().apply {
            isAntiAlias = true
            this.shader = shader
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        computeGridMetrics(w, h)
    }

    private fun computeGridMetrics(w: Int, h: Int) {
        // leave room for labels outside - x labels below, y labels on left
        val available = min(w, h)
        cellSizePx = available.toFloat() / (rows + 1) // +3 leaves more margin for bottom labels
        gridSizePx = cellSizePx * rows
        gridLeft = cellSizePx // one cell offset for left labels
        gridTop = cellSizePx  // one cell offset for top margin
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas) // ensure metrics
        computeGridMetrics(width, height)

        val textureWidth = cellSizePx.toInt()  // Change this value
        val textureHeight = cellSizePx.toInt() // Change this value

        if (grassBitmap == null ||
            grassBitmap!!.width != textureWidth ||
            grassBitmap!!.height != textureHeight) {
            val originalBitmap = BitmapFactory.decodeResource(resources, R.drawable.grass_block)
            grassBitmap = Bitmap.createScaledBitmap(
                originalBitmap,
                textureWidth,
                textureHeight,
                true
            )
            val shader = BitmapShader(grassBitmap!!, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            grassPaint.shader = shader
        }

        // Draw background only within the grid area, not the entire view
        canvas.drawRect(gridLeft, gridTop, gridLeft + gridSizePx, gridTop + gridSizePx, grassPaint)


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

    fun pixelToNearestCell(px: Float, py: Float): Pair<Int, Int> {
        val localX = px
        val localY = py

        val relativeX = localX - gridLeft
        val relativeY = localY - gridTop

        val rawX = (relativeX / cellSizePx).toInt()
        val rawGridRow = (relativeY / cellSizePx).toInt()

        val x = rawX.coerceIn(0, cols - 1)
        val gridRow = rawGridRow.coerceIn(0, rows - 1)

        val y = rows - 1 - gridRow

        return Pair(x, y)
    }

    fun cellCenterPixels(x: Int, y: Int): Pair<Float, Float> {
        val cx = gridLeft + (x + 0.5f) * cellSizePx
        val gridRow = rows - 1 - y // convert from bottom-up to top-down
        val cy = gridTop + (gridRow + 0.5f) * cellSizePx
        return Pair(cx, cy)
    }
    fun getCellSizePx(): Float = cellSizePx
}
