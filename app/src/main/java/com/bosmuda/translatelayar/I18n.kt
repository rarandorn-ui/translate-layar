package com.bosmuda.translatelayar

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Tampilan Bahasa Inggris. Teks bahasa Indonesia di aplikasi dicocokkan dengan kamus ini.
 * Teks yang tidak ada di kamus tetap ditampilkan apa adanya.
 * Pilihan bahasa tersimpan di preferensi "settings" dengan kunci "ui_lang" ("id" atau "en").
 */
object I18n {

    fun isEnglish(context: Context): Boolean =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("ui_lang", "id") == "en"

    /** Mengembalikan teks sesuai bahasa yang dipilih. Kalau bahasanya Indonesia, teks dikembalikan apa adanya. */
    fun tr(context: Context, text: String): String {
        if (!isEnglish(context)) return text
        EN[text]?.let { return it }
        var result = text
        for ((pre, en) in PREFIX) {
            if (result.startsWith(pre)) {
                result = en + result.substring(pre.length)
                break
            }
        }
        for ((id, en) in INNER) {
            result = result.replace(id, en)
        }
        return result
    }

    /** Menerjemahkan semua tulisan di sebuah tampilan dan turunannya. Dipanggil saat layar dibuka. */
    fun translateTree(context: Context, root: View) {
        if (!isEnglish(context)) return
        if (root is BottomNavigationView) {
            val menu = root.menu
            for (i in 0 until menu.size()) {
                val item = menu.getItem(i)
                item.title = tr(context, item.title.toString())
            }
        } else if (root is TextView && root !is EditText) {
            root.text = tr(context, root.text.toString())
            root.hint?.let { root.hint = tr(context, it.toString()) }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) translateTree(context, root.getChildAt(i))
        }
    }

    // Awalan teks yang isinya berubah-ubah, misalnya "Build terpasang: 12"
    private val PREFIX = listOf(
        "Gagal menerjemahkan: " to "Translation failed: ",
        "Build terpasang: " to "Installed build: ",
        "Suara bacaan: " to "Read-aloud voice: ",
        "Bahasa tujuan belum didukung: " to "Target language isn't supported yet: ",
        "Bahasa asal belum didukung: " to "Source language isn't supported yet: ",
        "Sudah versi terbaru (build " to "Already the latest version (build ",
        "Build terbaru: " to "Latest build: ",
        "Build kamu: " to "Your build: ",
        "Hapus model " to "Delete model ",
        "Gagal menghapus model " to "Couldn't delete model "
    )

    // Kalimat yang bisa muncul di dalam teks lain
    private val INNER = listOf(
        "Model bahasa belum selesai diunduh. Pastikan internet aktif, lalu coba lagi." to
            "The language model hasn't finished downloading. Make sure internet is on, then try again.",
        "Terjemahan terlalu lama. Coba lagi sebentar." to "Translation took too long. Try again in a moment.",
        "Deteksi bahasa terlalu lama." to "Language detection took too long."
    )

    private val EN: Map<String, String> = mapOf(
        // Layanan bulatan dan terjemahan
        "RTTS aktif" to "RTTS is running",
        "Ketuk bulatan untuk menyalakan atau mematikan terjemahan" to "Tap the bubble to turn translation on or off",
        "Hentikan" to "Stop",
        "Izin rekam layar gagal" to "Screen recording permission failed",
        "Bulatan siap. Ketuk untuk terjemahan, tekan lama untuk menu" to "Bubble ready. Tap to translate, long-press for the menu",
        "Model terjemahan siap" to "Translation model ready",
        "Model terjemahan belum siap. Pastikan internet aktif" to "Translation model isn't ready yet. Make sure internet is on",
        "Model terjemahan masih diunduh, tunggu sebentar" to "Translation model is still downloading, please wait a moment",
        "Terjemahan aktif" to "Translation on",
        "Terjemahan mati" to "Translation off",
        "Belum ada gambar dari layar. Coba gulir layar sedikit, RTTS akan membacanya" to
            "No image from the screen yet. Try scrolling a little, RTTS will read it",
        "Tunggu sebentar, lalu coba lagi" to "Wait a moment, then try again",
        "Layar belum terbaca. Coba gulir layar sedikit, lalu ulangi" to "Screen not read yet. Try scrolling a little, then try again",
        "Teks belum terbaca, coba lagi" to "Text not read yet, try again",
        "Tidak ada teks di layar" to "No text on screen",
        "Pilih baris yang mau disalin" to "Choose the lines to copy",
        "Salin semua" to "Copy all",
        "Salin pilihan" to "Copy selected",
        "Pilih teks yang mau dibacakan" to "Choose the text to read aloud",
        "Bacakan semua" to "Read all",
        "Bacakan pilihan" to "Read selected",
        "Batal" to "Cancel",
        "Belum ada baris yang dipilih" to "No lines selected yet",
        "Tidak ada teks yang dipilih" to "No text selected",
        "📋  Salin teks" to "📋  Copy text",
        "🔊  Bacakan terjemahan" to "🔊  Read translation aloud",
        "✖  Tutup" to "✖  Close",
        "RTTS dihentikan" to "RTTS stopped",
        "Mesin suara belum siap" to "Speech engine isn't ready yet",
        "Suara untuk bahasa ini belum terpasang di HP" to "Voice for this language isn't installed on the phone",
        "Bahasa ini tidak didukung mesin suara HP" to "This language isn't supported by the phone's speech engine",
        "Mulai RTTS dulu lewat tombol Mulai" to "Start RTTS first with the Start button",
        "Tidak ada tulisan di area itu" to "No text in that area",
        "Area tidak terbaca" to "The area couldn't be read",
        "Area terlalu kecil. Coba gambar kotak yang lebih besar" to "Area too small. Draw a bigger box",
        "Area tidak bisa dipotong" to "The area couldn't be cropped",
        "Sedang menerjemahkan..." to "Translating...",
        "Menerjemahkan..." to "Translating...",
        "Mendengarkan..." to "Listening...",
        "Teks disalin" to "Text copied",
        "Terjemahan disalin" to "Translation copied",
        "Hasil disalin" to "Result copied",

        // Halaman utama, pengaturan, dan dialog
        "Tidak ada suara yang terdengar. Coba lagi." to "No speech was heard. Try again.",
        "Model terjemahan: belum disiapkan" to "Translation model: not prepared yet",
        "Suara bacaan: sedang disiapkan" to "Read-aloud voice: being prepared",
        "Izin rekam layar dibatalkan" to "Screen recording permission cancelled",
        "Tidak ada browser untuk membuka link" to "No browser to open the link",
        "Pengaturan disimpan" to "Settings saved",
        "Layanan dihentikan" to "Service stopped",
        "Izin tampil di atas aplikasi lain: sudah" to "Display over other apps: granted",
        "Izin tampil di atas aplikasi lain: belum" to "Display over other apps: not yet",
        "Izin rekam layar diminta setiap kali layanan dimulai. Ini aturan Android." to
            "Screen recording permission is asked every time the service starts. This is an Android rule.",
        "Kalau layanan sedang jalan, ukuran langsung berubah saat slider dilepas." to
            "If the service is running, the size changes right away when you let go of the slider.",
        "Pengaturan diterapkan" to "Settings applied",
        "Sedang diunduh..." to "Downloading...",
        "Menunggu" to "Waiting",
        "Layanan sudah berjalan. Ketuk bulatan untuk memakainya." to "The service is already running. Tap the bubble to use it.",
        "Izinkan 'tampil di atas aplikasi lain' dulu" to "Allow 'display over other apps' first",
        "Izin sudah aktif" to "Permission is already on",
        "Model terjemahan: siap" to "Translation model: ready",
        "Model terjemahan: sedang disiapkan, perlu internet untuk pertama kali" to
            "Translation model: being prepared, needs internet the first time",
        "Model terjemahan: belum siap. Pastikan internet aktif, lalu buka aplikasi lagi" to
            "Translation model: not ready. Make sure internet is on, then reopen the app",
        "Suara bacaan: siap" to "Read-aloud voice: ready",
        "Tekan tombol mikrofon untuk mulai bicara." to "Press the mic button to start speaking.",
        "Pilih dua bahasa yang berbeda" to "Choose two different languages",
        "HP ini belum punya layanan pengenalan suara" to "This phone doesn't have a speech recognition service yet",
        "Pilih bahasa asal dulu, bukan Otomatis" to "Choose a source language first, not Auto",
        "Bahasa itu belum bisa dipakai untuk ditukar" to "That language can't be swapped yet",
        "Ketik teksnya dulu" to "Type the text first",
        "Gambar tidak bisa dibuka" to "The image can't be opened",
        "Membaca tulisan di gambar..." to "Reading text in the image...",
        "Tidak ada tulisan yang terbaca di gambar" to "No text could be read in the image",
        "Tulisan di gambar tidak terbaca" to "Text in the image couldn't be read",
        "Bicara sekarang" to "Speak now",
        "Belum ada model yang diunduh" to "No models have been downloaded yet",
        "Gagal membaca daftar model" to "Couldn't read the model list",
        "Model terjemahan" to "Translation models",
        "Tutup" to "Close",
        "Hapus" to "Delete",
        "Model ini akan dihapus dari HP. Kalau dipakai lagi, perlu diunduh ulang dengan internet." to
            "This model will be removed from the phone. If you use it again, it has to be downloaded again with internet.",
        "Model " to "Model ",
        " dihapus" to " deleted",
        "Mengecek pembaruan..." to "Checking for updates...",
        "Gagal cek pembaruan. Pastikan internet aktif" to "Update check failed. Make sure internet is on",
        "Ada pembaruan" to "Update available",
        "Buka halaman update untuk mengunduh APK terbaru?" to "Open the update page to download the latest APK?",
        "Buka halaman update" to "Open update page",
        "Cara memperbarui: copot (uninstall) RTTS yang lama dulu, lalu pasang APK yang baru. Pengaturan dan izin tampil di atas aplikasi lain perlu diatur ulang." to
            "How to update: uninstall the old RTTS first, then install the new APK. Settings and the display-over-other-apps permission will need to be set up again after updating.",
        "Nanti" to "Later",
        "Bahasa" to "Language",
        "Bahasa asal" to "Source language",
        "Bahasa tujuan" to "Target language",
        "Bahasa tampilan aplikasi" to "App display language",

        // Halaman proses teks dari aplikasi lain
        "Tidak ada teks yang bisa diterjemahkan" to "No text can be translated",
        "Terjemahan RTTS" to "RTTS translation",
        "Teks asli" to "Original text",
        "Terjemahan" to "Translation",
        "Salin" to "Copy",
        "Gagal menerjemahkan. Untuk pertama kali, pastikan internet aktif supaya model bahasa bisa diunduh." to
            "Translation failed. The first time, make sure internet is on so the language model can be downloaded.",

        // Nama bahasa
        "Otomatis (Latin dan Jepang)" to "Auto (Latin and Japanese)",
        "Inggris" to "English",
        "Indonesia" to "Indonesian",
        "Prancis" to "French",
        "Jerman" to "German",
        "Spanyol" to "Spanish",
        "Mandarin" to "Mandarin Chinese",
        "Jepang" to "Japanese",
        "Korea" to "Korean",
        "Arab" to "Arabic",
        "Thailand" to "Thai",
        "Vietnam" to "Vietnamese",

        // Tulisan di layar utama dan halaman Teks, Percakapan, serta Pengaturan
        "Beranda" to "Home",
        "Teks" to "Text",
        "Percakapan" to "Conversation",
        "Status" to "Status",
        "Cara pakai" to "How to use",
        "1. Izinkan tampil di atas aplikasi lain." to "1. Allow display over other apps.",
        "2. Tekan Mulai dan izinkan rekam layar." to "2. Tap Start and allow screen recording.",
        "3. Ketuk bulatan untuk menyalakan terjemahan otomatis." to "3. Tap the bubble to turn on automatic translation.",
        "4. Tekan lama bulatan untuk menu: terjemahan sekali, salin teks, atau bacakan terjemahan." to
            "4. Long-press the bubble for the menu: translate once, copy text, or read the translation aloud.",
        "Kontrol" to "Controls",
        "1. Izinkan tampil di atas aplikasi lain" to "1. Allow display over other apps",
        "2. Mulai (izinkan rekam layar)" to "2. Start (allow screen recording)",
        "Hentikan layanan" to "Stop service",
        "Siap dipakai" to "Ready to use",
        "Gagal membaca status terbaru. Yang tampil status terakhir." to "Couldn't read the latest status. Showing the last known status.",
        "Izin tampil di atas aplikasi lain" to "Display over other apps",
        "Izin tampil di atas aplikasi lain belum aktif" to "Display over other apps is not active yet",
        "Suara bacaan siap" to "Speech ready",
        "Dukung pengembang" to "Support the developer",
        "Kalau aplikasi ini membantu, kamu bisa mendukung pengembangannya lewat Saweria." to
            "If this app helps you, you can support its development through Saweria.",
        "Dukung lewat Saweria" to "Support through Saweria",
        "Pengaturan" to "Settings",
        "Ukuran" to "Size",
        "Ukuran bulatan" to "Bubble size",
        "Ukuran tulisan terjemahan" to "Translated text size",
        "Simpan pengaturan" to "Save settings",
        "Pembaruan" to "Updates",
        "Cek pembaruan" to "Check for updates",
        "Suara bacaan" to "Read-aloud voice",
        "Kecepatan" to "Speed",
        "Nada" to "Pitch",
        "Model bahasa disimpan di HP supaya bisa dipakai tanpa internet. Lima bahasa paling umum diunduh otomatis." to
            "Language models are stored on the phone so they work offline. The five most common languages are downloaded automatically.",
        "Kelola model bahasa" to "Manage language models",
        "Ketik atau tempel teks di sini" to "Type or paste text here",
        "🔊 Bacakan" to "🔊 Read aloud",
        "✖ Hapus" to "✖ Delete",
        "Dari gambar" to "From image",
        "Dengan suara" to "With voice",
        "Terjemahkan" to "Translate",
        "Hasil" to "Result",
        "Hasil terjemahan akan muncul di sini" to "The translation will appear here",
        "Salin hasil" to "Copy result",
        "🔊 Bacakan hasil" to "🔊 Read result aloud",
        "Saya bicara" to "I speak",
        "Lawan bicara" to "Other person",
        "🎤 Saya bicara" to "🎤 I speak",
        "🎤 Lawan bicara" to "🎤 Other person",
        "Riwayat" to "History",
        "Hapus percakapan" to "Clear conversation",
        "Model bahasa" to "Language models",
        "Unduh" to "Download",
        "Unduh semua" to "Download all",
        "Hapus semua" to "Delete all",
        "Sudah diunduh" to "Downloaded",
        "Belum diunduh" to "Not downloaded",
        "Lanjutkan" to "Continue",
        "Unduh semua model?" to "Download all models?",
        "Hapus semua model?" to "Delete all models?",
        "Semua bahasa akan diunduh. Ini bisa memakai banyak kuota internet dan ruang penyimpanan HP. Sebaiknya pakai Wi-Fi." to "All languages will be downloaded. This can use a lot of mobile data and storage. Wi-Fi is recommended.",
        "Semua model yang sudah diunduh akan dihapus dari HP. Model akan diunduh lagi saat dipakai." to "All downloaded models will be removed from the phone. A model will be downloaded again when it's used.",
        "Membaca status model..." to "Reading model status...",
        "Model yang sudah diunduh bisa dipakai tanpa internet." to "Downloaded models can be used without internet.",
        "Tunggu, masih ada model yang diproses" to "Please wait, models are still being processed",
        "Unduhan selesai" to "Download finished",
        "Ada beberapa model yang gagal diunduh. Pastikan internet aktif." to "Some models failed to download. Make sure the internet is on.",
        "Model dihapus" to "Model deleted",
        "Ada beberapa model yang gagal dihapus" to "Some models failed to be deleted",
        "Model bahasa umum sedang diunduh" to "Common language models are downloading",
        "Model bahasa umum sudah siap" to "Common language models are ready",
        "Sebagian model belum terunduh. Dicoba lagi saat aplikasi dibuka" to "Some models are not downloaded yet. Will retry next time the app opens"
    )
}
