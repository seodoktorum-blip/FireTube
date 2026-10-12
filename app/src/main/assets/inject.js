/* YouTube sayfasının içine MainActivity tarafından enjekte edilir.
   window bayrağı sayesinde sayfa içi geçişlerde bir kez kurulur ve
   gözlemci tüm SPA gezinmeleri boyunca yaşar. */
(function () {
  if (window.__uykuTube) return;
  window.__uykuTube = true;

  var q = function (s) { return document.querySelector(s); };
  var say = function (m) { try { if (window.FireTube) window.FireTube.log(m); } catch (e) {} };

  // Her denetimin hatası bir kez günlüğe yazılır: tek try içinde bir denetimin
  // hatası diğer bekçileri de sessizce susturuyordu.
  var reported = {};
  function reportError(name, e) {
    var key = name + ':' + (e && e.message);
    if (reported[key]) return;
    reported[key] = 1;
    say('HATA ' + name + ': ' + (e && e.message));
  }
  function run(name, fn) {
    try { fn(); } catch (e) { reportError(name, e); }
  }
  if (!window.__firePre && /(^|\.)youtube\.com$/.test(location.hostname)) say('UYARI: ön script yok (sayfa yeniden yazılamadı) — reklam verisi temizlenmiyor');

  /* Ekran kapanınca Android sayfayı "gizli" işaretler ve YouTube oynatıcısı
     kendini duraklatır. Sayfaya her zaman görünür olduğunu söylüyoruz; böylece
     ekran kapalıyken de çalmaya devam eder. */
  function spoofVisibility() {
    try {
      Object.defineProperty(Document.prototype, 'hidden', { get: function () { return false; }, configurable: true });
      Object.defineProperty(Document.prototype, 'visibilityState', { get: function () { return 'visible'; }, configurable: true });
      Object.defineProperty(Document.prototype, 'webkitHidden', { get: function () { return false; }, configurable: true });
      Object.defineProperty(Document.prototype, 'webkitVisibilityState', { get: function () { return 'visible'; }, configurable: true });
      Document.prototype.hasFocus = function () { return true; };
    } catch (e) {}
    // Ekran kapanınca sayfa "blur" olayı da alır; YouTube bunu duraklatmak için
    // kullanabiliyor. visibilitychange dahil bu olayların sayfaya ulaşması engelleniyor.
    // Yalnız pencere/belge düzeyindeki olaylar yutulur: yakalama aşamasında
    // her şeyi yutmak input/arama kutusu gibi öğelerin blur'unu da öldürüyordu.
    ['visibilitychange', 'webkitvisibilitychange', 'blur', 'pagehide'].forEach(function (ev) {
      var swallow = function (e) {
        if (e.target === window || e.target === document) e.stopImmediatePropagation();
      };
      window.addEventListener(ev, swallow, true);
      document.addEventListener(ev, swallow, true);
    });
  }
  spoofVisibility();

  var adActive = false;
  var savedMuted = false;
  var savedRate = 1;
  var adStartAt = 0;      // reklam modu başlama zamanı
  var adLastPos = -1;     // reklam videosunun son görülen konumu
  var adProgressAt = 0;   // reklam videosunun son ilerleme zamanı
  var adEscapeDone = false;
  var lastSkipClickAt = 0;
  var lastAdSeekAt = 0;
  var stallSince = 0;
  var stallRetries = 0;
  var lastUser = Date.now();

  /* Duraklatma niyeti iki katmanlı:
     - userPause: bizim bekçilerimiz (autoStart, handleStall, reklam) videoyu
       kendiliğinden sürdürmez. Herhangi bir play olayında kalkar.
     - holdPause: Android tarafının (zamanlayıcı, kulaklık, telefon, bildirim,
       video sonu) ya da önceki sayfanın duraklatması: kullanıcı dokunuşu
       olmadan gelen HER play (YouTube autoplay, sıradaki video) anında geri
       duraklatılır. Kullanıcı dokunuşu / __uykuResume kaldırır.
     userPause değişince Android'e bildirilir: sayfa yeniden yüklenirse
     (kurtarma, yenileme) yeni sayfa duraklatılmış başlar. */
  var userPause = false;
  var holdPause = false;
  var pauseOwner = null; // son duraklatmanın sahibi: 'telefon', 'zamanlayıcı', 'kullanıcı'...
  var lastHoldLogAt = 0;
  function setUserPause(on) {
    if (userPause === on) return;
    userPause = on;
    try { if (window.FireTube) window.FireTube.onHold(on); } catch (e) {}
  }
  /* Duraklatılmış açılan sayfada YouTube adresteki t= başlangıcını kendi
     (engellenen) otomatik başlatmasında uygular: devam edilince video 0'dan
     başlıyordu. Başlangıç saklanır, ilk oynatmada o saniyeye atlanır. */
  var bootStart = null;
  try {
    if (window.__uykuHoldBoot) {
      var bu = new URL(location.href);
      var bt = parseInt((bu.searchParams.get('t') || '').replace(/s$/, ''), 10);
      if (bt > 0) bootStart = { vid: bu.searchParams.get('v'), t: bt };
    }
  } catch (e) {}
  function applyBootStart(v) {
    if (!bootStart || !v) return;
    try {
      var vid = new URL(location.href).searchParams.get('v');
      if (vid === bootStart.vid && v.currentTime < bootStart.t - 5) v.currentTime = bootStart.t;
    } catch (e) {}
    bootStart = null;
  }

  // Önceki sayfadan devralınan duraklatma (ön script daha önce korumaya başladı)
  // Dokunuş inject'ten önce geldiyse kilit (holdPause) kalkmıştır ama niyet
  // (userPause) sürer; Java'nın güncel niyeti eşzamanlı sorulur (yükleme
  // sırasında bildirimden "Oynat"a basılmış olabilir).
  if (window.__uykuHoldBoot) {
    var early = window.__uykuHoldEarly === true;
    window.__uykuHoldEarly = false;
    var javaHold = true;
    try { if (window.FireTube && window.FireTube.isHold) javaHold = window.FireTube.isHold(); } catch (e) {}
    var v0 = q('video');
    if (!javaHold) {
      say('önceki duraklatma bu arada kaldırılmış — kilit yok');
    } else if (v0 && !v0.paused && !v0.ended) {
      // kullanıcı inject gelmeden videoyu oynattı: niyet kalktı
      try { if (window.FireTube) window.FireTube.onHold(false); } catch (e) {}
    } else {
      userPause = true;
      holdPause = early;
      pauseOwner = window.__uykuHoldOwner || 'önceki sayfa';
      say('önceki duraklatma korunuyor (' + pauseOwner + (early ? '' : ', dokunuldu — kilit yok') + ')');
    }
  }

  // Geri tuşu / geri hareketi sayfaya dokunuş olarak ulaşmaz: Android tarafı
  // gezinmeden önce bunu çağırır (kullanıcı uyanık → kilit kalkar, niyet sürer)
  window.__uykuWake = function () {
    lastUser = Date.now();
    holdPause = false;
    window.__uykuHoldEarly = false;
  };

  // Kullanıcının son dokunuşu: sistem kaynaklı duraklamaları kullanıcı
  // duraklatmasından ayırt etmek için takip ediliyor. Dokunuş = uyanık:
  // duraklatma kilidi (holdPause) kalkar, userPause play'e kadar sürer.
  ['pointerdown', 'keydown', 'wheel', 'touchstart'].forEach(function (ev) {
    document.addEventListener(ev, function () {
      lastUser = Date.now();
      holdPause = false;
    }, { capture: true, passive: true });
  });

  // Kilit açıkken dokunuşsuz play → hemen geri duraklat (diğer play
  // dinleyicilerinden ÖNCE kaydedilir; Android'e "çalıyor" sinyali gitmesin)
  document.addEventListener('play', function (e) {
    if (!holdPause || !e.target || e.target.tagName !== 'VIDEO') return;
    if (Date.now() - (window.__uykuUserPlayAt || 0) < 5000) { holdPause = false; return; }
    try { e.target.pause(); } catch (x) {}
    e.stopImmediatePropagation();
    if (Date.now() - lastHoldLogAt > 60000) {
      lastHoldLogAt = Date.now();
      say('duraklatma korunuyor (' + pauseOwner + ') — kendiliğinden oynatma engellendi');
    }
  }, true);

  function notifyPlaying() {
    var v = q('video');
    try { if (v && window.FireTube) window.FireTube.onPlaying(!v.paused && !v.ended); } catch (e) {}
  }
  ['play', 'playing', 'pause', 'ended'].forEach(function (ev) {
    document.addEventListener(ev, notifyPlaying, true);
  });

  // Dokunma olmadan gelen kullanıcı duraklatmaları: medya düğmeleri
  // (preinject'teki mediaSession sarmalayıcısı __uykuUserPauseAt'i işaretler)
  document.addEventListener('pause', function () {
    // Kilit açıkken gelen pause bizimdir (__uykuPause / kilit dinleyicisi):
    // dokunuş kilidi önceden kaldırdığı için gerçek kullanıcı duraklatması kaçmaz
    if (holdPause) return;
    var now = Date.now();
    if (now - lastUser < 5000 || now - (window.__uykuUserPauseAt || 0) < 5000) {
      pauseOwner = 'kullanıcı';
      setUserPause(true);
      say('kullanıcı duraklattı — otomatik devam kapalı');
    }
  }, true);
  document.addEventListener('play', function (e) {
    pauseOwner = null;
    setUserPause(false);
    if (e.target && e.target.tagName === 'VIDEO') {
      // Java niyeti JS'ten ayrışmış olabilir (önyükleme): her gerçek play eşitler
      try { if (window.FireTube) window.FireTube.onHold(false); } catch (x) {}
      applyBootStart(e.target);
    }
  }, true);

  /* Android tarafı (zamanlayıcı, kulaklık çıkması, telefon, bildirim, video
     sonu) bunu çağırır: düz video.pause() "sistem duraklatması" sanılıp
     autoStart/handleStall tarafından birkaç saniyede geri alınıyordu.
     Video o an çalmıyorsa bile (bitti / autoplay geçişi) kilit kurulur:
     YouTube'un sıradaki play'i engellenir. Dönüş: video çalıyor ya da
     sıradakine geçiyor muydu (telefon bitince sürdürülsün mü). */
  window.__uykuPause = function (reason) {
    reason = reason || 'zamanlayıcı';
    var v = q('video');
    var wasPlaying = !!(v && !v.paused && !v.ended);
    var inTransition = !!(v && !userPause && (v.ended || v.paused));
    // Var olan bir kullanıcı/zamanlayıcı duraklatmasının sahibi değişmez
    if (wasPlaying || inTransition || !userPause
        || (pauseOwner === 'telefon' && reason !== 'telefon')) pauseOwner = reason;
    holdPause = true;
    setUserPause(true);
    if (wasPlaying) v.pause();
    restoreFade();
    say(reason + ': video durduruldu — otomatik devam kapalı');
    return wasPlaying || inTransition;
  };

  /* Arama bitince / bildirimden "Oynat" ile Android tarafı çağırır. Telefonun
     sürdürmesi yalnız duraklatmanın sahibi hâlâ telefonsa yapılır: görüşme
     sırasında zamanlayıcı dolduysa ya da kulaklık çıktıysa video durmuş kalır. */
  window.__uykuResume = function (reason) {
    if (reason === 'telefon bitti' && pauseOwner !== 'telefon') {
      say('telefon bitti ama duraklatma artık ' + pauseOwner + ' — sürdürülmedi');
      return false;
    }
    holdPause = false;
    pauseOwner = null;
    setUserPause(false);
    var v = q('video');
    if (!v || !v.paused || v.ended) return false;
    var p = v.play();
    if (p && p.catch) p.catch(function () {});
    say((reason || 'devam') + ': video sürdürüldü');
    return true;
  };

  /* Zamanlayıcının son 60 sn'si: ses yavaşça kısılır (f: 1→0). f>=1 ya da
     duraklatma sesi eski düzeyine döndürür — mobil YouTube'da ses kaydırıcısı
     yok, kısık kalırsa düzeltilemezdi. Telefonun sistem sesine dokunulmaz. */
  var fadeBase = null;
  function restoreFade() {
    var v = q('video');
    if (fadeBase !== null && v) v.volume = fadeBase;
    fadeBase = null;
  }
  window.__uykuFade = function (f) {
    var v = q('video');
    if (!v) return;
    if (f >= 1) { restoreFade(); return; }
    if (adActive) return;
    if (fadeBase === null) fadeBase = v.volume;
    v.volume = Math.max(0, Math.min(1, fadeBase * f));
  };

  /* "Bu video bitince dur": Android tarafı açar (sayfa yenilense de yeniden
     iter). Video bitmeye ~1 sn kala — autoplay sıradakine geçmeden —
     duraklatılır. Canlı yayın (süre sonsuz) ve reklam hariç. */
  var stopAtEnd = false;
  window.__uykuStopAtEnd = function (on) { stopAtEnd = !!on; };
  function finishStopAtEnd() {
    stopAtEnd = false;
    window.__uykuPause('video sonu');
    try { if (window.FireTube) window.FireTube.onStopAtEndDone(); } catch (e) {}
  }
  function checkStopAtEnd() {
    if (!stopAtEnd || adActive) return;
    var v = q('video');
    if (!v || v.paused) return;
    var d = v.duration;
    if (!isFinite(d) || d <= 0) return;
    if (d - v.currentTime <= 1.2) finishStopAtEnd();
  }
  document.addEventListener('timeupdate', function (e) {
    if (stopAtEnd && e.target && e.target.tagName === 'VIDEO') run('videosonu', checkStopAtEnd);
  }, true);
  document.addEventListener('ended', function (e) {
    if (stopAtEnd && !adActive && e.target && e.target.tagName === 'VIDEO') run('videosonu', finishStopAtEnd);
  }, true);

  // Bildirim/kilit ekranı için başlık ve kanal (JSON metni)
  // (kanal: sahip simgesinin aria-label'ı "Kanal Adı, 852 B abone. Kanala gidin.")
  window.__uykuMeta = function () {
    var channel = '';
    var icon = q('a.slim-video-owner-icon');
    var aria = icon && icon.getAttribute('aria-label');
    if (aria) channel = aria.split(',')[0].trim();
    if (!channel) {
      var ch = q('.slim-video-information-channel-name, .slim-owner-channel-name');
      channel = ch ? (ch.textContent || '').trim() : '';
    }
    return JSON.stringify({
      title: (document.title || '').replace(/ - YouTube$/, ''),
      channel: channel.slice(0, 80),
      watch: location.pathname === '/watch',
    });
  };

  function handleAd() {
    var p = q('#movie_player') || q('.html5-video-player');
    var v = q('video');
    if (!p || !v) return;
    var ad = (p.classList && p.classList.contains('ad-showing')) ||
             !!q('.ytp-ad-player-overlay, .videoAdUi, .ytp-ad-overlay-close-container');
    if (ad) {
      if (!adActive) {
        adActive = true;
        savedMuted = v.muted;
        savedRate = v.playbackRate || 1;
        adStartAt = Date.now();
        adProgressAt = Date.now();
        adLastPos = -1;
        say('reklam algılandı — ses kısılıp hızlandırılıyor');
        try { if (window.FireTube) window.FireTube.onAdBlocked(); } catch (e) {}
      }
      // Reklamı ağdan engelleyemediysek bile uyandırmasın: sessiz + 16x hız
      v.muted = true;
      try { if (v.playbackRate < 16) v.playbackRate = 16; } catch (e) {}
      // Duraklatılmışken (telefon, zamanlayıcı, kulaklık...) atlama/sarma/kaçış
      // yapılmaz, sayaçlar donar: yoksa 90 sn sonra "reklam takıldı" yenilemesi
      // ya da atla tıklaması duraklatmayı silip videoyu başlatabiliyordu.
      if (userPause && v.paused) {
        adStartAt = Date.now();
        adProgressAt = Date.now();
        adLastPos = v.currentTime;
        return;
      }
      // En hızlı yol: reklamı İNDİRİLMİŞ verinin sonuna atla (veriyi beklemek
      // yok; 16x de arka planda sürer). 2 sn'de bir tekrarlanır, her seferinde
      // daha ileriye atlar. Tam sona atlamak kuyruk indirmeyi bekletiyordu.
      if (Date.now() - lastAdSeekAt > 2000) {
        lastAdSeekAt = Date.now();
        var adDur2 = v.duration;
        var target = 0;
        try {
          var b = v.buffered;
          for (var bi = 0; bi < b.length; bi++) target = Math.max(target, b.end(bi));
        } catch (e) {}
        if (isFinite(adDur2) && adDur2 > 0) target = Math.min(target, adDur2 - 0.2);
        if (target > v.currentTime + 1) {
          try {
            v.currentTime = target;
            say('reklam ileri atlandı → ' + Math.round(target) + '/' +
                Math.round(isFinite(adDur2) ? adDur2 : 0) + ' sn');
          } catch (e) {}
        }
      }
      // Atla düğmesi en fazla 2 sn'de bir tıklanır: daha sık basışta oynatıcı
      // tıklama fırtınasıyla bozulabiliyordu (günlükte 4 sn'de 30+ basış görüldü)
      var skip = q('.ytp-ad-skip-button-modern, .ytp-ad-skip-button, .ytp-skip-ad-button, .videoAdUiSkipButton, .ytp-ad-skip-slot button');
      if (skip && Date.now() - lastSkipClickAt > 2000) {
        lastSkipClickAt = Date.now();
        skip.click();
        say('atla düğmesine basıldı');
      }

      // Reklam-takılma çıkışı: yalnızca reklam videosu OYNIYOR ama ilerlemiyorsa
      // "donuk" sayılır; duraklıysa önce oynatılmaya çalışılır. Çıkışlar
      // uykuReload freninden geçer (döngü olmasın).
      if (v.currentTime > adLastPos + 0.15) {
        adLastPos = v.currentTime;
        adProgressAt = Date.now();
      } else if (v.paused && !userPause) {
        var ap = v.play();
        if (ap && ap.catch) ap.catch(function () {});
      }
      var adTotalMs = Date.now() - adStartAt;
      var adFrozenMs = v.paused ? 0 : Date.now() - adProgressAt;
      if (!adEscapeDone && (adFrozenMs > 30000 || adTotalMs > 90000)) {
        adEscapeDone = true;
        uykuReload('reklam takıldı (toplam ' + Math.round(adTotalMs / 1000) +
            ' sn) — sayfa yenileniyor');
      }
    } else if (adActive) {
      adActive = false;
      adEscapeDone = false;
      v.muted = savedMuted;
      try { v.playbackRate = savedRate; } catch (e) {}
      say('reklam bitti — ses geri açıldı');
    }
  }

  /* Reklama ANINDA tepki: yalnız turlara (ekran kapalıyken saniyede bir)
     güvenilirse sızan reklamın ilk saniyesi tam sesle çalabiliyordu (günlükte
     gece 02-04 arası 4 sızıntı). Medya olayları ve oynatıcının sınıf değişimi
     handleAd'i hemen çağırır; turlar yedek olarak kalır. */
  function safeHandleAd() {
    try { handleAd(); } catch (e) { reportError('handleAd', e); }
  }
  ['loadstart', 'loadedmetadata', 'durationchange', 'play', 'playing'].forEach(function (ev) {
    document.addEventListener(ev, function (e) {
      if (e.target && e.target.tagName === 'VIDEO') safeHandleAd();
    }, true);
  });
  // Reklam sürerken oynatıcı hızı/sesi geri alırsa hemen yeniden uygula
  // (yalnız değer farklıysa atanır: kendi atamamız olay döngüsü yaratmaz)
  ['ratechange', 'volumechange'].forEach(function (ev) {
    document.addEventListener(ev, function (e) {
      var v = e.target;
      if (!adActive || !v || v.tagName !== 'VIDEO') return;
      if (!v.muted) v.muted = true;
      try { if (v.playbackRate < 16) v.playbackRate = 16; } catch (x) {}
    }, true);
  });
  var observedPlayer = null, playerObserver = null;
  function observePlayer() {
    if (typeof MutationObserver === 'undefined') return;
    var p = q('#movie_player') || q('.html5-video-player');
    if (!p || p === observedPlayer) return;
    if (playerObserver) playerObserver.disconnect();
    playerObserver = new MutationObserver(safeHandleAd);
    playerObserver.observe(p, { attributes: true, attributeFilter: ['class'] });
    observedPlayer = p;
  }

  function handleDialogs() {
    // "Video duraklatıldı. İzlemeye devam edilsin mi?" oynatıcı onayı
    var dlg = q('.ytp-confirm-dialog');
    if (dlg && dlg.clientHeight > 0) {
      var btns = Array.prototype.slice.call(dlg.querySelectorAll('button'));
      var yes = null;
      for (var i = 0; i < btns.length; i++) {
        var t = (btns[i].textContent || '') + ' ' + (btns[i].getAttribute('aria-label') || '');
        if (/devam|continue|reprendre|fortfahren|weiter/i.test(t)) { yes = btns[i]; break; }
      }
      var target = yes || btns[btns.length - 1];
      if (target) { target.click(); say('oynatıcı onay kutusu onaylandı'); }
    }
    // "İzlemeye devam etmek istiyor musun?" sayfa içi onay kutusu
    var ytd = q('ytd-confirm-dialog-renderer');
    if (ytd && /devam|continue|watching|izlemeye/i.test(ytd.textContent || '')) {
      var b = ytd.querySelector('#confirm-button, yt-button-shape button');
      if (b) { b.click(); say('sayfa onay kutusu onaylandı'); }
    }
  }

  // "Uygulamayı aç" / "Open app" butonu ve akıllı bant: hem CSS hem metin eşleyerek gizle
  function hideOpenApp() {
    var sel = 'ytm-mobile-topbar-renderer a, ytm-mobile-topbar-renderer button, ' +
              'ytm-mobile-topbar-renderer [role="button"], ytm-mobile-topbar-renderer ytm-button-renderer, ' +
              '#smart-banner, ytm-smart-banner-renderer, ytm-smart-banner';
    var els = document.querySelectorAll(sel);
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      var t = ((el.textContent || '') + ' ' + (el.getAttribute('aria-label') || '')).trim();
      if (t.length < 30 && /uygulam|open (the )?app|open in app|app'i aç|appyi aç|get the app/i.test(t)) {
        if (el.style.display !== 'none') say('"uygulamayı aç" ögesi gizlendi');
        el.style.display = 'none';
      }
    }
  }

  // "Uygulamada aç" tarzı bantları gizle
  function hideClutter() {
    if (document.getElementById('uyku-style')) return;
    var st = document.createElement('style');
    st.id = 'uyku-style';
    st.textContent = '#smart-banner,.smart-banner,ytm-smart-banner,ytm-smart-banner-renderer,' +
        '#masthead-ad,ytd-promoted-sparkles-web-renderer{display:none !important;}';
    document.documentElement.appendChild(st);
  }

  function handleStall() {
    var v = q('video');
    if (!v) return;
    if (v.paused && !v.ended && !adActive && !userPause && v.readyState >= 2
        && Date.now() - lastUser > 60000) {
      if (!stallSince) {
        stallSince = Date.now();
      } else if (Date.now() - stallSince > 15000 && stallRetries < 8) {
        stallSince = 0;
        stallRetries++;
        say('video beklenmedik durmuş — devam ettiriliyor');
        var p = v.play();
        if (p && p.catch) p.catch(function () {});
      }
    } else if (!v.paused) {
      stallSince = 0;
      stallRetries = 0;
    }
  }

  // Video kendiliğinden başlamazsa (mweb bazen duraklatılmış açar) otomatik başlat.
  // Denemeler en az 3 sn arayla: ekran kapanınca oluşan tek seferlik duraklamaları
  // yakalayıp sessizce telafi etsin.
  var autoStartTries = 0;
  var autoStartLastAt = 0;
  function autoStart() {
    if (location.pathname !== '/watch') return;
    var v = q('video');
    var now = Date.now();
    if (v && v.paused && !v.ended && !userPause && !adActive && v.readyState >= 2
        && autoStartTries < 10 && now - lastUser > 4000
        && now - autoStartLastAt > 3000) {
      autoStartTries++;
      autoStartLastAt = now;
      say('otomatik başlatma deneniyor (' + autoStartTries + ')');
      var p = v.play();
      if (p && p.catch) p.catch(function (e) { say('başlatma reddedildi: ' + (e && e.name)); });
    }
    if (!v || !v.paused) autoStartTries = 0;
  }

  /* Tüm otomatik yenilemeler buradan geçer: 10 dakikada en fazla 3 kez.
     (Yanlış tetiklenme durumunda bile yenileme döngüsüne girilmesin.) */
  function uykuReload(reason) {
    var now = Date.now();
    var times = [];
    try { times = JSON.parse(sessionStorage.getItem('__uykuRL') || '[]'); } catch (e) {}
    times = times.filter(function (t) { return now - t < 600000; });
    if (times.length >= 3) {
      say('otomatik yenileme sınırı doldu (10 dk/3) — yenilenmedi: ' + reason);
      return false;
    }
    times.push(now);
    try { sessionStorage.setItem('__uykuRL', JSON.stringify(times)); } catch (e) {}
    var target = resumeUrl();
    var go = function () {
      if (target) location.replace(target); else location.reload();
    };
    // Ağ yokken yenilemek WebView'i hata sayfasında bırakıyordu: bağlantıyı bekle
    // (navigator.onLine'ı Android tarafı ağ durumuna göre günceller)
    if (navigator.onLine === false) {
      say(reason + ' — ağ yok, bağlantı gelince yenilenecek');
      window.addEventListener('online', function once() {
        window.removeEventListener('online', once);
        setTimeout(go, 1500);
      });
      return true;
    }
    say(reason + (target ? ' (kaldığı yerden: ' + target.replace(/.*[?&]t=/, 't=') + ')' : ''));
    setTimeout(go, 1200);
    return true;
  }

  /* Yenilemede kaldığın saniyeden devam: izleme geçmişi kapalıysa video
     baştan başlıyordu (günlükte 43. dk → 4. dk). Shorts, canlı yayın ve
     reklam sırasında (konum reklamın konumu) uygulanmaz. */
  function resumeUrl() {
    try {
      var v = q('video');
      if (location.pathname !== '/watch' || !v || v.ended) return null;
      var u = new URL(location.href);
      var t;
      if (adActive) {
        // Reklam sırasında v.currentTime reklamın konumu: reklamdan önceki son
        // içerik konumu kullanılır (aynı video, 10 dk'dan taze)
        if (lastContent.vid !== u.searchParams.get('v') || Date.now() - lastContent.at > 600000) return null;
        t = Math.floor(lastContent.t) - 3;
      } else {
        if (!isFinite(v.duration) || v.duration <= 0) return null;
        t = Math.floor(v.currentTime) - 3;
      }
      if (t < 10) return null;
      u.searchParams.set('t', t + 's');
      return u.toString();
    } catch (e) {
      return null;
    }
  }

  // Reklam dışı son içerik konumu (reklam kaçışı yenilemesinde kullanılır)
  var lastContent = { vid: null, t: 0, at: 0 };
  document.addEventListener('timeupdate', function (e) {
    var v = e.target;
    if (adActive || !v || v.tagName !== 'VIDEO' || location.pathname !== '/watch') return;
    if (!isFinite(v.duration) || v.duration <= 0 || v.currentTime < 1) return;
    try {
      lastContent = { vid: new URL(location.href).searchParams.get('v'), t: v.currentTime, at: Date.now() };
    } catch (x) {}
  }, true);

  /* Akış takılması (ses gider, sonra bazen döner bazen dönmez): oynarken
     pozisyon ilerlemiyorsa önce küçük bir dürtme, düzelmezse sayfa yenileme. */
  var lastPos = -1, lastAdvanceAt = Date.now(), lastNudgeAt = 0, lastVideoEl = null;
  function handleBufferStall() {
    var v = q('video');
    if (!v) { lastAdvanceAt = Date.now(); lastPos = -1; lastVideoEl = null; return; }
    // Video elementi değiştiyse (yeni video) sayaç sıfırlanmalı — yoksa
    // "ilerlemiyor" sanılıp her videoda hatalı yenileme yapılırdı.
    if (v !== lastVideoEl) {
      lastVideoEl = v;
      lastPos = v.currentTime;
      lastAdvanceAt = Date.now();
      return;
    }
    var pos = v.currentTime;
    if (pos > lastPos + 0.2) {
      lastPos = pos;
      lastAdvanceAt = Date.now();
      return;
    }
    if (pos < lastPos - 2) { // yeni video ya da geri sarma: takılma değil
      lastPos = pos;
      lastAdvanceAt = Date.now();
      return;
    }
    // Duraklatılmış/reklamdaki süre takılma sayılmaz: sayaç burada tazelenir,
    // yoksa uzun bir duraklatmadan sonra devam edince hemen "takıldı" sanılıyordu.
    if (v.paused || adActive || userPause) {
      lastPos = pos;
      lastAdvanceAt = Date.now();
      return;
    }
    var stuckMs = Date.now() - lastAdvanceAt;
    if (stuckMs > 45000 && Date.now() - lastNudgeAt > 30000) {
      lastNudgeAt = Date.now();
      say('akış takıldı (' + Math.round(stuckMs / 1000) + ' sn) — dürtülüyor');
      try { v.currentTime = pos + 0.25; } catch (e) {}
      var p = v.play();
      if (p && p.catch) p.catch(function () {});
    } else if (stuckMs > 150000) {
      uykuReload('akış 2,5 dakikadır takılı — sayfa yenileniyor');
    }
  }

  /* Ölümcül video hatası (ağ/çözüme hatası): oynatıcı simsiyah kalır;
     birkaç saniye sonra sayfa yenilenip kaldığı yerden sürdürülür. */
  document.addEventListener('error', function (e) {
    var t = e.target;
    // kod 1 (aborted) zararsızdır; 2-4 (ağ/çözüme/kaynak) ölümcül
    if (t && t.tagName === 'VIDEO' && t.error && t.error.code !== 1) {
      if (userPause) { say('video hatası kod=' + t.error.code + ' — duraklatılmış, yenilenmedi'); return; }
      say('video hatası kod=' + t.error.code + ' — 5 sn sonra sayfa yenilenecek');
      setTimeout(function () {
        if (!userPause) uykuReload('video hatası kod=' + t.error.code);
      }, 5000);
    }
  }, true);

  /* Video bitince müdahale YOK: YouTube'un kendi autoplay ayarı kapalıysa
     video bitince durması doğrudur; açıksa YouTube kendisi sıradakine geçer. */

  /* Güvence süpürmesi: YouTube kendi verisini sonradan yeniden atayıp
     reklam alanlarını geri getirebilir (ön script'in setter tuzağını
     defineProperty ile ezerek). Her turda ucuzca kontrol edilir. */
  function sweepPlayerResponse() {
    try {
      var y = window.ytInitialPlayerResponse;
      if (y && (y.adPlacements || y.adSlots)) {
        delete y.adPlacements;
        delete y.adSlots;
        delete y.adBreakHeartbeatParams;
        say('geç gelen player verisinden reklam alanları silindi');
      }
    } catch (e) {}
  }

  // Oynatma denetimleri: her biri ayrı korunur (biri hata verirse diğerleri sürer)
  var CHECKS = [
    ['sweep', sweepPlayerResponse], ['gozlemci', observePlayer], ['reklam', handleAd],
    ['diyalog', handleDialogs], ['durma', handleStall], ['takilma', handleBufferStall],
    ['otobaslat', autoStart], ['videosonu', checkStopAtEnd],
  ];
  function runChecks() {
    for (var i = 0; i < CHECKS.length; i++) run(CHECKS[i][0], CHECKS[i][1]);
  }

  function tickChecks() {
    runChecks();
    run('clutter', hideClutter);
    run('openapp', hideOpenApp);
  }

  /* Ekran kapalıyken tarayıcı zamanlayıcıları kısıtlanır; Android tarafı
     (PlaybackService) bu fonksiyonu saniyede bir doğrudan çağırır. */
  window.__uykuTick = runChecks;

  window.__uykuStatus = function () {
    var v = q('video');
    if (!v) return 'video-yok';
    return (v.paused ? (v.ended ? 'BITTI' : 'DURAKLADI') : 'caliyor')
        + ' t=' + Math.floor(v.currentTime) + 's/' + Math.floor(v.duration || 0) + 's'
        + ' rs=' + v.readyState
        + (adActive ? ' [REKLAM]' : '')
        + (userPause ? ' [KULLANICI-DURDURDU]' : '')
        + ' sayfa=' + location.pathname.slice(0, 18);
  };

  setInterval(tickChecks, 300);
  run('gozlemci', observePlayer);

  say('hazır');
})();
