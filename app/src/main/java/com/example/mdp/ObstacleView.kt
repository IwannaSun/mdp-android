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

    private val bgPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f * resources.displayMetrics.density / 2f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private var number: Int = 1

    fun setNumber(n: Int) {
        number = n
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Obstacle will be square: width==height. Parent code should set LayoutParams size.
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val paint = Paint().apply { color = Color.BLACK }
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 40f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val xPos = width / 2f
        val yPos = (height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2)
        canvas.drawText(number.toString(), xPos, yPos, textPaint)
    }
}
