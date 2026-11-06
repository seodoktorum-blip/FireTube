# FireTube 🔥🌙

**Uykuya dalmak için video dinleyenler için reklamsız, arka planda çalan Android YouTube tarayıcısı. Telefonda ve kumandayla TV kutusunda çalışır.**

A minimal, ad-free YouTube player for people who fall asleep listening to videos — keeps playing with the screen off, mutes & fast-forwards any ad that slips through, heals itself when things go wrong at 3 AM, and works with a remote on Android TV boxes.

[English summary below ⬇](#english)

## Ne yapıyor?

### Reklam ve akış

- **Reklam engelleme (üç katman):**
  1. **Ağ:** reklam/izleyici altyapısı engellenir (doubleclick, googlesyndication, pagead vb.; 6 ABP topluluk listesi ve `rules.json` desenleri).
  2. **Veri:** ön script, YouTube'un kodundan önce çalışıp oynatıcı verisinden reklam alanlarını siler; reklam oynatıcıya hiç ulaşmaz. Reklam anahtarları uBlock topluluk listesinden ve `rules.json`'dan gelir, APK kurmadan güncellenir.
  3. **Oynatıcı:** yine de sızan reklam **anında** (medya olayları + oynatıcı gözlemcisi) yakalanır: **sesi kısılır, 16x hıza çekilir**, ileri sarılır, "Atla" düğmesine otomatik basılır. En kötü senaryoda bile reklam seni sesle uyandıramaz.
  - Reklamın *medya akışını* ağdan engellemek oynatıcıyı kilitlediği için bilinçli olarak **yapılmıyor** (denedik, simsiyah ekrandan ibaret kaldı 🙃).
- **Akışta yalnız videolar:** topluluk gönderileri, anketler, anket/geri bildirim kutuları, oyunlar (Playables) ve tanıtım bantları akıştan gizlenir. Videolar ve Shorts kalır; bir kanalın kendi Gönderiler sekmesine dokunulmaz.
- **SponsorBlock:** videoya gömülü sponsor okumaları, tanıtımlar ve "abone ol" hatırlatmaları topluluk veritabanından (sponsor.ajay.app) bulunup atlanır. Gizlilik: sunucuya video kimliği değil yalnız özetinin ilk 4 karakteri gider. Menüden kapatılabilir.
- **Koyu tema:** YouTube her cihazda koyu açılır (eski WebView'li TV kutularında da). YouTube ayarından açık temayı açıkça seçersen ona uyulur.

### Uyku ve kesintisiz çalma

- **Ekran kapalıyken / kilit ekranında ses devam eder (gerçek cihazda test edildi):** Chromium WebView'in "pencere görünmez → medyayı duraklat" davranışı `KeepAliveWebView` ile devre dışı bırakılır; sayfaya her zaman görünür olduğu söylenir. Video oynarken bildirimde servis çalışır (partial wake lock; Android 13 ve altında ayrıca wifi lock). Ekran kapalıyken reklam/duraklama denetimi, sayfa zamanlayıcı kısıtlamasına takılmadığı için uygulama tarafından saniyede bir `__uykuTick` ile sürdürülür.
- **Video kendiliğinden durmaz:** dokunma gerekliliği kaldırıldı; açılan video kendiliğinden başlar. "Devam etmek istiyor musun?" kutuları otomatik onaylanır. Sen dokunmadığın halde video durursa kendiliğinden devam ettirilir — ama **sen duraklattıysan dokunulmaz**.
- **Sessiz başlama düzeltmesi:** YouTube'un dokunuşsuz yüklemede (kurtarma, sıradaki video, bağlantıyla açılan Shorts) videoyu sessiz başlatması otomatik düzeltilir.
- **YouTube'un ayarlarına saygı:** autoplay'in kapalıysa video bitince durur; uygulama kendi başına sıradaki videoya **geçmez**.
- **Uyku zamanlayıcı:** 🛡 rozetinden 15–120 dk ya da **"Bu video bitince dur"** seç. Son 60 saniyede ses yavaşça kısılır, süre bitince video durur ve **durmuş kalır** (otomatik devam, autoplay ya da sayfa yenilenmesi geri başlatmaz).
- **Uyandırmayan duraklatmalar:**
  - **Telefon çalınca / görüşmede** video durur, görüşme bitince kaldığı yerden sürer (görüşme sırasında zamanlayıcı dolduysa ya da kulaklık çıktıysa sürmez). Not: telefon sessiz/titreşimdeyken zil sırasında Android ses modunu değiştirmediği için video ancak arama cevaplanınca durur.
  - **Kulaklık çıkınca / Bluetooth kopunca** video durur (ses hoparlöre geçmez).
- **Bildirim ve kilit ekranı kontrolü:** oynat/duraklat, **+15 dk**, **Durdur**; video başlığı, kanal ve kalan süre görünür. Kulaklık tuşu da çalışır (MediaSession).
- **Kesintisiz gece:** kurtarma/yenilemede video **kaldığı saniyeden** sürer; internet kesilirse hata sayfasında kalmaz, bağlantı gelince yeniden dener. Kilitlemeden uyursan 25 dk dokunulmadığında ekranı açık tutmayı bırakır (ses sürer).

### Kendi kendine iyileşme

- Video motoru (renderer) çökerse WebView sıfırdan kurulur, video kaldığı yerden devam eder. Ekran siyah kalmaz.
- Sayfa 2 dakika yanıt vermezse aynı kurtarma devreye girer.
- Akış takılınca önce dürtülür, 2,5 dakikada düzelmezse sayfa yenilenir.
- **Boş sayfa bekçisi:** YouTube sayfası 30 saniye içinde çizilmezse takıldığı adım günlüğe yazılır ve sayfa yenilenir.
- Sayfa açılırken bağlantı kurulamazsa ön script birkaç saniye arayla yeniden denenir (ön scriptsiz sayfa reklam verisi alır).
- **Döngü freni:** otomatik yenilemeler 10 dakikada en fazla 3 kez — yanlış tetikleme bile gece boyu kesinti yaratamaz.
- Not: MIUI/HyperOS ekran kapalıyken çok işlemci kullanan video motorunu gece bir kez öldürebiliyor (pil muafiyeti bunu önlemiyor); uygulama birkaç saniyede kaldığı yerden sürdürür.

### Hız ve pil

- **Daha hızlı açılış:** sayfa HTML'i sıkıştırılmış iner ve tamamı inmeden WebView'e akar (TV kutusunda ilk görüntü 11,8 sn'den 6,4 sn'ye indi). Reklam listeleri açılışta düşük öncelikle işlenir.
- **Ekran kapalıyken 144p:** ekran kapalıyken ya da uygulama arka plandayken video en düşük kaliteye iner (ölçümde ekran kapalıyken de saniyede ~30 kare çözülüyordu): daha az pil ve veri, zayıf ağda daha az takılma. Ekran açılınca (kilidi açmadan) senin kalite ayarın birebir geri gelir. Menüden kapatılabilir.
- **Arka planda dondurma:** video çalmıyorken uygulama arka plandaysa (servis kapandıktan sonra) sayfa dondurulur, işlemci ve pil harcamaz; uygulamaya dönünce kaldığı gibi açılır.
- Ekran kapalıyken ya da uygulama arka plandayken sayfadaki sık denetimler durur; denetimi saniyede bir Android tarafı yürütür.

### Diğer

- **Google girişi uygulamada kalır:** Google'ın ülke alan adları (`accounts.google.com.tr`, `accounts.google.by` vb.) tarayıcıya gitmez; giriş Chrome'da bitip YouTube uygulamasını açmaz.
- **Aşağı çek → yenile** (orijinal YouTube uygulamasındaki gibi), tam ekran video, geri tuşu ile gezinme, reklam sayacı.
- **Kara kutu:** uygulama kendi günlüğünü `files/uyku.log`'a yazar (nabız + olaylar + açılışta önceki kapanma nedenleri ve WebView sürümü; TV'de Geri kararları ve kumandanın alışılmadık tuşları); logcat silinse bile olan biten incelenebilir.

## Android TV kutusu (kumanda)

TV'de uygulama kendiliğinden TV kipine geçer. Yalnız bir kutuda denendi: X96Q (Android 10, 4 çekirdek, 2 GB, WebView 80).

| Tuş | Sayfada | İzlerken (tam ekran) |
|---|---|---|
| Yön tuşları | en yakın öğeye geç (mavi çerçeve); video kartı tek durak | sol/sağ 10 sn sar (basılı: 30 sn), yukarı süreyi göster, aşağı tam ekrandan çık |
| Tamam | aç / tıkla; oynatıcı seçiliyse tam ekran | oynat / duraklat |
| Geri | açık menüyü kapat → önceki sayfa → ana sayfa; ana sayfada iki kez basınca çıkar | tam ekrandan çık |
| Menü | uygulama menüsü (zamanlayıcı, Alt yazı, Ara, Abonelikler, Geçmiş…) | aynı |
| Oynat/duraklat, ileri/geri medya tuşları | — | oynat/duraklat, 10 sn sar (kodda var, kutuda denenmedi) |
| SUBTITLE | alt yazı ayarını değiştir | alt yazı aç/kapat |

- Video tam ekran açılır. Aşağı ya da Geri tam ekrandan çıkarır; oynatıcı seçili kalır, Tamam yeniden tam ekran yapar.
- Sayfanın en üstünden yukarı basınca 🛡 rozeti seçilir; Tamam menüyü açar, aşağı sayfaya döner.
- Shorts'ta yukarı/aşağı önceki/sonraki kısa video, Tamam oynat/duraklat.
- **Alt yazı** TV'de varsayılan kapalı ve her videoda bu ayara çekilir: YouTube alt yazı bir kez açılınca her videoda açmayı hatırlıyordu. Menüden ya da SUBTITLE tuşuyla açılır/kapanır, seçim kalıcıdır.
- **YouTube'un harf/rakam kısayolları** (C alt yazı, 0-9 videonun %0-90'ına atlama, K/J/L/M/F…) TV'de kapalı; kumandanın bu tuşları videoyu karıştırmaz. Yazı alanında normal çalışır.
- **Fare kipi:** kumanda fare kipine geçerse (kumandadaki fare tuşu) uyarı çıkar; yön tuşlarıyla gezmek için fare tuşuna tekrar bas.
- **Zayıf kutu için:** CSS animasyonları anlık yapılır (ölçüm: ana sayfada boşta işlemci %29 → %9), akıştaki sessiz önizlemeler oynatılmaz, denetim döngüsü saniyede birdir. Ekran açık tutma 25 dk'da bırakılmaz (film izlerken ekran koruyucu gelmesin); TV'yi uyku zamanlayıcısı kapatır.
- Kutunun saati yanlışsa (ör. 2020) güvenli bağlantılar kurulamaz ve YouTube açılmaz: Ayarlar → Tarih ve saat → **Otomatik**.
- Android System WebView'i Play Store'dan güncellemek eski kutularda yardımcı olabilir, ama her kutuda mümkün değil: X96Q'da WebView üreticinin anahtarıyla imzalı olduğu için Play "Can't install" diyor.
- Uygulama Android TV ana ekranı (Leanback) kaydı taşımıyor; yalnız o ana ekranı kullanan cihazlarda uygulama listesinde görünmeyebilir.

