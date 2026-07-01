# 🎮 GameTranslate TH — เอกสารโปรเจกต์ (สำหรับอัปเดต/พัฒนาต่อ)

> แอป Android แปลข้อความในเกม emulator (PS1/GBA เช่น Pokémon, Final Fantasy) เป็นไทยแบบ real-time
> จับหน้าจอ → OCR → แปล (AI หรือออฟไลน์) → แสดง overlay บนจอ

- **Repo:** https://github.com/phonemart/game-translate-th (public)
- **Source local:** `/Users/ggez/game-translate-th/`
- **APK ล่าสุด:** https://github.com/phonemart/game-translate-th/releases/latest
- **Package:** `com.thunder.gametranslate`
- **สถานะ:** ใช้เล่นเอง ไม่ลง Play Store (คงการแจกผ่าน GitHub + ปุ่มอัปเดตในแอป)

---

## 📂 โครงสร้างไฟล์

| ไฟล์ | หน้าที่ |
|------|--------|
| `MainActivity.kt` | หน้าตั้งค่า (UI การ์ด), บันทึกค่า, ขอสิทธิ์, เริ่ม service, ปุ่มอัปเดตในแอป |
| `OverlayService.kt` | หัวใจหลัก — จับจอ, OCR, ปุ่มลอย, กรอบ, แปล, overlay คำแปล, ออโต้, TTS, จำบทสนทนา |
| `GeminiClient.kt` | เรียก Gemini API (REST generateContent) |
| `OpenAIClient.kt` | เรียก Groq + DeepSeek (OpenAI-compatible chat/completions) |
| `OfflineTranslator.kt` | แปลออฟไลน์ ML Kit (ไม่ต้องใช้ key) |
| `Updater.kt` | เช็ค+โหลด APK ใหม่จาก GitHub releases (ปุ่มอัปเดตในแอป) |
| `.github/workflows/build.yml` | GitHub Actions build APK + ออก release อัตโนมัติ |
| `gt.p12` | กุญแจเซ็น APK คงที่ (PKCS12) — **ห้ามลบ** |

---

## ✨ ฟีเจอร์ทั้งหมด

- **4 เอนจินแปล:** 🤖 Gemini AI / ⚡ Groq (Llama 3.3 70B) / 🐳 DeepSeek / 📴 ออฟไลน์ ML Kit
- **โหมดแปล:** กดทีละครั้ง ("แปล") หรือ ⚡ ออโต้ (ตรวจจับบทพูดเปลี่ยน แปลเอง)
- **กรอบเลือกพื้นที่:** จัดกรอบครอบกล่องบทพูด (เก็บเป็นสัดส่วนของจอ รองรับหมุนจอ)
- **🌏 4 ภาษาต้นทาง:** อังกฤษ/โรมัน, ญี่ปุ่น, จีน, เกาหลี → ไทย
- **🧠 จำบทสนทนา:** ส่งบทพูด 3 บรรทัดก่อนหน้าให้ AI เข้าใจเนื้อเรื่องต่อเนื่อง + ชื่อสม่ำเสมอ
- **🔄 สลับโมเดล Gemini อัตโนมัติ** เมื่อโควต้าเต็ม
- **💾 แคชคำแปล** (LRU 80) — กดซ้ำไม่ยิง API
- **🔍 OCR ขยายภาพ 3x** ก่อนอ่าน → ฟอนต์ pixel เกมเก่าแม่นขึ้น
- **🔊 อ่านออกเสียงไทย (TTS)** — Google TTS ในตัว, ปรับความเร็วได้
- **🎨 ปรับแต่งกล่องคำแปล** — ธีมสี + ความทึบ + ขนาดฟอนต์
- **📏 ย่อแถบลอย** (แตะ ≡)
- **🔄 อัปเดตในแอป** (โหลด APK ใหม่จาก GitHub มาติดตั้งทับ)
- **🔋 ปุ่มปิดประหยัดแบต** กันแอปถูกระบบฆ่า

---

## ⚙️ ค่าตั้งค่า (SharedPreferences keys) — ใน `MainActivity` companion

