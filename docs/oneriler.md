# Gerçek veri kontrolü (12 Eki 2026)

Öneriler telefonun v1.12 günlüğüyle (`uyku.log`, 2–12 Ekim, 2623 satır) karşılaştırıldı:

| Öneri | Ajan değerlendirmesi | Günlükte görülen | Düzeltilmiş öncelik |
|---|---|---|---|
| A1 gereksiz KURTARMA | yüksek, "neredeyse her oturum" | 0 vaka: servis durduktan sonraki her kullanımdan önce sayfa baştan yüklenmiş (MIUI süreci öldürüyor) | düşük (gizli hata, tek satır) |
| A2 sızan reklamın ilk saniyesi | orta | ön script sonrası 9 gecede 4 sızıntı, hepsi 02:00–04:00, ekran kapalı | **yüksek** |
| A4 kurtarma duraklatmayı unutuyor | yüksek | 7 renderer çökmesinin 7'si de video ÇALARKEN; duraklatılmışken 0 | orta (zamanlayıcı artık çalıştığı için ileride önem kazanabilir) |
| B6 kaldığın yerden devam | düşük | 7 kurtarmanın 2'sinde konum kayboldu: 43. dk → 4. dk, 123. dk → 4. dk (7 Eki) | **orta-yüksek** |

---

# FireTube: iyileştirme önerileri (v1.13 sonrası)

Toplam 22 öneri doğrulamadan geçti. Aynı konudakileri birleştirince 18 madde kaldı. 8 öneri elendi; listesi en sonda.

## A) Değiştirelim: hata ve sağlamlık düzeltmeleri

**A1. Servis her başladığında gereksiz "KURTARMA" oluyor** · Efor S · Etki yüksek · `PlaybackService.java:111`, `MainActivity.java:62, 349, 361`
- **Neden:** Donma bekçisi son durum bilgisinin 2 dakikadan eski olup olmadığına bakıyor. Ama `lastStatusAt` servis çalışmıyorken hiç güncellenmiyor. Ana sayfada 2 dakikadan uzun gezinip videoya basınca ya da videoyu 5 dakikadan uzun duraklatıp devam edince, 1 saniye sonra WebView yıkılıp sayfa baştan yükleniyor. Activity günlerce açık kaldığı için bu, neredeyse her oturumun ilk play'inde olabilir. Tam ekrandayken olursa sistem çubukları gizli kalıyor. Ayrıca 120 saniyelik kurtarma hakkını boşa harcıyor.
- **Ne yapılacak:** `onStartCommand`'da `MainActivity.instance.lastStatusAt = now` yapılsın. `rebuildWebView`'in tam ekran dalında sistem çubuğu bayrakları geri alınsın (`SYSTEM_UI_FLAG_VISIBLE`).
- **Kontrol:** uyku.log'da "SERVIS: başladı" satırından yaklaşık 1 sn sonra gelen "KURTARMA: durum-2dk-dir-gelmiyor" satırı artık görünmemeli.

**A2. Araya sızan reklamın ilk saniyesi tam sesle çalabiliyor** · S · orta · `inject.js:85-158, 339-354, 367-369`
- **Neden:** Reklamı sessize alma ve 16x hız yalnız zamanlayıcıyla kontrol ediliyor; ekran kapalıyken bu saniyede bir oluyor. Reklam başlayınca bir sonraki kontrole kadar (en fazla 1 sn) normal hızda ve sesli çalıyor. Art arda iki reklam gelince ikincisinin başında da aynısı olabiliyor. Dosya başındaki yorum "gözlemci" diyor ama kodda MutationObserver yok.
- **Ne yapılacak:**
  - `loadstart`, `play` ve `playing` olaylarında (capture aşamasında) `handleAd()` çağrılsın.
  - `ratechange` ve `volumechange` olaylarında reklam sürüyorsa 16x ve sessiz geri konsun; yalnız değer değiştiyse atansın ki olay döngüsü oluşmasın.
  - `#movie_player`'ın class değişimine bir MutationObserver bağlansın.
  - Mevcut 300 ms ve 1 sn'lik turlar yedek olarak kalsın.
- **Deneme:** DevTools'ta `#movie_player`'a elle `ad-showing` eklenince video anında sessize alınıp 16x olmalı.