## Kurulum (Android 8+)

```
./gradlew assembleRelease     # Gradle wrapper (9.8.0) — veya Android Studio ile açıp Run
adb install -r app/build/outputs/apk/release/app-release.apk
```

Testler:

```
./gradlew :app:testDebugUnitTest      # kurallar, filtreler, SponsorBlock, alan adları, HTML, tema çerezi (JUnit)
node app/src/test/js/preinject.test.js # ön script reklam temizliği (Node)
node app/src/test/js/inject-stall.test.js # takılma bekçisi (Node)
```

Çıktı debug anahtarıyla imzalanır (kişisel kullanım için kurulabilir).

Öneriler:
- İlk açılışta YouTube'da **hesabına giriş yap** — oturum kalıcıdır, "bot musun?" duvarına takılmazsın.
- Video oynarken başka uygulamaya geçsen de çalmaya devam eder (uyku uygulaması olduğu için bilinçli böyle). Duraklatmak için videoya dokun.
- MIUI/Xiaomi: Ayarlar → Uygulamalar → FireTube → Pil → **Kısıtlama yok** (uygulama ilk oynatışta zaten izin ister; menüdeki "Arka plan çalma izni" ilgili ekranları açar).

## Mimari (tek göz atış)

| Dosya | İş |
|---|---|
| `app/src/main/java/com/uykutube/app/MainActivity.java` | WebView, istek engelleme, ana HTML'e ön script gömme (`rewriteHtml`, çerez/yönlendirme, akış), arayüz, kendi kendine iyileşme, arka planda dondurma, TV kipi (tuşlar, Geri, fare kipi) |
| `app/src/main/java/com/uykutube/app/PlaybackService.java` | Ekran kapalıyken ses (wake lock), uyku zamanlayıcısı, telefon/kulaklık duraklatması, bildirim ve MediaSession, yerel tick ile sayfa denetimi, donuk-renderer bekçisi |
| `app/src/main/java/com/uykutube/app/AdFilter.java` | Reklam engelleme verisi: kurallar, ağ desenleri, ABP domain kümeleri, uBlock anahtarları (disk + ağdan tazeleme) |
| `app/src/main/java/com/uykutube/app/Rules.java` | `rules.json` ayrıştırma/doğrulama, sürüm seçimi (GitHub ↔ adb ↔ önbellek) |
| `app/src/main/java/com/uykutube/app/FilterParser.java` | ABP `\|\|domain^` kuralları ve uBlock quick-fixes json-prune anahtarlarının ayrıştırılması |
| `app/src/main/java/com/uykutube/app/SponsorBlock.java` | SponsorBlock sorgusu (sha256 öneki), bölüm ayıklama, önbellek |
| `app/src/main/java/com/uykutube/app/Hosts.java` | Uygulamada kalacak alan adları (YouTube, Google ülke alan adları, medya) |
| `app/src/main/java/com/uykutube/app/HtmlHead.java` | Akış hâlindeki HTML'de `<head>` konumunu bayt düzeyinde bulma |
| `app/src/main/java/com/uykutube/app/YtPref.java` | YouTube `PREF` çerezinde koyu tema biti |
| `app/src/main/java/com/uykutube/app/AppLog.java` | Kalıcı teşhis günlüğü (`files/uyku.log`) |
| `app/src/main/assets/preinject.js` | YouTube kodundan ÖNCE çalışır: player verisinden reklam alanlarını derin temizler (`ytInitialPlayerResponse` tuzağı, fetch/JSON.parse sarmalayıcı); TV'de YouTube kısayollarını kapatan tuş kapısı |
| `app/src/main/assets/inject.js` | Sayfa yüklenince: sızan reklamı yakalama (sessize al + 16x + ileri sar + atla), otomatik devam, akış takılması kurtarma, görünürlük korusu, SponsorBlock atlama, video dışı öğeleri gizleme, açılış bekçisi; TV gezinmesi, tam ekran ve alt yazı |
| `rules.json` | Uzaktan güncellenen kurallar (reklam anahtarları, ağ desenleri, ABP liste adresleri) — APK kurmadan güncellenir; `update-filters.bat` ile adb'den anında itilebilir |

