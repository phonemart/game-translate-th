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
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText

    companion object {
        const val PREFS = "gt_prefs"
        const val KEY_API = "api_key"
        const val KEY_MODEL = "model"
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        private const val REQ_PROJECTION = 1001
        private const val REQ_OVERLAY = 1002
        private const val REQ_NOTIF = 1003
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
            text = "แปลข้อความในเกมเป็นไทยด้วย Gemini AI"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(20))
            setTextColor(Color.DKGRAY)
        })

        root.addView(label("Gemini API Key"))
        keyInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            hint = "วาง API Key จาก aistudio.google.com"
            setText(prefs.getString(KEY_API, ""))
        }
        root.addView(keyInput)

        root.addView(label("Model (ปกติไม่ต้องแก้)"))
        modelInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs.getString(KEY_MODEL, DEFAULT_MODEL))
        }
        root.addView(modelInput)

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

        root.addView(TextView(this).apply {
            text = """

                วิธีใช้:
                1. ขอ API Key ฟรีที่ aistudio.google.com → วางด้านบน → บันทึก
                2. กด "เริ่มแปลหน้าจอ" → อนุญาตสิทธิ์ทั้งหมด
                3. จะมีปุ่มลอย "แปล" ลอยอยู่บนจอ
                4. เปิดเกม เจอบทพูด → แตะปุ่ม "แปล"
                5. คำแปลไทยจะขึ้นด้านล่างจอ (แตะเพื่อปิด)

                เคล็ดลับ POCO/HyperOS:
                - เปิด Autostart + ไม่จำกัดแบตให้แอปนี้
                - อนุญาต "แสดงบนแอปอื่น"
            """.trimIndent()
            setPadding(0, dp(20), 0, 0)
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

    private fun saveSettings() {
        prefs.edit()
            .putString(KEY_API, keyInput.text.toString().trim())
            .putString(KEY_MODEL, modelInput.text.toString().trim().ifBlank { DEFAULT_MODEL })
            .apply()
    }

    private fun startFlow() {
        saveSettings()
        if (keyInput.text.toString().isBlank()) {
            toast("ใส่ Gemini API Key ก่อน")
            return
        }
        // 1) overlay permission
        if (!Settings.canDrawOverlays(this)) {
            toast("อนุญาต \"แสดงบนแอปอื่น\" แล้วกดเริ่มอีกครั้ง")
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
            return
        }
        // 2) notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        // 3) screen capture permission
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
            toast("เริ่มแล้ว! กดปุ่ม \"แปล\" ที่ลอยอยู่ได้เลย")
            moveTaskToBack(true)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
