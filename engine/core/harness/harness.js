(function (G) {
  const DIR = G.__VOLT_DIR, MAIN = G.__VOLT_MAIN, host = G.__host, read = G.__read;
  const CBS = new Map(); let CBN = 0;
  const enc = (v) => JSON.stringify(v, (k, x) => typeof x === 'function' ? (CBS.set(++CBN, x), { __volt_cb: CBN }) : x);
  const call = (o) => JSON.parse(host(enc(o)));
  const fmt = (a) => a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ');
  const log = (...a) => call({ op: 'log', text: fmt(a) });
  G.console = { log, info: log, warn: log, error: log, debug: log };
  const hostcall = (name, method, args) => { const r = call({ op: 'call', plugin: name, method: method || '', args }); if (r && r.error) throw new Error(r.error); return r ? r.value : null; };
  const target = (name) => new Proxy(function (method, args) { return hostcall(name, method, args || {}); },
    { get: (t, m) => m === 'then' ? undefined : (...a) => hostcall(name, m, a) });
  const android = new Proxy({ call: (n, m, a) => hostcall(n, m, a || {}), emit: (name, data) => { host(enc({ op: 'emit', name, data })); } },
    { get: (t, k) => k in t ? t[k] : target(k) });
  G.android = android; G.native = android;
  const norm = (p) => { const out = []; for (const s of p.split('/')) { if (s === '..') out.pop(); else if (s && s !== '.') out.push(s); } return '/' + out.join('/'); };
  const cache = {};
  function load(file) {
    if (cache[file]) return cache[file].exports;
    const src = read(file);
    if (src == null) return undefined;
    const module = { exports: {} }; cache[file] = module;
    if (file.endsWith('.json')) { module.exports = JSON.parse(src); return module.exports; }
    const dir = file.slice(0, file.lastIndexOf('/'));
    new Function('module', 'exports', 'require', '__filename', '__dirname', src + '\n//# sourceURL=' + file)(module, module.exports, makeRequire(dir), file, dir);
    return module.exports;
  }
  function makeRequire(dir) {
    return (name) => {
      const base = name.startsWith('/') ? name : norm(dir + '/' + name);
      for (const f of [base, base + '.js', base + '.json', base + '/index.js']) { if (read(f) != null) return load(f); }
      throw new Error("Cannot find module '" + name + "'");
    };
  }
  const module = { exports: {} };
  G.module = module; G.exports = module.exports; G.require = makeRequire(DIR);
  let loadErr = null;
  try { (0, eval)(read(MAIN) + '\n//# sourceURL=' + MAIN); } catch (e) { loadErr = e; }
  const fn = (n) => typeof G[n] === 'function' ? G[n] : module.exports[n];
  const err = (e) => String((e && e.stack) ? e + '\n' + e.stack : e).split('\n').slice(0, 6).join('\n');
  G.__volt = function (name, arg) {
    try {
      if (loadErr) throw loadErr;
      const a = JSON.parse(arg);
      if (name === '__cb') { const f = CBS.get(a[0]); if (!f) throw new Error('callback was released'); const v = f(...a.slice(1)); return enc({ value: v === undefined ? null : v }); }
      if (name === '__release') { CBS.delete(a[0]); return '{"value":true}'; }
      if (name === 'step') {
        let st = a.state;
        if (st == null) st = fn('init') ? fn('init')() : {};
        if (a.event != null && fn('update')) { const r = fn('update')(st, a.event); if (r !== undefined) st = r; }
        return enc({ state: st, ui: fn('view') ? fn('view')(st) : null });
      }
      const f = fn(name);
      if (!f) throw new Error('no function ' + name);
      const v = f(...(Array.isArray(a) ? a : [a]));
      return enc({ value: v === undefined ? null : v });
    } catch (e) { return JSON.stringify({ error: err(e) }); }
  };
})(globalThis);