| Key | ความหมาย | default |
|-----|----------|---------|
| `api_key` | Gemini API key | "" |
| `groq_key` / `deepseek_key` | key Groq / DeepSeek | "" |
| `engine` | เอนจิน: gemini/groq/deepseek/offline | gemini |
| `model` | โมเดล Gemini | gemini-2.5-flash-lite |
| `game` | ชื่อเกม (บริบท) | "" |
| `lang` | ภาษาต้นทาง: latin/ja/zh/ko | latin |
| `font` | ขนาดฟอนต์คำแปล | 18 |
| `panel_theme` / `panel_alpha` | ธีมสี / ความทึบกล่อง | 0 / 100 |
| `tts` / `tts_rate` | อ่านออกเสียง / ความเร็ว | false / 100 |
| `fallback` | สลับโมเดล Gemini อัตโนมัติ | true |
| `memory` | จำบทสนทนา | true |
| `region` | กรอบ (fx,fy,fw,fh สัดส่วน) | 0.15,0.70,0.70,0.24 |

### เอนจิน endpoints/models (แก้ได้ใน `MainActivity` companion)
- Groq: `https://api.groq.com/openai/v1/chat/completions` · model `llama-3.3-70b-versatile`
- DeepSeek: `https://api.deepseek.com/chat/completions` · model `deepseek-chat`
- Gemini fallback: `gemini-2.5-flash-lite → gemini-2.5-flash → gemini-2.0-flash`

---

## 🛠️ Build & Deploy

**ทุกครั้งที่ push ขึ้น `main`** → GitHub Actions build APK + ออก release `v1.0.<run_number>` อัตโนมัติ

```bash
# แก้โค้ดใน /Users/ggez/game-translate-th/ แล้ว:
git add -A
git commit -m "..."
git push origin main
# Actions build ~2 นาที → APK ที่ releases/latest → ผู้ใช้กด "ตรวจหาอัปเดต" ในแอป
```

- `versionCode` = `GITHUB_RUN_NUMBER` (build.gradle อ่าน env) → release tag = `v1.0.<n>` ใช้เทียบเวอร์ชันตอนอัปเดต
- เซ็นด้วยกุญแจคงที่ `gt.p12` (storePass/keyPass `gametranslate`, alias `gt`) → อัปเดตทับได้
- APK ~34MB (จำกัด `abiFilters 'arm64-v8a'`)

### ⚠️ กับดัก/ข้อจำกัด (สำคัญ)
1. **แก้ไฟล์ `.github/workflows/*` จาก CLI ไม่ได้** — token (gr289514-max) ไม่มี `workflow` scope (org `phonemart` ล็อก) → ต้องแก้ workflow ผ่านเว็บ GitHub UI เท่านั้น. โค้ดอื่น push ปกติ
2. **ห้ามลบ `gt.p12`** — ไม่งั้นลายเซ็นเปลี่ยน อัปเดตทับไม่ได้ ("แพ็กเกจขัดแย้ง" ต้องถอนลงใหม่)
3. **เปลี่ยนกุญแจ/applicationId** = ผู้ใช้ต้องถอนแล้วลงใหม่ 1 ครั้ง

---

## 🔑 Gemini Free Tier (ข้อมูล มิ.ย. 2026)
- `gemini-2.5-flash`: 5 RPM / **20 ครั้งต่อวัน (RPD)** ต่อโมเดล
- `gemini-2.5-flash-lite`: 10 RPM / 20 RPD
- ใช้จริง → ควรเปิด **billing** (บัตรเครดิต/เดบิตธนาคารจริง — **TrueWallet/prepaid ใช้ไม่ได้** error OR_CCR_104)
- flash-lite paid ถูกมาก (~สตางค์/พันครั้ง)
- **Groq** = ทางเลือกฟรีที่โควต้าเยอะกว่า Gemini มาก (แนะนำเวลา Gemini หมด)

---

## 🔧 วิธีเพิ่ม/แก้ฟีเจอร์ (แนวทาง)

- **เพิ่มเอนจิน AI ใหม่** (OpenAI-compatible) → เพิ่ม URL/MODEL/KEY ใน `MainActivity` + branch ใน `OverlayService.translate()` เรียก `OpenAIClient.translate(...)` + ช่อง key ในการ์ด "API Keys" + ตัวเลือกใน `ENGINES`
- **เพิ่มภาษา OCR** → เพิ่ม dependency `text-recognition-xxx` + case ใน `getRecognizer()` + ตัวเลือกใน `LANGS`
- **เพิ่มตั้งค่าใหม่** → เพิ่ม KEY ใน companion + field + การ์ด UI + `saveSettings()` + อ่านค่าใน `OverlayService`
- **ปรับ prompt แปล** → `GeminiClient.kt` + `OpenAIClient.kt` (prompt เหมือนกัน)

---

## 📜 Changelog

