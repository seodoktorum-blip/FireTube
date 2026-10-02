/* YouTube sayfasının içine MainActivity tarafından enjekte edilir.
   window bayrağı sayesinde sayfa içi geçişlerde bir kez kurulur ve
   gözlemci tüm SPA gezinmeleri boyunca yaşar. */
(function () {
  if (window.__uykuTube) return;
  window.__uykuTube = true;

  var q = function (s) { return document.querySelector(s); };
  var say = function (m) { try { if (window.FireTube) window.FireTube.log(m); } catch (e) {} };

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
    ['visibilitychange', 'webkitvisibilitychange', 'blur', 'pagehide'].forEach(function (ev) {
      window.addEventListener(ev, function (e) { e.stopImmediatePropagation(); }, true);
      document.addEventListener(ev, function (e) { e.stopImmediatePropagation(); }, true);
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
  var stallSince = 0;
  var stallRetries = 0;
  var lastUser = Date.now();
  var userPause = false;

  // Kullanıcıın son dokunuşu: sistem kaynaklı duraklamaları kullanıcı
  // duraklatmasından ayırt etmek için takip ediliyor.
  ['pointerdown', 'keydown', 'wheel', 'touchstart'].forEach(function (ev) {
    document.addEventListener(ev, function () { lastUser = Date.now(); }, { capture: true, passive: true });
  });

  function notifyPlaying() {
    var v = q('video');
    try { if (v && window.FireTube) window.FireTube.onPlaying(!v.paused && !v.ended); } catch (e) {}
  }
  ['play', 'playing', 'pause', 'ended'].forEach(function (ev) {
    document.addEventListener(ev, notifyPlaying, true);
  });

  document.addEventListener('pause', function () {
    if (Date.now() - lastUser < 5000) {
      userPause = true;
      say('kullanıcı duraklattı — otomatik devam kapalı');
    }
  }, true);
  document.addEventListener('play', function () { userPause = false; }, true);

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
      var skip = q('.ytp-ad-skip-button-modern, .ytp-ad-skip-button, .ytp-skip-ad-button, .videoAdUiSkipButton, .ytp-ad-skip-slot button');
      if (skip) { skip.click(); say('atla düğmesine basıldı'); }

      // Reklam-takılma çıkışı: yalnızca reklam videosu OYNIYOR ama ilerlemiyorsa
      // "donuk" sayılır; duraklıysa önce oynatılmaya çalışılır. Çıkışlar
      // uykuReload freninden geçer (döngü olmasın).
      if (v.currentTime > adLastPos + 0.15) {
        adLastPos = v.currentTime;
        adProgressAt = Date.now();
      } else if (v.paused) {
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
    say(reason);
    setTimeout(function () { location.reload(); }, 1200);
    return true;
  }

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
    var stuckMs = Date.now() - lastAdvanceAt;
    if (v.paused || adActive || userPause) return;
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
      say('video hatası kod=' + t.error.code + ' — 5 sn sonra sayfa yenilenecek');
      setTimeout(function () { uykuReload('video hatası kod=' + t.error.code); }, 5000);
    }
  }, true);

  /* Video bitince müdahale YOK: YouTube'un kendi autoplay ayarı kapalıysa
     video bitince durması doğrudur; açıksa YouTube kendisi sıradakine geçer. */

  function tickChecks() {
    handleAd();
    handleDialogs();
    hideClutter();
    hideOpenApp();
    handleStall();
    handleBufferStall();
    autoStart();
  }

  /* Ekran kapalıyken tarayıcı zamanlayıcıları kısıtlanır; Android tarafı
     (PlaybackService) bu fonksiyonu saniyede bir doğrudan çağırır. */
  window.__uykuTick = function () {
    try { handleAd(); handleDialogs(); handleStall(); handleBufferStall(); autoStart(); } catch (e) {}
  };

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

  setInterval(function () {
    try { tickChecks(); } catch (e) {}
  }, 300);

  say('hazır');
})();
