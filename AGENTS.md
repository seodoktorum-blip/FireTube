Şu an: Tüm fazlar + telefon doğrulaması bitti | Son yapılan: v1.13 telefonda doğrulandı, takılma bekçisi düzeltildi | Sıradaki: commit/push (kullanıcı onayıyla)

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
- [ ] Elle denenmedi: Google çıkış/giriş (yönlendirme+çerez yolu), tam ekran, kilit açılınca siyah ekran olmaması
- [ ] Commit + push (rules.json v3 GitHub'a gitmeli — uygulama oradan çekiyor)
- [ ] `./gradlew` ilk çalıştırmada Gradle 9.8.0 dağıtımını indirir (yerelde denenmedi)
- Bilinen sınır: WebView'de sistem MediaSession yok; kulaklık düğmesi işaretlemesi yalnız YouTube mediaSession işleyicisi çağrılırsa çalışır
- Bilinçli bırakılan: release `debuggable=true` (run-as günlük + update-filters.bat için)
