package com.example.mdp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class RobotView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val robotDrawable: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_robot_north)
    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#000000") // Green background for robot
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    init {
        // Make sure the view is clickable for drag operations
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        // Draw border
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)

        // Draw robot icon in the center
        robotDrawable?.let { drawable ->
            val iconSize = (width * 0.6f).toInt() // 60% of the view size
            val left = (width - iconSize) / 2
            val top = (height - iconSize) / 2
            val right = left + iconSize
            val bottom = top + iconSize

            drawable.setBounds(left, top, right, bottom)
            drawable.draw(canvas)
        }
    }
}