**A3. Uyku zamanlayıcısı 5 dakikadan uzun duraklatmada sessizce siliniyor** · S · orta · `PlaybackService.java:157-160`, `MainActivity.java:433, 447-449`
- **Neden:** `stop()` ilk iş olarak `timerEndAt = 0` yapıyor. Video durduktan 5 dakika sonra servis durunca zamanlayıcı da gidiyor. 60 dakikalık zamanlayıcı kurup 6 dakika duraklatan kullanıcı devam edince video bütün gece çalıyor. Tek iz, rozetteki ⏱ işaretinin kaybolması.
- **Ne yapılacak:**
  - Silme satırı `stop()`'tan çıkarılsın. Zamanlayıcıyı yalnız "KAPALI" seçimi ve `onDestroy` temizlesin.
  - `onStartCommand`, süresi zaten dolmuş zamanlayıcıyı temizlesin. Yoksa devam edilen video hemen durur.
  - İsteğe bağlı: süre dolduğunda `__uykuPause` henüz tanımlı değilse (sayfa yükleniyorsa) birkaç tik boyunca yeniden denensin.

**A4. Kendini onarma, kullanıcının ya da zamanlayıcının duraklatmasını unutuyor** · M · yüksek · `MainActivity.java:336-343, 363-388`, `inject.js:48, 73, 146, 232-246, 312-319`, `PlaybackService.java:134-141`
- **Neden:** Örnek gece:
  - Zamanlayıcı videoyu 01:00'de durduruyor.
  - MIUI 03:00'te video motorunu (renderer) öldürüyor. CHANGELOG v1.7'ye göre bu 5 gecenin 5'inde oldu.
  - WebView yeniden kurulunca izleme sayfası açılıyor. `userPause` sıfırlandığı için video sesli başlıyor.
  - Zamanlayıcı artık 0 olduğu için video sabaha kadar çalıyor.

  Video hatasından ve uzun süren reklamdan sonra yapılan yenilemeler de duraklatmaya bakmıyor. Java tarafında "bilerek durduruldu" bilgisi tutulmuyor.
- **Ne yapılacak:**
  - Java'da bir `holdPaused` bayrağı tutulsun. Bayrağı sayfa köprü üzerinden (`onUserPause`), zamanlayıcı ise süre dolunca doğrudan açsın.
  - Bayrak açıksa `rebuildWebView` ölü görünümü kaldırsın ama sayfayı yüklemesin. Sayfa, uygulama yeniden açılınca (`onResume`) yüklensin.
  - inject.js'te uzun reklamdan kaçış ve video hatası yolları, `userPause || v.ended` iken sayfayı yenilemesin.
  - Bayrak, kullanıcı dokunarak play'e basınca temizlensin.
  - Sayfanın yine de yüklendiği yollarda play'i yakalayan koruma preinject.js'te olmalı. inject.js sayfa yüklendikten sonra geldiği için geç kalıyor.
- **Test:** Videoyu zamanlayıcıyla durdur, renderer'ı öldür. Video duraklatılmış olarak kalmalı.

**A5. Kilitlemeden uyuyunca ekran sabaha kadar açık kalıyor** · S · orta · `MainActivity.java:440-449, 643-655`
- **Neden:** Video çalarken `FLAG_KEEP_SCREEN_ON` ekleniyor ve yalnız video durunca kaldırılıyor. Zamanlayıcı kurulmamışsa ve autoplay açıksa ekran bütün gece yanıyor: pil gidiyor, OLED ekranda iz kalma riski var, ışık uykuyu bölüyor. Ayrıca Chromium video çalarken WebView'in kendi `setKeepScreenOn(true)` ayarını açık tutuyor, bu yüzden yalnız pencere bayrağını kaldırmak yetmez.
- **Ne yapılacak:**
  - `KeepAliveWebView`'de `setKeepScreenOn` geçersiz kılınsın; bir "bırak" bayrağı açıkken ekranı açık tutmasın.
  - Son dokunuş zamanı `onUserInteraction`'da kaydedilsin.
  - Ekran açık, video çalıyor, tam ekran değil ve 20-30 dakikadır dokunulmadıysa iki tutma da bırakılsın. Dokununca geri alınsın.
  - Önce telefonda ekran zaman aşımı 30 sn'ye çekilip davranış doğrulansın.

