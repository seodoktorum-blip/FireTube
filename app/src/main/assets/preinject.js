/* Bu script HTML belgesinin <head>'inin en başına gömülür (MainActivity,
   shouldInterceptRequest ile ana belgeyi kendisi indirip enjekte eder) —
   YouTube'un kendi kodundan ÖNCE çalışır. Amaç: masaüstü reklam engelleyicilerin
   (uBlock Origin tarzı) tekniği — oynatıcıya reklam verisinin hiç ulaşmaması. */
(function () {
  if (window.__firePre) return;
  window.__firePre = true;

  function say(m) { try { if (window.FireTube) window.FireTube.log('[ön] ' + m); } catch (e) {} }

  /* Orijinal JSON.parse: hem sarmalayıcı hem yama aynı temele bassın */
  var origJSONParse = JSON.parse;

  /* Player verisinden reklam alanlarını sil (uBlock Origin'in YouTube için
     kullandığı alanlar). */
  function pruneAds(o) {
    try {
      if (!o || typeof o !== 'object') return o;
      delete o.adPlacements;
      delete o.adSlots;
      delete o.adBreakHeartbeatParams;
      if (o.playerConfig && o.playerConfig.ssapConfig) delete o.playerConfig.ssapConfig;
      if (o.player) {
        if (o.player.playerAds) delete o.player.playerAds;
        if (o.player.ads) delete o.player.ads;
      }
    } catch (e) {}
    return o;
  }

  /* Derin temizleme: yanıtın herhangi bir derinliğindeki reklam-şekilli
     anahtarları söker (YouTube alan adlarını sık değiştiriyor; bilinen
     üst düzey alanlar yetmeyebiliyor). Yalnızca /youtubei/ yanıtlarında
     kullanılır (büyük nesneler, seyrek çağrı). */
  var AD_KEYS = {
    adPlacements: 1, adSlots: 1, adSlotRenderer: 1, adSlotMetadata: 1,
    adBreakHeartbeatParams: 1, adParams: 1, adPods: 1, adSegments: 1,
    adSurvey: 1, adTelemetry: 1, adSystemMetadata: 1, adCueRanges: 1,
    adSlotsInfo: 1, adPlayers: 1, ssapConfig: 1, adBreakChart: 1,
    adChunk: 1, adPlacementRenderer: 1, adMessage: 1
  };

  function deepPrune(o, depth) {
    try {
      if (!o || typeof o !== 'object' || depth > 7) return o;
      if (Array.isArray(o)) {
        for (var i = 0; i < o.length; i++) deepPrune(o[i], depth + 1);
        return o;
      }
      for (var k in o) {
        if (AD_KEYS[k]) {
          // say('derin: ' + k); // gürültü olur; gerekirse aç
          delete o[k];
        } else {
          deepPrune(o[k], depth + 1);
        }
      }
    } catch (e) {}
    return o;
  }

  function hasAds(o) {
    try {
      return !!(o && (o.adPlacements || o.adSlots || o.adBreakHeartbeatParams
          || (o.playerConfig && o.playerConfig.ssapConfig)
          || (o.player && (o.player.playerAds || o.player.ads))));
    } catch (e) { return false; }
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
        if (hasAds(v)) say('ilk player verisinden reklam alanları silindi');
        stored = pruneAds(v);
      }
    });
  } catch (e) {}

  /* 2) fetch sarmalayıcı: /youtubei/ API yanıtlarındaki (SPA gezinmeler ve
     player çağrıları) reklam alanlarını sil — üstelik DERİN temizlikle.
     Yalnızca değişiklik gerekirse yeni Response döner; hata olursa orijinal
     yanıta dokunulmaz. */
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
                var obj = origJSONParse.call(JSON, txt);
                if (!hasAds(obj)) return res;
                pruneAds(obj);
                deepPrune(obj, 0);
                say('API yanıtından reklam alanları silindi (derin): ' +
                    u.slice(u.lastIndexOf('/youtubei/'), u.lastIndexOf('/youtubei/') + 30));
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
     Ucuz üst-düzey kontrol önce; reklam-şekilli yanıt değilse dokunmaz. */
  try {
    var origParse = origJSONParse;
    JSON.parse = function () {
      var r = origParse.apply(this, arguments);
      try {
        if (r && typeof r === 'object' && hasAds(r)) {
          pruneAds(r);
          say('JSON.parse yolundan reklam alanları silindi');
        }
      } catch (e) {}
      return r;
    };
  } catch (e) {}

  say('ön script aktif (reklam verisi oynatıcıya hiç ulaşmayacak)');
})();
