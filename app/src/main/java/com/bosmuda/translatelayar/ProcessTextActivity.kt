package com.bosmuda.translatelayar

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * Menerima teks dari menu "Pilih teks" di aplikasi lain (PROCESS_TEXT),
 * lalu menampilkan terjemahannya. Tidak perlu layanan RTTS dalam keadaan aktif.
 */
class ProcessTextActivity : Activity() {

    private var translated: String? = null
    private lateinit var tvResult: TextView
    private lateinit var btnCopy: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            toast("Tidak ada teks yang bisa diterjemahkan")
            finish()
            return
        }

        setTitle("Terjemahan RTTS")
        setContentView(buildLayout(text))
        window.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).roundToInt(),
            (resources.displayMetrics.heightPixels * 0.7).roundToInt()
        )
        translate(text)
    }

    private fun buildLayout(original: String): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        // Bagian yang bisa digulir, supaya teks panjang tetap muat
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(label("Teks asli"))
        content.addView(TextView(this).apply {
            text = original
            textSize = 15f
            setTextColor(Color.parseColor("#424242"))
            setTextIsSelectable(true)
        })
        content.addView(label("Terjemahan", topDp = 18))
        tvResult = TextView(this).apply {
            text = "Sedang menerjemahkan..."
            textSize = 18f
            setTextColor(Color.parseColor("#111111"))
            setTextIsSelectable(true)
        }
        content.addView(tvResult)

        val scroll = ScrollView(this)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Tombol di bawah, selalu terlihat
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnClose = pill("Tutup", "#E0E0E0", "#111111") { finish() }
        btnCopy = pill("Salin", "#D32F2F", "#FFFFFF") { copyResult() }
        btnCopy.isEnabled = false
        btnCopy.alpha = 0.4f
        row.addView(btnClose, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            btnCopy,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) }
        )
        root.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) }
        )
        return root
    }

    private fun translate(original: String) {
        val p = getSharedPreferences("settings", Context.MODE_PRIVATE)
        // "auto" berarti bahasa asal dideteksi otomatis (null)
        val src = p.getString("source", "auto")?.takeIf { it != "auto" }
        val tgt = p.getString("target", "id") ?: "id"

        Thread {
            // Terjemahan butuh background thread, bukan main thread
            val out: String? = try {
                Translator.translate("", listOf(original), src, tgt).firstOrNull()
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) showResult(out)
            }
        }.start()
    }

    private fun showResult(out: String?) {
        if (out.isNullOrBlank()) {
            tvResult.text = "Gagal menerjemahkan. Untuk pertama kali, pastikan internet aktif supaya model bahasa bisa diunduh."
            return
        }
        translated = out
        tvResult.text = out
        btnCopy.isEnabled = true
        btnCopy.alpha = 1f
    }

    private fun copyResult() {
        val text = translated ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("terjemahan RTTS", text))
        toast("Terjemahan disalin")
    }

    private fun label(caption: String, topDp: Int = 0): TextView =
        TextView(this).apply {
            text = caption
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#D32F2F"))
            setPadding(0, dp(topDp), 0, dp(4))
        }

    private fun pill(caption: String, bg: String, fg: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = caption
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(fg))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setShape(GradientDrawable.RECTANGLE)
                setCornerRadius(dp(24).toFloat())
                setColor(Color.parseColor(bg))
            }
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