**A6. Gece arızaları uyku.log'a düşmüyor** · S · orta · `PlaybackService.java:130-131, 151-152`, `MainActivity.java:217-241, 335-343, 366`, `inject.js:353, 367-369`
- **Neden:** Şu durumlar yalnız logcat'e yazılıyor:
  - servis başlatılamazsa,
  - ön script sayfaya eklenemezse,
  - kurtarma frene takılırsa,
  - saniyelik kontrol hata verirse.

  Sabah bakıldığında uyku.log'da yalnız NABIZ satırları kalıyor. inject.js'te bütün denetimler tek bir try içinde: reklam kontrolü hata verirse takılma bekçisi ve otomatik devam da o turda sessizce çalışmıyor. Uygulamanın kendisi öldürülürse hiç iz kalmıyor.
- **Ne yapılacak:**
  - Yukarıdaki yollara `AppLog.write` eklensin; aynı satır dakikada en fazla bir kez yazılsın.
  - inject.js'te her denetim ayrı bir try ile sarılsın ve her hata bir kez bildirilsin. Ön script yoksa açılışta uyarı yazılsın.
  - Açılışta (API 30+) `getHistoricalProcessExitReasons` ile uygulamanın neden kapandığı ("ÇIKIŞ: neden=…") ve WebView sürümü yazılsın.
  - Renderer ölüm satırına `rendererPriorityAtExit()` eklensin.

  Böylece MIUI ayarı ya da kalite düşürme gibi kararlar veriye dayanır.

**A7. İnternet kesilince hata sayfasında kalıyor** · M · orta · `MainActivity.java:240-242, 267-344`, `inject.js:305-307`, `PlaybackService.java:77-83, 99-102`
- **Neden:** Gece modem yeniden başlarsa video 150 sn takılıyor ve sayfa internet yokken yenileniyor. WebView hata sayfasına düşüyor; internet geri gelse de hiçbir şey yeniden denemiyor, sabaha kadar ses yok. Sayfada video olmadığı için donma bekçisi de devreye girmiyor. Servis bu sırada wake lock ve Wi-Fi kilidini tutmaya devam ediyor.
- **Ne yapılacak:**
  - Ana sayfa için `onReceivedError` eklensin: hata günlüğe yazılsın ve sayfa artan aralıklarla (30, 60, 120, 300 sn; deneme sayısı sınırlı) yeniden yüklensin.
  - İsteğe bağlı: `ACCESS_NETWORK_STATE` izniyle internet geri gelince hemen denensin.
  - JS'te internet yokken yenileme yapılmasın, `online` olayına kadar beklensin.
  - A4'teki duraklatma bayrağına uyulsun.

**A8. Ön script sessizce devre dışı kalabiliyor; `addDocumentStartJavaScript`'e geçilsin** · M · orta · `MainActivity.java:170-246, 283-288`
- **Neden:** Ön script ancak sayfanın HTML'i ikinci kez indirilip yeniden yazılabilirse ekleniyor. Gece zayıf Wi-Fi'de bu indirme zaman aşımına uğrarsa sayfa ön scriptsiz açılıyor ve reklam verisi oynatıcıya ulaşıyor. YouTube tek sayfalık bir uygulama olduğu için durum oturum boyunca böyle kalıyor. Bu yol çerez, yönlendirme ve güvenlik başlıklarını da kendisi taşıyor; v1.13'teki yamalar da buradaydı.
- **Ne yapılacak:**
  - Önce A6'daki günlükle bunun ne sıklıkla olduğu ölçülsün.
  - Sonra androidx.webkit (en az 1.9.0) eklensin. Destekleniyorsa `WebViewCompat.addDocumentStartJavaScript` kullanılsın; HTML'i yeniden yazma yolu yedek olarak kalsın.
  - Kurallar güncellenince script yeniden kaydedilsin.
- **Dikkat:** Derleme `--offline` yapıldığı için bağımlılık bir kez çevrimiçi indirilmeli. YouTube'un güvenlik başlıkları (CSP) artık yürürlüğe girer; giriş-çıkış ve inject.js telefonda denenmeli.
- **Durum (v1.18, 26 Eki):** ölçüldü — 15–26 Eki arasında 1 kez görüldü (24 Eki 03:02, kurtarmada DNS çözülemedi; WebView 2 sn sonra açabildi). Hafif çözüm yapıldı: bağlantı kurulamazsa 2 sn arayla 2 yeniden deneme. Günlükte "ön script N. denemede eklendi" / "UYARI: ön script eklenemedi (... 3 deneme)" izlensin; yine görülürse asıl çözüme (addDocumentStartJavaScript) geçilsin.

