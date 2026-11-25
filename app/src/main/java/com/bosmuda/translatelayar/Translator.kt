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

    private const val MEMO_LIMIT = 2000

    private val clients = HashMap<String, com.google.mlkit.nl.translate.Translator>()

    // Teks yang sudah pernah diterjemahkan, supaya tidak diterjemahkan ulang
    private val memo = HashMap<String, String>()

    /**
     * @param apiKey tidak dipakai lagi, hanya dibiarkan agar pemanggil tidak perlu diubah
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

        val pair = "$src>$tgt"
        val result = arrayOfNulls<String>(texts.size)
        val misses = ArrayList<Int>()
        for (i in texts.indices) {
            val hit = memo[pair + "\u0001" + texts[i]]
            if (hit != null) {
                result[i] = hit
            } else {
                misses.add(i)
            }
        }
        if (misses.isEmpty()) return result.map { it ?: "" }

        val translator = clients.getOrPut(pair) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(tgt)
                    .build()
            )
        }

        // Model bahasa diunduh sekali (perlu internet), setelah itu bisa offline
        Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))

        for (i in misses) {
            val out = Tasks.await(translator.translate(texts[i]))
            if (memo.size >= MEMO_LIMIT) memo.clear()
            memo[pair + "\u0001" + texts[i]] = out
            result[i] = out
        }
        return result.map { it ?: "" }
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
