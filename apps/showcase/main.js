const ACCENT = '#4F46E5';

function init() {
  return { tab: 'ui', size: 'M', qty: 3, agree: false, plan: 'pro', date: '', time: '', dots: [], dialog: false, kind: 'line' };
}

function update(s, e) {
  const id = e.id || '';
  if (e.type === 'result') {
    if (e.value && e.value.error) return s;
    if (id === 'date') s.date = e.value;
    if (id === 'time') s.time = e.value;
    return s;
  }
  if (['ui', 'charts', 'draw', 'media'].includes(id)) s.tab = id;
  else if (id === 'size') s.size = e.value;
  else if (id === 'qty') s.qty = e.value;
  else if (id === 'agree') s.agree = e.value;
  else if (id === 'plan') s.plan = e.value;
  else if (id === 'pick-date') s._pickDate = { id: 'date', value: s.date };
  else if (id === 'pick-time') s._pickTime = { id: 'time', value: s.time };
  else if (id === 'order') s.dialog = true;
  else if (id === 'confirm') { s.dialog = false; s._toast = `Ordered ${s.qty} × ${s.size} (${s.plan})`; }
  else if (id === 'cancel') s.dialog = false;
  else if (id === 'pad' && e.value) s.dots.push([Math.round(e.value.x), Math.round(e.value.y)]);
  else if (id === 'clear') s.dots = [];
  else if (id.startsWith('kind:')) s.kind = id.slice(5);
  return s;
}

const card = (title, ...children) => ({ type: 'card', style: 'card', children: [{ type: 'text', text: title, style: 'h2' }, ...children] });

function uiTab(s) {
  return [
    { type: 'stack', id: 'hero', animate: { from: { alpha: 0, y: 20 }, duration: 400 }, children: [
      { type: 'column', gradient: ['#4F46E5', '#9333EA'], angle: 0, radius: [20, 20, 20, 4], padding: 20, gap: 4, children: [
        { type: 'text', text: 'Fully styled', color: '#E0E7FF', uppercase: true, letterSpacing: 0.15, size: 12, weight: 700 },
        { type: 'text', text: 'Every pixel is yours', color: '#fff', size: 26, weight: 800, lineHeight: 1.1 },
        { type: 'text', text: 'Gradients · per-corner radius · stack overlays', color: '#C7D2FE', size: 13 } ] },
      { type: 'text', text: 'NEW', position: { top: 12, right: 12 }, bg: '#F59E0B', color: '#fff', size: 11, weight: 800, paddingH: 8, paddingV: 3, radius: 99 } ] },
    card('Form controls',
      { type: 'select', id: 'size', value: s.size, options: [{ value: 'S', label: 'Small' }, { value: 'M', label: 'Medium' }, { value: 'L', label: 'Large' }] },
      { type: 'row', justify: 'between', children: [{ type: 'text', text: 'Quantity' }, { type: 'text', text: String(s.qty), weight: 700 }] },
      { type: 'slider', id: 'qty', min: 1, max: 10, step: 1, value: s.qty, color: ACCENT },
      { type: 'radio', id: 'plan', value: s.plan, horizontal: true, options: [{ value: 'free', label: 'Free' }, { value: 'pro', label: 'Pro' }, { value: 'team', label: 'Team' }] },
      { type: 'checkbox', id: 'agree', text: 'I agree to the terms', value: s.agree },
      { type: 'row', gap: 8, children: [
        { type: 'button', id: 'pick-date', text: s.date || 'Pick date', flex: 1, style: 'ghost' },
        { type: 'button', id: 'pick-time', text: s.time || 'Pick time', flex: 1, style: 'ghost' } ] },
      { type: 'button', id: 'order', text: 'Place order', enabled: s.agree, gradient: ['#4F46E5', '#7C3AED'] }),
    card('Grid',
      { type: 'grid', columns: 3, gap: 8, children: ['⚡ Fast', '🔒 Secure', '🎨 Styled', '🧩 Plugins', '📴 Offline', '🚀 Live'].map((t, i) =>
        ({ type: 'text', text: t, id: 'g' + i, align: 'center', bg: '#EEF2FF', radius: 12, paddingV: 14, size: 13, weight: 600, animate: { from: { scale: 0.6, alpha: 0 }, delay: i * 60 } })) }),
  ];
}

