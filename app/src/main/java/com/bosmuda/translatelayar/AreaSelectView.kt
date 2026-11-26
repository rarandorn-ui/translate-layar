package com.bosmuda.translatelayar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Layar transparan yang menutupi seluruh layar untuk memilih area.
 * Pengguna menggeser jari untuk menggambar kotak. Saat jari diangkat, [onDone] dipanggil
 * dengan kotak itu dalam koordinat layar (piksel). Kalau kotaknya terlalu kecil, nilainya null.
 */
class AreaSelectView(
    context: Context,
    private val hint: String,
    private val onDone: (RectF?) -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density
    private val sel = RectF()
    private var hasSel = false
    private var startX = 0f
    private var startY = 0f
    private var finished = false

    private val dim = Paint().apply { color = Color.parseColor("#8A000000") }
    private val border = Paint().apply {
        color = Color.parseColor("#D32F2F")
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val hintPaint = Paint().apply {
        color = Color.WHITE
        textSize = 16f * density
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (!hasSel) {
            canvas.drawRect(0f, 0f, w, h, dim)
        } else {
            // Area yang dipilih dibiarkan terang, sisanya digelapkan
            canvas.drawRect(0f, 0f, w, sel.top, dim)
            canvas.drawRect(0f, sel.bottom, w, h, dim)
            canvas.drawRect(0f, sel.top, sel.left, sel.bottom, dim)
            canvas.drawRect(sel.right, sel.top, w, sel.bottom, dim)
            canvas.drawRect(sel, border)
        }
        canvas.drawText(hint, 16f * density, 56f * density, hintPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (finished) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                sel.set(startX, startY, startX, startY)
                hasSel = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                sel.set(
                    min(startX, event.x), min(startY, event.y),
                    max(startX, event.x), max(startY, event.y)
                )
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                finished = true
                val cukup = sel.width() >= MIN_DP * density && sel.height() >= MIN_DP * density
                onDone(if (cukup) RectF(sel) else null)
            }
            MotionEvent.ACTION_CANCEL -> {
                finished = true
                onDone(null)
            }
        }
        return true
    }

    companion object {
        private const val MIN_DP = 40f
    }
}