**A9. Kurtarma freni ölü WebView'i ekranda bırakıyor** · S · düşük · `MainActivity.java:336-343, 363-368`
- **Neden:** 120 sn içinde ikinci kez renderer ölürse `rebuildWebView` yalnız log yazıp dönüyor. Ölü WebView ekranda ve `web` alanında kalıyor. Servis çalışmıyorsa kullanıcı siyah bir sayfaya dönüyor; aşağı çekip yenileme de bu ölü sayfayı kullanıyor.
- **Ne yapılacak:** Renderer öldüğünde, frenden bağımsız olarak ölü görünüm hemen kaldırılıp yok edilsin ve yeni bir WebView kurulsun. `web` alanı null bırakılmasın; birçok yer null kontrolü yapmıyor. Fren yalnız sayfa yüklemesini geciktirsin. A1 ve A4 ile aynı kod bölgesi, birlikte yapılabilir.

**A10. Wi-Fi kilidi Android 14+'da gece işe yaramıyor; wake lock 10. saatte düşüyor** · S · düşük · `PlaybackService.java:20-23, 77-83`, `README.md:15, 75`
- **Neden:** `WIFI_MODE_FULL_HIGH_PERF` Android 14'ten beri otomatik olarak düşük gecikme kilidine çevriliyor. O kilit de yalnız ekran açık ve uygulama öndeyken etkin: gece hiçbir işe yaramıyor, gündüz pil harcıyor. Wake lock bir kez 10 saatliğine alınıyor ve uzun bir oturumda yenilenmiyor.
- **Ne yapılacak:**
  - Wi-Fi kilidi yalnız Android 13 ve altında alınsın (`SDK_INT < 34`).
  - Wake lock saniyelik kontrolde yaklaşık 30 dakikada bir yenilensin.
  - README'deki "wifi lock" iddiası ve koddaki "ses odağı alır" yorumu düzeltilsin.

**A11. "Zaten muaf" mesajı MIUI için yanıltıcı** · S · düşük · `MainActivity.java:550-563`
- **Neden:** Kod yalnız Android'in standart pil muafiyetine bakıyor. MIUI'nin ayrı "Kısıtlama yok", "Otomatik başlatma" ve "son uygulamalarda kilitle" ayarları kapalı olabilir.
- **Ne yapılacak:** Bu üç ayar bir kez elle kontrol edilsin; kod gerekmez. Kodda yalnız mesaj değişsin: "Android muafiyeti tamam — MIUI'de ayrıca Kısıtlama yok + Otomatik başlatma seç". Ardından uygulamanın ayar sayfası (`ACTION_APPLICATION_DETAILS_SETTINGS`) açılsın.

## B) Ekleyelim: yeni özellikler

**B1. Kulaklık çıkınca ya da Bluetooth kopunca video duraklasın** · S · yüksek (kulaklıkla uyuyorsan) · `PlaybackService.java` onCreate ve onDestroy (173-184)
- **Neden:** Bluetooth kulaklığın şarjı gece bitince ses telefon hoparlörüne geçiyor ve video çalmaya devam ediyor; sen ya da yanındaki uyanıyor. Android bu an için `ACTION_AUDIO_BECOMING_NOISY` yayınını gönderiyor, ama kodda dinleyen yok. WebView bunu kendisi yapmıyor; ses odağı da bilinçli olarak istenmiyor.
- **Ne yapılacak:** Servisin `onCreate`'inde bu yayın için bir alıcı kaydedilsin, `onDestroy`'da kaldırılsın. Yayın gelince `__uykuPause()` çağrılsın, A4'teki bayrak açılsın ve günlüğe "KULAKLIK: çıktı" yazılsın. Ses odağı istenmesin.

