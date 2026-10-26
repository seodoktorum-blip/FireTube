Şu an: v1.18, izleme | Son yapılan: v1.18 commit + push (e8786f4) | Sıradaki: gece günlüğü izlensin (KURTARMA sonrası takılma yenilemesi, 'ön script N. denemede', DONDURMA)

# FireTube — plan

Derleme: `tools/gradle-9.8.0/bin/gradle assembleRelease --offline -q` (ya da `./gradlew assembleRelease`). GRADLE_USER_HOME=Y:\cache\gradle (26 Eki bir kez çevrimiçi dolduruldu)
Testler: `:app:testDebugUnitTest` + `node app/src/test/js/preinject.test.js` + `node app/src/test/js/inject-stall.test.js`
Öneri kaynağı: docs/oneriler.md (A*/B* kodları oradan)

## v1.13 (Faz 1–4) ✅ (ayrıntı: docs/done-phases.md)
İnceleme düzeltmeleri, AdFilter/AppLog/Rules/FilterParser, testler, wrapper; telefonda ve elle doğrulandı; 49f4190 push edildi.

# v1.14 — uyandırmama + kesintisiz gece + kontrol ✅ kodlandı (ayrıntı: docs/done-phases.md)
Faz 5–7 özellikleri + 2 tur inceleme düzeltmeleri; derleme/testler geçti; v1.14 telefonda kurulu.
- [x] 8.2 Kullanıcının elle denemesi: [x] gerçek arama (21:45 günlükte doğrulandı); [x] kulaklık çıkarma (kullanıcı denedi); [x] kilit ekranı kartı düğmeleri görünüyor (kullanıcı); [x] internet kesip açma (21:49 'AG: bağlantı yok' ×2, çalma sürdü); denenmedi (düşük risk): bildirime dokununca açılma, 25 dk ekran bırakma, uzun kesintide hata sayfası yeniden denemesi
- [x] 8.3 Commit + push: faaeb74 (seodoktorum-blip hesabıyla)

# v1.15 — düşük kalite + SponsorBlock ✅ kodlandı (ayrıntı: docs/done-phases.md)
Ekran kapalıyken/arka planda 144p (özgün ayar birebir geri), SponsorBlock (sha256 öneki); 21 JUnit + Node testi; telefonda doğrulandı; v1.15 kurulu.
- [x] Commit + push (seodoktorum-blip hesabıyla)

# v1.16 — siyah ekran + sessiz kurtarma ✅ (ayrıntı: docs/done-phases.md)
Kök neden 1: SwipeRefreshLayout eski WebView'i ölçüyordu (kurtarmada yeni WebView 0x0) → kalıcı kap. Kök neden 2: dokunuşsuz yüklemede YouTube sessiz başlatıyor → ses açma. Telefonda yeniden üretildi + doğrulandı.
- [x] Commit + push (seodoktorum-blip hesabıyla)
- [x] Gerçek gece doğrulandı (13 Eki 04:17 kurtarma: sesli devam, sabah ekran normal). v1.16.1: ekran kısa açılıp kapanınca ölçüm yanlış alarmı düzeltildi
- [x] v1.16.1 commit + push (seodoktorum-blip hesabıyla)

