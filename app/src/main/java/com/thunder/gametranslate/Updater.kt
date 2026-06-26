package com.thunder.gametranslate

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * เช็ค + โหลด APK เวอร์ชันใหม่จาก GitHub Releases ของ repo นี้
 * tag รูปแบบ "v1.0.<run_number>" → เลขท้าย = versionCode
 */
object Updater {

    const val REPO = "phonemart/game-translate-th"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    data class Release(val versionCode: Int, val versionName: String, val apkUrl: String)

    /** @return ข้อมูล release ล่าสุด หรือ null ถ้าดึงไม่ได้ */
    fun fetchLatest(): Release? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string().orEmpty())
                val tag = json.optString("tag_name") // v1.0.N
                val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
                val assets = json.optJSONArray("assets") ?: return null
                var apk = ""
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        apk = a.optString("browser_download_url"); break
                    }
                }
                if (apk.isBlank()) return null
                Release(code, tag, apk)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** ดาวน์โหลด APK ลงไฟล์ปลายทาง @return true ถ้าสำเร็จ */
    fun download(url: String, dest: File): Boolean {
        val req = Request.Builder().url(url).build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body ?: return false
                dest.outputStream().use { out -> body.byteStream().copyTo(out) }
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
