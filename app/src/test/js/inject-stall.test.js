// inject.js takılma bekçisi (handleBufferStall) mantık testi. Çalıştır:
//   node app/src/test/js/inject-stall.test.js
// İşlev, inject.js'ten metin olarak çıkarılıp sahte video/saat ile çalıştırılır.
const assert = require('assert');
const fs = require('fs');
const path = require('path');

const src = fs.readFileSync(path.join(__dirname, '../../main/assets/inject.js'), 'utf8');
const start = src.indexOf('var lastPos = -1, lastAdvanceAt');
const fnStart = src.indexOf('function handleBufferStall()', start);
const end = src.indexOf('\n  }\n', fnStart) + 4;
assert(start > 0 && fnStart > start && end > fnStart, 'handleBufferStall bulunamadı');
const block = src.slice(start, end);

function harness(video) {
  let now = 1000000;
  const said = [], reloads = [];
  const env = new Function('q', 'say', 'uykuReload', 'Date',
    'var adActive = false, userPause = false;\n' + block + '\nreturn handleBufferStall;');
  const fakeDate = { now: () => now };
  const tick = env(() => video, (m) => said.push(m), (r) => { reloads.push(r); return true; }, fakeDate);
  return {
    said, reloads,
    run(seconds, eachSecond) {
      for (let i = 0; i < seconds; i++) {
        now += 1000;
        if (eachSecond) eachSecond();
        tick();
        if (reloads.length) return i + 1;
      }
      return -1;
    },
  };
}

// readyState 0 videosu: konum ilerlemez; atanan değer okunduğunda geri gelir
function stuckVideo(t) {
  return { paused: false, get currentTime() { return t; }, set currentTime(x) { t = x; }, play() { return Promise.resolve(); } };
}

// 1) Takılı video: dürtme ilerleme sayılmamalı, ~150 sn'de yenileme olmalı
//    (24 Eki: dürtmenin +0,25 sn'si ilerleme sanılıyor, yenileme hiç gelmiyordu)
{
  const h = harness(stuckVideo(0));
  const at = h.run(400);
  assert(at > 0, 'takılı videoda yenileme hiç olmadı; dürtme sayısı: ' + h.said.length);
  assert(at >= 150 && at <= 160, 'yenileme ' + at + '. saniyede (beklenen ~151)');
  assert(h.said.length >= 2, 'yenilemeden önce dürtme beklenirdi');
  assert(/2,5 dakikadır/.test(h.reloads[0]));
}

// 2) Normal oynayan video: hiç dürtme/yenileme yok
{
  const v = stuckVideo(0);
  const h = harness(v);
  assert.strictEqual(h.run(600, () => { v.currentTime += 1; }), -1);
  assert.strictEqual(h.said.length, 0);
}

// 3) Dürtme işe yararsa (video yeniden ilerler) yenileme yapılmaz
{
  const v = stuckVideo(10);
  let flowing = false;
  const realPlay = v.play;
  v.play = function () { flowing = true; return realPlay(); };
  const h = harness(v);
  assert.strictEqual(h.run(600, () => { if (flowing) v.currentTime += 1; }), -1);
  assert.strictEqual(h.said.length, 1);
}

// 4) Duraklatılmışken takılma sayılmaz
{
  const v = stuckVideo(5);
  v.paused = true;
  const h = harness(v);
  assert.strictEqual(h.run(600), -1);
  assert.strictEqual(h.said.length, 0);
}

console.log('inject-stall.test.js: tüm testler geçti');
