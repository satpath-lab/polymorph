function init() { return { count: 0 }; }

function update(s, e) {
  if (e.id === 'inc') s.count++;
  else if (e.id === 'dec') s.count--;
  else if (e.id === 'reset') { s.count = 0; s._toast = 'Reset!'; }
  return s;
}

function view(s) {
  return { title: 'Counter', accent: '#F59E0B', body: { type: 'column', padding: 24, gap: 16, align: 'center', children: [
    { type: 'text', text: 'JavaScript ⚡', size: 14, color: '#F59E0B', bold: true },
    { type: 'text', text: String(s.count), size: 72, bold: true },
    { type: 'row', gap: 12, children: [
      { type: 'button', id: 'dec', text: '−', width: 80 },
      { type: 'button', id: 'inc', text: '+', width: 80 },
    ] },
    { type: 'button', id: 'reset', text: 'Reset', bg: '#EEE', color: '#333' },
  ] } };
}
