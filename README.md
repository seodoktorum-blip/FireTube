# FireTube 🔥🌙

**Uykuya dalmak için video dinleyenler için reklamsız, arka planda çalan Android YouTube tarayıcısı.**

A minimal, ad-free YouTube player for people who fall asleep listening to videos — keeps playing with the screen off, mutes & fast-forwards any ad that slips through, and heals itself when things go wrong at 3 AM.

[English summary below ⬇](#english)

## Ne yapıyor?

- **Reklam engelleme (iki katman):**
  1. Ağ seviyesinde reklam/izleyici altyapısı engellenir (doubleclick, googlesyndication, pagead vb.).
  2. Sızan reklam olursa oynatıcı seviyesinde yakalanır: **sesi anında kısılır, 16x hıza çekilir**, "Atla" düğmesine otomatik basılır. En kötü senaryoda bile reklam seni sesle uyandıramaz.
  - Reklamın *medya akışını* ağdan engellemek oynatıcıyı kilitlediği için bilinçli olarak **yapılmıyor** (denedik, simsiyah ekrandan ibaret kaldı 🙃).
- **Ekran kapalıyken / kilit ekranında ses devam eder (gerçek cihazda test edildi):** Chromium WebView'in "pencere görünmez → medyayı duraklat" davranışı `KeepAliveWebView` ile devre dışı bırakılır; sayfaya her zaman görünür olduğu söylenir. Video oynarken bildirimde servis çalışır (partial wake lock + wifi lock). Ekran kapalıyken reklam/duraklama denetimi, sayfa zamanlayıcı kısıtlamasına takılmadığı için uygulama tarafından saniyede bir `__uykuTick` ile sürdürülür.
- **Video kendiliğinden durmaz:** dokunma gerekliliği kaldırıldı; açılan video kendiliğinden başlar. "Devam etmek istiyor musun?" kutuları otomatik onaylanır. Sen dokunmadığın halde video durursa kendiliğinden devam ettirilir — ama **sen duraklattıysan dokunulmaz**.
- **Kendi kendine iyileşme:**
  - Video motoru (renderer) çökerse WebView sıfırdan kurulur, video kaldığı yerden devam eder.
  - Sayfa 2 dakika yanıt vermezse aynı kurtarma devreye girer.
  - Akış takılınca önce dürtülür, düzelmezse sayfa yenilenir.
  - **Döngü freni:** otomatik yenilemeler 10 dakikada en fazla 3 kez — yanlış tetikleme bile gece boyu kesinti yaratamaz.
- **YouTube'un ayarlarına saygı:** autoplay'in kapalıysa video bitince durur; uygulama kendi başına sıradaki videoya **geçmez**.
- **Uyku zamanlayıcı:** 🛡 rozetinden 15–120 dk seç, süre bitince video durur, ekran kapanabilir.
- **Aşağı çek → yenile** (orijinal YouTube uygulamasındaki gibi), tam ekran video, geri tuşu ile gezinme, reklam sayacı.
- **Kara kutu:** uygulama kendi günlüğünü `files/uyku.log`'a yazar (nabız + olaylar); logcat silinse bile gece olan biten incelenebilir.

## Kurulum (Android 8+)

```
gradle assembleRelease        # Gradle 9.x — veya Android Studio ile açıp Run
adb install -r app/build/outputs/apk/release/app-release.apk
```

Çıktı debug anahtarıyla imzalanır (kişisel kullanım için kurulabilir).

Öneriler:
- İlk açılışta YouTube'da **hesabına giriş yap** — oturum kalıcıdır, "bot musun?" duvarına takılmazsın.
- Video oynarken başka uygulamaya geçsen de çalmaya devam eder (uyku uygulaması olduğu için bilinçli böyle). Duraklatmak için videoya dokun.
- MIUI/Xiaomi: Ayarlar → Uygulamalar → FireTube → Pil → **Kısıtlama yok** (uygulama ilk oynatışta zaten izin ister).

## Mimari (tek göz atış)

| Dosya | İş |
|---|---|
| `app/src/main/java/com/uykutube/app/MainActivity.java` | WebView + reklam engelleme listesi (`BLOCK_RE`), arayüz, inset düzeltmeleri, kalıcı günlük |
| `app/src/main/java/com/uykutube/app/PlaybackService.java` | Ekran kapalıyken ses (wake lock), uyku zamanlayıcısı, yerel tick ile sayfa denetimi, donuk-renderer bekçisi |
| `app/src/main/assets/inject.js` | Sayfaya enjekte edilen taraf: reklam yakalama (sessize al + 16x + atla), otomatik devam, akış takılması kurtarma, görünürlük korusu |

> Dahili paket kimliği `com.uykutube.app` olarak kaldı (görünen ad FireTube).

## Not

Bu uygulama kişisel kullanım için yazılmış, YouTube mobil sitesini görüntüleyen basit bir tarayıcıdır. YouTube'un kullanım şartlarına uymak kullanıcının sorumluluğundadır. Reklam geliriyle yaşayan içerik üreticilerini desteklemek istiyorsan ara sıra YouTube'a da uğra ❤️

---

# English

**FireTube** is a minimal Android WebView-based YouTube player built for one purpose: falling asleep to videos without being woken up by ads.

- **Two-layer ad handling:** network-level blocking of ad/tracking infrastructure, plus a player-level watchdog that mutes and 16x fast-forwards any ad that still plays. Ad *media* URLs are deliberately never blocked — returning empty responses for them wedges the player (learned the hard way).
- **Plays with screen off / locked:** a WebView subclass swallows window-visibility changes so Chromium doesn't pause media, backed by a foreground service holding wake/wifi locks. Page-side watchdogs keep running via a native per-second tick that bypasses timer throttling.
- **Self-healing:** renderer crashes, frozen pages and stalled streams are detected and recovered automatically — with a rate limiter so recovery itself can never loop.
- **Respects your YouTube settings:** if autoplay is off, videos simply stop when they end; the app never advances on its own.
- Sleep timer, pull-to-refresh, fullscreen video, persistent diagnostic log (`files/uyku.log`).

Requires Android 8+. MIT licensed. Personal-use project; you are responsible for complying with YouTube's Terms of Service.
