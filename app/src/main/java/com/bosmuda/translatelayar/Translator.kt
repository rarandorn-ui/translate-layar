package com.bosmuda.translatelayar

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Terjemahan di HP lewat ML Kit (gratis, tanpa API key).
 * Model bahasa diunduh sekali saat pertama dipakai, setelah itu bisa offline.
 * Harus dipanggil dari background thread, bukan main thread.
 */
object Translator {

    private val cache = HashMap<String, com.google.mlkit.nl.translate.Translator>()

    /**
     * @param apiKey tidak dipakai lagi, dibiarkan agar pemanggil tidak perlu diubah
     * @param source kode bahasa asal, atau null untuk deteksi otomatis
     * @param target kode bahasa tujuan
     * @return hasil terjemahan dengan urutan yang sama dengan [texts]
     */
    @Throws(Exception::class)
    fun translate(apiKey: String, texts: List<String>, source: String?, target: String): List<String> {
        val tgt = TranslateLanguage.fromLanguageTag(target)
            ?: throw IllegalArgumentException("Bahasa tujuan belum didukung: $target")

        val srcTag = source ?: detectLanguage(texts.joinToString(" "))
        val src = TranslateLanguage.fromLanguageTag(srcTag)
            ?: throw IllegalArgumentException("Bahasa asal belum didukung: $srcTag")

        if (src == tgt) return texts

        val translator = cache.getOrPut("$src>$tgt") {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(tgt)
                    .build()
            )
        }

        // Model bahasa diunduh sekali (perlu internet), setelah itu bisa offline
        Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))

        return texts.map { Tasks.await(translator.translate(it)) }
    }

    /** Menebak bahasa dari teks. Kalau tidak yakin, dianggap bahasa Inggris. */
    private fun detectLanguage(text: String): String {
        val identifier = LanguageIdentification.getClient()
        try {
            val code = Tasks.await(identifier.identifyLanguage(text))
            return if (code == "und") "en" else code
        } finally {
            identifier.close()
        }
    }
}