# v1.17 — kurtarma freni, kilitler, MIUI mesajı (13 Eki 2026)
- [x] A9: renderer ölünce fren olsa da ölü WebView hemen yenisiyle değişsin; fren yalnız sayfa yüklemesini geciktirsin
- [x] A10: Wi-Fi kilidi yalnız Android 13 ve altında (14+'da işlevsiz); uyku kilidi 30 dk'da bir yenilensin; README/yorumlar
- [x] A11: menüdeki pil seçeneği MIUI'nin ayrı ayarlarını (Kısıtlama yok, Otomatik başlatma) anlatsın ve ilgili ekranı açsın
- [x] Derleme + telefonda doğrulama + CHANGELOG
- [x] Commit + push (seodoktorum-blip hesabıyla)

# v1.18 — gece sağlamlığı (26 Eki 2026, kaynak: 26 Eki günlük kontrolü)
- [x] 1. Takılma bekçisi: dürtme ilerleme sayılmasın → 150 sn'de yenileme gerçekten olsun (inject.js handleBufferStall). Test: inject-stall.test.js (eski kodla başarısız: 8 dürtme, yenileme yok; yenisiyle 151. sn). Telefonda taklit güvenilmez (hız 0 → YouTube sayfayı kendi yeniledi)
- [x] 2. Ön script: bağlantı hatasında kısa arayla yeniden deneme (A8'in hafif hâli; rewriteHtml → rewriteHtmlOnce, 3 deneme/2 sn/25 sn bütçe)
- [x] 3. Ekran kapalıyken CPU: renderer CPU'sunu ölç (önce/sonra), denetimleri seyrelt
  - [x] Ölçüm (26 Eki, ekran kapalı, 144p, yağmur videosu): renderer %24-26 (CrRendererMain %11,5, Media %6,6), uygulama %43-54 (ana iş parçacığı %14, MediaCodec %7). JS profili: çoğu YouTube (base.js, ytmweb); bizimki küçük pay
  - Neden yüksek: KeepAliveWebView pencereyi hep görünür sayıyor (bilinçli: gizlenince ses durur) → Chromium arka plan iyileştirmelerini (video izi kapatma, zamanlayıcı kısma) yapmıyor. Bu yüzden MIUI öldürmesi tamamen önlenemeyebilir; kurtarma (1, 2) asıl güvence
  - [x] Kod: ekran kapalı/arka planda 300 ms döngü ve görsel temizlik durur (__uykuBackground; Android tiki sürer, servis yoksa 5 sn'de yedek tur)
  - [x] Önce/sonra (aynı oturum, 120 sn): renderer %23,2 → %22,9 (CrRendererMain %12,1 → %10,9). Kazanç ~%1; MIUI öldürmesi önlenemez, kabul edildi
- [x] 4. Servis durunca ve arka plandayken WebView'i dondur (maybeFreezeWeb/unfreezeWeb, günlükte DONDURMA). Telefonda: duraklatılmış+servis açık renderer %5,4/uygulama %12 → donunca ~%0/%0,1; ekran açılınca 'çözüldü', sayfa normal, dokunuşla oynatma çalıştı
- [x] 5. Derleme + testler + telefonda doğrulama + CHANGELOG/README/oneriler(A8 durumu) + sürüm 1.18 (versionCode 19)
- [x] 6. Commit + push: e8786f4 (seodoktorum-blip hesabıyla)

## Açık işler
- [ ] `./gradlew` ilk çalıştırmada Gradle 9.8.0 dağıtımını indirir (yerelde denenmedi)
- Bilinçli bırakılan: release `debuggable=true` (run-as günlük + update-filters.bat için)
- Kalan öneri (docs/oneriler.md): A8 — günlükte 'ön script eklenemedi' görülürse. (A4 duraklatma kilidiyle büyük ölçüde çözüldü; A9/A10/A11/B4/B7 yapıldı)
- Günlük kontrolü 15 Eki (13 Eki 09:37 → 15 Eki 10:08, 803 satır): HATA/UYARI/SIYAH-TEST 0, reklam sızıntısı 0, AG 0. 1 renderer ölümü (15 Eki 02:38, video bittikten 10 sn sonra) → kurtarma doğru. Kapanmalar: çoğu SwipeUpClean (kullanıcı), 1 DUSUK_BELLEK (arka planda), WebView 153→155 güncellemesi. Gece autoplay'de YouTube her yeni videoyu sessiz+duraklatılmış başlatıyor; checkAutoMuted + autoStart her seferinde düzeltti (00:44–01:25 kesintisiz). Kulaklık çıkınca duraklatma çalıştı (01:25).
- Günlük kontrolü 26 Eki (15 Eki 10:08 → 26 Eki 12:05, 2252 satır, telefona kablosuz adb 192.168.8.2): HATA/SIYAH 0; telefon (21 Eki) ve kulaklık duraklatması doğru. Bulunan sorunlar (v1.18 adayları):
  - [ ] Takılma bekçisi kendi dürtmesini ilerleme sanıyor: `currentTime = pos + 0.25` > 0,2 eşiği → sayaç sıfırlanıyor, 150 sn yenilemesine hiç varılmıyor. 24 Eki 04:04–05:02 58 dk sessizlik (reklamdan sonra sıradaki video rs=0, süre 0). Düzeltme: dürtmeden sonra lastPos = yeni konum
  - [ ] A8 tetiklendi: 24 Eki 03:02 kurtarmada DNS çözülemedi (UnknownHost, 12 sn) → ön scriptsiz sayfa → 04:02 reklam → yukarıdaki takılma. WebView 2 sn sonra sayfayı açabildi → önce basit çözüm: bağlantı hatasında birkaç sn arayla 2-3 yeniden deneme
  - [ ] MIUI `ScreenOffCPUCheckKill` (ekran kapalı 10 dk'da ~%7 CPU, sınır %2) her gece ~40-50 dk sonra renderer'ı öldürüyor (9 gecenin 8'i; 15 Eki 02:38 da buymuş). Uygulama pil muafiyetinde (deviceidle user whitelist, RUN_ANY_IN_BACKGROUND allow) ama bu denetim muafiyete bakmıyor. Kurtarma her seferinde 2-6 sn'de sesli devam etti. Ekran kapalıyken renderer CPU'su ölçülüp saniyelik kontroller seyreltilebilir
  - [ ] Küçük: duraklatılmış/biten sayfa arka planda CPU yakıyor → 23 Eki 2 kez EXCESSIVE CPU ile kapatıldı (5 dk'da %12). Servis durunca ve arka plandaysa WebView dondurulabilir
  - Not: ana sayfada önizlemeler duraklayınca "kullanıcı duraklattı" günlük gürültüsü (145 satır), zararsız
