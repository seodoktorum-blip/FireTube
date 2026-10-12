Şu an: Faz 8, adım 8.2 | Son yapılan: v1.14 kodlandı, 2 tur incelendi, telefonda doğrulandı, CHANGELOG/README | Sıradaki: kullanıcı elle denemeleri + commit/push onayı

# FireTube — plan

Derleme: `tools/gradle-9.8.0/bin/gradle assembleRelease --offline -q` (ya da `./gradlew assembleRelease`)
Testler: `:app:testDebugUnitTest` + `node app/src/test/js/preinject.test.js`
Öneri kaynağı: docs/oneriler.md (A*/B* kodları oradan)

## v1.13 (Faz 1–4) ✅ (ayrıntı: docs/done-phases.md)
İnceleme düzeltmeleri, AdFilter/AppLog/Rules/FilterParser, testler, wrapper; telefonda ve elle doğrulandı; 49f4190 push edildi.

# v1.14 — uyandırmama + kesintisiz gece + kontrol ✅ kodlandı (ayrıntı: docs/done-phases.md)
Faz 5–7 özellikleri + 2 tur inceleme düzeltmeleri; derleme/testler geçti; v1.14 telefonda kurulu.
- [ ] 8.2 Kullanıcının elle denemesi: gerçek arama (çalarken dur, bitince sür; sessiz kipte cevaplanınca dur), kulaklık/Bluetooth çıkarma, kilit ekranı medya kartı (+15 dk, Durdur, kalan süre), bildirime dokununca açılma, 25 dk ekran bırakma, internet kesip açma
- [ ] 8.3 Commit + push (kullanıcı onayıyla; push seodoktorum-blip hesabıyla)

## Açık işler
- [ ] `./gradlew` ilk çalıştırmada Gradle 9.8.0 dağıtımını indirir (yerelde denenmedi)
- Bilinçli bırakılan: release `debuggable=true` (run-as günlük + update-filters.bat için)
- Kapsam dışı (docs/oneriler.md): A8, A9, A10, A11, B4, B7 (A4 duraklatma kilidiyle büyük ölçüde çözüldü)
