// preinject.js mantık testi (tarayıcısız). Çalıştır:  node app/src/test/js/preinject.test.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');

const src = fs.readFileSync(path.join(__dirname, '../../main/assets/preinject.js'), 'utf8');

const logs = [];
const handlers = {};
global.window = globalThis;
window.FireTube = { log: (m) => logs.push(m) };
window.__FIRE_UBO_KEYS = ['uboOzelAd'];
Object.defineProperty(globalThis, 'navigator', {
  value: { mediaSession: { setActionHandler(a, h) { handlers[a] = h; } } },
  configurable: true,
});
// Sahte fetch: reklam anahtarı yalnız DERİNDE (üst düzeyde değil) — eski
// hasAds kapısı bunu hiç temizlemiyordu.
const body = JSON.stringify({ a: { b: { c: { adSlots: [1], keep: 1 } } }, player: { ads: 1, keep: 2 } });
window.fetch = async () => ({
  url: 'https://m.youtube.com/youtubei/v1/player?x', status: 200, statusText: 'OK', headers: {},
  clone() { return { text: async () => body }; },
});
global.Response = class { constructor(b) { this.b = b; } async text() { return this.b; } };

eval(src);

(async () => {
  // 1) fetch: derin temizlik, üst düzey anahtar olmasa da
  const r = await fetch('x');
  assert.deepStrictEqual(JSON.parse(await r.text()), { a: { b: { c: { keep: 1 } } }, player: { keep: 2 } });

  // 2) ytInitialPlayerResponse setter (uBO anahtarı dahil)
  window.ytInitialPlayerResponse = { adPlacements: [1], videoDetails: { x: { uboOzelAd: 1, t: 'a' } } };
  assert.deepStrictEqual(window.ytInitialPlayerResponse, { videoDetails: { x: { t: 'a' } } });

  // 3) JSON.parse yaması; anahtarsız metne dokunmaz
  const pad = 'x'.repeat(80);
  assert.deepStrictEqual(JSON.parse('{"q":{"playerAds":[1],"p":"' + pad + '"}}'), { q: { p: pad } });
  assert.deepStrictEqual(JSON.parse('{"adsense":1,"p":"' + pad + '"}'), { adsense: 1, p: pad });

  // 4) Uygulamanın canlı anahtar itmesi (temel küme değişir)
  window.__fireSetKeys(['adPlacements', 'yeniReklam'], null);
  assert.deepStrictEqual(JSON.parse('{"z":{"yeniReklam":1,"adSlots":2,"p":"' + pad + '"}}'),
    { z: { adSlots: 2, p: pad } });
  // temel kümede adPlacements yoksa yerleşik varsayılana düşer
  window.__fireSetKeys(['yanlis'], null);
  assert.deepStrictEqual(JSON.parse('{"adSlots":1,"yanlis":2,"p":"' + pad + '"}'), { yanlis: 2, p: pad });

  // 5) Medya düğmesi duraklatması işaretlenir
  navigator.mediaSession.setActionHandler('pause', () => 'ok');
  assert.strictEqual(handlers.pause(), 'ok');
  assert.ok(Date.now() - window.__uykuUserPauseAt < 1000);

  console.log('preinject.test.js: tüm testler geçti');
})().catch((e) => { console.error(e); console.error(logs.join('\n')); process.exit(1); });
