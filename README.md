# V2m - Advanced Android Proxy Client 🚀

[🇮🇷 توضیحات فارسی ](#-توضیحات-فارسی)

V2m is a highly customized and advanced version of the popular [v2rayNG](https://github.com/2dust/v2rayNG) Android client, specifically engineered to bypass severe network censorship and DPI (Deep Packet Inspection) systems.

This project is powered by a custom-modified **Xray-core**, developed and maintained by [@patterniha](https://github.com/patterniha). 

## ✨ Key Features & Improvements

*   **Advanced Fragmentation:** Built-in support for TCP/TLS fragmentation (finalmask) to effectively bypass SNI blocking and strict DPIs.
*   **Unsafe Fingerprint Support:** Fully supports the `unsafe` uTLS fingerprint for maximum camouflage and bypassing client hello analysis.
*   **Internal MitM (Man-in-the-Middle):** Innovative internal local tunnel for traffic decryption, repackaging, and re-routing.
*   **Cloudflare Upload Speed Fix:** Successfully resolved the notorious upload speed drop/stall issue for **VLESS + WebSocket (WS)** configurations routed behind Cloudflare CDNs.
*   **Smart Routing:** Intelligent injection of core settings without breaking default VPN policies (Per-App proxy, Local DNS, etc. remain fully functional).
*   **Modern UI:** Rebuilt and customized User Interface for a better, smoother experience.

*   Modified Xray-core Engine: [Patterniha](https://github.com/patterniha).
*   Powered by Project X (Xray-core).

---

# 🇮🇷 توضیحات فارسی

برنامه **V2m** یک نسخه کاملاً شخصی‌سازی شده و پیشرفته از کلاینت محبوب [v2rayNG](https://github.com/2dust/v2rayNG) برای سیستم‌عامل اندروید است. این نسخه با هدف عبور از فیلترینگ شدید و سیستم‌های بازرسی عمیق بسته‌ها (DPI) بهینه‌سازی شده است.

موتور محرک این برنامه، نسخه اصلاح‌شده‌ای از **Xray-core** است که توسط  [@patterniha](https://github.com/patterniha) توسعه یافته است.

## ✨ قابلیت‌ها و تغییرات کلیدی

*   **پشتیبانی از Fragment (قطعه‌بندی):** دارای سیستم داخلی فرگمنت برای دور زدن فیلترینگ SNI و عبور از فایروال‌های پیشرفته.
*   **فینگرپرینت Unsafe:** پشتیبانی از فینگرپرینت `unsafe` جهت مخفی‌سازی بهتر ترافیک و شبیه‌سازی دقیق‌تر کلاینت‌ها.
*   **پشتیبانی از MitM:** دارای تونل محلی داخلی برای رمزگشایی، دستکاری و بسته‌بندی مجدد ترافیک (Man-in-the-Middle).
*   **حل مشکل سرعت آپلود:** رفع مشکل  افت سرعت و قطعی آپلود در کانفیگ‌های **VLESS + WS** (وب‌سوکت) که پشت CDN کلادفلر قرار دارند.
*   **تزریق هوشمند کانفیگ:** حفظ تمامی عملکردهای پیش‌فرض برنامه (مثل تونل‌زنی اسپلیت/Per-App Proxy و DNSهای محلی) در کنار اعمال تنظیمات سفارشی کاربر.
*   **رابط کاربری اختصاصی:** طراحی مدرن، روان و اختصاصی منوها و صفحه‌ی اصلی.

*   توسعه‌دهنده هسته اصلاح‌شده Xray: پروژه [Patterniha](https://github.com/patterniha).
