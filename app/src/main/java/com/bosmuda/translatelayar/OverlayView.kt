package com.bosmuda.translatelayar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Menggambar hasil terjemahan di atas posisi teks asli.
 * View ini tidak menerima sentuhan, jadi sentuhan tetap diteruskan ke aplikasi di bawahnya.
 */
class OverlayView(context: Context) : View(context) {

    data class Item(val rect: RectF, val text: String)

    private var items: List<Item> = emptyList()

    /** Pengali ukuran tulisan (1.0 = normal). */
    var textScale: Float = 1f
        set(value) {
            field = value
            invalidate()
        }

    private val bgPaint = Paint().apply {
        color = Color.argb(235, 255, 255, 255)
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
    }

    fun setItems(newItems: List<Item>) {
        items = newItems
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = textScale.coerceAtLeast(0.3f)
        for (item in items) {
            // Kalau tulisan diperbesar, kotak boleh ikut melebar
            val maxWidth = item.rect.width() * maxOf(1f, scale) - 4f
            var size = item.rect.height() * 0.8f * scale
            textPaint.textSize = size
            while (size > 8f && textPaint.measureText(item.text) > maxWidth) {
                size -= 1f
                textPaint.textSize = size
            }
            val textWidth = textPaint.measureText(item.text)
            val right = maxOf(item.rect.right, item.rect.left + textWidth + 4f)
            canvas.drawRect(item.rect.left, item.rect.top, right, item.rect.bottom, bgPaint)

            val baseline = item.rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(item.text, item.rect.left + 2f, baseline, textPaint)
        }
    }
}
