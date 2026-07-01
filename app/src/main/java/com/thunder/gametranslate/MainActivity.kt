package com.thunder.gametranslate

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var keyInput: EditText
    private lateinit var groqInput: EditText
    private lateinit var deepseekInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var gameInput: EditText
    private lateinit var fallbackCheck: CheckBox
    private lateinit var statusView: TextView
    private var langValue = "latin"
    private var fontValue = 18
    private var engineValue = "gemini"
    private var panelThemeValue = 0
    private var panelAlphaValue = 100
    private var ttsRateValue = 100
    private lateinit var ttsCheck: CheckBox
    private lateinit var memoryCheck: CheckBox
    private lateinit var modelSpinner: Spinner
    private var modelEngine = "gemini"

    companion object {
        const val PREFS = "gt_prefs"
        const val KEY_API = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_GAME = "game"
        const val KEY_LANG = "lang"
        const val KEY_FONT = "font"
        const val KEY_FALLBACK = "fallback"
        const val KEY_ENGINE = "engine"
        const val KEY_GROQ = "groq_key"
        const val KEY_DEEPSEEK = "deepseek_key"
        const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
        const val GROQ_MODEL = "llama-3.3-70b-versatile"
        const val DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"
        const val DEEPSEEK_MODEL = "deepseek-chat"
        // โมเดลที่เลือกไว้ต่อเอนจิน + รายชื่อโมเดลที่ดึงมา (cache) + endpoint รายชื่อโมเดล
        const val KEY_MODEL_GROQ = "model_groq"
        const val KEY_MODEL_DEEPSEEK = "model_deepseek"
        const val KEY_MODELS_GEMINI = "models_gemini"
        const val KEY_MODELS_GROQ = "models_groq"
        const val KEY_MODELS_DEEPSEEK = "models_deepseek"
        const val GROQ_MODELS_URL = "https://api.groq.com/openai/v1/models"
        const val DEEPSEEK_MODELS_URL = "https://api.deepseek.com/models"
        val DEFAULT_MODELS_GEMINI = listOf("gemini-2.5-flash-lite", "gemini-2.5-flash", "gemini-flash-latest", "gemini-2.0-flash", "gemini-3-flash-preview")
        val DEFAULT_MODELS_GROQ = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "gemma2-9b-it")
        val DEFAULT_MODELS_DEEPSEEK = listOf("deepseek-chat", "deepseek-reasoner")
        const val KEY_PANEL_THEME = "panel_theme"
        const val KEY_PANEL_ALPHA = "panel_alpha"
        const val KEY_TTS = "tts"
        const val KEY_TTS_RATE = "tts_rate"
        const val KEY_MEMORY = "memory"
        const val DEFAULT_MODEL = "gemini-2.5-flash-lite"
        private const val REQ_PROJECTION = 1001
        private const val REQ_OVERLAY = 1002
        private const val REQ_NOTIF = 1003

        val MODELS = listOf(
            "gemini-2.5-flash-lite",
            "gemini-2.5-flash",
            "gemini-flash-latest",
            "gemini-2.0-flash",
            "gemini-3-flash-preview",
            "อื่นๆ (พิมพ์เอง)"
        )
        // ลำดับสำรองเมื่อโควต้าเต็ม (รุ่นฟรีหลายตัว → ใช้ฟรีได้นานขึ้น)
        val FALLBACK_MODELS = listOf("gemini-2.5-flash-lite", "gemini-2.5-flash", "gemini-2.0-flash")

        val LANGS = listOf(
            "อังกฤษ / ตัวโรมัน" to "latin",
            "ญี่ปุ่น (日本語)" to "ja",
            "จีน (中文)" to "zh",
            "เกาหลี (한국어)" to "ko"
        )
        val FONTS = listOf("เล็ก" to 16, "กลาง" to 18, "ใหญ่" to 22, "ใหญ่มาก" to 26)
        val ENGINES = listOf(
            "🤖 Gemini AI (เข้าใจบริบทดีสุด)" to "gemini",
            "⚡ Groq / Llama (เร็วมาก ฟรี)" to "groq",
            "🐳 DeepSeek (ถูก คุณภาพดี)" to "deepseek",
            "📴 ออฟไลน์ ML Kit (ฟรี ไม่ต้องใช้ key)" to "offline"
        )
        // ธีมกล่องคำแปล: ป้าย, สีพื้น, สีตัวอักษร
        val PANEL_THEMES = listOf(
            Triple("ขาว – ตัวดำ", "#FFFFFF", "#15151F"),
            Triple("ดำ – ตัวขาว", "#15151F", "#FFFFFF"),
            Triple("น้ำเงินเข้ม – ตัวขาว", "#1A237E", "#FFFFFF"),
            Triple("เหลือง – ตัวดำ", "#FFF176", "#15151F"),
            Triple("เขียวเข้ม – ตัวขาว", "#1B5E20", "#FFFFFF")
        )
        val ALPHAS = listOf("ทึบเต็ม" to 100, "โปร่ง 85%" to 85, "โปร่ง 70%" to 70, "โปร่ง 55%" to 55)
        val TTS_RATES = listOf("ช้า" to 80, "ปกติ" to 100, "เร็ว" to 130, "เร็วมาก" to 160)
        const val ACCENT = "#667EEA"
        const val KEY_DARK = "dark"
    }

    // palette (เปลี่ยนตามโหมดมืด/สว่าง)
    private var isDark = false
    private var colBg = 0
    private var colCard = 0
    private var colText = 0
    private var colSub = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        langValue = prefs.getString(KEY_LANG, "latin").orEmpty().ifBlank { "latin" }
        fontValue = prefs.getInt(KEY_FONT, 18)
        engineValue = prefs.getString(KEY_ENGINE, "gemini").orEmpty().ifBlank { "gemini" }
        panelThemeValue = prefs.getInt(KEY_PANEL_THEME, 0)
        panelAlphaValue = prefs.getInt(KEY_PANEL_ALPHA, 100)
        ttsRateValue = prefs.getInt(KEY_TTS_RATE, 100)

        // ---- ธีม (มืด/สว่าง) ----
        isDark = prefs.getBoolean(KEY_DARK, false)
        setTheme(if (isDark) android.R.style.Theme_Material else android.R.style.Theme_Material_Light_DarkActionBar)
        if (isDark) {
            colBg = Color.parseColor("#0F1115"); colCard = Color.parseColor("#1B1E26")
            colText = Color.parseColor("#F3F4F8"); colSub = Color.parseColor("#9AA0AC")
        } else {
            colBg = Color.parseColor("#EEF0F6"); colCard = Color.WHITE
            colText = Color.parseColor("#15151F"); colSub = Color.parseColor("#6B7280")
        }

        val scroll = ScrollView(this).apply { setBackgroundColor(colBg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(28))
        }

        // ---- header banner (gradient) ----
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#667EEA"), Color.parseColor("#764BA2"))
            ).apply { cornerRadius = dp(20).toFloat() }
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        banner.addView(TextView(this).apply {
            text = "🎮 GameTranslate TH"
            textSize = 25f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        banner.addView(TextView(this).apply {
            text = "แปลข้อความในเกมเป็นไทยด้วย AI  •  v${BuildConfig.VERSION_NAME}"
            textSize = 13f
            setPadding(0, dp(4), 0, 0)
            setTextColor(Color.parseColor("#E8EAFF"))
        })
        root.addView(banner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        // ---- ปุ่มสลับโหมดมืด/สว่าง ----
        root.addView(plainButton(if (isDark) "☀️ สลับเป็นโหมดสว่าง" else "🌙 สลับเป็นโหมดมืด") {
            prefs.edit().putBoolean(KEY_DARK, !isDark).apply()
            recreate()
        })

        // ---- card: API keys ----
        card(root, "🔑 API Keys") { c ->
            c.addView(hint("ใส่เฉพาะ key ของเอนจินที่จะใช้ (เลือกเอนจินด้านล่าง)"))

            c.addView(label2("🤖 Gemini"))
            keyInput = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                hint = "AIza..."
                setText(prefs.getString(KEY_API, ""))
            }
            c.addView(keyInput)
            c.addView(linkText("→ ขอฟรีที่ aistudio.google.com/apikey") { openUrl("https://aistudio.google.com/apikey") })

            c.addView(label2("⚡ Groq"))
            groqInput = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                hint = "gsk_..."
                setText(prefs.getString(KEY_GROQ, ""))
            }
            c.addView(groqInput)
            c.addView(linkText("→ ขอฟรีที่ console.groq.com/keys") { openUrl("https://console.groq.com/keys") })

            c.addView(label2("🐳 DeepSeek"))
            deepseekInput = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                hint = "sk-..."
                setText(prefs.getString(KEY_DEEPSEEK, ""))
            }
            c.addView(deepseekInput)
            c.addView(linkText("→ ขอที่ platform.deepseek.com/api_keys") { openUrl("https://platform.deepseek.com/api_keys") })
        }

        // ---- card: game ----
        card(root, "🎯 ชื่อเกมที่กำลังเล่น", true) { c ->
            c.addView(hint("ช่วยให้ AI แปลตรงบริบท (ชื่อตัวละคร/ศัพท์เฉพาะ)"))
            gameInput = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                hint = "เช่น Pokemon FireRed, Final Fantasy VII"
                setText(prefs.getString(KEY_GAME, ""))
            }
            c.addView(gameInput)
        }

        // ---- card: language ----
        card(root, "🌏 ภาษาในเกม (ภาษาต้นทาง)", true) { c ->
            val sp = Spinner(this)
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, LANGS.map { it.first })
            sp.setSelection(LANGS.indexOfFirst { it.second == langValue }.coerceAtLeast(0))
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { langValue = LANGS[pos].second }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            c.addView(sp)
        }

        // ---- card: engine ----
        card(root, "⚙️ เอนจินแปล") { c ->
            c.addView(hint("Gemini = เข้าใจบริบทเกมดีสุด (ต้องมี key) | ออฟไลน์ = ฟรี ไม่มีลิมิต แปลตรงตัวกว่า"))
            val sp = Spinner(this)
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ENGINES.map { it.first })
            sp.setSelection(ENGINES.indexOfFirst { it.second == engineValue }.coerceAtLeast(0))
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    engineValue = ENGINES[pos].second
                    if (this@MainActivity::modelInput.isInitialized && engineValue != "offline" && engineValue != modelEngine) {
                        // บันทึกโมเดลของเอนจินเดิม แล้วโหลดของเอนจินใหม่
                        prefs.edit().putString(modelKey(modelEngine),
                            modelInput.text.toString().trim().ifBlank { defaultModel(modelEngine) }).apply()
                        modelEngine = engineValue
                        populateModelCard(modelEngine)
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            c.addView(sp)
            memoryCheck = CheckBox(this).apply {
                text = "จำบทสนทนา — ส่งบทพูดก่อนหน้าให้ AI เข้าใจเนื้อเรื่องต่อเนื่อง (ใช้โควต้าเพิ่มเล็กน้อย, ไม่มีผลกับออฟไลน์)"
                isChecked = prefs.getBoolean(KEY_MEMORY, true)
                setPadding(0, dp(8), 0, 0)
            }
            c.addView(memoryCheck)
        }

        // ---- card: model ----
        card(root, "🤖 โมเดล (ตามเอนจินที่เลือก)", true) { c ->
            c.addView(hint("รายชื่อโมเดลของเอนจินที่เลือกด้านบน"))
            modelSpinner = Spinner(this)
            c.addView(modelSpinner)
            modelInput = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT }
            c.addView(modelInput)
            modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val count = (modelSpinner.adapter?.count ?: 0)
                    if (pos < count - 1) modelInput.setText(modelSpinner.getItemAtPosition(pos).toString())
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            c.addView(accentButton("🔄 อัปเดตรายชื่อโมเดล (ทุก AI)", "#34A853") { updateModelLists() })

            fallbackCheck = CheckBox(this).apply {
                text = "สลับโมเดล Gemini อัตโนมัติเมื่อโควต้าเต็ม (ใช้ฟรีได้นานขึ้น)"
                isChecked = prefs.getBoolean(KEY_FALLBACK, true)
                setPadding(0, dp(8), 0, 0)
            }
            c.addView(fallbackCheck)

            modelEngine = if (engineValue == "offline") "gemini" else engineValue
            populateModelCard(modelEngine)
        }

        // ---- card: font size ----
        card(root, "🔠 ขนาดตัวอักษรคำแปล", true) { c ->
            val sp = Spinner(this)
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, FONTS.map { it.first })
            sp.setSelection(FONTS.indexOfFirst { it.second == fontValue }.coerceAtLeast(1))
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { fontValue = FONTS[pos].second }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            c.addView(sp)
        }

        // ---- card: panel customize ----
        card(root, "🎨 หน้าตากล่องคำแปล", true) { c ->
            c.addView(hint("ธีมสี"))
            val spTheme = Spinner(this)
            spTheme.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, PANEL_THEMES.map { it.first })
            spTheme.setSelection(panelThemeValue.coerceIn(0, PANEL_THEMES.lastIndex))
            spTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { panelThemeValue = pos }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            c.addView(spTheme)
            c.addView(hint("ความทึบ"))
            val spAlpha = Spinner(this)
            spAlpha.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ALPHAS.map { it.first })
            spAlpha.setSelection(ALPHAS.indexOfFirst { it.second == panelAlphaValue }.coerceAtLeast(0))
            spAlpha.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { panelAlphaValue = ALPHAS[pos].second }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            c.addView(spAlpha)
        }

        // ---- card: TTS ----
        card(root, "🔊 อ่านออกเสียงคำแปล", true) { c ->
            ttsCheck = CheckBox(this).apply {
                text = "อ่านออกเสียงไทยอัตโนมัติเมื่อแปลเสร็จ"
                isChecked = prefs.getBoolean(KEY_TTS, false)
            }
            c.addView(ttsCheck)
            c.addView(hint("ความเร็วเสียง"))
            val spRate = Spinner(this)
            spRate.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, TTS_RATES.map { it.first })
            spRate.setSelection(TTS_RATES.indexOfFirst { it.second == ttsRateValue }.coerceAtLeast(1))
            spRate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { ttsRateValue = TTS_RATES[pos].second }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            c.addView(spRate)
        }

        root.addView(accentButton("💾 บันทึก", ACCENT) { saveSettings(); toast("บันทึกแล้ว") })
        root.addView(accentButton("▶️ เริ่มแปลหน้าจอ", "#34A853") { startFlow() })
        root.addView(accentButton("⏹️ หยุด", "#9CA3AF") {
            stopService(Intent(this@MainActivity, OverlayService::class.java)); toast("หยุดแล้ว")
        })

        // ---- card: maintenance ----
        card(root, "⚙️ อื่นๆ", true) { c ->
            c.addView(plainButton("🔄 ตรวจหาอัปเดต") { checkUpdate() })
            c.addView(plainButton("🔋 ปิดประหยัดแบต (กันแอปเด้ง)") { requestIgnoreBattery() })
            statusView = TextView(this).apply {
                setPadding(0, dp(8), 0, 0)
                setTextColor(Color.parseColor(ACCENT)); textSize = 13f
            }
            c.addView(statusView)
        }

        card(root, "❓ วิธีใช้", true) { c ->
            c.addView(TextView(this).apply {
                text = """
                    1. ขอ API Key ฟรี → วาง → บันทึก
                    2. เลือกเอนจิน/ภาษา (พับไว้ในการ์ดด้านบน)
                    3. กด "เริ่มแปลหน้าจอ" → อนุญาตสิทธิ์
                    4. ในเกม: แตะ 💬 เปิดเมนู → "▢ กรอบ" จัดกรอบ → "เสร็จ"
                    5. "แปล" = แปล 1 ครั้ง | "⚡ ออโต้" = แปลเอง | "💡 ถาม" = ผู้ช่วย AI
                """.trimIndent()
                setTextColor(colSub); textSize = 13f
            })
        }

        root.addView(scrollSpacerDummy())
        scroll.addView(root)
        setContentView(scroll)

        // ถูกเปิดจาก service เพราะการจับภาพหลุด → ขอสิทธิ์จับภาพใหม่อัตโนมัติ
        if (intent?.getBooleanExtra("autostart", false) == true) {
            scroll.post { startFlow() }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("autostart", false) == true) {
            window.decorView.post { startFlow() }
        }
    }

    // ---------------- UI helpers ----------------

    private fun card(parent: LinearLayout, title: String, collapsed: Boolean = false, build: (LinearLayout) -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(colCard); cornerRadius = dp(16).toFloat()
                if (isDark) setStroke(dp(1), Color.parseColor("#2C313C"))
            }
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val titleView = TextView(this).apply {
            textSize = 15f
            setTextColor(colText)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(2))
        }
        fun refresh() { titleView.text = (if (body.visibility == View.GONE) "▶  " else "▼  ") + title }
        titleView.setOnClickListener {
            body.visibility = if (body.visibility == View.GONE) View.VISIBLE else View.GONE
            refresh()
        }
        c.addView(titleView)
        build(body)
        c.addView(body)
        body.visibility = if (collapsed) View.GONE else View.VISIBLE
        refresh()
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        parent.addView(c, lp)
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t; textSize = 12f
        setTextColor(colSub); setPadding(0, 0, 0, dp(4))
    }

    private fun label2(t: String) = TextView(this).apply {
        text = t; textSize = 13f
        setTextColor(colText)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(10), 0, dp(2))
    }

    private fun linkText(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 12f
        setTextColor(Color.parseColor(ACCENT))
        setPadding(0, dp(3), 0, dp(2))
        setOnClickListener { onClick() }
    }

    private fun accentButton(text: String, colorHex: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        isAllCaps = false
        background = GradientDrawable().apply {
            setColor(Color.parseColor(colorHex)); cornerRadius = dp(14).toFloat()
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
        lp.topMargin = dp(8)
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    private fun plainButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(colText)
        background = GradientDrawable().apply {
            setColor(if (isDark) Color.parseColor("#2A2E38") else Color.parseColor("#E7EAF2"))
            cornerRadius = dp(12).toFloat()
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        lp.topMargin = dp(8)
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    private fun scrollSpacerDummy() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20))
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
    }

    // ---------------- โมเดลต่อเอนจิน ----------------

    private fun modelKey(engine: String) = when (engine) {
        "groq" -> KEY_MODEL_GROQ; "deepseek" -> KEY_MODEL_DEEPSEEK; else -> KEY_MODEL
    }
    private fun defaultModel(engine: String) = when (engine) {
        "groq" -> GROQ_MODEL; "deepseek" -> DEEPSEEK_MODEL; else -> DEFAULT_MODEL
    }
    private fun defaultModels(engine: String) = when (engine) {
        "groq" -> DEFAULT_MODELS_GROQ; "deepseek" -> DEFAULT_MODELS_DEEPSEEK; else -> DEFAULT_MODELS_GEMINI
    }
    private fun modelsCacheKey(engine: String) = when (engine) {
        "groq" -> KEY_MODELS_GROQ; "deepseek" -> KEY_MODELS_DEEPSEEK; else -> KEY_MODELS_GEMINI
    }
    private fun cachedModels(engine: String): List<String> {
        val s = prefs.getString(modelsCacheKey(engine), null)
        return s?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.ifEmpty { null } ?: defaultModels(engine)
    }

    private fun populateModelCard(engine: String) {
        val saved = prefs.getString(modelKey(engine), defaultModel(engine)).orEmpty().ifBlank { defaultModel(engine) }
        val models = cachedModels(engine).toMutableList()
        if (!models.contains(saved)) models.add(0, saved)
        models.add("อื่นๆ (พิมพ์เอง)")
        modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
        val idx = models.indexOf(saved)
        modelSpinner.setSelection(if (idx >= 0) idx else models.lastIndex)
        modelInput.setText(saved)
    }

    private fun updateModelLists() {
        toast("กำลังอัปเดตรายชื่อโมเดล…")
        val gemKey = keyInput.text.toString().trim()
        val groqKey = groqInput.text.toString().trim()
        val dsKey = deepseekInput.text.toString().trim()
        Thread {
            val gem = ModelsFetcher.listGemini(gemKey)
            val groq = ModelsFetcher.listOpenAI(GROQ_MODELS_URL, groqKey)
            val ds = ModelsFetcher.listOpenAI(DEEPSEEK_MODELS_URL, dsKey)
            runOnUiThread {
                val e = prefs.edit()
                var n = 0
                if (gem != null) { e.putString(KEY_MODELS_GEMINI, gem.joinToString(",")); n++ }
                if (groq != null) { e.putString(KEY_MODELS_GROQ, groq.joinToString(",")); n++ }
                if (ds != null) { e.putString(KEY_MODELS_DEEPSEEK, ds.joinToString(",")); n++ }
                e.apply()
                if (modelEngine != "offline") populateModelCard(modelEngine)
                toast(if (n > 0) "อัปเดตรายชื่อโมเดลแล้ว ($n ผู้ให้บริการ)" else "อัปเดตไม่สำเร็จ — เช็ค key/เน็ต")
            }
        }.start()
    }

    // ---------------- settings ----------------

    private fun saveSettings() {
        prefs.edit()
            .putString(KEY_API, keyInput.text.toString().trim())
            .putString(KEY_GROQ, groqInput.text.toString().trim())
            .putString(KEY_DEEPSEEK, deepseekInput.text.toString().trim())
            .putString(modelKey(modelEngine), modelInput.text.toString().trim().ifBlank { defaultModel(modelEngine) })
            .putString(KEY_GAME, gameInput.text.toString().trim())
            .putString(KEY_LANG, langValue)
            .putInt(KEY_FONT, fontValue)
            .putBoolean(KEY_FALLBACK, fallbackCheck.isChecked)
            .putString(KEY_ENGINE, engineValue)
            .putInt(KEY_PANEL_THEME, panelThemeValue)
            .putInt(KEY_PANEL_ALPHA, panelAlphaValue)
            .putBoolean(KEY_TTS, ttsCheck.isChecked)
            .putInt(KEY_TTS_RATE, ttsRateValue)
            .putBoolean(KEY_MEMORY, memoryCheck.isChecked)
            .apply()
    }

    // ---------------- start translate ----------------

    private fun startFlow() {
        saveSettings()
        val missingKey = when (engineValue) {
            "gemini" -> keyInput.text.toString().isBlank()
            "groq" -> groqInput.text.toString().isBlank()
            "deepseek" -> deepseekInput.text.toString().isBlank()
            else -> false
        }
        if (missingKey) {
            toast("ใส่ API Key ของเอนจินที่เลือกก่อน (หรือเปลี่ยนเป็นออฟไลน์)"); return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("อนุญาต \"แสดงบนแอปอื่น\" แล้วกดเริ่มอีกครั้ง")
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), REQ_OVERLAY
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        requestProjection()
    }

    private fun requestProjection() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) requestProjection()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION && resultCode == Activity.RESULT_OK && data != null) {
            val svc = Intent(this, OverlayService::class.java).apply {
                putExtra("code", resultCode); putExtra("data", data)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
            toast("เริ่มแล้ว! ใช้ปุ่มลอย แปล / ออโต้ / กรอบ ได้เลย")
            moveTaskToBack(true)
        }
    }

    // ---------------- battery ----------------

    private fun requestIgnoreBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---------------- update ----------------

    private fun checkUpdate() {
        status("กำลังตรวจหาอัปเดต…")
        Thread {
            val rel = Updater.fetchLatest()
            runOnUiThread {
                if (rel == null) { status("⚠️ ตรวจอัปเดตไม่สำเร็จ (เช็คเน็ต)"); return@runOnUiThread }
                if (rel.versionCode <= BuildConfig.VERSION_CODE) {
                    status("✅ เป็นเวอร์ชันล่าสุดแล้ว (${BuildConfig.VERSION_NAME})")
                } else {
                    status("พบเวอร์ชันใหม่ ${rel.versionName} กำลังดาวน์โหลด…")
                    doDownload(rel)
                }
            }
        }.start()
    }

    private fun doDownload(rel: Updater.Release) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            status("อนุญาต \"ติดตั้งแอปจากแหล่งนี้\" แล้วกดตรวจอัปเดตอีกครั้ง")
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
            return
        }
        Thread {
            val apk = File(getExternalFilesDir(null), "update.apk")
            val ok = Updater.download(rel.apkUrl, apk)
            runOnUiThread {
                if (!ok) { status("⚠️ ดาวน์โหลดไม่สำเร็จ"); return@runOnUiThread }
                status("ดาวน์โหลดเสร็จ กำลังเปิดตัวติดตั้ง…")
                installApk(apk)
            }
        }.start()
    }

    private fun installApk(apk: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            status("⚠️ เปิดตัวติดตั้งไม่ได้: ${e.message}")
        }
    }

    private fun status(s: String) { statusView.text = s }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
