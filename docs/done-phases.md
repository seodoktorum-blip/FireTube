# Biten fazlar (arşiv)

## Faz 1 — Kritik mantık hataları (12 Eki 2026)
- [x] 1.1 Uyku zamanlayıcı duraklatması 3 sn içinde geri alınıyordu (autoStart/handleStall "sistem duraklatması" sanıyordu).
  - inject.js: `window.__uykuPause()` → userPause=true + pause; reklam sırasında da userPause'a saygı.
  - PlaybackService: zamanlayıcı bitince `__uykuPause` çağırır.
  - preinject.js: mediaSession pause/stop işleyicileri sarılır → `__uykuUserPauseAt`; inject.js bunu kullanıcı duraklatması sayar.
- [x] 1.2 deepPrune `hasAds` üst-düzey kapısına takılıyordu → artık metinde herhangi bir AD_KEY geçiyorsa (KEY_RE ön eleme) derin temizlik; fetch + JSON.parse + ytInitialPlayerResponse aynı `prune()`'u kullanır. Derinlik 7→10. `playerAds` anahtarı eklendi (rules.json v3).
  - Node ile sahte sayfa testinde doğrulandı (iç içe anahtar, setter, JSON.parse, canlı anahtar güncelleme, mediaSession).
- [x] 1.3 Soğuk açılışta kurallar ilk sayfaya yetişmiyordu → `applyStoredRules()` onCreate'te eşzamanlı (yerel+önbellek kurallar, `ubo-keys.json`); uzak kurallar/uBO anahtarları gelince `pushKeysToPage()` → `window.__fireSetKeys`.
- [x] 1.4 rewriteHtml: Set-Cookie'ler CookieManager'a yazılır; yönlendirme izlenmez, 3xx → `location.replace` belgesi (10 sn/6 yönlendirme freni).
- (2.1 de burada yapıldı) Yeni `Rules.java`: org.json ile ayrıştırma/doğrulama; geçersiz uzak dosya önbelleğe yazılmaz; sayfaya yalnız doğrulanmış anahtar dizisi gömülür; sürüm seçimi önbelleği de karşılaştırır (3.1).

## Faz 2 — Güvenlik ve sağlamlık (12 Eki 2026)
- [x] 2.1 Uzak kurallar org.json ile doğrulanır (Rules.java, Faz 1.3 ile birlikte).
- [x] 2.2 inject.js görünürlük taklidi yalnız `e.target === window/document` olan blur/visibilitychange/pagehide'ı yutar; input blur'ları serbest.
- [x] 2.3 `kickWebViewSurface` tam ekranda (customView != null) hiçbir şey yapmaz; tam ekran çıkışında zaten çağrılıyor.
- [x] 2.4 Köprü `log` mesajı 300 karakterle sınırlı; manifest `allowBackup=false`. Release `debuggable` bilinçli olarak kaldı (run-as ile günlük + update-filters.bat buna bağlı).
- [x] 2.5 uBO quick-fixes: `!` yorum satırları atlanır; yalnız reklam-şekilli anahtarlar (`AD_SHAPED_KEY`) alınır. Gerçek listede görülen `legacyImportant` (uBO seçeneği) artık anahtar sayılmıyor.

