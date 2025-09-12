package com.example.mdp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

class ObstacleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var number: Int = 1
    private var direction: String = "N" // N, E, S, W

    fun setNumber(n: Int) {
        number = n
        invalidate()
    }

    fun getNumber(): Int {
        return number
    }

    fun setDirection(dir: String) {
        direction = dir
        invalidate()
    }

    fun getDirection(): String {
        return direction
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val paint = Paint().apply { color = Color.BLACK }
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 20f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }

        // Draw the main obstacle rectangle
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // Draw the number in the center
        val xPos = width / 2f
        val yPos = (height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2)
        canvas.drawText(number.toString(), xPos, yPos, textPaint)

        // Draw direction indicator (yellow line on the corresponding edge)
        val directionPaint = Paint().apply {
            color = Color.YELLOW
            strokeWidth = 4f
        }

        when (direction) {
            "N" -> {
                // Top edge
                canvas.drawLine(0f, 0f, width.toFloat(), 0f, directionPaint)
            }
            "E" -> {
                // Right edge
                canvas.drawLine(width.toFloat(), 0f, width.toFloat(), height.toFloat(), directionPaint)
            }
            "S" -> {
                // Bottom edge
                canvas.drawLine(0f, height.toFloat(), width.toFloat(), height.toFloat(), directionPaint)
            }
            "W" -> {
                // Left edge
                canvas.drawLine(0f, 0f, 0f, height.toFloat(), directionPaint)
            }
        }
    }
}
