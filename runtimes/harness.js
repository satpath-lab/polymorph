const fs = require('fs'), vm = require('vm'), readline = require('readline');
const path = process.argv[2];
const log = (...a) => process.stderr.write(a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ') + '\n');
const mem = {};
const fake = (n, m, a = {}) => n === 'device' ? { platform: 'web', model: 'preview', sdk: 0 } : n === 'storage' ? (m === 'set' ? (mem[a.key] = a.value, true) : m === 'get' ? mem[a.key] : mem) : n === 'clipboard' ? (m === 'set' ? (mem._clip = a.text, true) : mem._clip) : null;
const android = new Proxy({ call: fake, emit: () => {} }, { get: (t, k) => k in t ? t[k] : new Proxy((m, a) => fake(k, m, a), { get: (_, m) => (...a) => fake(k, m, a) }) });
const ctx = vm.createContext({ android, native: android, console: { log, error: log, warn: log, info: log }, require: require('module').createRequire(require('path').resolve(path)), module: { exports: {} }, Buffer, process: { env: process.env } });
let loadErr = null;
try { vm.runInContext(fs.readFileSync(path, 'utf8'), ctx, { filename: path }); } catch (e) { loadErr = e; }
const fn = n => ctx[n] || ctx.module.exports[n];
readline.createInterface({ input: process.stdin }).on('line', line => {
  let res;
  try {
    if (loadErr) throw loadErr;
    const req = JSON.parse(line);
    let st = req.state;
    if (st == null) st = fn('init') ? fn('init')() : {};
    if (req.event != null && fn('update')) { const r = fn('update')(st, req.event); if (r !== undefined) st = r; }
    res = { state: st, ui: fn('view')(st) };
  } catch (e) { res = { error: String((e && e.stack) || e).split('\n').slice(0, 5).join('\n') }; }
  process.stdout.write(JSON.stringify(res) + '\n');
});
