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

    /** [bg] dan [fg] adalah warna kotak dan tulisan, diambil dari warna latar di sekitar teks asli. */
    data class Item(
        val rect: RectF,
        val text: String,
        val bg: Int = Color.WHITE,
        val fg: Int = Color.BLACK
    )

    private class Fit(val layout: StaticLayout, val size: Float)

    private var items: List<Item> = emptyList()

    /** Pengali ukuran tulisan (1.0 = normal). */
    var textScale: Float = 1f
        set(value) {
            field = value
            invalidate()
        }

    // Warna kotak diambil per baris dari latar di sekitar teks, jadi tidak selalu putih
    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    // TextPaint dipakai karena StaticLayout (pembungkus baris) memerlukannya
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

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

        val scale = textScale.coerceAtLeast(0.3f)

        // Digambar dari atas ke bawah, supaya kotak di atas bisa dijadikan acuan agar tidak saling menimpa
        val sorted = items.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
        val placed = ArrayList<RectF>()
        for ((idx, item) in sorted.withIndex()) {
            // Batas bawah kotak: tepat sebelum baris berikutnya yang sejajar, supaya teks itu tidak tertutup
            var limit = screenBottom - 2f
            for (j in idx + 1 until sorted.size) {
                val other = sorted[j]
                val sejajar = other.rect.left < item.rect.right && other.rect.right > item.rect.left
                if (other.rect.top > item.rect.top + 1f && sejajar) {
                    limit = minOf(limit, other.rect.top - 1f)
                    break
                }
            }
            placed.add(drawItem(canvas, item, scale, minLeft, screenRight, limit, placed))
        }

        canvas.restore()
    }

    /** Menggambar satu kotak terjemahan, lalu mengembalikan area kotak itu. */
    private fun drawItem(
        canvas: Canvas,
        item: Item,
        scale: Float,
        minLeft: Float,
        screenRight: Float,
        limit: Float,
        placed: List<RectF>
    ): RectF {
        val rect = item.rect
        val h = rect.height().coerceAtLeast(MIN_BOX_PX)
        val padX = h * 0.12f
        val padY = h * 0.18f

        // Kotak boleh sedikit melebar, supaya terjemahan yang lebih panjang bisa dibungkus dengan lebih nyaman
        val left = (rect.left - padX).coerceAtLeast(minLeft)
        val maxW = minOf(screenRight - 2f - left, maxOf(rect.width() * 1.5f, h * 3f) + 2f * padX)
        val wrapW = (maxW - 2f * TEXT_PAD).toInt().coerceAtLeast(10)

        // Kotak tidak boleh menimpa kotak yang sudah digambar di atasnya
        var top = rect.top - padY
        for (p in placed) {
            if (p.left < left + maxW && p.right > left && p.top <= top && p.bottom > top) {
                top = p.bottom + 1f
            }
        }

        // Tulisan harus muat sampai batas bawah. Kalau tidak muat, ukurannya diperkecil
        val room = (limit - top).coerceAtLeast(MIN_BOX_PX)
        val maxTextH = minOf(maxOf(h + 2f * padY - 2f, (h + 2f * padY) * scale - 2f), room - 2f)
        val fit = fitText(
            item.text,
            wrapW,
            (h * 0.8f * scale).coerceAtLeast(MIN_TEXT_PX),
            maxTextH.coerceAtLeast(MIN_TEXT_PX)
        )
        val layout = fit.layout

        var longest = 0f
        for (i in 0 until layout.lineCount) {
            longest = maxOf(longest, layout.getLineWidth(i))
        }
        val boxW = minOf(maxW, maxOf(rect.width() + 2f * padX, longest + 2f * TEXT_PAD))

        // Kotak hanya tumbuh ke bawah kalau teksnya masih lebih tinggi, dan tidak melewati batas bawah
        val boxH = maxOf(h + 2f * padY, layout.height + 2f * padY)
        val bottom = maxOf(top + MIN_BOX_PX, minOf(top + boxH, limit))

        bgPaint.color = item.bg
        canvas.drawRect(left, top, left + boxW, bottom, bgPaint)

        // Ukuran dan warna harus diatur dulu, karena StaticLayout menggambar memakai paint saat ini
        textPaint.textSize = fit.size
        textPaint.color = item.fg
        canvas.save()
        canvas.translate(left + TEXT_PAD, top + (bottom - top - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()

        return RectF(left, top, left + boxW, bottom)
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
