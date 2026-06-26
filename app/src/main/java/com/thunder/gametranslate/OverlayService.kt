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
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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

    private var floatBtn: TextView? = null
    private var resultView: TextView? = null

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var sw = 0
    private var sh = 0
    private var dpi = 0
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startInForeground()

        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra("data")
        if (code == 0 || data == null) {
            toast("เริ่มไม่สำเร็จ ลองใหม่")
            stopSelf()
            return START_NOT_STICKY
        }

        val metrics = resources.displayMetrics
        sw = metrics.widthPixels
        sh = metrics.heightPixels
        dpi = metrics.densityDpi

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { cleanup() }
        }, main)

        imageReader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "gt_cap", sw, sh, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, main
        )

        showFloatingButton()
        return START_NOT_STICKY
    }

    // ---------------- UI ----------------

    private fun showFloatingButton() {
        if (floatBtn != null) return
        val btn = TextView(this).apply {
            text = "แปล"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            background = pill("#667EEA")
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sw - dp(120)
            y = sh / 3
        }
        attachDrag(btn, lp) { onTranslateClick() }
        windowManager.addView(btn, lp)
        floatBtn = btn
    }

    private fun onTranslateClick() {
        if (busy) return
        busy = true
        // hide our own overlays so they don't get captured / OCR'd
        floatBtn?.visibility = View.GONE
        removeResult()
        main.postDelayed({ captureAndTranslate() }, 250)
    }

    private fun captureAndTranslate() {
        val bmp = grabBitmap()
        floatBtn?.visibility = View.VISIBLE
        if (bmp == null) {
            busy = false
            toast("จับภาพหน้าจอไม่ได้")
            return
        }
        showResult("กำลังอ่านข้อความ…")
        val input = InputImage.fromBitmap(bmp, 0)
        recognizer.process(input)
            .addOnSuccessListener { vt ->
                val text = vt.text.replace("\n", " ").trim()
                bmp.recycle()
                if (text.isBlank()) {
                    showResult("ไม่พบข้อความบนหน้าจอ")
                    busy = false
                    return@addOnSuccessListener
                }
                translate(text)
            }
            .addOnFailureListener {
                bmp.recycle()
                showResult("อ่านข้อความไม่สำเร็จ")
                busy = false
            }
    }

    private fun translate(text: String) {
        showResult("กำลังแปล…")
        val prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        val key = prefs.getString(MainActivity.KEY_API, "").orEmpty()
        val model = prefs.getString(MainActivity.KEY_MODEL, MainActivity.DEFAULT_MODEL)
            .orEmpty().ifBlank { MainActivity.DEFAULT_MODEL }
        scope.launch {
            val out = withContext(Dispatchers.IO) { GeminiClient.translate(key, model, text) }
            showResult(out.removePrefix("ERROR: ").let { if (out.startsWith("ERROR")) "⚠️ $it" else it })
            busy = false
        }
    }

    // ---------------- capture ----------------

    private fun grabBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        var image: Image? = null
        repeat(3) {
            image = reader.acquireLatestImage()
            if (image != null) return@repeat
            try { Thread.sleep(60) } catch (_: Exception) {}
        }
        val img = image ?: return null
        return try {
            val plane = img.planes[0]
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
            cropped
        } catch (e: Exception) {
            null
        } finally {
            img.close()
        }
    }

    // ---------------- result overlay ----------------

    private fun showResult(msg: String) {
        main.post {
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
        }
    }

    private fun removeResult() {
        resultView?.let { runCatching { windowManager.removeView(it) } }
        resultView = null
    }

    // ---------------- helpers ----------------

    private fun attachDrag(view: View, lp: WindowManager.LayoutParams, onClick: () -> Unit) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > dp(8) || kotlin.math.abs(dy) > dp(8)) moved = true
                    lp.x = startX + dx
                    lp.y = startY + dy
                    runCatching { windowManager.updateViewLayout(view, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun pill(color: String) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = dp(24).toFloat()
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
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        virtualDisplay = null
        imageReader = null
        projection = null
        floatBtn?.let { runCatching { windowManager.removeView(it) } }
        floatBtn = null
        removeResult()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) =
        main.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
}