Sürüm ayrıntıları: [CHANGELOG.md](CHANGELOG.md)

> Dahili paket kimliği `com.uykutube.app` olarak kaldı (görünen ad FireTube).

## Not

Bu uygulama kişisel kullanım için yazılmış, YouTube mobil sitesini görüntüleyen basit bir tarayıcıdır. YouTube'un kullanım şartlarına uymak kullanıcının sorumluluğundadır. Reklam geliriyle yaşayan içerik üreticilerini desteklemek istiyorsan ara sıra YouTube'a da uğra ❤️

---

# English

**FireTube** is a minimal Android WebView-based YouTube player built for one purpose: falling asleep to videos without being woken up by ads. It runs on phones and, with a remote, on Android TV boxes.

**Ads and feed**
- **Three-layer ad handling:** network-level blocking of ad/tracking infrastructure (ABP community lists plus `rules.json` patterns), a pre-injected script that strips ad fields from player data before YouTube's own code sees them, and a player-level watchdog that mutes and 16x fast-forwards any ad that still plays. Ad *media* URLs are deliberately never blocked — returning empty responses for them wedges the player (learned the hard way).
- **Videos only in the feed:** community posts, polls, surveys, Playables games and promo banners are hidden. Videos and Shorts stay; a channel's own Posts tab is left alone.
- **SponsorBlock:** sponsor reads, self-promotion and "subscribe" reminders are skipped using the community database. Only the first 4 characters of the video ID's hash are sent. Can be turned off in the menu.
- **Dark theme** on every device, including old WebViews, unless you explicitly chose the light theme on YouTube.

