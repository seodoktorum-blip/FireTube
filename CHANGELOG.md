# Sürüm Geçmişi

## v1.13 — 12 Ekim 2026
**İnceleme düzeltmeleri.**
- **Uyku zamanlayıcısı artık gerçekten durduruyor:** zamanlayıcının duraklatması
  "sistem duraklatması" sanılıp birkaç saniyede otomatik devam ettiriliyordu.
  Artık kullanıcı duraklatması sayılıyor; medya düğmeleriyle duraklatma da öyle.
- **Derin temizlik her zaman çalışıyor:** yalnız üst düzeyde bilinen alan varsa
  devreye giriyordu; artık yanıtta herhangi bir reklam anahtarı geçiyorsa tüm
  derinlikler temizleniyor (rules.json ve uBlock anahtarları gerçekten etkili).
- **Kurallar ilk sayfaya yetişiyor:** diskteki kurallar açılışta eşzamanlı
  yükleniyor; uzaktan gelen güncellemeler açık sayfaya anında itiliyor.
- **Oturum çerezleri korunuyor:** ana belge yanıtındaki Set-Cookie'ler artık
  kaydediliyor; yönlendirmeler yanlış adres altında sunulmuyor.
- **Güvenlik:** rules.json JSON olarak doğrulanıyor, sayfaya yalnız temiz
  anahtar dizisi gömülüyor; bozuk dosya önbelleğe yazılmıyor. allowBackup kapalı.
- **ABP ayrıştırıcı:** yol içeren ve siteye özgü kurallar artık bütün domaini
  engellemiyor; listeden çıkan domainler tazelemede kalkıyor.
- **Duraklatma sonrası sahte "akış takıldı":** duraklatılmış geçen süre takılma
  sayılıyordu; uzun duraklatmadan sonra devam edince video gereksizce
  dürtülüyordu (telefonda testte bulundu).
- Arama kutusu gibi öğelerin blur olayları artık yutulmuyor; tam ekranda
  yüzey tekmesi yapılmıyor.
- Kod: AdFilter / AppLog / Rules / FilterParser sınıflarına bölündü; birim
  testleri (JUnit + Node) ve Gradle wrapper eklendi.

## v1.12 — 11 Ekim 2026
**uBlock Origin topluluk anahtarlarına abone olundu.** uAssets quick-fixes
listesi (YouTube json-prune kurallarının toplulukça güncellendiği yer)
uygulama tarafından 24 saatte bir çekilir; anahtarlar sayfaya gömülüp derin
temizliğe eklenir. YouTube alan adı değiştirdiğinde uBlock topluluğu günler
içinde listeyi günceller — bizim uygulama kendini otomatik iyileştirir.

## v1.11 — 11 Ekim 2026
**ABP topluluk listeleri entegre edildi.** Uygulama artık Adblock Plus'ın
sürekli güncellenen listelerini doğrudan indiriyor (24 saat önbellek):
turkish-filters+easylist, easyprivacy, fanboy-social/notifications,
distraction-control, abp-filters-anti-cv. ||domain^ kurallarından domain
kümesi çıkarılır; @@ istisnaları uygulanır; video altyapısı domainleri
(googlevideo/youtube/ytimg...) koruma listesiyle asla engellenmez.
Kullanılacak listeler rules.json'dan (filterLists) yapılandırılabilir.

## v1.10 — 11 Ekim 2026
**Kurallar artık oturum içinde de 6 saatte bir tazeleniyor** (açılıştaki
çekime ek olarak). Uzun gece oturumlarında da kural güncellemeleri
kendiliğinden alınır.

## v1.9 — 11 Ekim 2026
**adb ile anında filtre güncelleme.** Kural önceliği sürüm numarasına göre:
adb ile itilen `rules.local.json` ↔ GitHub `rules.json` ↔ önbellek — en
yüksek sürüm kazanır. Yeni `update-filters.bat`: telefon takılıyken tek
komutla kuralları itip uygulamayı yeniden başlatır (`--reset` ile GitHub
kaynağına döner).

## v1.8 — 11 Ekim 2026
**ABP tarzı otomatik güncellenen kurallar (uzaktan kural sistemi).**
Masaüstü reklam engelleyicilerin "hiç sızıntı olmamasının" sırrı günlük
güncellenen liste ekosistemi; uygulamada kurallar APK içine donuk gömülüydü.
Artık: kurallar GitHub'daki rules.json'da tutulur; uygulama her açılışta
çeker (önbellek + yerleşik yedek ile). YouTube format değiştirdiğinde
sadece rules.json güncellenir — APK kurulmadan telefon kendini iyileştirir.
- Ağ desenleri (BLOCK_RE) çalışma anında derlenir
- Derin temizlik anahtarları sayfaya gömülür (window.__FIRE_RULES)

