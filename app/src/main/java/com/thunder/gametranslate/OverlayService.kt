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
import android.util.Base64
import android.widget.ScrollView
import java.io.ByteArrayOutputStream
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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
    private var barChips: MutableList<View> = mutableListOf()
    private var barCollapsed = false
    private var firstBar = true

    // ผู้ช่วย AI
    private var askFrame: Bitmap? = null
    private var askPanel: View? = null
    private var askInputPanel: View? = null
    private var answerPanel: View? = null
    private var answerView: TextView? = null

    private val ASK_PRESETS = listOf(
        "ตอนนี้ควรทำอะไรต่อ?",
        "อธิบายจอนี้ให้หน่อย",
        "ตรงนี้/ตัวนี้คืออะไร?",
        "ควรไปทางไหนต่อ?",
        "มีอะไรที่ควรระวัง/สำคัญไหม?"
    )

    // โหมดแปลอัตโนมัติ
    private var autoMode = false
    private var lastSeenText = ""
    private var lastTranslatedText = ""

    // อ่านออกเสียง (TTS)
    private var tts: android.speech.tts.TextToSpeech? = null
    @Volatile private var ttsReady = false

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
            ensureTts()
        } catch (e: Exception) {
            toast("เริ่มไม่สำเร็จ: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------------- อ่านออกเสียง (TTS) ----------------

    private fun ensureTts() {
        if (tts != null) return
        tts = android.speech.tts.TextToSpeech(this) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                runCatching { tts?.language = java.util.Locale("th", "TH") }
                ttsReady = true
            }
        }
    }

    private fun speak(text: String, force: Boolean = false) {
        val on = force || getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_TTS, false)
        if (!on || text.isBlank()) return
        ensureTts()
        val rate = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getInt(MainActivity.KEY_TTS_RATE, 100) / 100f
        val doSpeak = Runnable {
            runCatching {
                tts?.setSpeechRate(rate)
                tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "gt")
            }
        }
        if (ttsReady) doSpeak.run() else main.postDelayed(doSpeak, 600)
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
        // รอจอหมุนเสร็จก่อน แล้วค่อยสร้าง capture ใหม่ + สร้างแถบลอยใหม่ (กันหลุดหายตอนหมุนจอ)
        main.postDelayed({
            runCatching { rebuildCapture() }
            runCatching {
                // ปิดแผงถาม/คำตอบที่อาจค้าง แล้วสร้างแถบลอยใหม่ตามขนาดจอใหม่
                removeAskChips(); removeAnswer(); removeAskInput()
                bar?.let { windowManager.removeView(it) }; bar = null
                barCollapsed = false
                showBar()
            }
        }, 400)
    }

    // ---------------- floating bar ----------------

    private fun showBar() {
        if (bar != null) return
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = pill("#CC222633")
            setPadding(dp(5), dp(4), dp(5), dp(4))
        }

        val handle = chip("💬", "#CC667EEA")
        val translateBtn = chip("แปล", "#667EEA")
        val askBtn = chip("💡 ถาม", "#FF9800")
        autoToggle = chip("⚡ ออโต้", "#33FFFFFF")
        regionToggle = chip("▢ กรอบ", "#33FFFFFF")

        val sp1 = space(); val sp2 = space(); val sp3 = space(); val sp4 = space()
        container.addView(handle)
        container.addView(sp1)
        container.addView(translateBtn)
        container.addView(sp2)
        container.addView(askBtn)
        container.addView(sp3)
        container.addView(autoToggle)
        container.addView(sp4)
        container.addView(regionToggle)

        // ย่อแล้วจะเหลือ [💬 แปล] เสมอ (แปลกดบ่อยสุด) — ซ่อนแค่ ถาม/ออโต้/กรอบ
        barChips = mutableListOf(sp2, askBtn, sp3, autoToggle!!, sp4, regionToggle!!)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (sw - dp(400)).coerceAtLeast(dp(8))
            y = dp(96)
        }

        attachDrag(handle, lp, container) { toggleBarCollapsed() }   // แตะ ≡ = ย่อ/กาง, ลาก = ย้าย
        translateBtn.setOnClickListener { onTranslateClick() }
        askBtn.setOnClickListener { onAskClick() }
        autoToggle?.setOnClickListener { toggleAuto() }
        regionToggle?.setOnClickListener { toggleEdit() }

        runCatching { windowManager.addView(container, lp) }
        bar = container

        // เริ่มมาแบบย่อ = จุด 💬 เล็กๆ (ไม่บังจอ) แตะเพื่อกางเมนู
        barCollapsed = true
        applyBarCollapsed()
        if (firstBar) { firstBar = false; toast("💬 = เปิดเมนูเพิ่ม (ถาม/ออโต้/กรอบ) · ปุ่มแปลอยู่ให้กดตลอด") }
    }

    private fun applyBarCollapsed() {
        barChips.forEach { it.visibility = if (barCollapsed) View.GONE else View.VISIBLE }
    }

    private fun toggleBarCollapsed() {
        barCollapsed = !barCollapsed
        applyBarCollapsed()
    }

    // ---------------- ผู้ช่วย AI ----------------

    private fun onAskClick() {
        if (busy) return
        removeAskChips(); removeAnswer()
        bar?.visibility = View.GONE
        removeResult()
        // ซ่อน overlay ก่อน แล้วจับภาพจอสะอาดๆ
        main.postDelayed({
            askFrame?.let { runCatching { it.recycle() } }
            askFrame = try { grabBitmap() } catch (e: Exception) { null }
            bar?.visibility = View.VISIBLE
            if (askFrame == null) { toast("จับภาพหน้าจอไม่ได้ ลองใหม่"); return@postDelayed }
            showAskChips()
        }, 250)
    }

    private fun showAskChips() {
        if (askPanel != null) return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F21B1E26")); cornerRadius = dp(18).toFloat()
                setStroke(dp(2), Color.parseColor("#FF9800"))
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        panel.addView(TextView(this).apply {
            text = "💡 ถาม AI เกี่ยวกับจอนี้"
            setTextColor(Color.WHITE); textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        for (q in ASK_PRESETS) {
            panel.addView(TextView(this).apply {
                text = q
                setTextColor(Color.WHITE); textSize = 15f
                background = pill("#33FFFFFF")
                setPadding(dp(14), dp(11), dp(14), dp(11))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
                setOnClickListener { removeAskChips(); runAsk(q) }
            })
        }
        panel.addView(TextView(this).apply {
            text = "✏️ พิมพ์คำถามเอง"
            setTextColor(Color.WHITE); textSize = 15f
            background = pill("#55FF9800")
            setPadding(dp(14), dp(11), dp(14), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { removeAskChips(); showAskInput() }
        })
        panel.addView(TextView(this).apply {
            text = "✕ ปิด"
            setTextColor(Color.parseColor("#FFCDD2")); textSize = 14f; gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(2))
            setOnClickListener {
                removeAskChips()
                askFrame?.let { runCatching { it.recycle() } }; askFrame = null
            }
        })
        val lp = WindowManager.LayoutParams(
            (sw - dp(80)).coerceAtMost(dp(520)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        runCatching { windowManager.addView(panel, lp) }
        askPanel = panel
    }

    private fun removeAskChips() {
        askPanel?.let { runCatching { windowManager.removeView(it) } }
        askPanel = null
    }

    /** ช่องพิมพ์คำถามเอง (หน้าต่าง focusable เพื่อให้คีย์บอร์ดขึ้นได้) */
    private fun showAskInput() {
        if (askInputPanel != null) return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F21B1E26")); cornerRadius = dp(18).toFloat()
                setStroke(dp(2), Color.parseColor("#FF9800"))
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        panel.addView(TextView(this).apply {
            text = "✏️ พิมพ์คำถามเกี่ยวกับจอนี้"
            setTextColor(Color.WHITE); textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        val et = EditText(this).apply {
            hint = "เช่น ปริศนานี้ทำยังไง / ตัวไหนควรอัปเลเวล"
            setHintTextColor(Color.parseColor("#88FFFFFF"))
            setTextColor(Color.WHITE); textSize = 15f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#22FFFFFF")); cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minLines = 1; maxLines = 3
        }
        panel.addView(et, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(TextView(this).apply {
            text = "ยกเลิก"; setTextColor(Color.parseColor("#FFCDD2")); textSize = 15f
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setOnClickListener { removeAskInput(); askFrame?.let { runCatching { it.recycle() } }; askFrame = null }
        })
        row.addView(space())
        row.addView(TextView(this).apply {
            text = "🚀 ถาม"; setTextColor(Color.WHITE); textSize = 15f
            background = pill("#FF9800"); setPadding(dp(18), dp(9), dp(18), dp(9))
            setOnClickListener {
                val q = et.text.toString().trim()
                if (q.isBlank()) { toast("พิมพ์คำถามก่อนนะ"); return@setOnClickListener }
                removeAskInput(); runAsk(q)
            }
        })
        panel.addView(row)

        val lp = WindowManager.LayoutParams(
            (sw - dp(80)).coerceAtMost(dp(560)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,   // ไม่ใส่ NOT_FOCUSABLE → คีย์บอร์ดขึ้นได้
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        runCatching { windowManager.addView(panel, lp) }
        askInputPanel = panel
        et.requestFocus()
        main.postDelayed({
            runCatching {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 180)
    }

    private fun removeAskInput() {
        askInputPanel?.let { p ->
            runCatching {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(p.windowToken, 0)
            }
            runCatching { windowManager.removeView(p) }
        }
        askInputPanel = null
    }

    private fun runAsk(question: String) {
        val frame = askFrame
        if (frame == null) { showAnswer("ไม่มีภาพ — กด 💡 ถาม ใหม่อีกครั้ง"); return }
        if (busy) return
        busy = true
        val prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        val engine = prefs.getString(MainActivity.KEY_ENGINE, "gemini").orEmpty().ifBlank { "gemini" }
        val game = prefs.getString(MainActivity.KEY_GAME, "").orEmpty()
        showAnswer("🤔 กำลังคิด…")

        when (engine) {
            "offline" -> {
                showAnswer("⚠️ ผู้ช่วยต้องใช้ Gemini / Groq / DeepSeek\n(ออฟไลน์ไม่รองรับ) — สลับเอนจินในตั้งค่าก่อนนะ")
                cleanupAsk()
            }
            "gemini" -> {
                val key = prefs.getString(MainActivity.KEY_API, "").orEmpty()
                val model = prefs.getString(MainActivity.KEY_MODEL, MainActivity.DEFAULT_MODEL)
                    .orEmpty().ifBlank { MainActivity.DEFAULT_MODEL }
                scope.launch {
                    val out = withContext(Dispatchers.IO) {
                        val b64 = encodeImage(frame)
                        if (b64.isBlank()) "ERROR: เข้ารหัสภาพไม่ได้" else GeminiClient.ask(key, model, b64, question, game)
                    }
                    finishAsk(out)
                }
            }
            else -> { // groq / deepseek → OCR อ่านจอ แล้วถามแบบข้อความ
                val lang = prefs.getString(MainActivity.KEY_LANG, "latin").orEmpty().ifBlank { "latin" }
                val url = if (engine == "groq") MainActivity.GROQ_URL else MainActivity.DEEPSEEK_URL
                val key = prefs.getString(if (engine == "groq") MainActivity.KEY_GROQ else MainActivity.KEY_DEEPSEEK, "").orEmpty()
                val model = if (engine == "groq")
                    prefs.getString(MainActivity.KEY_MODEL_GROQ, MainActivity.GROQ_MODEL).orEmpty().ifBlank { MainActivity.GROQ_MODEL }
                else
                    prefs.getString(MainActivity.KEY_MODEL_DEEPSEEK, MainActivity.DEEPSEEK_MODEL).orEmpty().ifBlank { MainActivity.DEEPSEEK_MODEL }
                try {
                    getRecognizer(lang).process(InputImage.fromBitmap(frame, 0))
                        .addOnSuccessListener { vt ->
                            val screenText = vt.text.replace("\n", " ").trim()
                            scope.launch {
                                val out = withContext(Dispatchers.IO) { OpenAIClient.ask(url, key, model, question, game, screenText) }
                                finishAsk(out)
                            }
                        }
                        .addOnFailureListener { finishAsk("ERROR: อ่านข้อความจากจอไม่สำเร็จ") }
                } catch (e: Exception) { finishAsk("ERROR: ${e.message}") }
            }
        }
    }

    private fun finishAsk(out: String) {
        val clean = when {
            out.startsWith("QUOTA:") -> "⚠️ โควต้าเต็ม ลองใหม่ภายหลัง หรือสลับเอนจิน"
            out.startsWith("ERROR") -> "⚠️ ${out.removePrefix("ERROR: ")}"
            else -> out
        }
        showAnswer(clean)
        cleanupAsk()
    }

    private fun cleanupAsk() {
        askFrame?.let { runCatching { it.recycle() } }
        askFrame = null
        busy = false
    }

    private fun encodeImage(src: Bitmap): String {
        return try {
            val maxDim = 1024
            val big = maxOf(src.width, src.height)
            val scaled = if (big > maxDim) {
                val f = maxDim.toFloat() / big
                Bitmap.createScaledBitmap(src, (src.width * f).toInt().coerceAtLeast(1), (src.height * f).toInt().coerceAtLeast(1), true)
            } else src
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 78, baos)
            if (scaled != src) scaled.recycle()
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) { "" }
    }

    private fun showAnswer(msg: String) {
        main.post {
            try {
                if (answerPanel == null) {
                    val panel = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#F21B1E26")); cornerRadius = dp(18).toFloat()
                            setStroke(dp(2), Color.parseColor("#FF9800"))
                        }
                        setPadding(dp(16), dp(12), dp(16), dp(14))
                    }
                    val header = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    }
                    header.addView(TextView(this).apply {
                        text = "💬 ผู้ช่วย AI"
                        setTextColor(Color.parseColor("#FFB74D")); textSize = 15f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    header.addView(TextView(this).apply {
                        text = "🔊"; textSize = 18f; setPadding(dp(10), dp(4), dp(12), dp(4))
                        setOnClickListener { answerView?.text?.toString()?.let { speak(it, true) } }
                    })
                    header.addView(TextView(this).apply {
                        text = "✕"; setTextColor(Color.WHITE); textSize = 18f; setPadding(dp(8), dp(4), dp(4), dp(4))
                        setOnClickListener { removeAnswer() }
                    })
                    panel.addView(header)
                    val scroll = ScrollView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, (sh * 0.5f).toInt()
                        )
                    }
                    val tv = TextView(this).apply {
                        setTextColor(Color.WHITE); textSize = 16f; setPadding(0, dp(8), 0, 0)
                    }
                    scroll.addView(tv)
                    panel.addView(scroll)
                    val lp = WindowManager.LayoutParams(
                        (sw - dp(60)).coerceAtMost(dp(640)),
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
                    ).apply { gravity = Gravity.CENTER }
                    windowManager.addView(panel, lp)
                    answerPanel = panel; answerView = tv
                }
                answerView?.text = msg
            } catch (_: Exception) {}
        }
    }

    private fun removeAnswer() {
        answerPanel?.let { runCatching { windowManager.removeView(it) } }
        answerPanel = null; answerView = null
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
        val ocrBmp = preprocessForOcr(target)
        val lang = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getString(MainActivity.KEY_LANG, "latin").orEmpty().ifBlank { "latin" }
        try {
            getRecognizer(lang).process(InputImage.fromBitmap(ocrBmp, 0))
                .addOnSuccessListener { vt ->
                    recycleBitmaps(bmp, target, ocrBmp)
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
                .addOnFailureListener { recycleBitmaps(bmp, target, ocrBmp) }
        } catch (e: Exception) {
            recycleBitmaps(bmp, target, ocrBmp)
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
        val ocrBmp = preprocessForOcr(target)
        showResult("กำลังอ่าน…")
        val lang = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getString(MainActivity.KEY_LANG, "latin").orEmpty().ifBlank { "latin" }
        try {
            getRecognizer(lang).process(InputImage.fromBitmap(ocrBmp, 0))
                .addOnSuccessListener { vt ->
                    val text = vt.text.replace("\n", " ").trim()
                    recycleBitmaps(bmp, target, ocrBmp)
                    if (text.isBlank()) { showResult("ไม่พบข้อความ (ลองขยับ/ขยายกรอบ)"); busy = false; return@addOnSuccessListener }
                    translate(text)
                }
                .addOnFailureListener {
                    recycleBitmaps(bmp, target, ocrBmp)
                    showResult("อ่านข้อความไม่สำเร็จ"); busy = false
                }
        } catch (e: Exception) {
            recycleBitmaps(bmp, target, ocrBmp)
            showResult("ผิดพลาด: ${e.message}"); busy = false
        }
    }

    /** ขยายภาพในกรอบก่อนส่ง OCR → ฟอนต์ pixel เกมเก่าอ่านแม่นขึ้น (จำกัดขนาดสูงสุดกันหน่วง) */
    private fun preprocessForOcr(src: Bitmap): Bitmap {
        if (src.width <= 0 || src.height <= 0) return src
        val scale = 3.0f
        val maxDim = 2400
        var w = (src.width * scale).toInt()
        var h = (src.height * scale).toInt()
        val big = maxOf(w, h)
        if (big > maxDim) { val f = maxDim.toFloat() / big; w = (w * f).toInt(); h = (h * f).toInt() }
        if (w <= src.width) return src   // เล็กกว่าเดิม/เท่าเดิม ไม่ต้องขยาย
        return try {
            Bitmap.createScaledBitmap(src, w.coerceAtLeast(1), h.coerceAtLeast(1), true)
        } catch (e: Exception) { src }
    }

    /** รีไซเคิล bitmap ที่ไม่ซ้ำกัน (กันเผลอ recycle ตัวเดียวกัน 2 รอบ) */
    private fun recycleBitmaps(vararg bs: Bitmap?) {
        val seen = HashSet<Bitmap>()
        for (b in bs) if (b != null && seen.add(b)) runCatching { b.recycle() }
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
        val engine = prefs.getString(MainActivity.KEY_ENGINE, "gemini").orEmpty().ifBlank { "gemini" }
        val game = prefs.getString(MainActivity.KEY_GAME, "").orEmpty()
        val lang = prefs.getString(MainActivity.KEY_LANG, "latin").orEmpty().ifBlank { "latin" }
        val memoryOn = prefs.getBoolean(MainActivity.KEY_MEMORY, true)
        val historyText = if (memoryOn) buildHistoryText() else ""

        // แคช: เอนจิน+เกม+ข้อความ → คืนทันที
        val cacheKey = "$engine|$game|$text"
        synchronized(cache) { cache[cacheKey] }?.let { cached ->
            showResult(cached)
            speak(cached)
            recordHistory(text, cached)
            busy = false
            return
        }

        // ---- เอนจินออฟไลน์ (ML Kit) ----
        if (engine == "offline") {
            showResult("กำลังแปล (ออฟไลน์)… ครั้งแรกอาจโหลดโมเดลสักครู่")
            scope.launch {
                val out = withContext(Dispatchers.IO) { OfflineTranslator.translateBlocking(lang, text) }
                if (!out.startsWith("ERROR")) {
                    synchronized(cache) { cache[cacheKey] = out }
                    showResult(out); speak(out); recordHistory(text, out)
                } else showResult("⚠️ ${out.removePrefix("ERROR: ")}")
                busy = false
            }
            return
        }

        // ---- เอนจิน Groq / DeepSeek (OpenAI-compatible) ----
        if (engine == "groq") {
            val gm = prefs.getString(MainActivity.KEY_MODEL_GROQ, MainActivity.GROQ_MODEL).orEmpty().ifBlank { MainActivity.GROQ_MODEL }
            aiTranslate(MainActivity.GROQ_URL, prefs.getString(MainActivity.KEY_GROQ, "").orEmpty(),
                gm, text, game, historyText, cacheKey)
            return
        }
        if (engine == "deepseek") {
            val dm = prefs.getString(MainActivity.KEY_MODEL_DEEPSEEK, MainActivity.DEEPSEEK_MODEL).orEmpty().ifBlank { MainActivity.DEEPSEEK_MODEL }
            aiTranslate(MainActivity.DEEPSEEK_URL, prefs.getString(MainActivity.KEY_DEEPSEEK, "").orEmpty(),
                dm, text, game, historyText, cacheKey)
            return
        }

        // ---- เอนจิน Gemini AI ----
        val key = prefs.getString(MainActivity.KEY_API, "").orEmpty()
        val model = prefs.getString(MainActivity.KEY_MODEL, MainActivity.DEFAULT_MODEL)
            .orEmpty().ifBlank { MainActivity.DEFAULT_MODEL }
        val fallback = prefs.getBoolean(MainActivity.KEY_FALLBACK, true)

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
                out = withContext(Dispatchers.IO) { GeminiClient.translate(key, m, text, game, historyText) }
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
                    showResult(out); speak(out); recordHistory(text, out)
                }
            }
            busy = false
        }
    }

    /** แปลผ่าน API แบบ OpenAI-compatible (Groq / DeepSeek) */
    private fun aiTranslate(url: String, key: String, model: String, text: String, game: String, history: String, cacheKey: String) {
        showResult("กำลังแปล…")
        scope.launch {
            val out = withContext(Dispatchers.IO) { OpenAIClient.translate(url, key, model, text, game, history) }
            when {
                out.startsWith("QUOTA:") -> showResult("⚠️ โควต้าเต็ม ลองใหม่ภายหลัง หรือสลับเอนจิน")
                out.startsWith("ERROR") -> showResult("⚠️ ${out.removePrefix("ERROR: ")}")
                else -> {
                    synchronized(cache) { cache[cacheKey] = out }
                    showResult(out); speak(out); recordHistory(text, out)
                }
            }
            busy = false
        }
    }

    // ---------------- จำบทสนทนา ----------------

    private val historyLines = ArrayDeque<Pair<String, String>>()

    private fun buildHistoryText(): String {
        if (historyLines.isEmpty()) return ""
        return historyLines.joinToString("\n") { "- ${it.first} → ${it.second}" }
    }

    private fun recordHistory(src: String, thai: String) {
        if (src.isBlank() || thai.isBlank()) return
        if (historyLines.lastOrNull()?.first == src) return
        historyLines.addLast(src to thai)
        while (historyLines.size > 3) historyLines.removeFirst()
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
                val prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                val fontSize = prefs.getInt(MainActivity.KEY_FONT, 18).coerceIn(12, 34).toFloat()
                val themeIdx = prefs.getInt(MainActivity.KEY_PANEL_THEME, 0)
                    .coerceIn(0, MainActivity.PANEL_THEMES.lastIndex)
                val alpha = prefs.getInt(MainActivity.KEY_PANEL_ALPHA, 100)
                val theme = MainActivity.PANEL_THEMES[themeIdx]
                val rx = (fx * sw).toInt().coerceIn(0, (sw - dp(120)).coerceAtLeast(0))
                val rTop = (fy * sh).toInt().coerceIn(0, (sh - dp(60)).coerceAtLeast(0))
                val rw = (fw * sw).toInt().coerceAtLeast(dp(160))
                if (resultView == null) {
                    val tv = TextView(this).apply {
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
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
                resultView?.setTextColor(Color.parseColor(theme.third))
                resultView?.background = GradientDrawable().apply {
                    setColor(withAlpha(theme.second, alpha))
                    cornerRadius = dp(18).toFloat()
                    setStroke(dp(2), Color.parseColor("#667EEA"))
                }
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
        textSize = 14f
        gravity = Gravity.CENTER
        background = pill(color)
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    private fun space() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(4), 1)
    }

    private fun pill(color: String) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = dp(22).toFloat()
    }

    private fun withAlpha(colorHex: String, alphaPercent: Int): Int {
        val c = Color.parseColor(colorHex)
        val a = alphaPercent.coerceIn(0, 100) * 255 / 100
        return Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
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
        runCatching { OfflineTranslator.close() }
        runCatching { tts?.stop(); tts?.shutdown() }; tts = null; ttsReady = false
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
        removeAskChips(); removeAskInput(); removeAnswer()
        askFrame?.let { runCatching { it.recycle() } }; askFrame = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = main.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
}
