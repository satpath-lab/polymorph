function init() { return { info: null, saved: null, clip: null, n: 0 }; }

function update(s, e) {
  if (e.id === 'info') s.info = android.device();
  if (e.id === 'save') { s.n += 1; android.storage('set', { key: 'visits', value: s.n }); s.saved = android.storage('get', { key: 'visits' }); }
  if (e.id === 'toast') android.toast('show', { text: 'Hello from JavaScript running on the phone' });
  if (e.id === 'copy') { android.clipboard('set', { text: 'copied ' + s.n }); s.clip = android.clipboard('get'); }
  return s;
}

function view(s) {
  const row = (label, value) => ({ type: 'row', justify: 'between', children: [{ type: 'text', text: label, color: '#6B7280' }, { type: 'text', text: String(value), bold: true }] });
  return {
    title: 'Android bridge', accent: '#0F766E',
    body: { type: 'column', padding: 20, gap: 12, children: [
      { type: 'text', text: 'Direct Android calls', size: 22, bold: true },
      { type: 'button', id: 'info', text: 'android.device()' },
      { type: 'button', id: 'save', text: 'android.storage set + get' },
      { type: 'button', id: 'copy', text: 'android.clipboard set + get' },
      { type: 'button', id: 'toast', text: 'android.toast()' },
      { type: 'card', padding: 16, gap: 8, children: [
        row('model', s.info ? s.info.model : '–'),
        row('sdk', s.info ? s.info.sdk : '–'),
        row('stored visits', s.saved == null ? '–' : s.saved),
        row('clipboard', s.clip == null ? '–' : s.clip),
      ] },
    ] },
  };
}
