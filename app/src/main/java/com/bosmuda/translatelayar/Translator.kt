package com.bosmuda.translatelayar

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Terjemahan di HP lewat ML Kit (gratis, tanpa API key).
 * Model bahasa diunduh sekali saat pertama dipakai, setelah itu bisa offline.
 * Harus dipanggil dari background thread, bukan main thread.
 */
object Translator {

    private const val MEMO_LIMIT = 2000

    // Batas waktu supaya satu proses yang macet tidak menahan terjemahan berikutnya
    private const val DOWNLOAD_TIMEOUT_S = 120L
    private const val TRANSLATE_TIMEOUT_S = 30L
    private const val DETECT_TIMEOUT_S = 15L

    // Dipakai dari beberapa thread (layanan, menu pilih teks, dan persiapan model)
    private val clients = ConcurrentHashMap<String, com.google.mlkit.nl.translate.Translator>()

    // Teks yang sudah pernah diterjemahkan, supaya tidak diterjemahkan ulang
    private val memo = ConcurrentHashMap<String, String>()

    // Pasangan bahasa yang sedang diunduh, dan yang modelnya sudah siap
    private val downloading = ConcurrentHashMap.newKeySet<String>()
    private val ready = ConcurrentHashMap.newKeySet<String>()

    /**
     * Menyiapkan model bahasa lebih dulu (perlu internet sekali), supaya tidak menunggu saat dipakai.
     * Boleh dipanggil dari thread mana pun.
     */
    fun prepare(source: String, target: String) {
        val src = TranslateLanguage.fromLanguageTag(source) ?: return
        val tgt = TranslateLanguage.fromLanguageTag(target) ?: return
        if (src == tgt) return
        ensureModel(src, tgt, clientFor(src, tgt))
    }

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

        val translator = clientFor(src, tgt)

        // Model bahasa diunduh sekali (perlu internet), setelah itu bisa offline
        ensureModel(src, tgt, translator)

        for (i in misses) {
            val out = awaitOrFail(
                translator.translate(texts[i]),
                TRANSLATE_TIMEOUT_S,
                "Terjemahan terlalu lama. Coba lagi sebentar."
            )
            if (memo.size >= MEMO_LIMIT) memo.clear()
            memo[pair + "\u0001" + texts[i]] = out
            result[i] = out
        }
        return result.map { it ?: "" }
    }

    /** Mengunduh model pasangan bahasa kalau belum ada, sambil mencatat statusnya. */
    private fun ensureModel(src: String, tgt: String, client: com.google.mlkit.nl.translate.Translator) {
        val key = "$src>$tgt"
        if (ready.contains(key)) return
        downloading.add(key)
        try {
            awaitOrFail(
                client.downloadModelIfNeeded(DownloadConditions.Builder().build()),
                DOWNLOAD_TIMEOUT_S,
                "Model bahasa belum selesai diunduh. Pastikan internet aktif, lalu coba lagi."
            )
            ready.add(key)
        } finally {
            downloading.remove(key)
        }
    }

    /** Melupakan status model yang sudah siap, misalnya setelah model dihapus. */
    fun forgetReady() {
        ready.clear()
    }

    /** True kalau model pasangan bahasa ini sedang diunduh dan belum siap. */
    fun isDownloading(source: String, target: String): Boolean {
        val key = "$source>$target"
        return downloading.contains(key) && !ready.contains(key)
    }

    private fun clientFor(src: String, tgt: String): com.google.mlkit.nl.translate.Translator =
        clients.getOrPut("$src>$tgt") {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(tgt)
                    .build()
            )
        }

    /** Menunggu hasil dengan batas waktu. Kalau lewat, dilempar pesan yang bisa dibaca pengguna. */
    private fun <T> awaitOrFail(task: Task<T>, timeoutS: Long, message: String): T =
        try {
            Tasks.await(task, timeoutS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw IllegalStateException(message, e)
        }

    /** Menebak bahasa dari teks. Kalau tidak yakin, dianggap bahasa Inggris. */
    private fun detectLanguage(text: String): String {
        val identifier = LanguageIdentification.getClient()
        try {
            val code = awaitOrFail(identifier.identifyLanguage(text), DETECT_TIMEOUT_S, "Deteksi bahasa terlalu lama.")
            return if (code == "und") "en" else code
        } finally {
            identifier.close()
        }
    }
}
