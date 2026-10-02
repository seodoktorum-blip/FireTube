/* Bu script HTML belgesinin <head>'inin en başına gömülür (MainActivity,
   shouldInterceptRequest ile ana belgeyi kendisi indirip enjekte eder) —
   YouTube'un kendi kodundan ÖNCE çalışır. Amaç: masaüstü reklam engelleyicilerin
   (uBlock Origin tarzı) tekniği — oynatıcıya reklam verisinin hiç ulaşmaması. */
(function () {
  if (window.__firePre) return;
  window.__firePre = true;

  function say(m) { try { if (window.FireTube) window.FireTube.log('[ön] ' + m); } catch (e) {} }

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
     player çağrıları) reklam alanlarını sil. Yalnızca değişiklik gerekirse
     yeni Response döner; hata olursa orijinal yanıta dokunulmaz. */
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
                var obj = JSON.parse(txt);
                if (!hasAds(obj)) return res;
                pruneAds(obj);
                say('API yanıtından reklam alanları silindi: ' + u.slice(u.lastIndexOf('/youtubei/'), u.lastIndexOf('/youtubei/') + 30));
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

  say('ön script aktif (reklam verisi oynatıcıya hiç ulaşmayacak)');
})();
