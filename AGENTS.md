Şu an: v1.17 push edildi | Son yapılan: commit + push | Sıradaki: yok (A8 için günlükte 'ön script eklenemedi' izlensin)

# FireTube — plan

Derleme: `tools/gradle-9.8.0/bin/gradle assembleRelease --offline -q` (ya da `./gradlew assembleRelease`)
Testler: `:app:testDebugUnitTest` + `node app/src/test/js/preinject.test.js`
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

## Açık işler
- [ ] `./gradlew` ilk çalıştırmada Gradle 9.8.0 dağıtımını indirir (yerelde denenmedi)
- Bilinçli bırakılan: release `debuggable=true` (run-as günlük + update-filters.bat için)
- Kapsam dışı (docs/oneriler.md): A8, A9, A10, A11, B4, B7 (A4 duraklatma kilidiyle büyük ölçüde çözüldü)
