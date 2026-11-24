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
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Layanan latar yang:
 * - menampilkan bulatan mengambang di pinggir layar (tap = nyala/mati terjemahan)
 * - merekam layar lewat MediaProjection, lalu mengenali teks (ML Kit, di HP)
 * - menerjemahkan teks (Google Cloud Translation, lewat internet)
 * - menggambar hasil terjemahan di atas teks aslinya
 */
class TranslateService : Service() {

    companion object {
        const val ACTION_START = "com.bosmuda.translatelayar.START"
        const val ACTION_STOP = "com.bosmuda.translatelayar.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        private const val CHANNEL_ID = "translate_layar"
        private const val NOTIF_ID = 1
        private const val CAPTURE_INTERVAL_MS = 1500L
        private const val HIDE_DELAY_MS = 150L
        private const val COLOR_OFF = "#616161"
        private const val COLOR_ON = "#2E7D32"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var handler: Handler
    private val worker = Executors.newSingleThreadExecutor()

    private var density = 1f
    private var densityDpi = 160
    private var screenW = 0
    private var screenH = 0
    private var bubbleSizePx = 0

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var recognizer: TextRecognizer? = null

    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var overlay: OverlayView? = null

    private var translateOn = false
    private var busy = false

    private val loopRunnable = Runnable { runCycle() }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        handler = Handler(Looper.getMainLooper())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_STOP -> stopEverything()
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
            val channel = NotificationChannel(CHANNEL_ID, "Translate Layar", NotificationManager.IMPORTANCE_LOW)
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
            .setContentTitle("Translate Layar aktif")
            .setContentText("Ketuk bulatan untuk menyalakan atau mematikan terjemahan")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hentikan", stopPi)
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
        bubbleSizePx = (56 * density).roundToInt()
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

        addViews()
        toast("Bulatan siap. Ketuk bulatan untuk mulai")
    }

    private fun buildRecognizer(script: Script): TextRecognizer = when (script) {
        Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
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

        tv.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
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
                    if (moved) snapToEdge(tv, lp) else toggleTranslate()
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

    private fun toggleTranslate() {
        translateOn = !translateOn
        updateBubbleColor()
        if (translateOn) {
            toast("Terjemahan aktif")
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

    private fun runCycle() {
        if (!translateOn || busy) return
        busy = true
        // Sembunyikan hasil terjemahan sebentar agar tidak ikut terekam
        setOverlayHidden(true)
        handler.postDelayed({ captureAndRecognize() }, HIDE_DELAY_MS)
    }

    private fun captureAndRecognize() {
        val bmp = acquireBitmap()
        setOverlayHidden(false)

        val rec = recognizer
        if (bmp == null || rec == null) {
            // Layar belum berubah (tidak ada frame baru), jadi tidak perlu OCR ulang
            finishCycle()
            return
        }

        rec.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { result -> onOcrDone(result) }
            .addOnFailureListener { finishCycle() }
    }

    private fun onOcrDone(result: Text) {
        if (!translateOn) {
            finishCycle()
            return
        }

        // Abaikan teks yang tertutup bulatan
        val lp = bubbleLp
        val bubbleRect = if (lp != null) {
            RectF(
                lp.x.toFloat(),
                lp.y.toFloat(),
                (lp.x + bubbleSizePx).toFloat(),
                (lp.y + bubbleSizePx).toFloat()
            )
        } else {
            RectF()
        }

        val lines = ArrayList<Pair<RectF, String>>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val rect = RectF(box)
                if (RectF.intersects(rect, bubbleRect)) continue
                val text = line.text.trim()
                if (text.length < 2) continue
                lines.add(rect to text)
            }
        }

        if (lines.isEmpty()) {
            finishCycle()
            return
        }

        val apiKey = prefs().getString("api_key", "").orEmpty()

        val (src, tgt) = currentLangs()
        val sourceParam = if (src == "auto") null else src
        val texts = lines.map { it.second }

        worker.execute {
            val translated: List<String> = try {
                Translator.translate(apiKey, texts, sourceParam, tgt)
            } catch (e: Exception) {
                handler.post {
                    toast("Gagal menerjemahkan: ${e.message?.take(80)}")
                    finishCycle()
                }
                return@execute
            }

            handler.post {
                if (translateOn) {
                    val items = lines.mapIndexed { i, pair ->
                        OverlayView.Item(pair.first, translated.getOrElse(i) { "" })
                    }
                    overlay?.setItems(items)
                }
                finishCycle()
            }
        }
    }

    private fun finishCycle() {
        busy = false
        if (translateOn) scheduleNext(CAPTURE_INTERVAL_MS)
    }

    private fun setOverlayHidden(hidden: Boolean) {
        overlay?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    /** Ambil satu frame terbaru dari layar. Mengembalikan null kalau tidak ada frame baru. */
    private fun acquireBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        return try {
            imageToBitmap(image)
        } finally {
            image.close()
        }
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
    }

    private fun stopEverything() {
        teardown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun toast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }
}
