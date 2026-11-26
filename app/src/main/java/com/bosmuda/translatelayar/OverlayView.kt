package com.bosmuda.translatelayar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View

/**
 * Menggambar hasil terjemahan di atas posisi teks asli.
 * View ini tidak menerima sentuhan, jadi sentuhan tetap diteruskan ke aplikasi di bawahnya.
 */
class OverlayView(context: Context) : View(context) {

    data class Item(val rect: RectF, val text: String)

    private class Fit(val layout: StaticLayout, val size: Float)

    private var items: List<Item> = emptyList()

    // Putih penuh, supaya tulisan asli di bawahnya tidak kelihatan
    private val bgPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    // TextPaint dipakai karena StaticLayout (pembungkus baris) memerlukannya
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
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
        val screenRight = loc[0] + width.toFloat()
        val screenBottom = loc[1] + height.toFloat()

        canvas.save()
        canvas.translate(-minLeft, -minTop)

        for (item in items) {
            drawItem(canvas, item, minLeft, screenRight, screenBottom)
        }

        canvas.restore()
    }

    private fun drawItem(
        canvas: Canvas,
        item: Item,
        minLeft: Float,
        screenRight: Float,
        screenBottom: Float
    ) {
        val rect = item.rect
        val h = rect.height().coerceAtLeast(MIN_BOX_PX)
        val padX = h * 0.12f
        val padY = h * 0.18f

        // Kotak menutupi teks asli sepenuhnya. Kotak boleh sedikit melebar,
        // supaya terjemahan yang lebih panjang bisa dibungkus dengan lebih nyaman
        val left = (rect.left - padX).coerceAtLeast(minLeft)
        val maxW = minOf(screenRight - 2f - left, maxOf(rect.width() * 1.5f, h * 3f) + 2f * padX)
        val wrapW = (maxW - 2f * TEXT_PAD).toInt().coerceAtLeast(10)

        // Tulisan dibungkus per kata. Kalau masih terlalu tinggi, ukurannya diperkecil
        val maxTextH = h + 2f * padY - 2f
        val fit = fitText(item.text, wrapW, (h * 0.8f).coerceAtLeast(MIN_TEXT_PX), maxTextH)
        val layout = fit.layout

        var longest = 0f
        for (i in 0 until layout.lineCount) {
            longest = maxOf(longest, layout.getLineWidth(i))
        }
        val boxW = minOf(maxW, maxOf(rect.width() + 2f * padX, longest + 2f * TEXT_PAD))

        // Kotak hanya tumbuh ke bawah kalau teksnya masih lebih tinggi, dan tidak keluar dari layar
        val top = rect.top - padY
        val boxH = maxOf(h + 2f * padY, layout.height + 2f * padY)
        val bottom = minOf(top + boxH, screenBottom - 2f)

        canvas.drawRect(left, top, left + boxW, bottom, bgPaint)

        // Ukuran harus dikembalikan dulu, karena StaticLayout menggambar memakai ukuran paint saat ini
        textPaint.textSize = fit.size
        canvas.save()
        canvas.translate(left + TEXT_PAD, top + (bottom - top - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    /** Mencari ukuran tulisan yang tingginya muat di [maxH]. Di ukuran terkecil, kotak dibiarkan tumbuh. */
    private fun fitText(text: String, wrapW: Int, startSize: Float, maxH: Float): Fit {
        var size = startSize
        var layout = buildLayout(text, wrapW, size)
        var tries = 0
        while (layout.height > maxH && size > MIN_TEXT_PX && tries < 8) {
            val next = minOf(size - 1f, size * maxH / layout.height)
            size = maxOf(MIN_TEXT_PX, next)
            layout = buildLayout(text, wrapW, size)
            tries++
        }
        return Fit(layout, size)
    }

    private fun buildLayout(text: String, wrapW: Int, size: Float): StaticLayout {
        textPaint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, wrapW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
    }

    companion object {
        private const val MIN_TEXT_PX = 8f
        private const val MIN_BOX_PX = 12f
        private const val TEXT_PAD = 3f
    }
}
