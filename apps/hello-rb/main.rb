def init
  { 'count' => 0 }
end

def update(s, e)
  case e['id']
  when 'inc' then s['count'] += 1
  when 'dec' then s['count'] -= 1
  when 'reset' then s['count'] = 0; s['_toast'] = 'Reset!'
  end
  s
end

def view(s)
  { title: 'Counter', accent: '#E11D48', body: { type: 'column', padding: 24, gap: 16, align: 'center', children: [
    { type: 'text', text: 'Ruby ⚡', size: 14, color: '#E11D48', bold: true },
    { type: 'text', text: s['count'].to_s, size: 72, bold: true },
    { type: 'row', gap: 12, children: [
      { type: 'button', id: 'dec', text: '−', width: 80 },
      { type: 'button', id: 'inc', text: '+', width: 80 }
    ] },
    { type: 'button', id: 'reset', text: 'Reset', bg: '#EEE', color: '#333' }
  ] } }
end
