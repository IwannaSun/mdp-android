package com.example.mdp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class GridWithAxesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val gridPaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 30f
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val rows = 20
        val cols = 20

        // Ensure square cells
        val cellSize = min(width, height) / (rows + 2) // +2 for space around labels
        val gridSize = cellSize * rows

        // Offsets for grid start (to fit labels)
        val offsetX = cellSize.toFloat()*2
        val offsetY = cellSize.toFloat()*2

        // Draw vertical grid lines
        for (i in 0..cols) {
            val x = offsetX + i * cellSize
            canvas.drawLine(x, offsetY, x, offsetY + gridSize, gridPaint)
        }

        // Draw horizontal grid lines
        for (i in 0..rows) {
            val y = offsetY + i * cellSize
            canvas.drawLine(offsetX, y, offsetX + gridSize, y, gridPaint)
        }

        // X-axis labels (start from 1 next to first block)
        for (i in 1..cols) {
            val x = offsetX + (i - 0.5f) * cellSize
            canvas.drawText(i.toString(), x - textPaint.measureText(i.toString()) / 2, offsetY - 10f, textPaint)
        }

        // Y-axis labels (start from 1 next to first block)
        for (i in 1..rows) {
            val y = offsetY + (i - 0.5f) * cellSize + (textPaint.textSize / 3)
            canvas.drawText(i.toString(), offsetX - (textPaint.measureText(i.toString()) + 12f), y, textPaint)
        }
    }
}
