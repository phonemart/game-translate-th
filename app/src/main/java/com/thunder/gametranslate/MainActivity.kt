package com.thunder.gametranslate

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
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
    private lateinit var statusView: TextView

    companion object {
        const val PREFS = "gt_prefs"
        const val KEY_API = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_GAME = "game"
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        private const val REQ_PROJECTION = 1001
        private const val REQ_OVERLAY = 1002
        private const val REQ_NOTIF = 1003

        val MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-flash-latest",
            "gemini-2.5-flash-lite",
            "gemini-2.0-flash",
            "gemini-3-flash-preview",
            "อื่นๆ (พิมพ์เอง)"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "🎮 GameTranslate TH"
            textSize = 24f
            setTextColor(Color.parseColor("#667EEA"))
        })
        root.addView(TextView(this).apply {
            text = "แปลข้อความในเกมเป็นไทยด้วย Gemini AI  •  v${BuildConfig.VERSION_NAME}"
            textSize = 13f
            setPadding(0, dp(4), 0, dp(16))
            setTextColor(Color.DKGRAY)
        })

        // ---- API key ----
        root.addView(label("Gemini API Key"))
        keyInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            hint = "วาง API Key จาก aistudio.google.com"
            setText(prefs.getString(KEY_API, ""))
        }
        root.addView(keyInput)

        // ---- game / context ----
        root.addView(label("ชื่อเกมที่กำลังเล่น (ช่วยให้แปลตรงบริบท)"))
        gameInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "เช่น Pokemon FireRed, Final Fantasy VII, Persona 5"
            setText(prefs.getString(KEY_GAME, ""))
        }
        root.addView(gameInput)

        // ---- model dropdown ----
        root.addView(label("เลือกโมเดล"))
        val savedModel = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, MODELS)
        val presetIdx = MODELS.indexOf(savedModel)
        spinner.setSelection(if (presetIdx >= 0) presetIdx else MODELS.lastIndex)
        root.addView(spinner)

        modelInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(savedModel)
        }
        root.addView(modelInput)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos < MODELS.lastIndex) modelInput.setText(MODELS[pos]) // ไม่ใช่ "อื่นๆ"
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        root.addView(Button(this).apply {
            text = "💾 บันทึก"
            setOnClickListener { saveSettings(); toast("บันทึกแล้ว") }
        })

        root.addView(Button(this).apply {
            text = "▶️ เริ่มแปลหน้าจอ"
            setOnClickListener { startFlow() }
        })

        root.addView(Button(this).apply {
            text = "⏹️ หยุด"
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                toast("หยุดแล้ว")
            }
        })

        // ---- update + battery ----
        root.addView(divider())

        root.addView(Button(this).apply {
            text = "🔄 ตรวจหาอัปเดต"
            setOnClickListener { checkUpdate() }
        })

        root.addView(Button(this).apply {
            text = "🔋 ปิดประหยัดแบต (กันแอปเด้ง)"
            setOnClickListener { requestIgnoreBattery() }
        })

        statusView = TextView(this).apply {
            setPadding(0, dp(8), 0, 0)
            setTextColor(Color.parseColor("#667EEA"))
            textSize = 13f
        }
        root.addView(statusView)

        // ---- help ----
        root.addView(TextView(this).apply {
            text = """

                วิธีใช้:
                1. ขอ API Key ฟรีที่ aistudio.google.com → วาง → บันทึก
                2. กด "เริ่มแปลหน้าจอ" → อนุญาตสิทธิ์ทั้งหมด
                3. ปุ่มลอยจะมี: "แปล" และ "▢ กรอบ"
                4. กด "▢ กรอบ" เพื่อแสดงกรอบสีแดง → ลากย้าย/ลากมุมขวาล่างปรับขนาด ครอบกล่องบทพูด
                5. กด "แปล" → แปลเฉพาะในกรอบ (ถ้าไม่เปิดกรอบ = แปลทั้งจอ)

                แก้แอปเด้งออก (POCO/HyperOS):
                - กด "🔋 ปิดประหยัดแบต" ด้านบน
                - ตั้งค่า → แอป → GameTranslate TH → เปิด Autostart
            """.trimIndent()
            setPadding(0, dp(16), 0, 0)
            setTextColor(Color.DKGRAY)
            textSize = 13f
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        setPadding(0, dp(14), 0, dp(2))
        setTextColor(Color.BLACK)
    }

    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(20); bottomMargin = dp(8)
        }
        setBackgroundColor(Color.LTGRAY)
    }

    private fun saveSettings() {
        prefs.edit()
            .putString(KEY_API, keyInput.text.toString().trim())
            .putString(KEY_MODEL, modelInput.text.toString().trim().ifBlank { DEFAULT_MODEL })
            .putString(KEY_GAME, gameInput.text.toString().trim())
            .apply()
    }

    // ---------------- start translate ----------------

    private fun startFlow() {
        saveSettings()
        if (keyInput.text.toString().isBlank()) {
            toast("ใส่ Gemini API Key ก่อน")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("อนุญาต \"แสดงบนแอปอื่น\" แล้วกดเริ่มอีกครั้ง")
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY
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

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) requestProjection()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION && resultCode == Activity.RESULT_OK && data != null) {
            val svc = Intent(this, OverlayService::class.java).apply {
                putExtra("code", resultCode)
                putExtra("data", data)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
            toast("เริ่มแล้ว! กดปุ่ม \"แปล\" / \"กรอบ\" ที่ลอยอยู่ได้เลย")
            moveTaskToBack(true)
        }
    }

    // ---------------- battery ----------------

    private fun requestIgnoreBattery() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
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
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
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
