package com.bosmuda.translatelayar

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Mesin suara bersama untuk fitur Bacakan. Dibuat sejak aplikasi dibuka,
 * supaya saat bulatan dinyalakan suaranya sudah siap dipakai.
 * Semua pemanggilan dilakukan dari main thread.
 */
object Speaker {

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private val waiting = ArrayList<(Boolean) -> Unit>()
    private var appCtx: Context? = null

    @Volatile
    var ready = false
        private set

    /** Membuat mesin suara kalau belum ada. [onReady] dipanggil saat siap atau gagal. */
    fun init(context: Context, onReady: (Boolean) -> Unit = {}) {
        appCtx = context.applicationContext
        if (ready) {
            main.post { onReady(true) }
            return
        }
        waiting.add(onReady)
        if (tts != null) return // Sedang disiapkan, tunggu sampai selesai

        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) tts = null // Supaya bisa dicoba lagi nanti
            val listeners = ArrayList(waiting)
            waiting.clear()
            listeners.forEach { it(ready) }
        }
    }

    /** Null kalau suara siap. Kalau belum, mengembalikan pesan yang bisa dibaca pengguna. */
    fun problemFor(code: String): String? {
        val engine = tts
        if (!ready || engine == null) return "Mesin suara belum siap"
        return when (engine.isLanguageAvailable(localeOf(code))) {
            TextToSpeech.LANG_MISSING_DATA -> "Suara untuk bahasa ini belum terpasang di HP"
            TextToSpeech.LANG_NOT_SUPPORTED -> "Bahasa ini tidak didukung mesin suara HP"
            else -> null
        }
    }

    /** Membacakan teks dengan bahasa [code]. Mengembalikan pesan kalau gagal, atau null kalau berhasil. */
    fun speak(text: String, code: String): String? {
        val problem = problemFor(code)
        if (problem != null) return problem
        val engine = tts ?: return "Mesin suara belum siap"
        val res = engine.setLanguage(localeOf(code))
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            return "Suara untuk bahasa ini belum terpasang di HP"
        }
        // Kecepatan dan nada dari pengaturan, 100 berarti normal
        val p = appCtx?.getSharedPreferences("settings", Context.MODE_PRIVATE)
        engine.setSpeechRate((p?.getInt("tts_rate", 100) ?: 100) / 100f)
        engine.setPitch((p?.getInt("tts_pitch", 100) ?: 100) / 100f)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "baca-layar")
        return null
    }

    /** Menghentikan bacaan yang sedang berjalan, tanpa mematikan mesin suara. */
    fun stop() {
        tts?.stop()
    }

    private fun localeOf(code: String): Locale =
        if (code == "auto") Locale.getDefault() else Locale.forLanguageTag(code)
}
