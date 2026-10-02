# Sürüm Geçmişi

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
