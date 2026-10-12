/* Bu script HTML belgesinin <head>'inin en başına gömülür (MainActivity,
   shouldInterceptRequest ile ana belgeyi kendisi indirip enjekte eder) —
   YouTube'un kendi kodundan ÖNCE çalışır. Amaç: masaüstü reklam engelleyicilerin
   (uBlock Origin tarzı) tekniği — oynatıcıya reklam verisinin hiç ulaşmaması. */
(function () {
  if (window.__firePre) return;
  window.__firePre = true;

  function say(m) { try { if (window.FireTube) window.FireTube.log('[ön] ' + m); } catch (e) {} }

  /* Medya düğmeleri (kulaklık/kilit ekranı): YouTube'un pause/stop
     işleyicisi çağrılırsa bu bir KULLANICI duraklatmasıdır; inject.js
     __uykuUserPauseAt'e bakıp otomatik devamı kapatır. */
  try {
    var ms = navigator.mediaSession;
    if (ms && ms.setActionHandler) {
      var origSAH = ms.setActionHandler.bind(ms);
      ms.setActionHandler = function (action, handler) {
        if ((action === 'pause' || action === 'stop' || action === 'play') && typeof handler === 'function') {
          var h = handler;
          var key = action === 'play' ? '__uykuUserPlayAt' : '__uykuUserPauseAt';
          handler = function () {
            window[key] = Date.now();
            return h.apply(this, arguments);
          };
        }
        return origSAH(action, handler);
      };
    }
  } catch (e) {}

  /* Önceki sayfadan devralınan duraklatma: video duraklatılmışken sayfa
     yeniden yüklenirse (kurtarma, yenileme) Android __uykuHoldBoot gömer.
     inject.js sayfa yüklenince gelir — o zamana kadar YouTube autoplay'i
     videoyu başlatırdı. Dokunuşsuz her play burada geri duraklatılır;
     dokunuş (uyanık kullanıcı) korumayı kaldırır. */
  if (window.__uykuHoldBoot) {
    window.__uykuHoldEarly = true;
    ['pointerdown', 'touchstart', 'keydown'].forEach(function (ev) {
      document.addEventListener(ev, function () { window.__uykuHoldEarly = false; },
          { capture: true, passive: true });
    });
    document.addEventListener('play', function (e) {
      if (window.__uykuHoldEarly !== true || !e.target || e.target.tagName !== 'VIDEO') return;
      try { e.target.pause(); } catch (x) {}
      e.stopImmediatePropagation();
    }, true);
    say('önceki duraklatma: sayfa duraklatılmış açılıyor');
  }

  /* Orijinal JSON.parse: hem sarmalayıcı hem yama aynı temele bassın */
  var origJSONParse = JSON.parse;

  /* Reklam-şekilli anahtarlar. Temel küme GitHub'taki rules.json'dan
     (window.__FIRE_RULES.adKeys), yoksa yerleşik varsayılan; üstüne uBlock
     topluluk anahtarları (window.__FIRE_UBO_KEYS) eklenir. Uygulama kurallar
     sonradan güncellenince window.__fireSetKeys ile açık sayfaya iter. */
  var DEFAULT_KEYS = [
    'adPlacements', 'adSlots', 'adSlotRenderer', 'adSlotMetadata',
    'adBreakHeartbeatParams', 'adParams', 'adPods', 'adSegments',
    'adSurvey', 'adTelemetry', 'adSystemMetadata', 'adCueRanges',
    'adSlotsInfo', 'adPlayers', 'ssapConfig', 'adBreakChart',
    'adChunk', 'adPlacementRenderer', 'adMessage', 'playerAds'
  ];
  var AD_KEYS = {};
  var KEY_RE = null; // metinde herhangi bir anahtar geçiyor mu? (ucuz ön eleme)

  function setKeys(base, ubo) {
    var keys = {};
    var list = (base && base.length && base.indexOf('adPlacements') >= 0) ? base : DEFAULT_KEYS;
    var i;
    for (i = 0; i < list.length; i++) keys[list[i]] = 1;
    if (ubo && ubo.length) for (i = 0; i < ubo.length; i++) keys[ubo[i]] = 1;
    var parts = [];
    for (var k in keys) parts.push(k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
    AD_KEYS = keys;
    KEY_RE = new RegExp('"(?:' + parts.join('|') + ')"');
    return parts.length;
  }

  try {
    var rk = window.__FIRE_RULES && window.__FIRE_RULES.adKeys;
    var uk = window.__FIRE_UBO_KEYS;
    var n0 = setKeys(rk, uk);
    say('anahtarlar: ' + n0 + ' (uzak=' + (rk ? rk.length : 0) + ', uBlock=' + (uk ? uk.length : 0) + ')');
  } catch (e) {
    setKeys(null, null);
  }

  window.__fireSetKeys = function (base, ubo) {
    try { say('anahtarlar güncellendi: ' + setKeys(base, ubo)); } catch (e) {}
  };

  /* Derin temizleme: yanıtın herhangi bir derinliğindeki reklam-şekilli
     anahtarları söker (YouTube alan adlarını sık değiştiriyor). Silinen
     alan sayısını döndürür. */
  function deepPrune(o, depth) {
    var n = 0;
    try {
      if (!o || typeof o !== 'object' || depth > 10) return 0;
      if (Array.isArray(o)) {
        for (var i = 0; i < o.length; i++) n += deepPrune(o[i], depth + 1);
        return n;
      }
      for (var k in o) {
        if (AD_KEYS[k]) {
          delete o[k];
          n++;
        } else {
          n += deepPrune(o[k], depth + 1);
        }
      }
    } catch (e) {}
    return n;
  }

  /* Anahtar adıyla yakalanamayan bilinen yollar (player.ads gibi genel adlar) */
  function pruneKnownPaths(o) {
    var n = 0;
    try {
      if (o && o.player && o.player.ads) { delete o.player.ads; n++; }
    } catch (e) {}
    return n;
  }

  function prune(o) {
    return pruneKnownPaths(o) + deepPrune(o, 0);
  }

  /* 1) ytInitialPlayerResponse: sayfanın satır içi script'i bu global'a player
     verisini atar. Atamayı bir setter ile yakalayıp temizleyerek oynatıcının
     temiz veriyi okumasını sağlıyoruz. */
  try {
    var stored;
    Object.defineProperty(window, 'ytInitialPlayerResponse', {
      configurable: true,
      get: function () { return stored; },
      set: function (v) {
        var n = prune(v);
        if (n) say('ilk player verisinden ' + n + ' reklam alanı silindi');
        stored = v;
      }
    });
  } catch (e) {}

  /* 2) fetch sarmalayıcı: /youtubei/ API yanıtlarındaki (SPA gezinmeler ve
     player çağrıları) reklam alanlarını DERİN temizlikle sil. Metinde hiçbir
     anahtar geçmiyorsa ayrıştırma yapılmaz; yalnızca bir şey silindiyse yeni
     Response döner; hata olursa orijinal yanıta dokunulmaz. */
  try {
    var origFetch = window.fetch;
    if (origFetch) {
      window.fetch = function () {
        var args = arguments;
        var self = this;
        return origFetch.apply(self, args).then(function (res) {
          try {
            var u = res.url || '';
            if (u.indexOf('/youtubei/') === -1 || res.status !== 200) return res;
            return res.clone().text().then(function (txt) {
              try {
                if (!KEY_RE || !KEY_RE.test(txt)) return res;
                var obj = origJSONParse.call(JSON, txt);
                var n = prune(obj);
                if (!n) return res;
                var at = u.lastIndexOf('/youtubei/');
                say('API yanıtından ' + n + ' reklam alanı silindi (derin): ' + u.slice(at, at + 30));
                return new Response(JSON.stringify(obj), {
                  status: res.status,
                  statusText: res.statusText,
                  headers: res.headers
                });
              } catch (e) {
                return res;
              }
            });
          } catch (e) {
            return res;
          }
        });
      };
    }
  } catch (e) {}

  /* 3) JSON.parse yaması: fetch dışı yollar (XHR vb.) da tıkansın diye.
     Ucuz metin ön elemesi önce; anahtar geçmiyorsa nesneye hiç dokunulmaz. */
  try {
    JSON.parse = function (text) {
      var r = origJSONParse.apply(this, arguments);
      try {
        if (r && typeof r === 'object' && typeof text === 'string' && text.length > 64
            && KEY_RE && KEY_RE.test(text)) {
          var n = prune(r);
          if (n) say('JSON.parse yolundan ' + n + ' reklam alanı silindi');
        }
      } catch (e) {}
      return r;
    };
  } catch (e) {}

  say('ön script aktif (reklam verisi oynatıcıya hiç ulaşmayacak)');
})();
