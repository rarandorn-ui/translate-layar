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

    // Putih penuh, supaya tulisan asli di bawahnya tidak kelihatan
    private val bgPaint = Paint().apply {
        color = Color.WHITE
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

        // Koordinat hasil OCR memakai layar penuh. Kalau jendela overlay ternyata
        // mulai di bawah bilah status, gambar digeser supaya sejajar dengan teks asli.
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val minLeft = loc[0].toFloat()
        val minTop = loc[1].toFloat()
        val screenRight = loc[0] + width - 2f

        canvas.save()
        canvas.translate(-minLeft, -minTop)

        val scale = textScale.coerceAtLeast(0.3f)
        for (item in items) {
            // Kotak sedikit lebih besar dari teks asli supaya tulisan asli tertutup rapi
            val pad = item.rect.height() * 0.12f
            val left = (item.rect.left - 2f).coerceAtLeast(minLeft)
            val top = item.rect.top - pad
            val bottom = item.rect.bottom + pad
            val available = screenRight - left - 4f
            val originalWidth = item.rect.width() * maxOf(1f, scale)

            // Tulisan boleh sedikit lebih lebar dari aslinya sebelum diperkecil,
            // dan tidak boleh keluar dari tepi layar
            val wideLimit = minOf(available, originalWidth * 1.25f)
            fitText(item.text, item.rect.height() * 0.8f * scale, wideLimit)
            if (textPaint.measureText(item.text) > available) {
                fitText(item.text, textPaint.textSize, available)
            }

            val textWidth = textPaint.measureText(item.text)
            val right = minOf(screenRight, left + maxOf(originalWidth, textWidth + 4f))
            canvas.drawRect(left, top, right, bottom, bgPaint)

            val baseline = (top + bottom) / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(item.text, left + 2f, baseline, textPaint)
        }

        canvas.restore()
    }

    /** Kecilkan tulisan sampai muat di lebar [maxWidth]. Ukuran akhirnya disimpan di textPaint. */
    private fun fitText(text: String, startSize: Float, maxWidth: Float) {
        var size = startSize
        textPaint.textSize = size
        while (size > 8f && textPaint.measureText(text) > maxWidth) {
            size -= 1f
            textPaint.textSize = size
        }
    }
}
