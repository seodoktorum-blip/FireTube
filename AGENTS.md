Şu an: İş tamamlandı | Son yapılan: tüm elle denemeler geçti (tam ekran, kilit ekranı, Google çıkış/giriş) | Sıradaki: yok

# FireTube — inceleme bulgularını düzeltme planı (12 Eki 2026)

Derleme: `tools/gradle-9.8.0/bin/gradle assembleRelease --offline -q` (ya da `./gradlew assembleRelease`)
Testler: `:app:testDebugUnitTest` + `node app/src/test/js/preinject.test.js`

## Faz 1 — Kritik mantık hataları ✅ (ayrıntı: docs/done-phases.md)
Zamanlayıcı/medya duraklatması kalıcı, derin temizlik kapısız, kurallar ilk sayfaya eşzamanlı + canlı itme, rewriteHtml çerez/yönlendirme düzeltmesi.

## Faz 2 — Güvenlik ve sağlamlık ✅ (ayrıntı: docs/done-phases.md)
Kurallar doğrulanıyor, blur yutma daraltıldı, tam ekranda yüzey tekmesi yok, log sınırı + allowBackup=false, uBO anahtarları süzülüyor.

## Faz 3 — Küçük düzeltmeler ✅ (ayrıntı: docs/done-phases.md)
Sürüm seçimi, liste tazeleme/önbellek, ABP ayrıştırıcı sıkılaştırma (FilterParser.java), menü sürümü, README.

## Faz 4 — Kod kalitesi ✅ (ayrıntı: docs/done-phases.md)
AdFilter/AppLog ayrıldı, 17 JUnit + Node testi, Gradle wrapper, v1.13.

## Açık işler
- [x] Telefonda doğrulama: soğuk açılış anahtarları, zamanlayıcı, blur, ekran kapalı çalma (ayrıntı: docs/done-phases.md)
- [x] Tam ekran: kullanıcı telefonda denedi, sorun yok (12 Eki 2026)
- [x] Kilit açılınca ekran: kullanıcı denedi, siyah kalmadı (12 Eki 2026)
- [x] Google çıkış/giriş (yönlendirme+çerez yolu): kullanıcı denedi, sorun yok (12 Eki 2026)
- [x] Commit + push: 49f4190 (seodoktorum-blip hesabıyla; makinedeki varsayılan git kimliği thecreepervibe ve yazma izni yok)
- [ ] `./gradlew` ilk çalıştırmada Gradle 9.8.0 dağıtımını indirir (yerelde denenmedi)
- Bilinen sınır: WebView'de sistem MediaSession yok; kulaklık düğmesi işaretlemesi yalnız YouTube mediaSession işleyicisi çağrılırsa çalışır
- Bilinçli bırakılan: release `debuggable=true` (run-as günlük + update-filters.bat için)
