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
        for (item in items) {
            canvas.drawRect(item.rect, bgPaint)

            val maxWidth = item.rect.width() - 4f
            var size = item.rect.height() * 0.8f
            textPaint.textSize = size
            // Kecilkan teks sampai muat di lebar kotak
            while (size > 8f && textPaint.measureText(item.text) > maxWidth) {
                size -= 1f
                textPaint.textSize = size
            }

            val baseline = item.rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(item.text, item.rect.left + 2f, baseline, textPaint)
        }
    }
}
