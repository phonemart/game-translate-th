package com.thunder.gametranslate

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ไคลเอนต์สำหรับ API แบบ OpenAI-compatible (Groq, DeepSeek)
 * endpoint = chat/completions  ส่ง Bearer token
 */
object OpenAIClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun translate(baseUrl: String, apiKey: String, model: String, sourceText: String, gameContext: String, history: String = ""): String {
        if (apiKey.isBlank()) return "ERROR: ยังไม่ได้ใส่ API Key"
        if (sourceText.isBlank()) return "ERROR: ไม่พบข้อความบนหน้าจอ"

        val contextLine = if (gameContext.isBlank()) ""
        else "ข้อความนี้มาจากเกม \"$gameContext\" — ใช้ชื่อตัวละคร/สถานที่/ศัพท์เฉพาะ และโทนของเกมนี้ให้ถูกต้อง\n\n"

        val historyLine = if (history.isBlank()) ""
        else "บทพูดก่อนหน้า (ใช้เข้าใจเนื้อเรื่องต่อเนื่อง + ชื่อให้สม่ำเสมอ อย่าแปลซ้ำ):\n$history\n\n"

        val prompt = """
            คุณเป็นนักแปลเกมมืออาชีพ แปลข้อความต่อไปนี้ที่ดึงมาจากหน้าจอเกมให้เป็นภาษาไทย
            โดยแปลแบบเป็นธรรมชาติ ลื่นไหล เข้าใจบริบท เหมือนบทพากย์/คำบรรยายในเกมจริง
            ห้ามอธิบายเพิ่ม ตอบกลับมาเฉพาะคำแปลภาษาไทยของข้อความล่าสุดเท่านั้น

            $contextLine${historyLine}ข้อความ:
            $sourceText
        """.trimIndent()

        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.3)
            put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", prompt)
            ))
        }.toString()

        val request = Request.Builder()
            .url(baseUrl)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .post(body.toRequestBody(JSON))
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    if (resp.code == 429) return "QUOTA: ⏳ โควต้าเต็ม — รอสักครู่"
                    val msg = runCatching {
                        JSONObject(text).getJSONObject("error").getString("message")
                    }.getOrDefault(text.take(200))
                    return "ERROR: (${resp.code}) $msg"
                }
                parse(text)
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    private fun parse(json: String): String {
        return try {
            val choices = JSONObject(json).optJSONArray("choices")
                ?: return "ERROR: ไม่มีผลลัพธ์"
            if (choices.length() == 0) return "ERROR: ไม่มีคำแปลกลับมา"
            val content = choices.getJSONObject(0)
                .getJSONObject("message")
                .optString("content")
            content.trim().ifBlank { "ERROR: คำแปลว่างเปล่า" }
        } catch (e: Exception) {
            "ERROR: อ่านผลลัพธ์ไม่ได้ (${e.message})"
        }
    }
}
