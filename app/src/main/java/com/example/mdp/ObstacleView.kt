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
    private var targetId: String? = null

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

    fun setTargetId(id: String, dir: String? = null) {
        targetId = id
        if (dir != null) direction = dir
        invalidate()
    }

    fun getTargetId(): String? {
        return targetId
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val paint = Paint().apply { color = Color.BLACK }
        // Optionally rotate canvas for direction (target face)
        when (direction) {
            "N" -> canvas.rotate(0f, width / 2f, height / 2f)
            "E" -> canvas.rotate(90f, width / 2f, height / 2f)
            "S" -> canvas.rotate(180f, width / 2f, height / 2f)
            "W" -> canvas.rotate(270f, width / 2f, height / 2f)
        }
        // Draw the main obstacle rectangle
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        if (targetId != null) {
            // Draw Target ID in large, bold, white font
            val targetPaint = Paint().apply {
                color = Color.WHITE
                textSize = width * 0.5f // Large font
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD
            }
            val xPos = width / 2f
            val yPos = (height / 2f) - ((targetPaint.descent() + targetPaint.ascent()) / 2)
            canvas.drawText(targetId!!, xPos, yPos, targetPaint)
            // Draw obstacle number smaller, less bold, below target ID
            val numberPaint = Paint().apply {
                color = Color.LTGRAY
                textSize = width * 0.18f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = Typeface.DEFAULT
            }
            val numberY = yPos + width * 0.3f
            canvas.drawText(number.toString(), xPos, numberY, numberPaint)
        } else {
            // Draw the number in the center (default)
            val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = 20f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD
            }
            val xPos = width / 2f
            val yPos = (height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2)
            canvas.drawText(number.toString(), xPos, yPos, textPaint)
        }

        // Draw direction indicator (yellow line on the corresponding edge)
        val directionPaint = Paint().apply {
            color = Color.YELLOW
            strokeWidth = 4f
        }
        when (direction) {
            "N" -> {
                canvas.drawLine(0f, 0f, width.toFloat(), 0f, directionPaint)
            }
            "E" -> {
                canvas.drawLine(width.toFloat(), 0f, width.toFloat(), height.toFloat(), directionPaint)
            }
            "S" -> {
                canvas.drawLine(0f, height.toFloat(), width.toFloat(), height.toFloat(), directionPaint)
            }
            "W" -> {
                canvas.drawLine(0f, 0f, 0f, height.toFloat(), directionPaint)
            }
        }
    }
}
