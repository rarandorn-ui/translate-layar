package com.bosmuda.translatelayar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var spSource: Spinner
    private lateinit var spTarget: Spinner
    private lateinit var etKey: EditText
    private lateinit var tvStatus: TextView

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
        etKey = findViewById(R.id.etKey)
        tvStatus = findViewById(R.id.tvStatus)

        spSource.adapter = langAdapter(Languages.SOURCE)
        spTarget.adapter = langAdapter(Languages.TARGET)
        loadSettings()

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
        val overlayOk = Settings.canDrawOverlays(this)
        val keyOk = prefs().getString("api_key", "").orEmpty().isNotBlank()
        tvStatus.text = buildString {
            appendLine("Izin tampil di atas aplikasi lain: ${if (overlayOk) "sudah" else "belum"}")
            appendLine("API key: ${if (keyOk) "sudah diisi" else "belum diisi"}")
        }
    }

    private fun startFlow() {
        saveSettings()
        if (etKey.text.toString().isBlank()) {
            toast("Isi API key dulu")
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
        etKey.setText(p.getString("api_key", ""))
        val src = p.getString("source", "auto") ?: "auto"
        val tgt = p.getString("target", "id") ?: "id"
        spSource.setSelection(Languages.SOURCE.indexOfFirst { it.code == src }.coerceAtLeast(0))
        spTarget.setSelection(Languages.TARGET.indexOfFirst { it.code == tgt }.coerceAtLeast(0))
    }

    private fun saveSettings() {
        prefs().edit()
            .putString("api_key", etKey.text.toString().trim())
            .putString("source", Languages.SOURCE[spSource.selectedItemPosition].code)
            .putString("target", Languages.TARGET[spTarget.selectedItemPosition].code)
            .apply()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
