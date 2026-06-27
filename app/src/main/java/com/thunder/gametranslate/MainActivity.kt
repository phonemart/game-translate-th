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
    private lateinit var modelInput: EditText
    private lateinit var gameInput: EditText
    private lateinit var fallbackCheck: CheckBox
    private lateinit var statusView: TextView
    private var langValue = "latin"
    private var fontValue = 18

    companion object {
        const val PREFS = "gt_prefs"
        const val KEY_API = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_GAME = "game"
        const val KEY_LANG = "lang"
        const val KEY_FONT = "font"
        const val KEY_FALLBACK = "fallback"
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
        const val ACCENT = "#667EEA"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        langValue = prefs.getString(KEY_LANG, "latin").orEmpty().ifBlank { "latin" }
        fontValue = prefs.getInt(KEY_FONT, 18)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#EEF0F6")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }

        // ---- header ----
        root.addView(TextView(this).apply {
            text = "🎮 GameTranslate TH"
            textSize = 26f
            setTextColor(Color.parseColor(ACCENT))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "แปลข้อความในเกมเป็นไทยด้วย Gemini AI  •  v${BuildConfig.VERSION_NAME}"
            textSize = 13f
            setPadding(0, dp(4), 0, dp(16))
            setTextColor(Color.parseColor("#6B7280"))
        })

        // ---- card: API key ----
        card(root, "🔑 Gemini API Key") { c ->
            keyInput = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                hint = "วาง API Key ที่นี่"
                setText(prefs.getString(KEY_API, ""))
            }
            c.addView(keyInput)
            c.addView(accentButton("🌐 ขอ API Key ฟรี (เปิดเว็บ)", "#34A853") {
                openUrl("https://aistudio.google.com/apikey")
            })
        }

        // ---- card: game ----
        card(root, "🎯 ชื่อเกมที่กำลังเล่น") { c ->
            c.addView(hint("ช่วยให้ AI แปลตรงบริบท (ชื่อตัวละคร/ศัพท์เฉพาะ)"))
            gameInput = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                hint = "เช่น Pokemon FireRed, Final Fantasy VII"
                setText(prefs.getString(KEY_GAME, ""))
            }
            c.addView(gameInput)
        }

        // ---- card: language ----
        card(root, "🌏 ภาษาในเกม (ภาษาต้นทาง)") { c ->
            val sp = Spinner(this)
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, LANGS.map { it.first })
            sp.setSelection(LANGS.indexOfFirst { it.second == langValue }.coerceAtLeast(0))
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { langValue = LANGS[pos].second }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            c.addView(sp)
        }

        // ---- card: model ----
        card(root, "🤖 โมเดล Gemini") { c ->
            val savedModel = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
            val sp = Spinner(this)
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, MODELS)
            val idx = MODELS.indexOf(savedModel)
            sp.setSelection(if (idx >= 0) idx else MODELS.lastIndex)
            c.addView(sp)
            modelInput = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                setText(savedModel)
            }
            c.addView(modelInput)
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (pos < MODELS.lastIndex) modelInput.setText(MODELS[pos])
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            fallbackCheck = CheckBox(this).apply {
                text = "สลับโมเดลอัตโนมัติเมื่อโควต้าเต็ม (ใช้ฟรีได้นานขึ้น)"
                isChecked = prefs.getBoolean(KEY_FALLBACK, true)
                setPadding(0, dp(8), 0, 0)
            }
            c.addView(fallbackCheck)
        }

        // ---- card: font size ----
        card(root, "🔠 ขนาดตัวอักษรคำแปล") { c ->
            val sp = Spinner(this)
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, FONTS.map { it.first })
            sp.setSelection(FONTS.indexOfFirst { it.second == fontValue }.coerceAtLeast(1))
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { fontValue = FONTS[pos].second }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            c.addView(sp)
        }

        root.addView(accentButton("💾 บันทึก", ACCENT) { saveSettings(); toast("บันทึกแล้ว") })
        root.addView(accentButton("▶️ เริ่มแปลหน้าจอ", "#34A853") { startFlow() })
        root.addView(accentButton("⏹️ หยุด", "#9CA3AF") {
            stopService(Intent(this@MainActivity, OverlayService::class.java)); toast("หยุดแล้ว")
        })

        // ---- card: maintenance ----
        card(root, "⚙️ อื่นๆ") { c ->
            c.addView(plainButton("🔄 ตรวจหาอัปเดต") { checkUpdate() })
            c.addView(plainButton("🔋 ปิดประหยัดแบต (กันแอปเด้ง)") { requestIgnoreBattery() })
            statusView = TextView(this).apply {
                setPadding(0, dp(8), 0, 0)
                setTextColor(Color.parseColor(ACCENT)); textSize = 13f
            }
            c.addView(statusView)
        }

        root.addView(TextView(this).apply {
            text = """
                วิธีใช้:
                1. ขอ API Key ฟรี → วาง → บันทึก
                2. เลือกภาษาในเกม + โมเดล + ขนาดฟอนต์
                3. กด "เริ่มแปลหน้าจอ" → อนุญาตสิทธิ์
                4. ในเกม: กด "▢ กรอบ" จัดกรอบครอบบทพูด → "เสร็จ"
                5. กด "แปล" = แปล 1 ครั้ง  |  "⚡ ออโต้" = แปลเองทุกบทพูด
            """.trimIndent()
            setPadding(dp(4), dp(18), dp(4), 0)
            setTextColor(Color.parseColor("#6B7280")); textSize = 13f
        })

        root.addView(scrollSpacerDummy())
        scroll.addView(root)
        setContentView(scroll)
    }

    // ---------------- UI helpers ----------------

    private fun card(parent: LinearLayout, title: String, build: (LinearLayout) -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        c.addView(TextView(this).apply {
            text = title; textSize = 15f
            setTextColor(Color.parseColor("#15151F"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        build(c)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(14)
        parent.addView(c, lp)
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t; textSize = 12f
        setTextColor(Color.parseColor("#9CA3AF")); setPadding(0, 0, 0, dp(4))
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
        setTextColor(Color.parseColor("#15151F"))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#EEF0F6")); cornerRadius = dp(12).toFloat()
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

    // ---------------- settings ----------------

    private fun saveSettings() {
        prefs.edit()
            .putString(KEY_API, keyInput.text.toString().trim())
            .putString(KEY_MODEL, modelInput.text.toString().trim().ifBlank { DEFAULT_MODEL })
            .putString(KEY_GAME, gameInput.text.toString().trim())
            .putString(KEY_LANG, langValue)
            .putInt(KEY_FONT, fontValue)
            .putBoolean(KEY_FALLBACK, fallbackCheck.isChecked)
            .apply()
    }

    // ---------------- start translate ----------------

    private fun startFlow() {
        saveSettings()
        if (keyInput.text.toString().isBlank()) { toast("ใส่ Gemini API Key ก่อน"); return }
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