**B2. Bildirim ve kilit ekranından kontrol** · 1. adım S, 2. adım M · orta · `PlaybackService.java:46-53, 61-66`, `MainActivity.java:437-452`
- **Neden:** Bildirime dokununca hiçbir şey olmuyor; düğme, video başlığı ya da kalan süre yok. Yataktan durdurmak ya da süreyi uzatmak için kilidi açıp uygulamaya girmek gerekiyor. WebView'in sistemde bir medya oturumu (MediaSession) olmadığı için kulaklık tuşu FireTube'a ulaşmıyor; hatta Spotify gibi başka bir uygulamayı başlatabiliyor.
- **Ne yapılacak:**
  1. Bildirime dokununca uygulama açılsın (`setContentIntent`, tek satır). "Duraklat", "+15 dk" ve "Durdur" düğmeleri eklensin. Başlık sayfa başlığından alınsın, kalan süre dakikada bir güncellensin.
  2. Sisteme bir `MediaSession` ve `Notification.MediaStyle` bildirimi eklensin. Duraklat/oynat düğmeleri sayfaya bağlansın, durum `Bridge.onPlaying`'den beslensin. Ses odağı istenmesin (`requestAudioFocus` çağrılmasın).
- **Telefonda denenecekler:** Bluetooth tuşu videoyu duraklatmalı, başka bir uygulama başlamamalı, eski play/pause döngüsü geri gelmemeli. İki adım aynı bildirimi değiştiriyor; bildirimi iki kez yazmamak için 2. adım baştan hesaba katılsın.

**B3. "Bu video bitince dur" seçeneği** · M · orta · `inject.js:321-322`, `MainActivity.java:515-546`
- **Neden:** Podcast ya da uzun video dinlerken zamanlayıcı için dakikayı tahmin etmek gerekiyor. Autoplay açıksa sıradaki videoya geçiliyor ve çalma bütün gece sürebiliyor.
- **Ne yapılacak:**
  - Menüye bir seçenek eklensin; seçilince sayfada bir bayrak açılsın.
  - Video bitmeye 1 saniye kala (`timeupdate` olayıyla) `__uykuPause()` çağrılsın.
  - Yedek olarak `ended` olayında da duraklatılsın ve A4'teki bayrak açılsın.
  - Canlı yayınlar ve reklam süresi bu kontrolün dışında tutulsun.

**B4. Ekran kapalıyken en düşük kalite (144p), ekran açılınca eski kalite** · M · orta · `PlaybackService.java:123-129`, `inject.js:356-365`
- **Neden:** Görünürlük taklidi yüzünden Chromium sayfayı hep görünür sanıyor. Gece 6-8 saat boyunca 360p-720p video indirilip çözülüyor. Düşük kalite daha az veri, daha az pil ve zayıf ağda daha az takılma demek.
- **Ne yapılacak:**
  - Önce yaklaşık 10 dakikalık bir ölçüm: telefonda `setPlaybackQualityRange` ve `getAvailableQualityLevels` var mı, ekran kapalıyken video kareleri çözülmeye devam ediyor mu? İkisinden biri yoksa öneri düşer.
  - Varsa ekran kapalıyken yaklaşık 10 saniyede bir en düşük kalite uygulansın; böylece sayfa yenilense de geçerli kalır.
  - Ekran açılınca ve uygulamaya dönülünce eski kalite geri yüklensin.
  - Reklam sırasında kaliteye dokunulmasın. Kalite bilgisi NABIZ satırına eklensin.

**B5. Zamanlayıcı bitmeden önce ses son 60 saniyede yavaşça kısılsın** · S · düşük · `PlaybackService.java:134-141`, `inject.js:78-83`
- **Neden:** Sesin birden kesilmesi uykuya dalmak üzere olan kişiyi uyandırabilir. Uyku zamanlayıcılı uygulamalarda yaygın çözüm sesi yavaşça kısmak.
- **Ne yapılacak:**
  - Sayfaya bir kısma fonksiyonu (`__uykuFade`) eklensin; saniyelik kontrol son 60 saniyede bunu çağırsın.
  - Video duraklatılınca ya da zamanlayıcı iptal edilip uzatılınca ses eski seviyeye dönsün.
  - Kısma yarıda kalırsa ses açılışta mutlaka geri yüklensin: mobil YouTube'da ses kaydırıcısı yok, kısık kalırsa düzeltilemez.
  - Telefonun sistem ses seviyesine dokunulmasın.