function chartsTab(s) {
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug'];
  return [
    { type: 'row', gap: 6, children: ['line', 'bar', 'pie', 'donut'].map(k => ({ type: 'button', id: 'kind:' + k, text: k, flex: 1, style: s.kind === k ? null : 'ghost', size: 13 })) },
    card('Revenue vs costs', { type: 'chart', kind: s.kind, height: 220, labels: months,
      series: [{ label: 'Revenue', values: [12, 19, 15, 25, 22, 30, 28, 36], color: ACCENT }, { label: 'Costs', values: [8, 11, 10, 14, 13, 17, 16, 19], color: '#10B981' }] }),
  ];
}

function drawTab(s) {
  const ops = [{ op: 'rect', x: 0, y: 0, w: 1000, h: 260, color: '#0F172A' },
    { op: 'text', x: 12, y: 24, text: 'Tap to draw · ' + s.dots.length + ' points', color: '#94A3B8', size: 13 }];
  if (s.dots.length > 1) ops.push({ op: 'path', points: s.dots, color: '#A78BFA', stroke: 3 });
  s.dots.forEach(([x, y]) => ops.push({ op: 'circle', x, y, r: 6, color: '#F472B6' }));
  return [card('Canvas (custom drawing)', { type: 'canvas', id: 'pad', height: 260, ops, radius: 12 },
    { type: 'button', id: 'clear', text: 'Clear', style: 'ghost' })];
}

function mediaTab() {
  return [
    card('Web view (maps, embeds, HTML)', { type: 'web', id: 'map', height: 220,
      html: '<html><body style="margin:0;font-family:sans-serif;background:linear-gradient(135deg,#e0e7ff,#fae8ff);height:100vh;display:flex;align-items:center;justify-content:center"><div style="text-align:center"><h2 style="margin:0">Any HTML here</h2><p>Leaflet / Google Maps / dashboards</p></div></body></html>' }),
    card('Video', { type: 'video', id: 'clip', src: 'https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4', height: 200, controls: true }),
    card('Native view from a plugin', { type: 'native', plugin: 'Gauge', id: 'gauge', props: { value: 72 } }),
  ];
}

function view(s) {
  const body = s.tab === 'charts' ? chartsTab(s) : s.tab === 'draw' ? drawTab(s) : s.tab === 'media' ? mediaTab() : uiTab(s);
  return {
    title: 'Showcase', route: s.tab, accent: ACCENT, bg: '#F5F7FF',
    theme: { text: { color: '#1E1B4B' }, button: { bg: ACCENT, radius: 14 } },
    styles: {
      card: { radius: 18, padding: 16, gap: 12, elevation: 1 },
      h2: { size: 17, weight: 800 },
      ghost: { bg: '#EEF2FF', color: ACCENT },
    },
    tabs: { selected: s.tab, items: [
      { id: 'ui', label: 'UI', icon: '🎛' }, { id: 'charts', label: 'Charts', icon: '📊' },
      { id: 'draw', label: 'Draw', icon: '✏️' }, { id: 'media', label: 'Media', icon: '🎬' } ] },
    dialog: s.dialog ? { title: 'Confirm order', text: `${s.qty} × size ${s.size} on the ${s.plan} plan${s.date ? ' for ' + s.date : ''}?`,
      buttons: [{ id: 'confirm', text: 'Order' }, { id: 'cancel', text: 'Cancel' }], cancel: 'cancel' } : null,
    body: { type: 'column', padding: 16, gap: 14, children: body },
  };
}
