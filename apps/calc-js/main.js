const { evaluate } = require('./lib/evaluate');

function init() { return { expr: '', result: '0' }; }

function update(s, e) {
  const k = (e.id || '').replace('k:', '');
  if (k === 'C') { s.expr = ''; s.result = '0'; }
  else if (k === '⌫') s.expr = s.expr.slice(0, -1);
  else if (k === '=') {
    try { s.result = evaluate(s.expr); s.expr = s.result; }
    catch (_) { s.result = 'Error'; }
  } else s.expr += k;
  return s;
}

function view(s) {
  const rows = [['C', '⌫', '%', '÷'], ['7', '8', '9', '×'], ['4', '5', '6', '-'], ['1', '2', '3', '+'], ['0', '.', '(', '=']];
  const op = k => '÷×-+=%'.includes(k);
  return { title: 'Calc', accent: '#111827', bg: '#111827', body: { type: 'column', padding: 16, gap: 10, children: [
    { type: 'text', text: s.expr || ' ', size: 28, color: '#9CA3AF', align: 'end' },
    { type: 'text', text: s.result, size: 56, bold: true, color: '#FFFFFF', align: 'end' },
    ...rows.map(r => ({ type: 'row', gap: 10, children: r.map(k => ({
      type: 'button', id: 'k:' + k, text: k, flex: 1, height: 64, size: 22,
      bg: op(k) ? '#F59E0B' : (k === 'C' || k === '⌫' ? '#6B7280' : '#374151'), color: '#FFFFFF' })) })),
  ] } };
}
