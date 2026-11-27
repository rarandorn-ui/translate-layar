package com.bosmuda.translatelayar

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import com.google.android.gms.tasks.Tasks
import com.google.android.material.button.MaterialButton
import com.google.mlkit.common.model.DownloadConditions
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import android.speech.RecognizerIntent
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.util.Locale
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        const val BUBBLE_MIN = 36
        const val BUBBLE_MAX = 80
        const val TEXT_MIN = 50
        const val TEXT_MAX = 150
        const val TTS_MIN = 50
        const val TTS_MAX = 200
        const val RELEASE_API = "https://api.github.com/repos/rarandorn-ui/translate-layar/releases/latest"
        const val RELEASE_PAGE = "https://github.com/rarandorn-ui/translate-layar/releases/latest"
    }

    private lateinit var spSource: Spinner
    private lateinit var spTarget: Spinner
    private lateinit var sbBubble: SeekBar
    private lateinit var tvBubbleVal: TextView
    private lateinit var sbText: SeekBar
    private lateinit var tvTextVal: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvBuild: TextView

    // Halaman Teks
    private lateinit var spTeksSource: Spinner
    private lateinit var spTeksTarget: Spinner
    private lateinit var etTeks: EditText
    private lateinit var tvHasilTeks: TextView
    private lateinit var btnSalinTeks: Button
    private lateinit var btnBacaHasil: Button

    // Halaman Percakapan: dua sisi bicara dengan bahasa berbeda
    private lateinit var spSaya: Spinner
    private lateinit var spLawan: Spinner
    private lateinit var tvStatusPercakapan: TextView
    private lateinit var tvPercakapan: TextView
    private lateinit var btnBicaraSaya: Button
    private lateinit var btnBicaraLawan: Button
    private var sisiSaya = true
    private val logPercakapan = StringBuilder()
    private val percakapanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val teks = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (teks.isNullOrBlank()) tvStatusPercakapan.text = I18n.tr(this, "Tidak ada suara yang terdengar. Coba lagi.")
        else terjemahkanPercakapan(teks, sisiSaya)
    }
    private var hasilTeks: String? = null

    // Kecepatan dan nada suara bacaan
    private lateinit var sbRate: SeekBar
    private lateinit var tvRateVal: TextView
    private lateinit var sbPitch: SeekBar
    private lateinit var tvPitchVal: TextView

    // Pemilih gambar dan pengenal suara untuk halaman Teks
    private val gambarLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) bacaGambar(uri)
    }
    private val suaraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val teks = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!teks.isNullOrBlank()) etTeks.setText(teks)
    }

    // Status model terjemahan, ditampilkan di Beranda
    private var modelStatus = "Model terjemahan: belum disiapkan"

    // Model bahasa yang paling umum diunduh otomatis, dan penanda tugas unduh atau hapus yang sedang berjalan
    private val MODEL_UMUM = listOf("id", "en", "ja", "zh", "ko")
    private var modelSibuk = false

    // Status suara bacaan, ditampilkan di Beranda
    private var speechStatus = "Suara bacaan: sedang disiapkan"

    // Pilihan bahasa tampilan aplikasi: 0 = Indonesia, 1 = English
    private lateinit var spUi: Spinner

    // Hasil izin rekam layar (MediaProjection)
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            startTranslateService(result.resultCode, data)
        } else {
            toast("Izin rekam layar dibatalkan")
        }
    }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Tulisan dari layout diganti ke Bahasa Inggris kalau pilihannya English
        I18n.translateTree(this, window.decorView)

        spSource = findViewById(R.id.spSource)
        spTarget = findViewById(R.id.spTarget)
        sbBubble = findViewById(R.id.sbBubble)
        tvBubbleVal = findViewById(R.id.tvBubbleVal)
        sbText = findViewById(R.id.sbText)
        tvTextVal = findViewById(R.id.tvTextVal)
        sbRate = findViewById(R.id.sbRate)
        tvRateVal = findViewById(R.id.tvRateVal)
        sbPitch = findViewById(R.id.sbPitch)
        tvPitchVal = findViewById(R.id.tvPitchVal)
        tvStatus = findViewById(R.id.tvStatus)
        tvBuild = findViewById(R.id.tvBuild)
        tvBuild.text = I18n.tr(this, "Build terpasang: ") + localBuild()

        spSource.adapter = langAdapter(Languages.SOURCE)
        spTarget.adapter = langAdapter(Languages.TARGET)
        spUi = findViewById(R.id.spUi)
        spUi.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf("Indonesia", "English")).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spUi.setSelection(if (I18n.isEnglish(this)) 1 else 0)

        sbBubble.max = BUBBLE_MAX - BUBBLE_MIN
        sbText.max = TEXT_MAX - TEXT_MIN
        sbRate.max = TTS_MAX - TTS_MIN
        sbPitch.max = TTS_MAX - TTS_MIN
        // Semua slider disimpan dan langsung berlaku begitu jari dilepas, tanpa perlu tombol Simpan
        val geser = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { simpanOtomatis() }
        }
        sbBubble.setOnSeekBarChangeListener(geser)
        sbText.setOnSeekBarChangeListener(geser)
        sbRate.setOnSeekBarChangeListener(geser)
        sbPitch.setOnSeekBarChangeListener(geser)
        loadSettings()

        // Bahasa dan tampilan disimpan begitu dipilih, tanpa perlu tombol Simpan
        val pilihBahasa = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                simpanBahasa()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spSource.onItemSelectedListener = pilihBahasa
        spTarget.onItemSelectedListener = pilihBahasa
        spUi.onItemSelectedListener = pilihBahasa

        // Navigasi bawah: Beranda dan Pengaturan
        val pageHome = findViewById<View>(R.id.pageHome)
        val pageSettings = findViewById<View>(R.id.pageSettings)
        val pageTeks = findViewById<View>(R.id.pageTeks)
        val pagePercakapan = findViewById<View>(R.id.pagePercakapan)
        findViewById<BottomNavigationView>(R.id.bottomNav).setOnItemSelectedListener { item ->
            pageHome.visibility = if (item.itemId == R.id.navHome) View.VISIBLE else View.GONE
            pageTeks.visibility = if (item.itemId == R.id.navTeks) View.VISIBLE else View.GONE
            pagePercakapan.visibility = if (item.itemId == R.id.navPercakapan) View.VISIBLE else View.GONE
            pageSettings.visibility = if (item.itemId == R.id.navSettings) View.VISIBLE else View.GONE
            true
        }

        setupTeksPage()
        setupPercakapanPage()

        val openSupport = View.OnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://saweria.co/RizkyAhmad9")))
            } catch (e: Exception) {
                Toast.makeText(this, "Tidak ada browser untuk membuka link", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnSupport).setOnClickListener(openSupport)
        findViewById<Button>(R.id.btnSupportHome).setOnClickListener(openSupport)
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            requestOverlayPermission()
        }
        // Satu tombol untuk dua keadaan: Mulai saat layanan mati, Hentikan saat layanan jalan
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (TranslateService.isRunning) {
                stopService(Intent(this, TranslateService::class.java))
                toast("Layanan dihentikan")
                findViewById<Button>(R.id.btnStart).postDelayed({ perbaruiTombolMulai() }, 500)
            } else {
                startFlow()
            }
        }
        findViewById<Button>(R.id.btnKelolaModel).setOnClickListener { kelolaModel() }
        findViewById<Button>(R.id.btnUpdate).setOnClickListener {
            checkUpdate()
        }

        // Siapkan mesin suara untuk fitur Bacakan, supaya siap saat dipakai
        Speaker.init(applicationContext) { if (!isDestroyed) checkSpeech() }
        checkSpeech()

        // Siapkan model terjemahan begitu aplikasi dibuka, supaya bulatan siap dipakai
        prepareModels()
        // Model bahasa yang paling umum diunduh otomatis, sekali saja
        unduhModelUmum()

        // Cek pembaruan otomatis saat aplikasi pertama dibuka
        if (savedInstanceState == null) autoCheckUpdate()

        // Android 13 ke atas perlu izin notifikasi untuk layanan latar
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        perbaruiTombolMulai()
    }

    /** Mengatur tombol btnStart sesuai keadaan layanan: Mulai (merah) atau Hentikan layanan (abu-abu). */
    private fun perbaruiTombolMulai() {
        val btn = findViewById<Button>(R.id.btnStart)
        if (TranslateService.isRunning) {
            btn.text = I18n.tr(this, "Hentikan layanan")
            btn.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#E0E0E0")))
            btn.setTextColor(Color.parseColor("#111111"))
        } else {
            btn.text = I18n.tr(this, "2. Mulai (izinkan rekam layar)")
            btn.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#D32F2F")))
            btn.setTextColor(Color.WHITE)
        }
    }

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        tvStatus.text = buildString {
            // Di dalam buildString, "this" adalah StringBuilder, jadi konteks aplikasinya ditulis eksplisit
            appendLine(I18n.tr(this@MainActivity, "Izin tampil di atas aplikasi lain: ${if (overlayOk) "sudah" else "belum"}"))
            appendLine(I18n.tr(this@MainActivity, modelStatus))
            appendLine(I18n.tr(this@MainActivity, speechStatus))
            appendLine(I18n.tr(this@MainActivity, "Izin rekam layar diminta setiap kali layanan dimulai. Ini aturan Android."))
            appendLine(I18n.tr(this@MainActivity, "Kalau layanan sedang jalan, ukuran langsung berubah saat slider dilepas."))
        }
    }

    private fun startFlow() {
        saveSettings()
        if (TranslateService.isRunning) {
            toast("Layanan sudah berjalan. Ketuk bulatan untuk memakainya.")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Izinkan 'tampil di atas aplikasi lain' dulu")
            requestOverlayPermission()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startTranslateService(resultCode: Int, data: Intent) {
        val intent = Intent(this, TranslateService::class.java).apply {
            action = TranslateService.ACTION_START
            putExtra(TranslateService.EXTRA_RESULT_CODE, resultCode)
            putExtra(TranslateService.EXTRA_DATA, data)
        }
        ContextCompat.startForegroundService(this, intent)
        // Layanan mulai sedikit setelahnya, jadi tombol diperbarui dengan jeda kecil
        findViewById<Button>(R.id.btnStart).postDelayed({ perbaruiTombolMulai() }, 800)
        // Kembali ke layar supaya bulatan langsung terlihat di atas aplikasi lain
        moveTaskToBack(true)
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            toast("Izin sudah aktif")
            return
        }
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun langAdapter(list: List<Lang>): ArrayAdapter<String> {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, list.map { I18n.tr(this, it.label) })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return adapter
    }

    private fun prefs() = getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun loadSettings() {
        val p = prefs()
        val src = p.getString("source", "auto") ?: "auto"
        val tgt = p.getString("target", "id") ?: "id"
        spSource.setSelection(Languages.SOURCE.indexOfFirst { it.code == src }.coerceAtLeast(0))
        spTarget.setSelection(Languages.TARGET.indexOfFirst { it.code == tgt }.coerceAtLeast(0))
        sbBubble.progress = p.getInt("bubble_dp", 56) - BUBBLE_MIN
        sbText.progress = p.getInt("text_pct", 100) - TEXT_MIN
        sbRate.progress = p.getInt("tts_rate", 100) - TTS_MIN
        sbPitch.progress = p.getInt("tts_pitch", 100) - TTS_MIN
        updateLabels()
    }

    private fun updateLabels() {
        tvBubbleVal.text = "${sbBubble.progress + BUBBLE_MIN} dp"
        tvTextVal.text = "${sbText.progress + TEXT_MIN}%"
        tvRateVal.text = "${sbRate.progress + TTS_MIN}%"
        tvPitchVal.text = "${sbPitch.progress + TTS_MIN}%"
    }

    /** Menyimpan semua pengaturan begitu ada yang berubah. Layar dibuat ulang kalau bahasa tampilan berganti. */
    private fun simpanOtomatis() {
        if (saveSettings()) recreate()
    }

    /** Dipanggil saat bahasa dipilih. Hanya menyimpan kalau pilihannya benar-benar berbeda dari yang tersimpan. */
    private fun simpanBahasa() {
        val p = prefs()
        val src = Languages.SOURCE[spSource.selectedItemPosition].code
        val tgt = Languages.TARGET[spTarget.selectedItemPosition].code
        val uiBaru = if (spUi.selectedItemPosition == 1) "en" else "id"
        val sama = src == (p.getString("source", "auto") ?: "auto") &&
            tgt == (p.getString("target", "id") ?: "id") &&
            uiBaru == (p.getString("ui_lang", "id") ?: "id")
        if (!sama) simpanOtomatis()
    }

    /** Menyimpan pengaturan. Mengembalikan true kalau bahasa tampilan berganti, supaya layar dibuat ulang. */
    private fun saveSettings(): Boolean {
        val uiLama = if (I18n.isEnglish(this)) "en" else "id"
        val uiBaru = if (spUi.selectedItemPosition == 1) "en" else "id"
        prefs().edit()
            .putString("ui_lang", uiBaru)
            .putString("source", Languages.SOURCE[spSource.selectedItemPosition].code)
            .putString("target", Languages.TARGET[spTarget.selectedItemPosition].code)
            .putInt("bubble_dp", sbBubble.progress + BUBBLE_MIN)
            .putInt("text_pct", sbText.progress + TEXT_MIN)
            .putInt("tts_rate", sbRate.progress + TTS_MIN)
            .putInt("tts_pitch", sbPitch.progress + TTS_MIN)
            .apply()
        // Bahasa bisa berganti, jadi model untuk pasangan baru perlu disiapkan
        prepareModels()
        checkSpeech()
        // Kalau layanan sedang jalan, terapkan ukuran baru sekarang juga
        if (TranslateService.isRunning) {
            startService(
                Intent(this, TranslateService::class.java).setAction(TranslateService.ACTION_APPLY)
            )
        }
        return uiBaru != uiLama
    }

    /**
     * Menyiapkan model terjemahan di latar belakang. Dipanggil saat aplikasi dibuka
     * dan saat bahasa diganti, supaya saat bulatan dinyalakan tidak perlu menunggu.
     */
    private fun prepareModels() {
        val p = prefs()
        val src = p.getString("source", "auto") ?: "auto"
        val tgt = p.getString("target", "id") ?: "id"
        // Otomatis: Inggris dipakai sebagai perkiraan paling umum
        val from = if (src == "auto") "en" else src
        if (from == tgt) {
            modelStatus = "Model terjemahan: siap"
            refreshStatus()
            return
        }
        modelStatus = "Model terjemahan: sedang disiapkan, perlu internet untuk pertama kali"
        refreshStatus()
        Thread {
            val ok = try {
                Translator.prepare(from, tgt)
                true
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                if (!isDestroyed) {
                    modelStatus = if (ok) {
                        "Model terjemahan: siap"
                    } else {
                        "Model terjemahan: belum siap. Pastikan internet aktif, lalu buka aplikasi lagi"
                    }
                    refreshStatus()
                }
            }
        }.start()
    }

    /** Mengecek kesiapan suara bacaan untuk bahasa tujuan, lalu menampilkannya di Beranda. */
    private fun checkSpeech() {
        if (!Speaker.ready) {
            speechStatus = "Suara bacaan: sedang disiapkan"
            refreshStatus()
            return
        }
        val tgt = prefs().getString("target", "id") ?: "id"
        val problem = Speaker.problemFor(tgt)
        speechStatus = if (problem == null) "Suara bacaan: siap" else "Suara bacaan: $problem"
        refreshStatus()
    }

    /** Halaman Teks: ketik teks, pilih bahasa, lalu terjemahkan dan salin hasilnya. */
    private fun setupTeksPage() {
        spTeksSource = findViewById(R.id.spTeksSource)
        spTeksTarget = findViewById(R.id.spTeksTarget)
        etTeks = findViewById(R.id.etTeks)
        tvHasilTeks = findViewById(R.id.tvHasilTeks)
        btnSalinTeks = findViewById(R.id.btnSalinTeks)
        btnBacaHasil = findViewById(R.id.btnBacaHasil)

        spTeksSource.adapter = langAdapter(Languages.SOURCE)
        spTeksTarget.adapter = langAdapter(Languages.TARGET)
        val p = prefs()
        val src = p.getString("text_source", "auto") ?: "auto"
        val tgt = p.getString("text_target", "id") ?: "id"
        spTeksSource.setSelection(Languages.SOURCE.indexOfFirst { it.code == src }.coerceAtLeast(0))
        spTeksTarget.setSelection(Languages.TARGET.indexOfFirst { it.code == tgt }.coerceAtLeast(0))

        findViewById<Button>(R.id.btnTukar).setOnClickListener { tukarBahasa() }
        findViewById<Button>(R.id.btnTerjemahkanTeks).setOnClickListener { terjemahkanTeks() }
        findViewById<Button>(R.id.btnGambarTeks).setOnClickListener { gambarLauncher.launch("image/*") }
        findViewById<Button>(R.id.btnSuaraTeks).setOnClickListener { ketikSuara() }
        findViewById<Button>(R.id.btnBacaTeks).setOnClickListener { bacaTeksAsal() }
        findViewById<Button>(R.id.btnHapusTeks).setOnClickListener { etTeks.setText("") }
        btnBacaHasil.setOnClickListener { bacaHasil() }
        btnSalinTeks.setOnClickListener {
            val text = hasilTeks ?: return@setOnClickListener
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("terjemahan RTTS", text))
            toast("Hasil disalin")
        }
    }

    /** Halaman Percakapan: dua orang bicara bahasa berbeda, RTTS menerjemahkan dan membacakannya. */
    private fun setupPercakapanPage() {
        spSaya = findViewById(R.id.spSaya)
        spLawan = findViewById(R.id.spLawan)
        tvStatusPercakapan = findViewById(R.id.tvStatusPercakapan)
        tvPercakapan = findViewById(R.id.tvPercakapan)
        btnBicaraSaya = findViewById(R.id.btnBicaraSaya)
        btnBicaraLawan = findViewById(R.id.btnBicaraLawan)

        spSaya.adapter = langAdapter(Languages.TARGET)
        spLawan.adapter = langAdapter(Languages.TARGET)
        val p = prefs()
        val kodeSaya = p.getString("chat_saya", "id") ?: "id"
        val kodeLawan = p.getString("chat_lawan", "en") ?: "en"
        spSaya.setSelection(Languages.TARGET.indexOfFirst { it.code == kodeSaya }.coerceAtLeast(0))
        spLawan.setSelection(Languages.TARGET.indexOfFirst { it.code == kodeLawan }.coerceAtLeast(0))

        btnBicaraSaya.setOnClickListener { bicaraPercakapan(true) }
        btnBicaraLawan.setOnClickListener { bicaraPercakapan(false) }
        findViewById<Button>(R.id.btnHapusPercakapan).setOnClickListener {
            logPercakapan.setLength(0)
            tvPercakapan.text = I18n.tr(this, "Tekan tombol mikrofon untuk mulai bicara.")
            tvStatusPercakapan.text = ""
        }
    }

    /** Membuka pengenal suara untuk salah satu sisi. Bahasa sisi itu dipakai untuk mengenali suaranya. */
    private fun bicaraPercakapan(saya: Boolean) {
        val kodeSaya = Languages.TARGET[spSaya.selectedItemPosition].code
        val kodeLawan = Languages.TARGET[spLawan.selectedItemPosition].code
        if (kodeSaya == kodeLawan) {
            toast("Pilih dua bahasa yang berbeda")
            return
        }
        prefs().edit().putString("chat_saya", kodeSaya).putString("chat_lawan", kodeLawan).apply()
        sisiSaya = saya
        val dari = if (saya) kodeSaya else kodeLawan
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, dari)
            putExtra(RecognizerIntent.EXTRA_PROMPT, if (saya) "Saya bicara" else "Lawan bicara")
        }
        try {
            tvStatusPercakapan.text = I18n.tr(this, "Mendengarkan...")
            percakapanLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            tvStatusPercakapan.text = ""
            toast("HP ini belum punya layanan pengenalan suara")
        }
    }

    /** Menerjemahkan ucapan, lalu membacakannya dengan suara bahasa sisi lawan bicara. */
    private fun terjemahkanPercakapan(teks: String, dariSaya: Boolean) {
        val kodeSaya = Languages.TARGET[spSaya.selectedItemPosition].code
        val kodeLawan = Languages.TARGET[spLawan.selectedItemPosition].code
        val dari = if (dariSaya) kodeSaya else kodeLawan
        val ke = if (dariSaya) kodeLawan else kodeSaya
        tvStatusPercakapan.text = I18n.tr(this, "Menerjemahkan...")
        Thread {
            val hasil = runCatching {
                Translator.translate(listOf(teks), dari, ke).firstOrNull().orEmpty()
            }
            runOnUiThread {
                if (!isDestroyed) {
                    hasil.onSuccess { terjemahan ->
                        val label = if (dariSaya) "Saya" else "Lawan"
                        logPercakapan.append("$label: $teks\n→ $terjemahan\n\n")
                        tvPercakapan.text = logPercakapan.toString()
                        tvStatusPercakapan.text = ""
                        bicara(terjemahan, ke)
                    }.onFailure { e ->
                        tvStatusPercakapan.text = I18n.tr(this, "Gagal menerjemahkan: ") + e.message
                    }
                }
            }
        }.start()
    }

    /** Menukar bahasa asal dan tujuan. Bahasa Otomatis tidak bisa ditukar. */
    private fun tukarBahasa() {
        val src = Languages.SOURCE[spTeksSource.selectedItemPosition].code
        val tgt = Languages.TARGET[spTeksTarget.selectedItemPosition].code
        if (src == "auto") {
            toast("Pilih bahasa asal dulu, bukan Otomatis")
            return
        }
        val newSrc = Languages.SOURCE.indexOfFirst { it.code == tgt }
        val newTgt = Languages.TARGET.indexOfFirst { it.code == src }
        if (newSrc < 0 || newTgt < 0) {
            toast("Bahasa itu belum bisa dipakai untuk ditukar")
            return
        }
        spTeksSource.setSelection(newSrc)
        spTeksTarget.setSelection(newTgt)
    }

    /** Menerjemahkan teks dari kotak ketik. Berjalan di thread terpisah supaya layar tidak macet. */
    private fun terjemahkanTeks() {
        val text = etTeks.text.toString().trim()
        if (text.isEmpty()) {
            toast("Ketik teksnya dulu")
            return
        }
        val src = Languages.SOURCE[spTeksSource.selectedItemPosition].code
        val tgt = Languages.TARGET[spTeksTarget.selectedItemPosition].code
        prefs().edit().putString("text_source", src).putString("text_target", tgt).apply()

        tvHasilTeks.text = I18n.tr(this, "Sedang menerjemahkan...")
        btnSalinTeks.isEnabled = false
        btnBacaHasil.isEnabled = false
        val sourceParam = if (src == "auto") null else src
        Thread {
            val hasil = runCatching {
                Translator.translate(listOf(text), sourceParam, tgt).firstOrNull().orEmpty()
            }
            runOnUiThread {
                if (!isDestroyed) {
                    hasil.onSuccess { teks ->
                        hasilTeks = teks
                        tvHasilTeks.text = teks
                        btnSalinTeks.isEnabled = teks.isNotBlank()
                        btnBacaHasil.isEnabled = teks.isNotBlank()
                    }.onFailure { e ->
                        tvHasilTeks.text = I18n.tr(this, "Gagal menerjemahkan: ") + e.message
                    }
                }
            }
        }.start()
    }

    /** Membacakan teks di kotak ketik. Kalau bahasanya Otomatis, bahasanya ditebak dulu supaya suaranya sesuai. */
    private fun bacaTeksAsal() {
        val text = etTeks.text.toString().trim()
        if (text.isEmpty()) {
            toast("Ketik teksnya dulu")
            return
        }
        val src = Languages.SOURCE[spTeksSource.selectedItemPosition].code
        if (src != "auto") {
            bicara(text, src)
            return
        }
        Thread {
            val kode = runCatching { Translator.detectLanguageTag(text) }.getOrDefault("en")
            runOnUiThread { if (!isDestroyed) bicara(text, kode) }
        }.start()
    }

    /** Membacakan hasil terjemahan dengan bahasa tujuan. */
    private fun bacaHasil() {
        val text = hasilTeks ?: return
        val tgt = Languages.TARGET[spTeksTarget.selectedItemPosition].code
        bicara(text, tgt)
    }

    /** Membacakan teks dengan suara HP. Kalau gagal, pesannya ditampilkan. Harus dipanggil dari main thread. */
    private fun bicara(text: String, kode: String) {
        val masalah = Speaker.speak(text, kode)
        if (masalah != null) toast(masalah)
    }

    /** Membaca tulisan dari foto yang dipilih, lalu mengisinya ke kotak ketik. */
    private fun bacaGambar(uri: Uri) {
        val src = Languages.SOURCE[spTeksSource.selectedItemPosition].code
        // Mode Otomatis memakai pengenal Jepang, sama seperti layanan utama (Jepang juga memuat huruf Latin)
        val script = Languages.byCode(src, Languages.SOURCE).script
        val rec = when (script) {
            Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        }
        val image = try {
            InputImage.fromFilePath(this, uri)
        } catch (e: Exception) {
            toast("Gambar tidak bisa dibuka")
            rec.close()
            return
        }
        toast("Membaca tulisan di gambar...")
        rec.process(image)
            .addOnSuccessListener { result ->
                if (result.text.isBlank()) toast("Tidak ada tulisan yang terbaca di gambar")
                else etTeks.setText(result.text)
            }
            .addOnFailureListener { toast("Tulisan di gambar tidak terbaca") }
            .addOnCompleteListener { rec.close() }
    }

    /** Mengetik teks dengan suara lewat pengenal suara yang ada di HP. */
    private fun ketikSuara() {
        val src = Languages.SOURCE[spTeksSource.selectedItemPosition].code
        val lang = if (src == "auto") Locale.getDefault().toLanguageTag() else src
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Bicara sekarang")
        }
        try {
            suaraLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            toast("HP ini belum punya layanan pengenalan suara")
        }
    }

    /** Semua bahasa yang dipakai aplikasi, untuk daftar model. */
    private fun semuaKodeModel(): List<String> =
        (Languages.SOURCE + Languages.TARGET).map { it.code }.filter { it != "auto" }.distinct()

    /** Kode bahasa yang modelnya sudah diunduh. Harus dipanggil dari background thread. */
    private fun kodeTerunduh(): Set<String> {
        val models = Tasks.await(
            RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java),
            30, TimeUnit.SECONDS
        )
        return models.map { it.language }.toSet()
    }

    /** Mengunduh satu model bahasa. Harus dipanggil dari background thread. */
    private fun unduhSatuModel(kode: String): Boolean = try {
        Tasks.await(
            RemoteModelManager.getInstance().download(
                TranslateRemoteModel.Builder(kode).build(),
                DownloadConditions.Builder().build()
            ),
            300, TimeUnit.SECONDS
        )
        true
    } catch (e: Exception) {
        false
    }

    /** Menghapus satu model bahasa. Harus dipanggil dari background thread. */
    private fun hapusSatuModel(kode: String): Boolean = try {
        Tasks.await(
            RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(kode).build()),
            30, TimeUnit.SECONDS
        )
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Mengunduh model bahasa yang paling sering dipakai, sekali saja.
     * Setelah itu terjemahan tidak perlu menunggu unduhan lagi saat pertama dipakai.
     */
    private fun unduhModelUmum() {
        if (prefs().getBoolean("model_umum_siap", false) || modelSibuk) return
        modelSibuk = true
        modelStatus = "Model bahasa umum sedang diunduh"
        refreshStatus()
        Thread {
            val gagal: List<String> = try {
                val terunduh = kodeTerunduh()
                MODEL_UMUM.filter { it !in terunduh && !unduhSatuModel(it) }
            } catch (e: Exception) {
                MODEL_UMUM
            }
            if (gagal.isEmpty()) prefs().edit().putBoolean("model_umum_siap", true).apply()
            runOnUiThread {
                modelSibuk = false
                if (!isDestroyed) {
                    modelStatus = if (gagal.isEmpty()) {
                        "Model bahasa umum sudah siap"
                    } else {
                        "Sebagian model belum terunduh. Dicoba lagi saat aplikasi dibuka"
                    }
                    refreshStatus()
                }
            }
        }.start()
    }

    /** Dialog kelola model yang sedang terbuka. Null kalau dialognya sudah ditutup. */
    private var kelolaDaftar: LinearLayout? = null
    private var kelolaInfo: TextView? = null

    // Status model di dialog: daftar yang sudah diunduh, bahasa yang sedang diunduh, dan yang menunggu
    private var terunduhTerakhir: Set<String> = emptySet()
    private var kodeSedangDiunduh: String? = null
    private val antrianUnduh = LinkedHashSet<String>()

    /** Dialog kelola model: daftar semua bahasa, dengan tombol unduh atau hapus per bahasa. */
    private fun kelolaModel() {
        val info = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#616161"))
        }
        val tombolSemua = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tombolSemua.addView(
            MaterialButton(this).apply {
                text = I18n.tr(this@MainActivity, "Unduh semua")
                setOnClickListener { konfirmasiUnduhSemua() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) }
        )
        tombolSemua.addView(
            MaterialButton(this).apply {
                text = I18n.tr(this@MainActivity, "Hapus semua")
                setOnClickListener { konfirmasiHapusSemua() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) }
        )
        val daftar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val isi = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(info)
            addView(
                tombolSemua,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(10) }
            )
            addView(
                daftar,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(10) }
            )
        }
        kelolaInfo = info
        kelolaDaftar = daftar
        AlertDialog.Builder(this)
            .setTitle(I18n.tr(this, "Model bahasa"))
            .setView(ScrollView(this).apply { addView(isi) })
            .setNegativeButton(I18n.tr(this, "Tutup"), null)
            .setOnDismissListener {
                kelolaDaftar = null
                kelolaInfo = null
            }
            .show()
        muatUlangDaftar()
    }

    /** Membaca status semua model, lalu menggambar ulang daftarnya di dialog yang sedang terbuka. */
    private fun muatUlangDaftar() {
        kelolaInfo?.text = I18n.tr(this, "Membaca status model...")
        Thread {
            val terunduh: Set<String>? = try {
                kodeTerunduh()
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val info = kelolaInfo ?: return@runOnUiThread
                if (terunduh == null) {
                    info.text = I18n.tr(this, "Gagal membaca daftar model")
                    return@runOnUiThread
                }
                info.text = I18n.tr(this, "Model yang sudah diunduh bisa dipakai tanpa internet.")
                terunduhTerakhir = terunduh
                renderDaftar()
            }
        }.start()
    }

    /** Menggambar ulang daftar bahasa di dialog, memakai status terakhir. */
    private fun renderDaftar() {
        val daftar = kelolaDaftar ?: return
        daftar.removeAllViews()
        for (kode in semuaKodeModel()) {
            daftar.addView(barisModel(kode, kode in terunduhTerakhir))
        }
    }

    /** Satu baris bahasa: nama, status, perkiraan ukuran, dan tombol. Saat sedang diunduh, ada bilah yang bergerak. */
    private fun barisModel(kode: String, sudah: Boolean): View {
        val nama = namaBahasa(kode)
        val sedang = kode == kodeSedangDiunduh
        val antri = kode in antrianUnduh
        val status = when {
            sedang -> I18n.tr(this, "Sedang diunduh...")
            antri -> I18n.tr(this, "Menunggu")
            sudah -> I18n.tr(this, "Sudah diunduh")
            else -> I18n.tr(this, "Belum diunduh")
        }
        val teks = TextView(this).apply {
            text = nama + "\n" + status
            textSize = 15f
            setTextColor(Color.parseColor("#111111"))
        }
        val tombol = MaterialButton(this).apply {
            text = I18n.tr(this@MainActivity, if (sudah) "Hapus" else "Unduh")
            isEnabled = !modelSibuk
            setOnClickListener {
                if (sudah) konfirmasiHapusSatu(kode, nama) else jalankanUnduh(listOf(kode))
            }
        }
        val atas = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(teks, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                tombol,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(atas)
            if (sedang) {
                // ML Kit tidak memberi persentase unduhan, jadi bilahnya bergerak terus sampai selesai
                val bilah = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#D32F2F"))
                }
                addView(
                    bilah,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(6) }
                )
            }
        }
    }

    /** Mengunduh satu atau beberapa model di background. Hanya satu tugas yang berjalan dalam satu waktu. */
    private fun jalankanUnduh(kodes: List<String>) {
        if (modelSibuk) {
            toast("Tunggu, masih ada model yang diproses")
            return
        }
        modelSibuk = true
        antrianUnduh.clear()
        antrianUnduh.addAll(kodes)
        renderDaftar()
        val namaList = kodes.map { namaBahasa(it) }
        Thread {
            var gagal = 0
            kodes.forEachIndexed { i, kode ->
                runOnUiThread {
                    antrianUnduh.remove(kode)
                    kodeSedangDiunduh = kode
                    kelolaInfo?.text = "Mengunduh ${namaList[i]} (${i + 1} dari ${kodes.size})"
                    renderDaftar()
                }
                val ok = unduhSatuModel(kode)
                if (!ok) gagal++
                runOnUiThread {
                    if (ok) terunduhTerakhir = terunduhTerakhir + kode
                    kodeSedangDiunduh = null
                }
            }
            runOnUiThread {
                modelSibuk = false
                antrianUnduh.clear()
                toast(
                    if (gagal == 0) "Unduhan selesai"
                    else "Ada beberapa model yang gagal diunduh. Pastikan internet aktif."
                )
                muatUlangDaftar()
            }
        }.start()
    }

    /** Menghapus satu atau beberapa model di background. Hanya model yang memang sudah diunduh yang dihapus. */
    private fun jalankanHapus(kodes: List<String>) {
        if (modelSibuk) {
            toast("Tunggu, masih ada model yang diproses")
            return
        }
        modelSibuk = true
        Thread {
            var gagal = 0
            try {
                val terunduh = kodeTerunduh()
                kodes.filter { it in terunduh }.forEach { if (!hapusSatuModel(it)) gagal++ }
            } catch (e: Exception) {
                gagal++
            }
            Translator.forgetReady()
            runOnUiThread {
                modelSibuk = false
                toast(if (gagal == 0) "Model dihapus" else "Ada beberapa model yang gagal dihapus")
                muatUlangDaftar()
            }
        }.start()
    }

    private fun konfirmasiHapusSatu(kode: String, nama: String) {
        AlertDialog.Builder(this)
            .setTitle(I18n.tr(this, "Hapus model ") + nama + "?")
            .setMessage(I18n.tr(this, "Model ini akan dihapus dari HP. Kalau dipakai lagi, perlu diunduh ulang dengan internet."))
            .setPositiveButton(I18n.tr(this, "Hapus")) { _, _ -> jalankanHapus(listOf(kode)) }
            .setNegativeButton(I18n.tr(this, "Batal"), null)
            .show()
    }

    private fun konfirmasiUnduhSemua() {
        AlertDialog.Builder(this)
            .setTitle(I18n.tr(this, "Unduh semua model?"))
            .setMessage(I18n.tr(this, "Semua bahasa akan diunduh. Ini bisa memakai banyak kuota internet dan ruang penyimpanan HP. Sebaiknya pakai Wi-Fi."))
            .setPositiveButton(I18n.tr(this, "Lanjutkan")) { _, _ -> jalankanUnduh(semuaKodeModel()) }
            .setNegativeButton(I18n.tr(this, "Batal"), null)
            .show()
    }

    private fun konfirmasiHapusSemua() {
        AlertDialog.Builder(this)
            .setTitle(I18n.tr(this, "Hapus semua model?"))
            .setMessage(I18n.tr(this, "Semua model yang sudah diunduh akan dihapus dari HP. Model akan diunduh lagi saat dipakai."))
            .setPositiveButton(I18n.tr(this, "Hapus")) { _, _ -> jalankanHapus(semuaKodeModel()) }
            .setNegativeButton(I18n.tr(this, "Batal"), null)
            .show()
    }

    private fun dp(nilai: Int): Int = (nilai * resources.displayMetrics.density).roundToInt()

    private fun namaBahasa(kode: String): String =
        Languages.TARGET.firstOrNull { it.code == kode }?.label?.let { I18n.tr(this, it) } ?: kode

    /** Nomor build aplikasi ini. Saat dibuild di GitHub, nomornya sama dengan run number. */
    @Suppress("DEPRECATION")
    private fun localBuild(): Long {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    /** Mengambil nomor build terbaru dari GitHub. Harus dipanggil dari background thread. */
    private fun fetchLatestBuild(): Long {
        val conn = URL(RELEASE_API).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "RTTS-Android")
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val tag = JSONObject(body).getString("tag_name")
            return Regex("build-(\\d+)").find(tag)?.groupValues?.get(1)?.toLongOrNull()
                ?: throw IllegalStateException("Format tag tidak dikenal: $tag")
        } finally {
            conn.disconnect()
        }
    }

    /** Cek manual lewat tombol. Selalu memberi tahu hasilnya. */
    private fun checkUpdate() {
        toast("Mengecek pembaruan...")
        Thread {
            try {
                val remote = fetchLatestBuild()
                val local = localBuild()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        if (remote > local) showUpdateDialog(remote, local)
                        else toast("Sudah versi terbaru (build $local)")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Gagal cek pembaruan. Pastikan internet aktif") }
            }
        }.start()
    }

    /** Cek otomatis saat aplikasi dibuka. Diam saja kalau gagal atau tidak ada yang baru. */
    private fun autoCheckUpdate() {
        Thread {
            try {
                val remote = fetchLatestBuild()
                val local = localBuild()
                val dismissed = prefs().getLong("dismissed_build", 0L)
                if (remote > local && remote > dismissed) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) showUpdateDialog(remote, local)
                    }
                }
            } catch (e: Exception) {
                // Gagal cek otomatis tidak perlu mengganggu pengguna
            }
        }.start()
    }

    /** Dialog pembaruan. Pengguna bisa membuka halaman update, atau menunda sampai ada build yang lebih baru. */
    private fun showUpdateDialog(remote: Long, local: Long) {
        AlertDialog.Builder(this)
            .setTitle(I18n.tr(this, "Ada pembaruan"))
            .setMessage(
                I18n.tr(this, "Build terbaru: ") + remote + "\n" +
                    I18n.tr(this, "Build kamu: ") + local + "\n\n" +
                    I18n.tr(this, "Buka halaman update untuk mengunduh APK terbaru?")
            )
            .setPositiveButton(I18n.tr(this, "Buka halaman update")) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE_PAGE)))
                } catch (e: ActivityNotFoundException) {
                    toast("Tidak ada browser untuk membuka link")
                }
            }
            .setNegativeButton(I18n.tr(this, "Nanti")) { _, _ -> catatTunda(remote) }
            .setOnCancelListener { catatTunda(remote) }
            .show()
    }

    /** Mencatat build yang ditunda, supaya tidak ditanyakan lagi sampai ada build yang lebih baru. */
    private fun catatTunda(build: Long) {
        prefs().edit().putLong("dismissed_build", build).apply()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, I18n.tr(this, msg), Toast.LENGTH_SHORT).show()
    }
}
