package com.bosmuda.translatelayar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Layanan latar yang:
 * - menampilkan bulatan mengambang di pinggir layar (tap = nyala/mati terjemahan)
 * - merekam layar lewat MediaProjection, lalu mengenali teks (ML Kit, di HP)
 * - menerjemahkan teks (ML Kit, di HP, tanpa internet setelah modelnya diunduh)
 * - menggambar hasil terjemahan di atas teks aslinya
 */
class TranslateService : Service() {

    companion object {
        const val ACTION_START = "com.bosmuda.translatelayar.START"
        const val ACTION_STOP = "com.bosmuda.translatelayar.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val ACTION_APPLY = "com.bosmuda.translatelayar.APPLY"

        /** Benar selama layanan sedang berjalan, dipakai MainActivity untuk tahu statusnya. */
        @Volatile
        var isRunning = false

        private const val CHANNEL_ID = "translate_layar"
        private const val NOTIF_ID = 1
        private const val CAPTURE_INTERVAL_MS = 1500L
        private const val HIDE_DELAY_MS = 150L
        // Batas waktu satu putaran. Lewat dari ini, putaran dianggap macet dan dilepas
        private const val STUCK_MS = 25_000L
        private const val COLOR_OFF = "#212121"
        private const val COLOR_ON = "#D32F2F"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var handler: Handler
    private val worker = Executors.newSingleThreadExecutor()

    private var density = 1f
    private var densityDpi = 160
    private var screenW = 0
    private var screenH = 0
    private var bubbleSizePx = 0
    private var textPct = 100
    private var menuView: LinearLayout? = null
    private var picker: View? = null
    private var oneShot = false
    private enum class OneShot { COPY, SPEAK }
    private var oneShotMode = OneShot.COPY
    private val clearOneShot = Runnable { overlay?.setItems(emptyList()) }

    private var projection: MediaProjection? = null
    // Frame terakhir, dipakai kalau layar diam dan tidak ada frame baru
    private var lastFrame: Bitmap? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var recognizer: TextRecognizer? = null

    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var overlay: OverlayView? = null

    private var translateOn = false
    private var busy = false
    private var busySince = 0L
    private var emptyFrames = 0
    private var noFrameHinted = false

    // Sidik jari layar. ocrSig = layar yang sedang diterjemahkan,
    // shownSig = layar yang hasil terjemahannya sedang tampil.
    private var ocrSig: IntArray? = null
    private var shownSig: IntArray? = null

    private val loopRunnable = Runnable { runCycle() }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        handler = Handler(Looper.getMainLooper())
        Speaker.init(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_STOP -> stopEverything()
            ACTION_APPLY -> applySizes()
            ACTION_START -> {
                startForegroundCompat()
                val data = extraData(intent)
                if (data == null) {
                    stopEverything()
                } else {
                    startCapture(intent.getIntExtra(EXTRA_RESULT_CODE, 0), data)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        worker.shutdown()
        super.onDestroy()
    }

    // ---------- Layanan latar & notifikasi ----------

    @Suppress("DEPRECATION")
    private fun extraData(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_DATA)
        }
    }

    private fun startForegroundCompat() {
        createChannel()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "RTTS", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopPi = PendingIntent.getService(
            this,
            0,
            Intent(this, TranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(I18n.tr(this, "RTTS aktif"))
            .setContentText(I18n.tr(this, "Ketuk bulatan untuk menyalakan atau mematikan terjemahan"))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, I18n.tr(this, "Hentikan"), stopPi)
            .setOngoing(true)
            .build()
    }

    // ---------- Rekam layar ----------

    @Suppress("DEPRECATION")
    private fun loadMetrics() {
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        density = dm.density
        densityDpi = dm.densityDpi
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        readSizes()
    }

    /** Baca ukuran dari pengaturan: ukuran bulatan (dp) dan ukuran tulisan (%). */
    private fun readSizes() {
        val p = prefs()
        bubbleSizePx = (p.getInt("bubble_dp", 56) * density).roundToInt()
        textPct = p.getInt("text_pct", 100)
    }

    /** Terapkan ukuran baru ke bulatan dan overlay yang sedang tampil. */
    private fun applySizes() {
        readSizes()
        overlay?.textScale = textPct / 100f
        val tv = bubble ?: return
        val lp = bubbleLp ?: return
        lp.width = bubbleSizePx
        lp.height = bubbleSizePx
        if (lp.x > screenW / 2) lp.x = screenW - bubbleSizePx
        if (lp.y > screenH - bubbleSizePx) lp.y = screenH - bubbleSizePx
        // Bulatan dipasang ulang dengan ukuran baru, supaya perubahannya pasti terlihat di layar
        runCatching { windowManager.removeView(tv) }
        windowManager.addView(tv, lp)
        toast("Pengaturan diterapkan")
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (projection != null) return
        loadMetrics()

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data)
        if (proj == null) {
            toast("Izin rekam layar gagal")
            stopEverything()
            return
        }
        projection = proj

        // Wajib didaftarkan sebelum VirtualDisplay dibuat (Android 14+)
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stopEverything() }
            }
        }, handler)

        val reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = proj.createVirtualDisplay(
            "TranslateLayar",
            screenW,
            screenH,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )

        val (src, _) = currentLangs()
        recognizer = buildRecognizer(Languages.byCode(src, Languages.SOURCE).script)
        warmUpRecognizer()
        prepareModels()

        addViews()
        isRunning = true
        toast("Bulatan siap. Ketuk untuk terjemahan, tekan lama untuk menu")
    }

    private fun buildRecognizer(script: Script): TextRecognizer = when (script) {
        Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    /**
     * Menyiapkan model terjemahan di latar belakang saat layanan mulai,
     * supaya tidak perlu menunggu saat teks pertama kali diterjemahkan.
     * Halaman sering campur bahasa, jadi selain bahasa pilihan, Inggris juga disiapkan.
     * Kalau bahasa asal Otomatis, Jepang dan Inggris yang disiapkan karena paling sering muncul.
     */
    private fun prepareModels() {
        val (src, tgt) = currentLangs()
        val froms = if (src == "auto") listOf("ja", "en") else listOf(src, "en")
        val pairs = froms.distinct().filter { it != tgt }
        if (pairs.isEmpty()) return
        Thread {
            try {
                for (from in pairs) Translator.prepare(from, tgt)
                handler.post { if (isRunning) toast("Model terjemahan siap") }
            } catch (e: Exception) {
                handler.post { if (isRunning) toast("Model terjemahan belum siap. Pastikan internet aktif") }
            }
        }.start()
    }

    // ---------- Bulatan & overlay ----------

    private fun addViews() {
        // Overlay terjemahan: di bawah bulatan, tidak menerima sentuhan
        val ov = OverlayView(this)
        windowManager.addView(
            ov,
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
        )
        ov.textScale = textPct / 100f
        overlay = ov

        // Bulatan: bisa digeser, tap untuk nyala/mati
        val size = bubbleSizePx
        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - size
            y = (screenH * 0.4f).roundToInt()
        }

        val tv = TextView(this).apply {
            text = "文"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(COLOR_OFF))
            }
            elevation = 8f * density
        }

        val slop = 8f * density
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var downTime = 0L

        tv.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    downTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        lp.x = startX + dx.roundToInt()
                        lp.y = startY + dy.roundToInt()
                        windowManager.updateViewLayout(tv, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        snapToEdge(tv, lp)
                    } else if (System.currentTimeMillis() - downTime > 450L) {
                        toggleMenu()
                    } else {
                        toggleTranslate()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(tv, lp)
        bubble = tv
        bubbleLp = lp
        updateBubbleColor()
    }

    private fun snapToEdge(view: View, lp: WindowManager.LayoutParams) {
        lp.x = if (lp.x + bubbleSizePx / 2 < screenW / 2) 0 else screenW - bubbleSizePx
        windowManager.updateViewLayout(view, lp)
    }

    private fun updateBubbleColor() {
        val tv = bubble ?: return
        (tv.background as? GradientDrawable)?.setColor(Color.parseColor(if (translateOn) COLOR_ON else COLOR_OFF))
        tv.invalidate()
    }

    /** Memberi tahu kalau model terjemahan masih diunduh, supaya jelas kenapa belum langsung jalan. */
    private fun warnIfModelDownloading() {
        val (src, tgt) = currentLangs()
        val from = if (src == "auto") "en" else src
        if (from != tgt && Translator.isDownloading(from, tgt)) {
            toast("Model terjemahan masih diunduh, tunggu sebentar")
        }
    }

    private fun toggleTranslate() {
        translateOn = !translateOn
        handler.removeCallbacks(clearOneShot)
        updateBubbleColor()
        ocrSig = null
        shownSig = null
        if (translateOn) {
            toast("Terjemahan aktif")
            warnIfModelDownloading()
            scheduleNext(0L)
        } else {
            handler.removeCallbacks(loopRunnable)
            overlay?.setItems(emptyList())
            toast("Terjemahan mati")
        }
    }

    // ---------- Siklus: ambil layar -> OCR -> terjemahkan -> gambar ----------

    private fun scheduleNext(delayMs: Long) {
        handler.removeCallbacks(loopRunnable)
        handler.postDelayed(loopRunnable, delayMs)
    }

    /** Menandai satu putaran kerja mulai, supaya bisa dideteksi kalau macet. */
    private fun beginWork() {
        busy = true
        busySince = SystemClock.uptimeMillis()
    }

    /** Sibuk hanya kalau putaran sebelumnya belum selesai dalam batas waktu. Lewat dari itu, dianggap macet dan dilepas. */
    private fun isBusy(): Boolean {
        if (!busy) return false
        if (SystemClock.uptimeMillis() - busySince < STUCK_MS) return true
        busy = false
        oneShot = false
        return false
    }

    private fun runCycle() {
        if (!translateOn) return
        if (isBusy()) {
            // Putaran sebelumnya belum selesai, cek lagi sebentar lagi
            scheduleNext(CAPTURE_INTERVAL_MS)
            return
        }
        beginWork()
        // Sembunyikan hasil terjemahan sebentar agar tidak ikut terekam
        setOverlayHidden(true)
        handler.postDelayed({ captureAndRecognize() }, HIDE_DELAY_MS)
    }

    /** Memuat model OCR lebih awal dengan membaca gambar kosong, supaya pembacaan pertama tidak lambat. */
    private fun warmUpRecognizer() {
        val rec = recognizer ?: return
        val blank = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        rec.process(InputImage.fromBitmap(blank, 0))
            .addOnCompleteListener { blank.recycle() }
    }

    /** Mencatat putaran tanpa gambar sama sekali. Kalau terlalu lama, beri tahu pengguna cara memancing gambar. */
    private fun noFrame() {
        emptyFrames++
        if (lastFrame == null && emptyFrames >= 6 && !noFrameHinted) {
            noFrameHinted = true
            toast("Belum ada gambar dari layar. Coba gulir layar sedikit, RTTS akan membacanya")
        }
    }

    private fun captureAndRecognize() {
        // Kalau layar diam, tidak ada gambar baru. Pakai gambar terakhir supaya tetap bisa dibaca
        val bmp = acquireBitmap() ?: lastFrame
        setOverlayHidden(false)

        val rec = recognizer
        if (bmp == null || rec == null) {
            noFrame()
            finishCycle()
            return
        }
        emptyFrames = 0

        val sig = signature(bmp)
        if (sameScreen(sig, shownSig)) {
            // Layar sama dengan yang sudah diterjemahkan, kotak sudah sesuai
            finishCycle()
            return
        }
        if (!sameScreen(sig, ocrSig)) {
            // Layar berubah: kotak lama sudah tidak sesuai, hapus dulu
            overlay?.setItems(emptyList())
            ocrSig = sig
        }

        rec.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { result -> onOcrDone(result, sig, bmp) }
            .addOnFailureListener {
                // Jangan tandai layar ini sudah dibaca, supaya dicoba lagi di putaran berikutnya
                finishCycle()
            }
    }

    /** Tinggi bilah status di layar (jam, sinyal, baterai). */
    private fun statusBarPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(28)
    }

    private fun onOcrDone(result: Text, sig: IntArray, shot: Bitmap?) {
        if (!translateOn && !oneShot) {
            finishCycle()
            return
        }
        translateLines(visibleLines(result), sig, shot)
    }

    /** Terjemahkan baris yang dipilih, lalu tampilkan hasilnya di layar. */
    private fun translateLines(lines: List<Pair<RectF, String>>, sig: IntArray, shot: Bitmap?) {
        if (lines.isEmpty()) {
            shownSig = sig
            finishCycle()
            return
        }


        val (src, tgt) = currentLangs()
        val chosen = if (src == "auto") null else src
        val texts = lines.map { it.second }
        // Warna kotak dan tulisan diambil sekarang, selagi gambar layar ini masih ada
        val colors = lines.map { sampleColors(shot, it.first) }

        worker.execute {
            val translated: List<String> = try {
                Translator.translateMixed(texts, chosen, tgt)
            } catch (e: Exception) {
                handler.post {
                    toast("Gagal menerjemahkan: ${e.message?.take(80)}")
                    shownSig = sig
                    finishCycle()
                }
                return@execute
            }

            handler.post {
                if (translateOn || oneShot) {
                    val items = lines.mapIndexedNotNull { i, pair ->
                        val out = translated.getOrElse(i) { "" }.trim()
                        // Yang sudah dalam bahasa tujuan, atau gagal diterjemahkan, dibiarkan apa adanya
                        if (out.isEmpty() || out == pair.second) null
                        else OverlayView.Item(pair.first, out, colors[i].first, colors[i].second)
                    }
                    overlay?.setItems(items)
                    shownSig = sig
                    if (!translateOn) {
                        // Hasil terjemahan sekali jalan hilang sendiri setelah 10 detik
                        handler.removeCallbacks(clearOneShot)
                        handler.postDelayed(clearOneShot, 10_000L)
                    }
                }
                finishCycle()
            }
        }
    }

    private fun finishCycle() {
        busy = false
        oneShot = false
        if (translateOn) scheduleNext(CAPTURE_INTERVAL_MS)
    }

    private fun setOverlayHidden(hidden: Boolean) {
        overlay?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    /** Terjemahan atau salin teks sekali dari layar, lalu berhenti. */
    private fun runOnce() {
        warnIfModelDownloading()
        if (isBusy()) {
            toast("Tunggu sebentar, lalu coba lagi")
            return
        }
        if (recognizer == null || imageReader == null) return
        beginWork()
        oneShot = true
        handler.removeCallbacks(clearOneShot)
        overlay?.setItems(emptyList())
        setOverlayHidden(true)
        handler.postDelayed({ captureOnce(0) }, HIDE_DELAY_MS)
    }

    private fun captureOnce(tries: Int) {
        val bmp = acquireBitmap() ?: lastFrame
        if (bmp == null) {
            // Layar belum menghasilkan frame baru, coba lagi sebentar
            if (tries < 5) {
                handler.postDelayed({ captureOnce(tries + 1) }, 200L)
            } else {
                setOverlayHidden(false)
                toast("Layar belum terbaca. Coba gulir layar sedikit, lalu ulangi")
                finishCycle()
            }
            return
        }
        setOverlayHidden(false)
        val rec = recognizer
        if (rec == null) {
            finishCycle()
            return
        }
        rec.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { result ->
                when (oneShotMode) {
                    OneShot.COPY -> {
                        copyText(result)
                        finishCycle()
                    }
                    OneShot.SPEAK -> pickSpeak(result)
                }
            }
            .addOnFailureListener {
                toast("Teks belum terbaca, coba lagi")
                finishCycle()
            }
    }

    /** Baris teks yang terlihat di layar: tanpa bilah status dan tanpa yang tertutup bulatan. */
    private fun visibleLines(result: Text): List<Pair<RectF, String>> {
        val bubbleRect = bubble?.let { view ->
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            RectF(
                loc[0].toFloat(),
                loc[1].toFloat(),
                (loc[0] + bubbleSizePx).toFloat(),
                (loc[1] + bubbleSizePx).toFloat()
            )
        } ?: RectF()
        val statusBar = statusBarPx()
        val lines = ArrayList<Pair<RectF, String>>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                if (box.bottom <= statusBar) continue
                val rect = RectF(box)
                if (RectF.intersects(rect, bubbleRect)) continue
                val text = line.text.trim()
                if (text.length < 2) continue
                lines.add(rect to text)
            }
        }
        return lines
    }

    /** Ambil baris teks yang terlihat, lalu tampilkan daftar pilihan untuk disalin. */
    private fun copyText(result: Text) {
        val lines = visibleLines(result).sortedWith(compareBy({ it.first.top }, { it.first.left }))
        if (lines.isEmpty()) {
            toast("Tidak ada teks di layar")
            return
        }
        val texts = lines.map { it.second }
        showPicker(texts, "Pilih baris yang mau disalin", "Salin semua", "Salin pilihan") { chosen ->
            sendToClipboard(chosen.joinToString("\n") { texts[it] })
        }
    }

    /** Bacakan sekali: pilih baris yang mau dibacakan, atau semuanya. */
    private fun pickSpeak(result: Text) {
        val lines = visibleLines(result).sortedWith(compareBy({ it.first.top }, { it.first.left }))
        if (lines.isEmpty()) {
            toast("Tidak ada teks di layar")
            finishCycle()
            return
        }
        showPicker(
            lines.map { it.second },
            "Pilih teks yang mau dibacakan",
            "Bacakan semua",
            "Bacakan pilihan",
            onCancel = { finishCycle() }
        ) { chosen -> readAloud(chosen.map { lines[it] }) }
    }

    /** Daftar baris dengan kotak centang: tombol "semua", dan tombol "pilihan" (hanya yang dicentang). */
    private fun showPicker(
        items: List<String>,
        title: String,
        allLabel: String,
        pickLabel: String,
        onCancel: () -> Unit = {},
        onDone: (List<Int>) -> Unit
    ) {
        closePicker()
        val checks = ArrayList<CheckBox>()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (item in items) {
            val cb = CheckBox(this).apply {
                text = item
                textSize = 15f
                setTextColor(Color.parseColor("#111111"))
            }
            checks.add(cb)
            list.addView(cb)
        }
        val scroll = ScrollView(this).apply { addView(list) }

        val titleView = TextView(this).apply {
            text = I18n.tr(this@TranslateService, title)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#D32F2F"))
        }
        val allBtn = pillButton(allLabel, Color.parseColor("#FFCDD2"), Color.parseColor("#B71C1C")) {
            closePicker()
            onDone(items.indices.toList())
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(pillButton("Batal", Color.parseColor("#E0E0E0"), Color.parseColor("#111111")) {
                closePicker()
                onCancel()
            })
            addView(pillButton(pickLabel, Color.parseColor("#D32F2F"), Color.WHITE) {
                val chosen = checks.indices.filter { checks[it].isChecked }
                if (chosen.isEmpty()) {
                    toast("Belum ada baris yang dipilih")
                    return@pillButton
                }
                closePicker()
                onDone(chosen)
            })
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(titleView)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(allBtn)
            addView(buttons, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) })
        }

        // Tanpa FLAG_NOT_FOCUSABLE supaya kotak centang bisa disentuh,
        // dan FLAG_NOT_TOUCH_MODAL supaya sentuhan di luar kotak tetap diteruskan ke aplikasi di bawahnya
        val pickerLp = WindowManager.LayoutParams(
            minOf(screenW - dp(32), dp(360)),
            (screenH * 0.6f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        windowManager.addView(panel, pickerLp)
        picker = panel
    }

    private fun closePicker() {
        picker?.let { runCatching { windowManager.removeView(it) } }
        picker = null
    }

    private fun pillButton(label: String, bg: Int, fg: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = I18n.tr(this@TranslateService, label)
            textSize = 15f
            setTextColor(fg)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(bg)
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }

    /** Terjemahkan baris yang dipilih di latar belakang, lalu salin hasil terjemahannya. */
    private fun copyTranslated(lines: List<String>) {
        val (src, tgt) = currentLangs()
        val sourceParam = if (src == "auto") null else src
        toast("Menerjemahkan...")
        worker.execute {
            val out: List<String> = try {
                Translator.translate(lines, sourceParam, tgt)
            } catch (e: Exception) {
                handler.post { toast("Gagal menerjemahkan: ${e.message?.take(80)}") }
                return@execute
            }
            handler.post { sendToClipboard(out.joinToString("\n")) }
        }
    }

    /** Salin teks ke clipboard lewat activity kecil yang langsung tampil lalu tertutup. */
    private fun sendToClipboard(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            toast("Tidak ada teks yang dipilih")
            return
        }
        // Clipboard hanya bisa diisi oleh aplikasi yang sedang di depan
        startActivity(
            Intent(this, CopyTextActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(CopyTextActivity.EXTRA_TEXT, clean)
        )
    }

    /** Terjemahkan teks yang terlihat di layar, lalu bacakan hasilnya dengan suara. */
    private fun readAloud(lines: List<Pair<RectF, String>>) {
        if (lines.isEmpty()) {
            toast("Tidak ada teks di layar")
            finishCycle()
            return
        }
        val (src, tgt) = currentLangs()
        val chosen = if (src == "auto") null else src
        val texts = lines.map { it.second }
        worker.execute {
            val translated: List<String> = try {
                Translator.translateMixed(texts, chosen, tgt)
            } catch (e: Exception) {
                handler.post {
                    toast("Gagal menerjemahkan: ${e.message?.take(80)}")
                    finishCycle()
                }
                return@execute
            }
            handler.post {
                speakAloud(translated.joinToString("\n"), tgt)
                finishCycle()
            }
        }
    }

    /** Bacakan teks dengan suara bawaan HP, memakai bahasa [code]. */
    private fun speakAloud(text: String, code: String) {
        val problem = Speaker.speak(text, code)
        if (problem != null) toast(problem)
    }

    private fun ttsLocale(code: String): Locale =
        if (code == "auto") Locale.getDefault() else Locale.forLanguageTag(code)

    private fun dp(v: Int): Int = (v * density).roundToInt()

    /** Menu kecil di dekat bulatan, dibuka dengan tekan lama. */
    private fun toggleMenu() {
        if (menuView != null) {
            closeMenu()
            return
        }
        val lp = bubbleLp ?: return
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(0, dp(6), 0, dp(6))
            elevation = dp(6).toFloat()
        }
        addMenuItem(menu, "📋  Salin teks") {
            closeMenu()
            oneShotMode = OneShot.COPY
            runOnce()
        }
        addMenuItem(menu, "🔊  Bacakan terjemahan") {
            closeMenu()
            oneShotMode = OneShot.SPEAK
            runOnce()
        }
        addMenuItem(menu, "✖  Tutup") { toast("RTTS dihentikan"); stopEverything() }

        val menuLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (if (lp.x > screenW / 2) lp.x - dp(170) else lp.x).coerceAtLeast(0)
            // Di bawah bulatan; kalau bulatan ada di separuh bawah layar, menu dibuka ke atas
            y = if (lp.y > screenH / 2) (lp.y - dp(220)).coerceAtLeast(0) else lp.y + bubbleSizePx + dp(8)
        }
        windowManager.addView(menu, menuLp)
        menuView = menu
    }

    private fun addMenuItem(parent: LinearLayout, label: String, onClick: () -> Unit) {
        val tv = TextView(this).apply {
            text = I18n.tr(this@TranslateService, label)
            textSize = 16f
            setTextColor(Color.parseColor("#111111"))
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnClickListener { onClick() }
        }
        parent.addView(tv)
    }

    private fun closeMenu() {
        menuView?.let { runCatching { windowManager.removeView(it) } }
        menuView = null
    }

    /**
     * Warna latar di sekitar satu baris teks, diambil dari gambar layar.
     * Hasilnya pasangan (warna kotak, warna tulisan): tulisan hitam di latar terang, putih di latar gelap.
     * Kalau gambar tidak ada atau gagal dibaca, dipakai putih dan hitam.
     */
    private fun sampleColors(shot: Bitmap?, rect: RectF): Pair<Int, Int> {
        val fallback = Pair(Color.WHITE, Color.BLACK)
        if (shot == null || shot.isRecycled) return fallback
        return try {
            val left = rect.left.toInt()
            val right = rect.right.toInt()
            val top = rect.top.toInt()
            val bottom = rect.bottom.toInt()
            val merah = ArrayList<Int>()
            val hijau = ArrayList<Int>()
            val biru = ArrayList<Int>()
            // Titik contoh di atas, bawah, kiri, dan kanan baris, sedikit di luar teksnya
            fun add(x: Int, y: Int) {
                if (x < 0 || y < 0 || x >= shot.width || y >= shot.height) return
                val c = shot.getPixel(x, y)
                merah.add(Color.red(c))
                hijau.add(Color.green(c))
                biru.add(Color.blue(c))
            }
            val stepX = maxOf(1, (right - left) / 6)
            val stepY = maxOf(1, (bottom - top) / 4)
            for (x in left..right step stepX) {
                add(x, top - 3)
                add(x, bottom + 3)
            }
            for (y in top..bottom step stepY) {
                add(left - 3, y)
                add(right + 3, y)
            }
            if (merah.isEmpty()) return fallback
            // Median, bukan rata-rata: piksel tulisan atau ikon di tepi baris tidak ikut menggelapkan warna kotak
            merah.sort()
            hijau.sort()
            biru.sort()
            val tengah = merah.size / 2
            val r = merah[tengah]
            val g = hijau[tengah]
            val b = biru[tengah]
            val terang = 0.299 * r + 0.587 * g + 0.114 * b > 140
            Pair(Color.rgb(r, g, b), if (terang) Color.BLACK else Color.WHITE)
        } catch (e: Exception) {
            fallback
        }
    }

    /** Sidik jari layar: sampel 24x48 piksel, untuk tahu apakah layar berubah. */
    private fun signature(bmp: Bitmap): IntArray {
        val cols = 24
        val rows = 48
        val stepX = bmp.width / cols
        val stepY = bmp.height / rows
        val out = IntArray(cols * rows)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                out[r * cols + c] = bmp.getPixel(c * stepX + stepX / 2, r * stepY + stepY / 2)
            }
        }
        return out
    }

    /** Dianggap layar yang sama kalau bedanya kecil (jam, kursor, dan sejenisnya). */
    private fun sameScreen(a: IntArray?, b: IntArray?): Boolean {
        if (a == null || b == null || a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            if (a[i] != b[i]) diff++
        }
        return diff <= a.size * 0.03
    }

    /** Ambil satu frame terbaru dari layar. Mengembalikan null kalau tidak ada frame baru. */
    private fun acquireBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        val bmp = try {
            imageToBitmap(image)
        } finally {
            image.close()
        }
        lastFrame = bmp
        return bmp
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * image.width
        val full = Bitmap.createBitmap(
            image.width + rowPadding / plane.pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        full.copyPixelsFromBuffer(plane.buffer)
        return Bitmap.createBitmap(full, 0, 0, image.width, image.height)
    }

    // ---------- Bahasa & preferensi ----------

    private fun prefs() = getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun currentLangs(): Pair<String, String> {
        val p = prefs()
        return Pair(p.getString("source", "auto") ?: "auto", p.getString("target", "id") ?: "id")
    }

    // ---------- Berhenti ----------

    private fun teardown() {
        isRunning = false
        closeMenu()
        closePicker()
        oneShot = false
        translateOn = false
        busy = false
        handler.removeCallbacksAndMessages(null)

        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        recognizer?.close()
        recognizer = null
        projection?.stop()
        projection = null

        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        bubbleLp = null
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
        Speaker.stop()
    }

    private fun stopEverything() {
        teardown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun toast(msg: String) {
        Toast.makeText(applicationContext, I18n.tr(applicationContext, msg), Toast.LENGTH_SHORT).show()
    }
}
