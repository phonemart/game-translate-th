package com.thunder.gametranslate

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * แปลภาษาออฟไลน์ด้วย Google ML Kit (ฟรี ไม่ต้องใช้ API key ไม่มีโควต้า)
 * โหลดโมเดลภาษาครั้งแรก ~30MB แล้วใช้ได้แบบออฟไลน์ตลอด
 * ปลายทาง = ไทยเสมอ
 */
object OfflineTranslator {

    private val translators = HashMap<String, Translator>()

    private fun srcLang(lang: String): String = when (lang) {
        "ja" -> TranslateLanguage.JAPANESE
        "zh" -> TranslateLanguage.CHINESE
        "ko" -> TranslateLanguage.KOREAN
        else -> TranslateLanguage.ENGLISH
    }

    private fun get(lang: String): Translator = synchronized(translators) {
        translators.getOrPut(lang) {
            val opts = TranslatorOptions.Builder()
                .setSourceLanguage(srcLang(lang))
                .setTargetLanguage(TranslateLanguage.THAI)
                .build()
            Translation.getClient(opts)
        }
    }

    /** เรียกจาก background thread (IO) — บล็อกจนเสร็จ */
    fun translateBlocking(lang: String, text: String): String {
        return try {
            val t = get(lang)
            Tasks.await(t.downloadModelIfNeeded())   // โหลดโมเดลถ้ายังไม่มี (ครั้งแรกเท่านั้น)
            val out = Tasks.await(t.translate(text))
            out?.trim().orEmpty().ifBlank { "ERROR: แปลออฟไลน์ไม่ได้ผลลัพธ์" }
        } catch (e: Exception) {
            "ERROR: แปลออฟไลน์ไม่สำเร็จ (${e.message}) — ครั้งแรกต้องต่อเน็ตเพื่อโหลดโมเดล"
        }
    }

    fun close() {
        synchronized(translators) {
            translators.values.forEach { runCatching { it.close() } }
            translators.clear()
        }
    }
}