## v1.7 — 11 Ekim 2026
**Siyah ekran kök çözümü (ağır yüzey onarımı).** 5 günlük saha verisi
(1294 nabız ≈ 21 saat dinleme) çözümlemesi: gece MIUI video motorunu
öldürüyor (5 gece / 5 kez), otomatik kurtarma çalışıyor ama ekran KAPALIYKEN
kurulan yeni WebView çizim yüzeyini alamıyordu → uyanınca simsiyah ekran.
Artık ekran açıldığında / uygulamaya dönüşte / kurtarma sonrasında:
- WebView duraklat/destekle (compositor yüzeyi bırak-yeniden iste)
- Görünüm ağacından sök/yeniden tak (Android Surface yeniden oluşur)
- Görünürlük geçişi
Ayrıca renderer ölümünde crash/kill ayrımı günlüğe yazılıyor.

## v1.6 — 6 Ekim 2026
**uBlock seviyesinde derin reklam temizleme** (1-2 reklamın sızmaya devam
etmesi üzerine):
- Derin özyinelemeli temizleme: /youtubei/ yanıtlarının HER derinliğindeki
  reklam-şekilli anahtarlar sökülür (adSlots, adPods, adSegments, adSurvey,
  ssapConfig vb. 19 anahtar) — YouTube alan adları değiştirdiğinde de tutar.
- JSON.parse yaması: fetch dışı yollar (XHR vb.) da kapanır.
- Güvence süpürmesi: YouTube player verisini sonradan yeniden atarsa
  reklam alanları tur içinde tekrar silinir.
- Günlük çözümlemesi: sızmalar /youtubei/v1/player/ad_break uç noktasından
  geliyordu; artık o yanıtlar derinlemesine temizleniyor.

## v1.5 — 2 Ekim 2026
**Pre-injection: masaüstü eklentisi tekniğiyle kökten reklam engelleme.**
Ana HTML belgesi uygulama tarafından indirilip `<head>`'in başına script gömülür;
script YouTube'un kodundan ÖNCE çalışır ve oynatıcıya giden veriden reklam
alanlarını siler (`ytInitialPlayerResponse` setter tuzağı + `/youtubei/v1/player`
fetch sarmalayıcı — uBlock Origin'in json-prune alanları). Oynatıcı reklam
moduna hiç girmez → sıfır kesinti. Sessize-al/16x/seek güvencesi arkada kalır.
Canlı test: 7+ dakika boyunca sıfır reklam olayı (öncesinde 2-6 dk'da bir).

## v1.4 — 2 Ekim 2026
**Reklamları seek ile hızlı geçiş.** Reklam algılanınca indirilmiş verinin
sonuna atlanır (2 sn'de bir ileri). Canlı test: 12+30 saniyelik iki reklam
toplam 8 saniyede geçti.

## v1.3 — 2 Ekim 2026
- **Siyah ekran (WebView yüzey kaybı) düzeltmesi:** uygulama arka plandayken
  ölen çizim yüzeyi, ekran açıldığında/uygulamaya dönüşte/tam ekran çıkışında
  otomatik tekrlelenir (`kickWebViewSurface`). Önceden yalnızca süreç yeniden
  başlatınca düzeliyordu.
- **"Atla" düğmesi freni:** en fazla 2 sn'de bir tıklama (önceki tıklama
  fırtınası oynatıcıyı geriyordu).
- **FireTube adı ve ikonu** (FreeTube ad çakışması nedeniyle).

## v1.2 — 2 Ekim 2026
- **Reklam-medya URL engelleme hatası düzeltmesi:** reklamın video akışına boş
  yanıt döndürmek oynatıcıyı kilitliyordu (simsiyah ekran + sessizlik).
  Engelleme listesi yalnızca izleyici/reklam altyapısına daraltıldı.
- **Döngü freni:** otomatik sayfa yenilemeleri 10 dk'da en fazla 3 kez.
- **Kalıcı teşhis günlüğü:** `files/uyku.log` (nabız + olaylar; logcat silinse
  bile gece olan biten incelenebilir).
- **Autoplay'e saygı:** video bitince uygulama kendi başına sıradaki videoya
  geçmez (YouTube'un autoplay ayarı geçerli).

## v1.1 — 1 Ekim 2026
**Kendi kendine iyileşme:**
- Renderer çökmesi yakalanır, WebView sıfırdan kurulur, video kaldığı yerden devam eder.
- Donuk sayfa bekçisi (durum 2 dk gelmezse kurtarma).
- Akış takılması kurtarma (dürtme → sayfa yenileme).
- İlk oynatışta pil optimizasyonu muafiyeti isteği.

## v1.0 — 27 Eylül 2026
İlk sürüm:
- İki katmanlı reklam engelleme (ağ filtresi + oynatıcı: sessize al, 16x, otomatik atla)
- Ekran kapalıyken/kilitliyken kesintisiz ses (`KeepAliveWebView` + foreground servis)
- Video oynatmada dokunma gerekliliği kaldırıldı; otomatik devam; onay kutuları otomatik onaylanır
- Uyku zamanlayıcı (15-120 dk), aşağı-çek yenileme, tam ekran video, reklam sayacı
- Google girişi (WebView'de UA hilesi ile), MIUI pil dayanıklılığı
