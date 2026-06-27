package com.thunder.gametranslate

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Calls Google Gemini (Generative Language REST API) to translate game text
 * into natural Thai. Runs on a background thread (call from a coroutine).
 */
object GeminiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * @param gameContext ชื่อเกม/บริบทที่ผู้ใช้ระบุ (เว้นว่างได้) เพื่อช่วยให้แปลตรงบริบทเกม
     * @return translated Thai text, or a string beginning with "ERROR:" on failure.
     */
    fun translate(apiKey: String, model: String, sourceText: String, gameContext: String = "", history: String = ""): String {
        if (apiKey.isBlank()) return "ERROR: ยังไม่ได้ใส่ Gemini API Key"
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

        val genConfig = JSONObject().put("temperature", 0.3)
        // ปิดโหมด "คิด" (thinking) ในรุ่นที่รองรับ → ตอบเร็วขึ้นมาก เหมาะกับงานแปลสั้นๆ
        val m = model.lowercase()
        if (m.contains("2.5") || m.contains("flash-latest") || m.contains("latest")) {
            genConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        }

        val body = JSONObject().apply {
            put("contents", org.json.JSONArray().put(
                JSONObject().put("parts", org.json.JSONArray().put(
                    JSONObject().put("text", prompt)
                ))
            ))
            put("generationConfig", genConfig)
        }.toString()

        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                model.trim() + ":generateContent?key=" + apiKey.trim()

        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON))
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching {
                        JSONObject(text).getJSONObject("error").getString("message")
                    }.getOrDefault(text.take(200))
                    if (resp.code == 429) {
                        val sec = Regex("retry in ([0-9]+)").find(msg)?.groupValues?.get(1)
                        val wait = if (sec != null) "รออีก ~${sec}s" else "รอสักครู่"
                        // QUOTA: = ตัวเรียกสามารถสลับไปลองโมเดลอื่นได้
                        return "QUOTA: ⏳ โควต้าเต็ม — $wait"
                    }
                    return "ERROR: (${resp.code}) $msg"
                }
                parseTranslation(text)
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    private fun parseTranslation(json: String): String {
        return try {
            val candidates = JSONObject(json).optJSONArray("candidates")
                ?: return "ERROR: ไม่มีผลลัพธ์จาก Gemini"
            if (candidates.length() == 0) return "ERROR: Gemini ไม่ส่งคำแปลกลับมา"
            val parts = candidates.getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(parts.getJSONObject(i).optString("text"))
            }
            sb.toString().trim().ifBlank { "ERROR: คำแปลว่างเปล่า" }
        } catch (e: Exception) {
            "ERROR: อ่านผลลัพธ์ไม่ได้ (${e.message})"
        }
    }
}
