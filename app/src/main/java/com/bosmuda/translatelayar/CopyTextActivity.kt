package com.bosmuda.translatelayar

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Activity kecil dan transparan, hanya untuk menyalin teks ke clipboard.
 * Android hanya mengizinkan clipboard diisi saat aplikasi sedang di depan,
 * jadi activity ini dibuka sebentar, menyalin, lalu langsung ditutup.
 */
class CopyTextActivity : Activity() {

    companion object {
        const val EXTRA_TEXT = "text"
    }

    private var copied = false
    private val handler = Handler(Looper.getMainLooper())
    private val closeLater = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Jaga-jaga kalau fokus tidak datang, activity tetap ditutup sendiri
        handler.postDelayed(closeLater, 2000L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || copied) return
        copied = true
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        if (text.isNotEmpty()) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("teks layar", text))
            Toast.makeText(this, I18n.tr(this, "Teks disalin"), Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(closeLater)
        super.onDestroy()
    }
}
