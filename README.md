# 🎮 GameTranslate TH

แอป Android แปลข้อความในเกม (PS1 / GBA emulator เช่น Pokemon, Final Fantasy) **เป็นภาษาไทยแบบ real-time** ด้วย **Gemini AI** ซ้อนทับบนหน้าจอ

## ทำงานยังไง
จับภาพหน้าจอ → OCR อ่านตัวอักษร (Google ML Kit, ทำงานในเครื่อง) → ส่งให้ **Gemini แปลไทยแบบเป็นธรรมชาติ** → แสดง overlay ด้านล่างจอ

## วิธีติดตั้ง
1. ไปที่หน้า **Releases** ของ repo นี้ → โหลดไฟล์ `app-debug.apk` ลงมือถือ
2. เปิดไฟล์ → อนุญาต "ติดตั้งจากแหล่งที่ไม่รู้จัก" → ติดตั้ง
3. เปิดแอป → ใส่ **Gemini API Key** (ขอฟรีที่ https://aistudio.google.com) → บันทึก
4. กด "เริ่มแปลหน้าจอ" → อนุญาตสิทธิ์ทั้งหมด
5. เปิดเกม → เจอบทพูด → แตะปุ่มลอย **"แปล"** → คำแปลไทยขึ้นด้านล่าง

## สิทธิ์ที่ใช้
- แสดงบนแอปอื่น (overlay), บันทึกหน้าจอ (MediaProjection), อินเทอร์เน็ต, การแจ้งเตือน

## เคล็ดลับ POCO X6 Pro (HyperOS/MIUI)
ตั้งค่า → แอป → GameTranslate TH → เปิด **Autostart** + ตั้งประหยัดแบตเป็น **ไม่จำกัด**

## Build เอง
GitHub Actions จะ build APK อัตโนมัติทุกครั้งที่ push (ดู `.github/workflows/build.yml`)
