package com.thunder.gametranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var bar: View? = null
    private var resultView: TextView? = null
    private var regionView: FrameLayout? = null
    private var regionLp: WindowManager.LayoutParams? = null
    private var regionMode = false
    private var regionToggle: TextView? = null

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var sw = 0
    private var sh = 0
    private var dpi = 0
    private var busy = false

    // เก็บเฟรมล่าสุดแบบต่อเนื่อง (กันปัญหา "จับภาพหน้าจอไม่ได้")
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val frameLock = Any()
    @Volatile private var latestFrame: Bitmap? = null
    @Volatile private var captureErr: String? = null
    @Volatile private var frameCount = 0
    @Volatile private var lastCapMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startInForeground()

        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra("data")
        if (code == 0 || data == null) {
            toast("เริ่มไม่สำเร็จ เปิดแอปแล้วกด \"เริ่ม\" อีกครั้ง")
            stopSelf()
            return START_NOT_STICKY
        }

        val metrics = resources.displayMetrics
        sw = metrics.widthPixels
        sh = metrics.heightPixels
        dpi = metrics.densityDpi

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { cleanup() }
            }, main)

            imageReader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 3)
            // background thread อ่านเฟรมต่อเนื่อง เก็บเฟรมล่าสุดไว้เสมอ
            captureThread = HandlerThread("gt_capture").also { it.start() }
            captureHandler = Handler(captureThread!!.looper)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
                    ?: return@setOnImageAvailableListener
                // throttle: copy เฟรมอย่างมากทุก ~200ms (drain buffer ทุกครั้งแต่ไม่ copy ถี่)
                val now = SystemClock.uptimeMillis()
                if (now - lastCapMs < 200) { runCatching { image.close() }; return@setOnImageAvailableListener }
                lastCapMs = now
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * sw
                    val full = Bitmap.createBitmap(
                        sw + rowPadding / pixelStride, sh, Bitmap.Config.ARGB_8888
                    )
                    full.copyPixelsFromBuffer(buffer)
                    val cropped = Bitmap.createBitmap(full, 0, 0, sw, sh)
                    if (cropped != full) full.recycle()
                    synchronized(frameLock) {
                        latestFrame?.let { runCatching { it.recycle() } }
                        latestFrame = cropped
                    }
                    frameCount++
                    captureErr = null
                } catch (e: Exception) {
                    captureErr = e.message
                } finally {
                    runCatching { image.close() }
                }
            }, captureHandler)
            virtualDisplay = projection?.createVirtualDisplay(
                "gt_cap", sw, sh, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, captureHandler
            )
            showBar()
        } catch (e: Exception) {
            toast("เริ่มไม่สำเร็จ: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------------- floating bar ----------------

    private fun showBar() {
        if (bar != null) return
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = pill("#CC222633")
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        val handle = chip("≡", "#44FFFFFF")
        val translateBtn = chip("แปล", "#667EEA")
        regionToggle = chip("▢ กรอบ", "#33FFFFFF")

        container.addView(handle)
        container.addView(space())
        container.addView(translateBtn)
        container.addView(space())
        container.addView(regionToggle)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sw - dp(220)
            y = dp(120)
        }

        attachDrag(handle, lp, container) {}
        translateBtn.setOnClickListener { onTranslateClick() }
        regionToggle?.setOnClickListener { toggleRegion() }

        runCatching { windowManager.addView(container, lp) }
        bar = container
    }

    private fun toggleRegion() {
        regionMode = !regionMode
        if (regionMode) {
            showRegion()
            regionToggle?.text = "▣ กรอบ"
            regionToggle?.background = pill("#FF5252")
            toast("ลากย้ายกรอบ / ลากมุมขวาล่างปรับขนาด ครอบกล่องบทพูด")
        } else {
            regionView?.let { runCatching { windowManager.removeView(it) } }
            regionView = null
            regionToggle?.text = "▢ กรอบ"
            regionToggle?.background = pill("#33FFFFFF")
        }
    }

    private fun showRegion() {
        if (regionView != null) return
        val box = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setStroke(dp(3), Color.parseColor("#FF5252"))
                setColor(Color.parseColor("#22FF5252"))
            }
        }
        // resize handle ที่มุมขวาล่าง
        val handle = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF5252"))
                cornerRadius = dp(4).toFloat()
            }
        }
        box.addView(handle, FrameLayout.LayoutParams(dp(40), dp(40)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
        })

        val lp = WindowManager.LayoutParams(
            sw - dp(60),
            dp(170),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(30)
            y = (sh * 0.55).toInt()
        }
        regionLp = lp

        // ลากตัวกรอบ = ย้าย
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        box.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - tx).toInt()
                    lp.y = sy + (e.rawY - ty).toInt()
                    runCatching { windowManager.updateViewLayout(box, lp) }; true
                }
                else -> false
            }
        }
        // ลาก handle = ปรับขนาด
        var sw0 = 0; var sh0 = 0; var hx = 0f; var hy = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sw0 = lp.width; sh0 = lp.height; hx = e.rawX; hy = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.width = (sw0 + (e.rawX - hx).toInt()).coerceAtLeast(dp(80))
                    lp.height = (sh0 + (e.rawY - hy).toInt()).coerceAtLeast(dp(60))
                    runCatching { windowManager.updateViewLayout(box, lp) }; true
                }
                else -> false
            }
        }

        runCatching { windowManager.addView(box, lp) }
        regionView = box
    }

    // ---------------- capture + translate ----------------

    private fun onTranslateClick() {
        if (busy) return
        busy = true
        bar?.visibility = View.GONE
        regionView?.visibility = View.GONE
        removeResult()
        main.postDelayed({ captureAndTranslate() }, 250)
    }

    private fun captureAndTranslate() {
        val bmp = try { grabBitmap() } catch (e: Exception) { null }
        bar?.visibility = View.VISIBLE
        regionView?.visibility = View.VISIBLE
        if (bmp == null) {
            busy = false
            val reason = captureErr?.let { " ($it)" } ?: ""
            showResult("จับภาพหน้าจอไม่ได้ ลองใหม่$reason")
            return
        }
        val target = if (regionMode) cropToRegion(bmp) else bmp
        showResult("กำลังอ่านข้อความ…")
        try {
            val input = InputImage.fromBitmap(target, 0)
            recognizer.process(input)
                .addOnSuccessListener { vt ->
                    val text = vt.text.replace("\n", " ").trim()
                    runCatching { if (target != bmp) target.recycle() }
                    runCatching { bmp.recycle() }
                    if (text.isBlank()) { showResult("ไม่พบข้อความ (ลองขยับ/ขยายกรอบ)"); busy = false; return@addOnSuccessListener }
                    translate(text)
                }
                .addOnFailureListener {
                    runCatching { if (target != bmp) target.recycle() }
                    runCatching { bmp.recycle() }
                    showResult("อ่านข้อความไม่สำเร็จ"); busy = false
                }
        } catch (e: Exception) {
            showResult("ผิดพลาด: ${e.message}"); busy = false
        }
    }

    private fun cropToRegion(src: Bitmap): Bitmap {
        val lp = regionLp ?: return src
        val border = dp(3)
        val x = (lp.x + border).coerceIn(0, sw - 1)
        val y = (lp.y + border).coerceIn(0, sh - 1)
        val w = (lp.width - border * 2).coerceIn(1, sw - x)
        val h = (lp.height - border * 2).coerceIn(1, sh - y)
        return try { Bitmap.createBitmap(src, x, y, w, h) } catch (e: Exception) { src }
    }

    private fun translate(text: String) {
        showResult("กำลังแปล…")
        val prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        val key = prefs.getString(MainActivity.KEY_API, "").orEmpty()
        val model = prefs.getString(MainActivity.KEY_MODEL, MainActivity.DEFAULT_MODEL)
            .orEmpty().ifBlank { MainActivity.DEFAULT_MODEL }
        val game = prefs.getString(MainActivity.KEY_GAME, "").orEmpty()
        scope.launch {
            val out = withContext(Dispatchers.IO) { GeminiClient.translate(key, model, text, game) }
            showResult(if (out.startsWith("ERROR")) "⚠️ ${out.removePrefix("ERROR: ")}" else out)
            busy = false
        }
    }

    private fun grabBitmap(): Bitmap? {
        if (imageReader == null) { captureErr = "ยังไม่ได้เริ่มจับภาพ"; return null }
        // รอเฟรมแรกได้นานสุด ~2 วินาที (หลังจากนั้นมีเฟรมล่าสุดเสมอ)
        var tries = 0
        while (latestFrame == null && tries < 20) {
            try { Thread.sleep(100) } catch (_: Exception) {}
            tries++
        }
        synchronized(frameLock) {
            val l = latestFrame
            if (l == null) {
                if (captureErr == null) captureErr = "ยังไม่มีเฟรมจากหน้าจอ"
                return null
            }
            return try {
                l.copy(Bitmap.Config.ARGB_8888, false)
            } catch (e: Exception) {
                captureErr = e.message; null
            }
        }
    }

    // ---------------- result ----------------

    private fun showResult(msg: String) {
        main.post {
            try {
                if (resultView == null) {
                    val tv = TextView(this).apply {
                        setTextColor(Color.WHITE)
                        textSize = 18f
                        background = pill("#DD000000")
                        setPadding(dp(18), dp(14), dp(18), dp(14))
                        setOnClickListener { removeResult() }
                    }
                    val lp = WindowManager.LayoutParams(
                        sw - dp(24),
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        overlayType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                        y = dp(60)
                    }
                    windowManager.addView(tv, lp)
                    resultView = tv
                }
                resultView?.text = msg
            } catch (_: Exception) {}
        }
    }

    private fun removeResult() {
        resultView?.let { runCatching { windowManager.removeView(it) } }
        resultView = null
    }

    // ---------------- helpers ----------------

    private fun attachDrag(
        grip: View, lp: WindowManager.LayoutParams, moveTarget: View, onClick: () -> Unit
    ) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f; var moved = false
        grip.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; touchX = e.rawX; touchY = e.rawY; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt(); val dy = (e.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > dp(8) || kotlin.math.abs(dy) > dp(8)) moved = true
                    lp.x = startX + dx; lp.y = startY + dy
                    runCatching { windowManager.updateViewLayout(moveTarget, lp) }; true
                }
                MotionEvent.ACTION_UP -> { if (!moved) onClick(); true }
                else -> false
            }
        }
    }

    private fun chip(text: String, color: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 15f
        gravity = Gravity.CENTER
        background = pill(color)
        setPadding(dp(16), dp(10), dp(16), dp(10))
    }

    private fun space() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(6), 1)
    }

    private fun pill(color: String) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = dp(22).toFloat()
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun startInForeground() {
        val channelId = "gt_channel"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channelId, "GameTranslate", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = Notification.Builder(this, channelId)
            .setContentTitle("GameTranslate TH")
            .setContentText("พร้อมแปลหน้าจอ — แตะปุ่ม \"แปล\"")
            .setSmallIcon(R.drawable.ic_app)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }
    }

    private fun cleanup() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        runCatching { captureThread?.quitSafely() }
        captureThread = null; captureHandler = null
        synchronized(frameLock) { latestFrame?.let { runCatching { it.recycle() } }; latestFrame = null }
        virtualDisplay = null; imageReader = null; projection = null
        bar?.let { runCatching { windowManager.removeView(it) } }; bar = null
        regionView?.let { runCatching { windowManager.removeView(it) } }; regionView = null
        removeResult()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = main.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
}
