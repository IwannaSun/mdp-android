package com.example.mdp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat

class ObstacleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var number: Int = 1
    private var direction: String = "N" // N, E, S, W
    private var targetId: String? = null
    private var backgroundBitmap: Bitmap? = null

    private val regularTypeface: Typeface? = ResourcesCompat.getFont(context, R.font.minecraftregular)
    private val boldTypeface: Typeface? = ResourcesCompat.getFont(context, R.font.minecraftbold)

    // Load obsidian block texture
    private val obsidianBitmap: Bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.obsidianblock)

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

    fun setTargetId(id: String?) {
        targetId = id
        invalidate()
    }

    fun setBackgroundBitmap(bitmap: Bitmap?) {
        backgroundBitmap = bitmap
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 12f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            typeface = regularTypeface
            strokeWidth = 1f
        }

        // Draw and rotate background image if present
        backgroundBitmap?.let { bmp ->
            val saveCount = canvas.save()
            when (direction) {
                "N" -> canvas.rotate(0f, width / 2f, height / 2f)
                "E" -> canvas.rotate(90f, width / 2f, height / 2f)
                "S" -> canvas.rotate(180f, width / 2f, height / 2f)
                "W" -> canvas.rotate(270f, width / 2f, height / 2f)
            }
            canvas.drawBitmap(bmp, null, Rect(0, 0, width, height), null)
            canvas.restoreToCount(saveCount)
        }

        // Draw the main obstacle with obsidian texture if no background image
        if (backgroundBitmap == null) {
            // Draw obsidian texture scaled to fit the view
            val destRect = Rect(0, 0, width, height)
            canvas.drawBitmap(obsidianBitmap, null, destRect, null)
        }

        // Draw the target ID or obstacle number
        val xPos = width / 2f
        val yPos = (height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2) + 1f
        if (targetId != null) {
            val targetPaint = Paint().apply {
                color = Color.WHITE
                textSize = 20f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = boldTypeface
                strokeWidth = 2f
            }
            canvas.drawText(targetId!!, xPos, yPos, targetPaint)
        } else {
            canvas.drawText(number.toString(), xPos, yPos, textPaint)
        }

        // Draw direction indicator (yellow line on the corresponding edge)
        val directionPaint = Paint().apply {
            color = Color.YELLOW
            strokeWidth = 4f
        }
        when (direction) {
            "N" -> canvas.drawLine(0f, 0f, width.toFloat(), 0f, directionPaint)
            "E" -> canvas.drawLine(width.toFloat(), 0f, width.toFloat(), height.toFloat(), directionPaint)
            "S" -> canvas.drawLine(0f, height.toFloat(), width.toFloat(), height.toFloat(), directionPaint)
            "W" -> canvas.drawLine(0f, 0f, 0f, height.toFloat(), directionPaint)
        }
    }
}