**Sleep and uninterrupted playback**
- **Plays with screen off / locked:** a WebView subclass swallows window-visibility changes so Chromium doesn't pause media, backed by a foreground service holding a wake lock (plus a wifi lock on Android 13 and below). Page-side watchdogs keep running via a native per-second tick that bypasses timer throttling.
- **Respects your YouTube settings:** if autoplay is off, videos simply stop when they end; the app never advances on its own. If you paused, nothing restarts it.
- **Sleep timer:** 15–120 minutes or "stop when this video ends", with a 60-second volume fade; the video stays stopped afterwards.
- **Pauses that don't wake you:** phone calls (resumes afterwards) and headphones or Bluetooth disconnecting (sound never jumps to the speaker).
- **Notification and lock-screen controls:** play/pause, +15 min, Stop, with title, channel and remaining time.

**Self-healing**
- Renderer crashes, frozen pages, stalled streams and pages that never draw are detected and recovered automatically, resuming from the same second and unmuted — with a rate limiter (3 reloads per 10 minutes) so recovery itself can never loop.
- MIUI/HyperOS may kill the video engine once a night while the screen is off, regardless of battery exemptions; the app resumes within seconds.

**Speed and battery**
- The page HTML is fetched compressed and streamed into the WebView, so first paint comes earlier (11.8 s → 6.4 s on the test TV box).
- 144p while the screen is off or the app is in the background; your quality setting returns exactly when the screen comes back on. Can be turned off in the menu.
- A paused page in the background is frozen so it stops using CPU.