**B6. Kaldığın yerden devam** · S · düşük · `inject.js:250-264`, `MainActivity.java:61, 383-385`, `PlaybackService.java:97-108`
- **Neden:** Kurtarma ve yenileme sırasında videonun konumu taşınmıyor. İzleme geçmişi kapalıysa video baştan başlıyor ve uyurken aynı bölüm yeniden çalıyor.
- **Ne yapılacak:**
  - Sayfa yenilenirken video çalıyorsa adrese `t=<şimdiki saniye - 3>s` eklenerek yüklensin.
  - `rebuildWebView` de durum sorgusundan bilinen son saniyeyi eklesin.
  - Shorts, canlı yayın ve reklam bunun dışında tutulsun.
  - İsteğe bağlı: menüye "↩ Kaldığın yer" eklensin (son 24 saat); video asla kendiliğinden açılmasın.

**B7. SponsorBlock ile sponsor ve tanıtım bölümlerini atlama (isteğe bağlı)** · M · düşük · `inject.js:352-354`
- **Neden:** Videoya gömülü sponsor okumalarını hiçbir reklam katmanı görmüyor. Ama bu bölümler çoğunlukla videonun başında, sen henüz uyanıkken geliyor. Uykuya faydası belirsiz; kazanç daha çok uyanıkken konfor.
- **Ne yapılacak:**
  - Menüden açılıp kapansın.
  - Bölüm aralıkları Java tarafında, ayrı bir iş parçacığında sponsor.ajay.app'ten çekilsin; sayfaya yalnız sayılar gönderilsin.
  - Video o aralığa gelince ileri atlansın.
  - Video duraklatılmışken ya da reklam sırasında atlanmasın; her atlama günlüğe yazılsın.

## Önerim: önce şu 3'ü
1. **A1, gereksiz KURTARMA:** tek satırlık bir değişiklik. Neredeyse her oturumu etkiliyor ve A4 ile A9'u tetikleyen durumları da azaltıyor.
2. **A2, reklam olay dinleyicileri:** "reklam uyandırmasın" hedefine doğrudan hizmet ediyor. Yalnız inject.js değişiyor, risk düşük.
3. **B1, kulaklık çıkınca duraklat:** küçük bir alıcı yeterli. Ses kulaklıktan hoparlöre geçince uyandırma riskini ortadan kaldırıyor.

Üçünün de eforu küçük (S); tek sürümde (v1.14) çıkabilirler. Tek satırlık **A3** (zamanlayıcının silinmesi) de aynı sürüme eklenebilir. Sonraki turda A4 ile A9 birlikte yapılsın (aynı kod bölgesi). Ardından A6'nın günlüğüyle bir hafta veri toplanıp A7, A8 ve B4'e karar verilsin.

## Elenenler
- **Durum kaydında sınırsız geçmiş ve çökme riski:** Chromium geçmişi 50 kayıtla sınırlıyor ve haftalardır çökme görülmedi; `loadUrl(null)` da zararsız.
- **Kilitlemeden uyuyunca siyah "gece perdesi":** normal izlemeyi de bozuyor ve zamanlayıcısız durumu kapsamıyor; A5 aynı sorunu daha basit çözüyor.
- **Sunucu tarafında eklenen (SSAP) reklamlar için algılayıcı:** mobil YouTube'da SSAP görüldüğüne dair kanıt yok; yanlış ileri sarma içerik kaçırtır.
- **Reklam seçicilerinin rules.json'dan güncellenmesi:** asıl sinyal `ad-showing` yıllardır aynı, atla düğmesi olmadan da reklam geçiliyor; efor orta, getiri çok küçük.
- **fetch/XHR sarmalayıcılarındaki ve iframe'lerdeki boşluklar:** bugün mobil YouTube'da bir arıza yok, fetch yolu çalışıyor; ileriye dönük bir önlem ve kırılma riski taşıyor.
- **Ana sayfa ve Shorts reklam kartları için görsel filtreler:** yalnız görsel, uyandırmaz; maliyetine değmez.
- **Engellenen isteklere CORS başlığı eklemek:** tek etkisi logcat'teki konsol gürültüsü; değeri yok, yeni bir hata yolu açabilir.
- **targetSdk 36 hazırlığı (geri hareketi, kenar boşlukları):** uygulama kişisel ve elle kuruluyor, 36'ya geçme zorunluluğu yok; somut bir kullanıcı sorunu da yok.