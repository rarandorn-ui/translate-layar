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
    private const val DETECT_PAUSE_MS = 60_000L

    // Dipakai dari beberapa thread (layanan, menu pilih teks, dan persiapan model)
    private val clients = ConcurrentHashMap<String, com.google.mlkit.nl.translate.Translator>()

    // Teks yang sudah pernah diterjemahkan, supaya tidak diterjemahkan ulang
    private val memo = ConcurrentHashMap<String, String>()

    // Pasangan bahasa yang sedang diunduh, dan yang modelnya sudah siap
    private val downloading = ConcurrentHashMap.newKeySet<String>()
    private val ready = ConcurrentHashMap.newKeySet<String>()

    // Pengenal bahasa per baris, dibuat sekali supaya tidak lambat untuk banyak baris
    private val identifier by lazy { LanguageIdentification.getClient() }

    // Hasil pengenalan bahasa per baris, supaya baris yang sama tidak dikenali ulang
    private val detectMemo = ConcurrentHashMap<String, String>()

    // Sampai kapan pengenalan per baris dilewati, setelah ia gagal atau macet
    @Volatile
    private var detectPausedUntil = 0L

    // Bahasa Asia timur yang sering tertukar oleh pengenal
    private val CJK = setOf("ja", "zh", "ko")

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
     * Tidak memakai API key apa pun. Terjemahan berjalan di HP lewat ML Kit.
     * @param source kode bahasa asal, atau null untuk deteksi otomatis
     * @param target kode bahasa tujuan
     * @return hasil terjemahan dengan urutan yang sama dengan [texts]
     */
    @Throws(Exception::class)
    fun translate(texts: List<String>, source: String?, target: String): List<String> {
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

    /**
     * Menerjemahkan banyak baris sekaligus, dengan bahasa asal ditentukan untuk tiap baris.
     * Jadi teks Inggris di halaman yang bahasa asalnya Jepang tetap diterjemahkan sebagai Inggris.
     * Harus dipanggil dari background thread.
     * @param chosen bahasa asal yang dipilih pengguna, atau null untuk otomatis
     */
    @Throws(Exception::class)
    fun translateMixed(texts: List<String>, chosen: String?, target: String): List<String> {
        val sources = texts.map { resolveSource(it, chosen) }

        // Baris dikelompokkan per bahasa asal, supaya tiap pasangan bahasa hanya dipanggil sekali
        val byLang = LinkedHashMap<String, MutableList<Int>>()
        sources.forEachIndexed { i, src -> byLang.getOrPut(src) { ArrayList() }.add(i) }

        val result = arrayOfNulls<String>(texts.size)
        var firstError: Exception? = null
        for ((src, indexes) in byLang) {
            try {
                val out = translate(indexes.map { texts[it] }, src, target)
                indexes.forEachIndexed { k, i -> result[i] = out.getOrElse(k) { "" } }
            } catch (e: Exception) {
                // Kelompok yang gagal dikosongkan, kelompok lain tetap diterjemahkan
                if (firstError == null) firstError = e
            }
        }
        // Hanya kalau semua kelompok gagal, penyebabnya dilaporkan ke pemanggil
        val err = firstError
        if (err != null && result.all { it == null }) throw err
        return result.map { it ?: "" }
    }

    /** Bahasa asal untuk satu baris. Kalau pengenal tidak yakin, dipakai tebakan dari jenis huruf, lalu pilihan pengguna. */
    private fun resolveSource(text: String, chosen: String?): String {
        val pick: String? = chosen?.takeIf { TranslateLanguage.fromLanguageTag(it) != null }
        val detected: String? = detectLine(text)?.takeIf { TranslateLanguage.fromLanguageTag(it) != null }
        if (detected != null) {
            // Jepang, Mandarin, dan Korea sering tertukar. Kalau pilihan pengguna salah satu dari itu, pilihan itu yang dipakai
            if (pick != null && pick in CJK && detected in CJK) return pick
            return detected
        }
        return scriptGuess(text, pick) ?: pick ?: "en"
    }

    /**
     * Tebakan bahasa dari jenis huruf, untuk teks pendek yang pengenalnya tidak yakin.
     * Hangul berarti Korea, kana berarti Jepang, dan kanji saja mengikuti pilihan atau Jepang.
     */
    private fun scriptGuess(text: String, pick: String?): String? {
        var kana = false
        var hangul = false
        var han = false
        for (ch in text) {
            when (Character.UnicodeBlock.of(ch)) {
                Character.UnicodeBlock.HIRAGANA, Character.UnicodeBlock.KATAKANA -> kana = true
                Character.UnicodeBlock.HANGUL_SYLLABLES,
                Character.UnicodeBlock.HANGUL_JAMO,
                Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO -> hangul = true
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS -> han = true
                else -> Unit
            }
        }
        return when {
            hangul -> "ko"
            kana -> "ja"
            han -> if (pick != null && pick in CJK) pick else "ja"
            else -> null
        }
    }

    /** Bahasa satu baris teks, atau null kalau pengenal tidak yakin atau gagal. */
    private fun detectLine(text: String): String? {
        detectMemo[text]?.let { return it.ifEmpty { null } }
        // Kalau pengenal baru saja gagal, lewati dulu supaya terjemahan tidak tertahan baris demi baris
        if (System.currentTimeMillis() < detectPausedUntil) return null
        val code: String = try {
            awaitOrFail(identifier.identifyLanguage(text), DETECT_TIMEOUT_S, "Deteksi bahasa terlalu lama.")
        } catch (e: Exception) {
            detectPausedUntil = System.currentTimeMillis() + DETECT_PAUSE_MS
            return null
        }
        if (detectMemo.size >= MEMO_LIMIT) detectMemo.clear()
        val tag = if (code == "und") "" else code
        detectMemo[text] = tag
        return tag.ifEmpty { null }
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

    /** Dipakai dari luar untuk menebak kode bahasa teks. Harus dipanggil dari background thread. */
    fun detectLanguageTag(text: String): String = detectLanguage(text)

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
