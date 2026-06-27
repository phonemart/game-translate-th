package com.thunder.gametranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.util.DisplayMetrics
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
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
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
    private var resultLp: WindowManager.LayoutParams? = null
    private var regionView: FrameLayout? = null      // กล่อง edit (แสดงเฉพาะตอนจัดกรอบ)
    private var regionToggle: TextView? = null
    private var autoToggle: TextView? = null

    // โหมดแปลอัตโนมัติ
    private var autoMode = false
    private var lastSeenText = ""
    private var lastTranslatedText = ""

    // พื้นที่แปล เก็บเป็นสัดส่วนของจอ (รองรับหมุนจอ) — default = แถบล่างกลางจอ
    private var fx = 0.15f
    private var fy = 0.70f
    private var fw = 0.70f
    private var fh = 0.24f

    private val main = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { removeResult() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ตัวอ่านข้อความ (OCR) แยกตามภาษา — สร้างเมื่อใช้ครั้งแรก
    private val recognizers = HashMap<String, TextRecognizer>()
    private fun getRecognizer(lang: String): TextRecognizer = recognizers.getOrPut(lang) {
        when (lang) {
            "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    // โหมดอัตโนมัติ: วนจับ region ทุก ~1.4 วิ แปลเมื่อข้อความ "นิ่ง" และเปลี่ยนไปจากเดิม
    private val autoRunnable = object : Runnable {
        override fun run() {
            if (!autoMode) return
            if (!busy) autoTick()
            main.postDelayed(this, 1400)
        }
    }

    private var sw = 0
    private var sh = 0
    private var dpi = 0
    private var busy = false

    // แคชคำแปล (LRU) — กดแปลข้อความเดิมซ้ำ → คืนทันที ไม่ยิง API
    private val cache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 80
    }

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

        readRealSize()
        loadRegion()

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { cleanup() }
            }, main)

            captureThread = HandlerThread("gt_capture").also { it.start() }
            captureHandler = Handler(captureThread!!.looper)

            setupCapture()
            showBar()
        } catch (e: Exception) {
            toast("เริ่มไม่สำเร็จ: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** อ่านขนาดจอจริงปัจจุบัน (รองรับการหมุนจอ) */
    private fun readRealSize() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val b = windowManager.maximumWindowMetrics.bounds
                sw = b.width(); sh = b.height()
            } else {
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(dm)
                sw = dm.widthPixels; sh = dm.heightPixels
            }
        } catch (e: Exception) {
            val dm = resources.displayMetrics
            sw = dm.widthPixels; sh = dm.heightPixels
        }
        dpi = resources.displayMetrics.densityDpi
        if (sw <= 0) sw = 1080
        if (sh <= 0) sh = 1920
    }

    /** สร้าง ImageReader + VirtualDisplay ตามขนาด sw,sh ปัจจุบัน */
    private fun setupCapture() {
        val handler = captureHandler ?: return
        val reader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            val image = try { r.acquireLatestImage() } catch (e: Exception) { null }
                ?: return@setOnImageAvailableListener
            val now = SystemClock.uptimeMillis()
            if (now - lastCapMs < 200) { runCatching { image.close() }; return@setOnImageAvailableListener }
            lastCapMs = now
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val w = image.width
                val h = image.height
                val rowPadding = rowStride - pixelStride * w
                val full = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                full.copyPixelsFromBuffer(buffer)
                val cropped = Bitmap.createBitmap(full, 0, 0, w, h)
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
        }, handler)
        virtualDisplay = projection?.createVirtualDisplay(
            "gt_cap", sw, sh, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
        imageReader = reader
    }

    /** สร้างการจับภาพใหม่เมื่อขนาดจอเปลี่ยน (หมุนจอ) */
    private fun rebuildCapture() {
        if (projection == null) return
        val ow = sw; val oh = sh
        readRealSize()
        if (sw == ow && sh == oh && virtualDisplay != null) return
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        virtualDisplay = null; imageReader = null
        synchronized(frameLock) { latestFrame?.let { runCatching { it.recycle() } }; latestFrame = null }
        lastCapMs = 0L
        setupCapture()
        // ถ้ากำลังจัดกรอบอยู่ ย้ายกล่อง edit ให้ตรงตำแหน่งใหม่ตามสัดส่วน
        regionView?.let { box ->
            (box.layoutParams as? WindowManager.LayoutParams)?.let { lp ->
                lp.x = (fx * sw).toInt(); lp.y = (fy * sh).toInt()
                lp.width = (fw * sw).toInt().coerceAtLeast(dp(100))
                lp.height = (fh * sh).toInt().coerceAtLeast(dp(70))
                runCatching { windowManager.updateViewLayout(box, lp) }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // รอจอหมุนเสร็จก่อน แล้วค่อยสร้าง capture ใหม่ตามขนาดใหม่
        main.postDelayed({ runCatching { rebuildCapture() } }, 350)
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
        autoToggle = chip("⚡ ออโต้", "#33FFFFFF")
        regionToggle = chip("▢ กรอบ", "#33FFFFFF")

        container.addView(handle)
        container.addView(space())
        container.addView(translateBtn)
        container.addView(space())
        container.addView(autoToggle)
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
            x = (sw - dp(330)).coerceAtLeast(dp(8))
            y = dp(110)
        }

        attachDrag(handle, lp, container) {}
        translateBtn.setOnClickListener { onTranslateClick() }
        autoToggle?.setOnClickListener { toggleAuto() }
        regionToggle?.setOnClickListener { toggleEdit() }

        runCatching { windowManager.addView(container, lp) }
        bar = container
    }

    // ---------------- โหมดอัตโนมัติ ----------------

    private fun toggleAuto() {
        autoMode = !autoMode
        if (autoMode) {
            autoToggle?.text = "⚡ ออโต้"
            autoToggle?.background = pill("#4CAF50")
            lastSeenText = ""; lastTranslatedText = ""
            removeResult()   // เปลี่ยนตำแหน่งกล่องคำแปล (ออโต้ = เหนือกรอบ)
            toast("โหมดอัตโนมัติ: เปิด — เจอบทพูดใหม่จะแปลเองทันที")
            main.postDelayed(autoRunnable, 600)
        } else {
            autoToggle?.text = "⚡ ออโต้"
            autoToggle?.background = pill("#33FFFFFF")
            main.removeCallbacks(autoRunnable)
            toast("โหมดอัตโนมัติ: ปิด")
        }
    }

    private fun autoTick() {
        val bmp = try { grabBitmap() } catch (e: Exception) { null } ?: return
        val target = cropToRegion(bmp)
        val lang = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getString(MainActivity.KEY_LANG, "latin").orEmpty().ifBlank { "latin" }
        try {
            getRecognizer(lang).process(InputImage.fromBitmap(target, 0))
                .addOnSuccessListener { vt ->
                    runCatching { if (target != bmp) target.recycle() }
                    runCatching { bmp.recycle() }
                    val text = vt.text.replace("\n", " ").trim()
                    if (text.isBlank()) { lastSeenText = ""; return@addOnSuccessListener }
                    // แปลเมื่อข้อความ "นิ่ง" (เท่าเดิม 1 รอบ — กัน typewriter) และต่างจากที่แปลล่าสุด
                    if (text == lastSeenText && text != lastTranslatedText) {
                        lastTranslatedText = text
                        busy = true
                        translate(text)
                    }
                    lastSeenText = text
                }
                .addOnFailureListener {
                    runCatching { if (target != bmp) target.recycle() }
                    runCatching { bmp.recycle() }
                }
        } catch (e: Exception) {
            runCatching { if (target != bmp) target.recycle() }
            runCatching { bmp.recycle() }
        }
    }

    // ---------------- ตั้งกรอบ (edit mode) ----------------

    private fun toggleEdit() {
        if (regionView == null) {
            showEditBox()
            regionToggle?.text = "✓ เสร็จ"
            regionToggle?.background = pill("#4CAF50")
            toast("ลากจัดกรอบให้ครอบกล่องบทพูด แล้วกด \"เสร็จ\"")
        } else {
            saveRegion()
            hideEditBox()
            resetRegionBtn()
            toast("บันทึกแล้ว — กด \"แปล\" คำแปลจะขึ้นในกรอบนี้")
        }
    }

    private fun resetRegionBtn() {
        regionToggle?.text = "▢ กรอบ"
        regionToggle?.background = pill("#33FFFFFF")
    }

    private fun showEditBox() {
        if (regionView != null) return
        val box = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2FFFFFF"))   // ขาวเกือบทึบ
                cornerRadius = dp(16).toFloat()
                setStroke(dp(3), Color.parseColor("#4CAF50"))
            }
        }
        box.addView(TextView(this@OverlayService).apply {
            text = "พื้นที่ที่จะแปล\nลากย้าย • ลากมุมขวาล่างปรับขนาด"
            setTextColor(Color.parseColor("#2E7D32"))
            textSize = 13f
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        val handle = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#4CAF50")); cornerRadius = dp(4).toFloat()
            }
        }
        box.addView(handle, FrameLayout.LayoutParams(dp(44), dp(44)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
        })

        val lp = WindowManager.LayoutParams(
            (fw * sw).toInt().coerceAtLeast(dp(100)),
            (fh * sh).toInt().coerceAtLeast(dp(70)),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (fx * sw).toInt(); y = (fy * sh).toInt()
        }

        fun sync() {
            fx = lp.x.toFloat() / sw; fy = lp.y.toFloat() / sh
            fw = lp.width.toFloat() / sw; fh = lp.height.toFloat() / sh
        }

        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        box.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - tx).toInt(); lp.y = sy + (e.rawY - ty).toInt()
                    runCatching { windowManager.updateViewLayout(box, lp) }; sync(); true
                }
                else -> false
            }
        }
        var w0 = 0; var h0 = 0; var hx = 0f; var hy = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { w0 = lp.width; h0 = lp.height; hx = e.rawX; hy = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.width = (w0 + (e.rawX - hx).toInt()).coerceAtLeast(dp(100))
                    lp.height = (h0 + (e.rawY - hy).toInt()).coerceAtLeast(dp(70))
                    runCatching { windowManager.updateViewLayout(box, lp) }; sync(); true
                }
                else -> false
            }
        }

        runCatching { windowManager.addView(box, lp) }
        regionView = box
    }

    private fun hideEditBox() {
        regionView?.let { runCatching { windowManager.removeView(it) } }
        regionView = null
    }

    private fun saveRegion() {
        getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
            .putString("region", "$fx,$fy,$fw,$fh").apply()
    }

    private fun loadRegion() {
        val s = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getString("region", null) ?: return
        val p = s.split(",").mapNotNull { it.toFloatOrNull() }
        if (p.size == 4) { fx = p[0]; fy = p[1]; fw = p[2]; fh = p[3] }
    }

    // ---------------- capture + translate ----------------

    private fun onTranslateClick() {
        if (busy) return
        busy = true
        // ออกจากโหมดจัดกรอบถ้าเปิดอยู่ + ซ่อนแถบ/คำแปลเก่า ก่อนจับภาพ
        if (regionView != null) { saveRegion(); hideEditBox(); resetRegionBtn() }
        bar?.visibility = View.GONE
        removeResult()
        main.postDelayed({ captureAndTranslate() }, 220)
    }

    private fun captureAndTranslate() {
        val bmp = try { grabBitmap() } catch (e: Exception) { null }
        bar?.visibility = View.VISIBLE
        if (bmp == null) {
            busy = false
            val reason = captureErr?.let { " ($it)" } ?: ""
            showResult("จับภาพหน้าจอไม่ได้ ลองใหม่$reason")
            return
        }
        val target = cropToRegion(bmp)
        showResult("กำลังอ่าน…")
        val lang = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getString(MainActivity.KEY_LANG, "latin").orEmpty().ifBlank { "latin" }
        try {
            val input = InputImage.fromBitmap(target, 0)
            getRecognizer(lang).process(input)
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
        val x = (fx * sw).toInt().coerceIn(0, sw - 1)
        val y = (fy * sh).toInt().coerceIn(0, sh - 1)
        val w = (fw * sw).toInt().coerceIn(1, sw - x)
        val h = (fh * sh).toInt().coerceIn(1, sh - y)
        return try { Bitmap.createBitmap(src, x, y, w, h) } catch (e: Exception) { src }
    }

    private fun translate(text: String) {
        val prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        val key = prefs.getString(MainActivity.KEY_API, "").orEmpty()
        val model = prefs.getString(MainActivity.KEY_MODEL, MainActivity.DEFAULT_MODEL)
            .orEmpty().ifBlank { MainActivity.DEFAULT_MODEL }
        val game = prefs.getString(MainActivity.KEY_GAME, "").orEmpty()
        val fallback = prefs.getBoolean(MainActivity.KEY_FALLBACK, true)

        // แคช: ข้อความเดิม (+เกม) → คืนทันที ไม่ยิง API
        val cacheKey = "$game|$text"
        synchronized(cache) { cache[cacheKey] }?.let { cached ->
            showResult(cached)
            busy = false
            return
        }

        // ลำดับโมเดล: ตัวที่เลือก → ตามด้วยตัวสำรอง (ถ้าเปิดสลับอัตโนมัติ)
        val order = mutableListOf(model)
        if (fallback) {
            for (m in MainActivity.FALLBACK_MODELS) if (m != model) order.add(m)
        }

        showResult("กำลังแปล…")
        scope.launch {
            var out = "QUOTA:"
            var usedModel = model
            for ((i, m) in order.withIndex()) {
                if (i > 0) showResult("โควต้าเต็ม… สลับเป็น $m")
                out = withContext(Dispatchers.IO) { GeminiClient.translate(key, m, text, game) }
                usedModel = m
                if (!out.startsWith("QUOTA:")) break   // สำเร็จ หรือ error อื่น → หยุด
            }
            when {
                out.startsWith("QUOTA:") ->
                    showResult("⚠️ โควต้าฟรีเต็มทุกรุ่นแล้ววันนี้ — ลองพรุ่งนี้ หรือเปิด billing")
                out.startsWith("ERROR") ->
                    showResult("⚠️ ${out.removePrefix("ERROR: ")}")
                else -> {
                    synchronized(cache) { cache[cacheKey] = out }
                    showResult(out)
                }
            }
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
                val fontSize = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                    .getInt(MainActivity.KEY_FONT, 18).coerceIn(12, 34).toFloat()
                val rx = (fx * sw).toInt().coerceIn(0, (sw - dp(120)).coerceAtLeast(0))
                val rTop = (fy * sh).toInt().coerceIn(0, (sh - dp(60)).coerceAtLeast(0))
                val rw = (fw * sw).toInt().coerceAtLeast(dp(160))
                if (resultView == null) {
                    val tv = TextView(this).apply {
                        setTextColor(Color.parseColor("#15151F"))
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        background = GradientDrawable().apply {
                            setColor(Color.WHITE)                     // ขาวล้วน ทึบ
                            cornerRadius = dp(18).toFloat()
                            setStroke(dp(2), Color.parseColor("#667EEA"))
                        }
                        setPadding(dp(18), dp(14), dp(18), dp(14))
                        setOnClickListener { removeResult() }          // แตะเพื่อปิด
                    }
                    val lp = WindowManager.LayoutParams(
                        rw,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        overlayType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT
                    )
                    windowManager.addView(tv, lp)
                    resultView = tv
                    resultLp = lp
                }
                resultView?.textSize = fontSize
                resultLp?.let {
                    it.width = rw
                    if (autoMode) {
                        // ออโต้: วางกล่อง "เหนือกรอบ" เพื่อไม่บังพื้นที่ที่ต้องอ่านซ้ำ
                        it.gravity = Gravity.BOTTOM or Gravity.START
                        it.x = rx
                        it.y = (sh - rTop + dp(8)).coerceAtLeast(dp(8))
                    } else {
                        // แมนนวล: วางทับกรอบ
                        it.gravity = Gravity.TOP or Gravity.START
                        it.x = rx
                        it.y = rTop
                    }
                    runCatching { windowManager.updateViewLayout(resultView, it) }
                }
                resultView?.text = msg
                // แมนนวลหายเองใน ~10 วิ / ออโต้คงไว้จนเจอบทใหม่ (แตะปิดได้เสมอ)
                main.removeCallbacks(dismissRunnable)
                if (!autoMode) main.postDelayed(dismissRunnable, 10000)
            } catch (_: Exception) {}
        }
    }

    private fun removeResult() {
        main.removeCallbacks(dismissRunnable)
        resultView?.let { runCatching { windowManager.removeView(it) } }
        resultView = null
        resultLp = null
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
        autoMode = false
        main.removeCallbacks(autoRunnable)
        recognizers.values.forEach { runCatching { it.close() } }
        recognizers.clear()
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
