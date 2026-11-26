package com.floatbox.ai

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CropImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private val imageRect = RectF()
    private val selectionRect = RectF()
    private val prevSelectionRect = RectF()

    private var startX = 0f
    private var startY = 0f
    private var isDragging = false

    private val dimPaint = Paint().apply {
        color = Color.argb(120, 9, 25, 54)
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1D5EFF")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    fun setImageBitmap(bmp: Bitmap?) {
        bitmap = bmp
        calculateBounds()
        invalidate()
    }

    fun getCroppedBitmap(): Bitmap? {
        val bmp = bitmap ?: return null
        if (imageRect.width() <= 0f || imageRect.height() <= 0f || selectionRect.isEmpty) {
            return bmp
        }

        val scaleX = bmp.width.toFloat() / imageRect.width()
        val scaleY = bmp.height.toFloat() / imageRect.height()

        val left = ((selectionRect.left - imageRect.left) * scaleX).toInt().coerceIn(0, bmp.width - 1)
        val top = ((selectionRect.top - imageRect.top) * scaleY).toInt().coerceIn(0, bmp.height - 1)
        val right = ((selectionRect.right - imageRect.left) * scaleX).toInt().coerceIn(left + 1, bmp.width)
        val bottom = ((selectionRect.bottom - imageRect.top) * scaleY).toInt().coerceIn(top + 1, bmp.height)

        return Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateBounds()
    }

    private fun calculateBounds() {
        val bmp = bitmap ?: return
        if (width <= 0 || height <= 0) return

        val pad = 8f
        val availW = (width - pad * 2).coerceAtLeast(1f)
        val availH = (height - pad * 2).coerceAtLeast(1f)
        val scale = min(availW / bmp.width.toFloat(), availH / bmp.height.toFloat())

        val drawnW = bmp.width * scale
        val drawnH = bmp.height * scale
        val left = (width - drawnW) / 2f
        val top = (height - drawnH) / 2f

        imageRect.set(left, top, left + drawnW, top + drawnH)

        val marginX = drawnW * 0.12f
        val marginY = drawnH * 0.15f
        selectionRect.set(
            imageRect.left + marginX,
            imageRect.top + marginY,
            imageRect.right - marginX,
            imageRect.bottom - marginY
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return

        canvas.drawBitmap(bmp, null, imageRect, null)

        if (!selectionRect.isEmpty) {
            canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, selectionRect.top, dimPaint)
            canvas.drawRect(imageRect.left, selectionRect.bottom, imageRect.right, imageRect.bottom, dimPaint)
            canvas.drawRect(imageRect.left, selectionRect.top, selectionRect.left, selectionRect.bottom, dimPaint)
            canvas.drawRect(selectionRect.right, selectionRect.top, imageRect.right, selectionRect.bottom, dimPaint)

            canvas.drawRoundRect(selectionRect, 12f, 12f, borderPaint)

            val r = 8f
            canvas.drawCircle(selectionRect.left, selectionRect.top, r, cornerPaint)
            canvas.drawCircle(selectionRect.right, selectionRect.top, r, cornerPaint)
            canvas.drawCircle(selectionRect.left, selectionRect.bottom, r, cornerPaint)
            canvas.drawCircle(selectionRect.right, selectionRect.bottom, r, cornerPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null || imageRect.isEmpty) return false
        parent?.requestDisallowInterceptTouchEvent(true)

        val x = event.x.coerceIn(imageRect.left, imageRect.right)
        val y = event.y.coerceIn(imageRect.top, imageRect.bottom)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                prevSelectionRect.set(selectionRect)
                startX = x
                startY = y
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(x - startX) > 12f || abs(y - startY) > 12f) {
                    isDragging = true
                }
                if (isDragging) {
                    selectionRect.set(
                        min(startX, x),
                        min(startY, y),
                        max(startX, x),
                        max(startY, y)
                    )
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging || selectionRect.width() < 24f || selectionRect.height() < 24f) {
                    selectionRect.set(prevSelectionRect)
                    invalidate()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}