**Android TV box (remote)**
- TV mode switches on automatically. Tested on one box only: X96Q (Android 10, 4 cores, 2 GB, WebView 80).
- **Navigation:** arrow keys move to the nearest item (blue frame), each video card is one stop, OK opens it. Up from the top of the page selects the 🛡 badge; the Menu key opens the app menu (timer, captions, search, subscriptions, history).
- **Watching:** videos open fullscreen. OK plays/pauses, left/right seeks 10 s (hold: 30 s), up shows the time, down or Back leaves fullscreen. In Shorts, up/down go to the previous/next clip.
- **Back never exits by accident:** it closes an open menu or fullscreen, then goes to the previous page, then home; on the home page you must press it twice to quit.
- **No surprise captions or jumps:** YouTube's letter and number shortcuts are disabled on TV, and captions stay off on every video until you turn them on in the menu or with the SUBTITLE key.
- **Mouse mode warning** if the remote switches to pointer mode.
- **Lighter on weak boxes:** CSS animations are made instant (idle CPU on the home page: 29% → 9%), feed previews don't play.
- The app has no Leanback launcher entry, so it may not appear in the app list on devices that use the Android TV home screen. If the box's clock is wrong, YouTube won't open: turn on automatic date and time.

Also: Google sign-in stays inside the app, pull-to-refresh, persistent diagnostic log (`files/uyku.log`). See [CHANGELOG.md](CHANGELOG.md) (Turkish) for version details.

Requires Android 8+. MIT licensed. Personal-use project; you are responsible for complying with YouTube's Terms of Service.
