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

    /**
     * ผู้ช่วย AI โหมดข้อความ — ส่ง "ข้อความที่ OCR จากจอ" + คำถาม (สำหรับ Groq/DeepSeek ที่ไม่มี vision)
     */
    fun ask(baseUrl: String, apiKey: String, model: String, question: String, gameContext: String, screenText: String): String {
        if (apiKey.isBlank()) return "ERROR: ยังไม่ได้ใส่ API Key"
        val ctx = if (gameContext.isBlank()) "" else "ผู้เล่นกำลังเล่นเกม \"$gameContext\".\n"
        val prompt = """
            คุณเป็นผู้ช่วยเล่นเกม ผู้เล่นส่ง "ข้อความที่อ่านได้จากหน้าจอเกม" + คำถามมาให้
            ตอบเป็นภาษาไทย สั้น กระชับ ตรงประเด็น อิงจากข้อความบนจอ ถ้าข้อมูลไม่พอให้บอกตรงๆ ห้ามเดามั่ว
            ${ctx}
            ข้อความบนจอ:
            ${screenText.ifBlank { "(อ่านข้อความไม่ได้)" }}

            คำถาม: $question
        """.trimIndent()

        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.4)
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

    /** โหมดคุยเล่น (chat) สำหรับ Groq/DeepSeek */
    fun chat(baseUrl: String, apiKey: String, model: String, turns: List<Pair<String, String>>): String {
        if (apiKey.isBlank()) return "ERROR: ยังไม่ได้ใส่ API Key"
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system")
            .put("content", "คุณเป็นผู้ช่วย AI ที่เป็นมิตร คุยกับผู้ใช้เป็นภาษาไทย ตอบสั้นกระชับ เป็นธรรมชาติ ช่วยได้ทุกเรื่องรวมถึงเรื่องเกม"))
        for ((role, text) in turns) {
            messages.put(JSONObject().put("role", if (role == "user") "user" else "assistant").put("content", text))
        }
        val body = JSONObject().apply {
            put("model", model); put("temperature", 0.7); put("messages", messages)
        }.toString()
        val request = Request.Builder().url(baseUrl)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .post(body.toRequestBody(JSON)).build()
        return try {
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    if (resp.code == 429) return "QUOTA: ⏳ โควต้าเต็ม"
                    val msg = runCatching {
                        JSONObject(text).getJSONObject("error").getString("message")
                    }.getOrDefault(text.take(200))
                    return "ERROR: (${resp.code}) $msg"
                }
                parse(text)
            }
        } catch (e: Exception) { "ERROR: ${e.message}" }
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
