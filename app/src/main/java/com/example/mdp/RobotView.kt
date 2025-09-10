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

    private var direction: String = "N" // N, E, S, W

    private val robotDrawables = mapOf(
        "N" to ContextCompat.getDrawable(context, R.drawable.ic_robot_north),
        "E" to ContextCompat.getDrawable(context, R.drawable.ic_robot_east),
        "S" to ContextCompat.getDrawable(context, R.drawable.ic_robot_south),
        "W" to ContextCompat.getDrawable(context, R.drawable.ic_robot_west)
    )

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

    fun setDirection(dir: String) {
        if (robotDrawables.containsKey(dir)) {
            direction = dir
            invalidate()
        }
    }

    fun getDirection(): String = direction

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        // Draw border
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)

        // Draw robot icon based on direction
        robotDrawables[direction]?.let { drawable ->
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
