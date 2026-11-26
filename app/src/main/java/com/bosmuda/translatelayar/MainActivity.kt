package com.bosmuda.translatelayar

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
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

    // Status model terjemahan, ditampilkan di Beranda
    private var modelStatus = "Model terjemahan: belum disiapkan"

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

        spSource = findViewById(R.id.spSource)
        spTarget = findViewById(R.id.spTarget)
        sbBubble = findViewById(R.id.sbBubble)
        tvBubbleVal = findViewById(R.id.tvBubbleVal)
        sbText = findViewById(R.id.sbText)
        tvTextVal = findViewById(R.id.tvTextVal)
        tvStatus = findViewById(R.id.tvStatus)
        tvBuild = findViewById(R.id.tvBuild)
        tvBuild.text = "Build terpasang: ${localBuild()}"

        spSource.adapter = langAdapter(Languages.SOURCE)
        spTarget.adapter = langAdapter(Languages.TARGET)

        sbBubble.max = BUBBLE_MAX - BUBBLE_MIN
        sbText.max = TEXT_MAX - TEXT_MIN
        val changed = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        sbBubble.setOnSeekBarChangeListener(changed)
        sbText.setOnSeekBarChangeListener(changed)
        loadSettings()

        // Navigasi bawah: Beranda dan Pengaturan
        val pageHome = findViewById<View>(R.id.pageHome)
        val pageSettings = findViewById<View>(R.id.pageSettings)
        findViewById<BottomNavigationView>(R.id.bottomNav).setOnItemSelectedListener { item ->
            val isHome = item.itemId == R.id.navHome
            pageHome.visibility = if (isHome) View.VISIBLE else View.GONE
            pageSettings.visibility = if (isHome) View.GONE else View.VISIBLE
            true
        }

        val openSupport = View.OnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://saweria.co/RizkyAhmad9")))
            } catch (e: Exception) {
                Toast.makeText(this, "Tidak ada browser untuk membuka link", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnSupport).setOnClickListener(openSupport)
        findViewById<Button>(R.id.btnSupportHome).setOnClickListener(openSupport)
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveSettings()
            toast("Pengaturan disimpan")
        }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            requestOverlayPermission()
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            startFlow()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, TranslateService::class.java))
            toast("Layanan dihentikan")
        }
        findViewById<Button>(R.id.btnUpdate).setOnClickListener {
            checkUpdate()
        }

        // Siapkan model terjemahan begitu aplikasi dibuka, supaya bulatan siap dipakai
        prepareModels()

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
    }

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        tvStatus.text = buildString {
            appendLine("Izin tampil di atas aplikasi lain: ${if (overlayOk) "sudah" else "belum"}")
            appendLine(modelStatus)
            appendLine("Izin rekam layar diminta setiap kali layanan dimulai. Ini aturan Android.")
            appendLine("Kalau layanan sedang jalan, ukuran langsung berubah saat Simpan pengaturan.")
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
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, list.map { it.label })
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
        updateLabels()
    }

    private fun updateLabels() {
        tvBubbleVal.text = "${sbBubble.progress + BUBBLE_MIN} dp"
        tvTextVal.text = "${sbText.progress + TEXT_MIN}%"
    }

    private fun saveSettings() {
        prefs().edit()
            .putString("source", Languages.SOURCE[spSource.selectedItemPosition].code)
            .putString("target", Languages.TARGET[spTarget.selectedItemPosition].code)
            .putInt("bubble_dp", sbBubble.progress + BUBBLE_MIN)
            .putInt("text_pct", sbText.progress + TEXT_MIN)
            .apply()
        // Bahasa bisa berganti, jadi model untuk pasangan baru perlu disiapkan
        prepareModels()
        // Kalau layanan sedang jalan, terapkan ukuran baru sekarang juga
        if (TranslateService.isRunning) {
            startService(
                Intent(this, TranslateService::class.java).setAction(TranslateService.ACTION_APPLY)
            )
        }
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
            .setTitle("Ada pembaruan")
            .setMessage("Build terbaru: $remote\nBuild kamu: $local\n\nBuka halaman update untuk mengunduh APK terbaru?")
            .setPositiveButton("Buka halaman update") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE_PAGE)))
                } catch (e: ActivityNotFoundException) {
                    toast("Tidak ada browser untuk membuka link")
                }
            }
            .setNegativeButton("Nanti") { _, _ -> catatTunda(remote) }
            .setOnCancelListener { catatTunda(remote) }
            .show()
    }

    /** Mencatat build yang ditunda, supaya tidak ditanyakan lagi sampai ada build yang lebih baru. */
    private fun catatTunda(build: Long) {
        prefs().edit().putLong("dismissed_build", build).apply()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