| เวอร์ชัน | สรุป |
|---------|------|
| v1.0.1 | เวอร์ชันแรก: จับจอ + OCR + Gemini + ปุ่มลอย "แปล" |
| v1.0.2–3 | กรอบเลือกพื้นที่, ปุ่มอัปเดตในแอป, dropdown โมเดล, ปุ่มปิดประหยัดแบต, ช่องชื่อเกม |
| v1.0.4 | แก้ "แปลได้ครั้งเดียว" (จับเฟรมต่อเนื่อง) + throttle |
| v1.0.5 | กุญแจเซ็นคงที่ `gt.p12` → อัปเดตทับได้ |
| v1.0.6 | แก้กรอบ OCR ตอนหมุนจอ (ขนาดจอจริง) + คำแปลหายเอง + ปิด thinking (เร็วขึ้น) |
| v1.0.7 | กล่องคำแปลขาวสวยที่กรอบ แตะปิด ไม่มีกรอบค้าง |
| v1.0.8 | แคชคำแปล + ข้อความ 429 อ่านง่าย |
| v1.0.9 | โหมดออโต้, OCR ญี่ปุ่น/จีน/เกาหลี, สลับโมเดล, UI พรีเมียม, ขนาดฟอนต์ |
| v1.0.10 | เอนจินออฟไลน์ ML Kit (ไม่ต้องใช้ key) |
| v1.0.11 | ย่อแถบ, ลดขนาดแอป 106→34MB, ปรับแต่งกล่อง, TTS อ่านออกเสียง |
| v1.0.12 | OCR ขยายภาพ 3x (ฟอนต์ pixel แม่นขึ้น) |
| v1.0.13 | เพิ่มเอนจิน Groq + DeepSeek |
| v1.0.14 | โหมดจำบทสนทนา (ส่งบทก่อนหน้าให้ AI) |
| v1.0.16 | โหมดมืด + UI แบนเนอร์ไล่สี + โลโก้ใหม่ (adaptive icon) |
| v1.0.17 | เลือกโมเดลแยกตามเอนจิน + ปุ่มอัปเดตรายชื่อโมเดล (ModelsFetcher) |
| v1.0.18 | **ผู้ช่วย AI ในเกม** — ปุ่ม 💡 ถาม + chips → Gemini vision (ส่งภาพ) / Groq+DeepSeek text (OCR) → กล่องคำตอบ + TTS |
| v1.0.19-21 | แถบลอยกะทัดรัด + ย่อเป็นจุด 💬 (แปลโชว์ตลอด) + พิมพ์คำถามเองในผู้ช่วย |
| v1.0.22 | หน้าตั้งค่าการ์ดพับได้ (สั้นลง) |
| v1.0.23 | แก้บั๊กหมุนจอแล้ว MediaProjection หลุด (self-heal + auto re-grant) |
| v1.0.24 | **โหมด 🗨️ คุยกับ AI** (ไม่แคปจอ) — chat multi-turn ทุกเอนจิน |

---

## 💡 ไอเดียที่ยังไม่ได้ทำ (เผื่ออัปเดตต่อ)
- 🤖 **ผู้ช่วย AI ต่อยอด:** พิมพ์คำถามเอง (focusable overlay window, เลี่ยง FLAG_ALT_FOCUSABLE_IM), สั่งด้วยเสียง (RECORD_AUDIO + FOREGROUND_SERVICE_MICROPHONE + service type), chat คุยต่อเนื่อง
- 🔊 **TTS เลือกเสียง + pitch** (voice picker จาก tts.voices กรอง locale th) — ผู้ใช้เคยบ่นเสียงไม่เป็นธรรมชาติ
- 📜 **ประวัติคำแปล** — UI เลื่อนย้อนอ่านบทที่ผ่านมา
- 🎯 **โปรไฟล์เกม** — จำกรอบ/ภาษา/เอนจิน แยกแต่ละเกม สลับเกมโหลดเอง
- 📚 **Glossary** — กำหนดคำแปลชื่อตัวละครเองให้สม่ำเสมอ
- 📋 คัดลอกคำแปล (แตะค้าง), 🌏 สลับภาษาจากปุ่มลอย

---

## 🗣️ วิธีสั่งงาน (เวลาจะอัปเดต)
ส่งไฟล์นี้ให้ + บอกว่าอยากเพิ่ม/แก้อะไร เช่น "เพิ่มประวัติคำแปล" / "เพิ่มเอนจิน Claude" / "แก้บั๊ก ..."
