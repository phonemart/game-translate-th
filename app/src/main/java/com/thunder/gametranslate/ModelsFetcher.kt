package com.thunder.gametranslate

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** ดึงรายชื่อโมเดลล่าสุดจาก provider ต่างๆ */
object ModelsFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Gemini: GET v1beta/models — กรองเฉพาะตัวที่ generateContent ได้ */
    fun listGemini(apiKey: String): List<String>? {
        if (apiKey.isBlank()) return null
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200&key=${apiKey.trim()}")
            .build()
        return try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("models") ?: return null
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val name = m.optString("name").removePrefix("models/")
                    if (!name.contains("gemini")) continue
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    var canGen = false
                    if (methods != null) for (j in 0 until methods.length())
                        if (methods.optString(j) == "generateContent") canGen = true
                    if (canGen) out.add(name)
                }
                out.distinct().ifEmpty { null }
            }
        } catch (e: Exception) { null }
    }

    /** OpenAI-compatible (Groq / DeepSeek): GET /models → data[].id */
    fun listOpenAI(url: String, apiKey: String): List<String>? {
        if (apiKey.isBlank()) return null
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .build()
        return try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("data") ?: return null
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val id = arr.getJSONObject(i).optString("id")
                    if (id.isBlank()) continue
                    val low = id.lowercase()
                    // ตัดโมเดลที่แปลข้อความไม่ได้ออก (เสียง/มอดิเรชัน)
                    if (low.contains("whisper") || low.contains("tts") || low.contains("guard")) continue
                    out.add(id)
                }
                out.distinct().sorted().ifEmpty { null }
            }
        } catch (e: Exception) { null }
    }
}