## Faz 3 — Küçük düzeltmeler (12 Eki 2026)
- [x] 3.1 Sürüm seçimi önbelleği de karşılaştırır, günlükte gerçek sürüm (`Rules.best`).
- [x] 3.2 Filtre kümeleri her tazelemede sıfırdan kurulur (hiç liste yoksa eskisi korunur); önbellek adı URL özetinden (`FilterParser.cacheName`); listeden çıkan eski dosyalar silinir; indirme önce `.tmp`'ye yapılıp yerine taşınır, başarısız indirmede bayat önbellek kullanılır.
- [x] 3.3 Yeni `FilterParser.java` (Android'siz): yol/joker içeren kurallar (`||x.com/ads/`) ve `domain=`, `popup`, `~third-party` vb. seçenekli kurallar alınmaz; kozmetik satırlar atlanır; güvenli sonek denetimi `equals || endsWith("."+safe)`.
  - Gerçek listelerle denendi: easyprivacy + turkish-filters+easylist → 93.858 domain, 153 istisna; doubleclick/google-analytics engelli, googlevideo/ytimg serbest; uBO → [adPlacements, playerAds, adSlots].
- [x] 3.4 Menüdeki yedek sürüm adı "1.5" → "?".
- [x] 3.5 README mimari tablosu: Rules, FilterParser, preinject.js, rules.json eklendi.

## Faz 4 — Kod kalitesi (12 Eki 2026)
- [x] 4.1 MainActivity 961→671 satır: `AdFilter` (kurallar/listeler/uBO, `shouldBlock`, `bootScript`, `setKeysScript`), `AppLog` (kalıcı günlük; PlaybackService doğrudan kullanır), `Rules`, `FilterParser`. uBO indirmesi başarısızsa bayat önbellek kullanılır.
- [x] 4.2 Birim testleri: `FilterParserTest` (12), `RulesTest` (5) — `./gradlew :app:testDebugUnitTest` (AGP 9'da release unit test görevi yok). `app/src/test/js/preinject.test.js` (Node). Test bağımlılıkları: junit 4.13.2, org.json 20240303.
- [x] 4.3 Gradle wrapper 9.8.0 (`gradlew`, `gradlew.bat`, `gradle/wrapper/`). README kurulum/test komutları güncellendi.
- Sürüm v1.13 (versionCode 13), CHANGELOG girdisi eklendi.

## Telefonda doğrulama (12 Eki 2026, Xiaomi 2406APNFAG, Android 16)
DevTools (adb forward → webview_devtools_remote_<pid>) ile sayfada JS çalıştırılarak:
- Soğuk açılış: ilk sayfa "anahtarlar: 20 (uzak=19, uBlock=3)"; ilk açılışta uBO anahtarları sayfaya canlı itildi.
- Zamanlayıcı: kontrol testi — düz `video.pause()` 2 sn içinde otomatik devam ediyordu (eski hata); `__uykuPause()` sonrası 90 sn DURAKLADI [KULLANICI-DURDURDU] kaldı; play ile normal devam.
- Blur: input blur'u ulaşıyor, window blur yutuluyor, document.hidden=false.
- Ekran kapalı 40 sn: çalmaya devam etti.
- Testte bulunan ek hata düzeltildi: handleBufferStall duraklatılmış süreyi takılma sayıyordu (devamda sahte "akış takıldı — dürtülüyor"); 55 sn duraklat→devam ile doğrulandı.

## v1.14 — Faz 5–8 (12 Eki 2026)
Kaynak: docs/oneriler.md (A*/B*), gerçek v1.12 günlüğüyle önceliklendirildi.
- Faz 5: A2 reklama anında tepki (medya olayları + MutationObserver + ratechange/volumechange); B1 kulaklık çıkınca duraklat (AUDIO_BECOMING_NOISY); YENİ telefon çalınca duraklat/bitince sürdür (AudioManager mod dinleyicisi, API<31 yoklama); A3 zamanlayıcı servis durunca silinmez; A1 servis başlarken lastStatusAt.
- Faz 6: B6 kurtarma/yenilemede t= ile kaldığı saniye; A7 onReceivedError geri çekilmeli yeniden deneme + NetworkCallback + setNetworkAvailable; A6 günlük (ApplicationExitInfo, WebView sürümü, servis/ön script/fren/tick hataları, inject.js ayrı try'lar); A5 25 dk dokunulmazsa ekranı açık tutmayı bırak.
- Faz 7: B3 "bu video bitince dur"; B5 son 60 sn ses kısma; B2 bildirim (aç, Oynat/Duraklat, +15 dk, Durdur, başlık/kanal/kalan süre) + MediaSession (CustomAction ile Android 13+ medya kartı).
- Faz 8.1 inceleme: 1. tur 22 doğrulanmış bulgu (kök neden: duraklatma niyeti kalıcı değildi) → duraklatma kilidi modeli: JS userPause+holdPause+pauseOwner, Java holdPaused, ön script __uykuHoldBoot, cancelCallResume/callSeq, STOP yarışı, playing eşitleme, ekran bırakma düzeltmeleri, reklamda duraklatma koruması, lastContent, applyBootStart, kanal adı aria-label. 2. tur 5 bulgu: önyükleme devralma + isHold köprüsü, callCancelled koşulsuz, kendi pause'umuzu kullanıcı saymama, __uykuWake (geri tuşu), durum sorgusuyla hold kendini düzeltme.
- Telefonda doğrulama sırasında bulunan: onPageFinished SPA geri gezinmede de geliyor → yedek duraklatma belge başına bir kez (__uykuHoldChecked); kilitli sayfada t= başlangıcının ilk oynatmada uygulanması.
- Telefonda doğrulandı (Android 16): kara kutu CIKIS satırları; medya tuşu → MediaSession; çökmede t= ile devam; ses kısma; video sonu; bildirim yapısı/kanal adı; duraklatılmışken çökme → duraklatılmış kalır + devamda doğru saniye; gerçek dokunuşla iki video + kilitliyken geri tuşu → önceki video çalar.
- Not: DevTools'tan programatik tıklamalarla oluşan geçmiş kayıtları Chromium'da "kullanıcı etkileşimsiz" sayılıp geri tuşunda atlanır — test ederken gerçek dokunuş (adb input tap) kullan.
- Kullanıcı elle denemeleri (12 Eki 21:45–21:50): arama → durdu/6 sn sonra sürdü (mod=1 zil=2); kulaklık → MediaSession + NOISY ikisi de duraklattı; HyperOS kilit ekranı kartında +15 dk/Durdur görünür ve çalışır; uçak modu → kopma algılandı, çalma sürdü.
