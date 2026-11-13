$__volt_cbs = {}
$__volt_cbn = 0
module JSON
  class ParserError < StandardError; end
  ESC = { 34 => '\\"', 92 => '\\\\', 8 => '\\b', 12 => '\\f', 10 => '\\n', 13 => '\\r', 9 => '\\t' }

  def self.generate(v, out = nil)
    o = out || []
    case v
    when nil then o << 'null'
    when true then o << 'true'
    when false then o << 'false'
    when Integer then o << v.to_s
    when Float then o << ((v.nan? || v.infinite?) ? 'null' : (v == v.floor && v.abs < 1e15 ? v.to_i.to_s : v.to_s))
    when String, Symbol
      s = v.to_s; b = '"'; i = 0; st = 0; n = s.bytesize
      while i < n
        c = s.getbyte(i)
        if c < 32 || c == 34 || c == 92
          b << s.byteslice(st, i - st) << (ESC[c] || format('\\u%04x', c)); st = i + 1
        end
        i += 1
      end
      o << b << s.byteslice(st, n - st) << '"'
    when Proc, Method
      $__volt_cbn += 1; $__volt_cbs[$__volt_cbn] = v
      o << '{"__volt_cb":' << $__volt_cbn.to_s << '}'
    when Array
      o << '['
      v.each_with_index { |x, i| o << ',' if i > 0; generate(x, o) }
      o << ']'
    when Hash
      o << '{'; first = true
      v.each { |k, x| o << ',' unless first; first = false; generate(k.to_s, o); o << ':'; generate(x, o) }
      o << '}'
    else generate(v.respond_to?(:to_h) ? v.to_h : v.to_s, o)
    end
    out ? o : o.join
  end

  def self.dump(v) generate(v) end

  def self.parse(s)
    @s = s; @i = 0; @n = s.bytesize
    v = val; v
  end

  def self.ws
    while @i < @n
      c = @s.getbyte(@i)
      break unless c == 32 || c == 9 || c == 10 || c == 13
      @i += 1
    end
  end

  def self.utf8(cp)
    if cp < 0x80 then [cp]
    elsif cp < 0x800 then [0xC0 | (cp >> 6), 0x80 | (cp & 0x3F)]
    elsif cp < 0x10000 then [0xE0 | (cp >> 12), 0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F)]
    else [0xF0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3F), 0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F)]
    end.pack('C*')
  end

  def self.str
    @i += 1; b = ''; st = @i
    while true
      raise ParserError, 'unterminated string' if @i >= @n
      c = @s.getbyte(@i)
      if c == 34
        b << @s.byteslice(st, @i - st); @i += 1; return b
      elsif c == 92
        b << @s.byteslice(st, @i - st)
        e = @s.getbyte(@i + 1)
        if e == 117
          cp = @s.byteslice(@i + 2, 4).to_i(16); @i += 6
          if cp >= 0xD800 && cp <= 0xDBFF && @s.getbyte(@i) == 92 && @s.getbyte(@i + 1) == 117
            lo = @s.byteslice(@i + 2, 4).to_i(16); cp = 0x10000 + (cp - 0xD800) * 0x400 + (lo - 0xDC00); @i += 6
          end
          b << utf8(cp)
        else
          b << ({ 98 => "\b", 102 => "\f", 110 => "\n", 114 => "\r", 116 => "\t" }[e] || e.chr); @i += 2
        end
        st = @i
      else
        @i += 1
      end
    end
  end

  def self.val
    ws
    c = @s.getbyte(@i)
    case c
    when 123
      @i += 1; h = {}; ws
      if @s.getbyte(@i) == 125 then @i += 1; return h end
      while true
        ws; k = str; ws; @i += 1; h[k] = val; ws
        d = @s.getbyte(@i); @i += 1
        return h if d == 125
      end
    when 91
      @i += 1; a = []; ws
      if @s.getbyte(@i) == 93 then @i += 1; return a end
      while true
        a << val; ws
        d = @s.getbyte(@i); @i += 1
        return a if d == 93
      end
    when 34 then str
    when 116 then @i += 4; true
    when 102 then @i += 5; false
    when 110 then @i += 4; nil
    else
      st = @i
      while @i < @n
        d = @s.getbyte(@i)
        break unless (d >= 48 && d <= 57) || d == 45 || d == 43 || d == 46 || d == 101 || d == 69
        @i += 1
      end
      t = @s.byteslice(st, @i - st)
      raise ParserError, "unexpected character at #{st}" if t.empty?
      (t.include?('.') || t.include?('e') || t.include?('E')) ? t.to_f : t.to_i
    end
  end
end

module Android
  def self.hostcall(name, method, args)
    r = JSON.parse(__host(JSON.generate({ 'op' => 'call', 'plugin' => name.to_s, 'method' => method.to_s, 'args' => args })))
    raise r['error'] if r.is_a?(Hash) && r['error']
    r.is_a?(Hash) ? r['value'] : r
  end
  def self.call(name, method = '', args = {}) hostcall(name, method, args) end
  def self.emit(name, data = nil) __host(JSON.generate({ 'op' => 'emit', 'name' => name.to_s, 'data' => data })); nil end
  class Target
    def initialize(n) @n = n end
    def method_missing(m, *a) Android.hostcall(@n, m.to_s, a) end
    def call(method = '', args = {}) Android.hostcall(@n, method, args) end
  end
  def self.method_missing(name, *a)
    a.empty? ? Target.new(name.to_s) : hostcall(name.to_s, a[0], a[1] || {})
  end
end
ANDROID = Android
Native = Android

def __volt_dirname(p) i = p.rindex('/'); i ? (i == 0 ? '/' : p[0, i]) : '.' end
def __volt_norm(p)
  out = []
  p.split('/').each { |s| if s == '..' then out.pop elsif s != '' && s != '.' then out << s end }
  '/' + out.join('/')
end
$__volt_dirs = [$VOLT_DIR]
$__volt_loaded = {}
module Kernel
  def puts(*a) a = [''] if a.empty?; a.flatten.each { |x| __host(JSON.generate({ 'op' => 'log', 'text' => x.to_s })) }; nil end
  def print(*a) __host(JSON.generate({ 'op' => 'log', 'text' => a.join })); nil end
  def p(*a) a.each { |x| puts x.inspect }; a.size <= 1 ? a.first : a end
  def __volt_load(path)
    return false if $__volt_loaded[path]
    $__volt_loaded[path] = true
    $__volt_dirs.push(__volt_dirname(path))
    begin __load(path) ensure $__volt_dirs.pop end
    true
  end
  def require_relative(n)
    f = __volt_norm($__volt_dirs.last + '/' + n); f += '.rb' unless f.end_with?('.rb')
    __volt_load(f)
  end
  def require(n)
    return true if %w[json set].include?(n)
    [$VOLT_DIR, $VOLT_DIR + '/lib'].each do |d|
      f = __volt_norm(d + '/' + (n.end_with?('.rb') ? n : n + '.rb'))
      return __volt_load(f) unless __read(f).nil?
    end
    raise LoadError, "cannot load such file -- #{n}"
  end
end

$__volt_err = nil
begin
  __volt_load($VOLT_MAIN)
rescue Exception => e
  $__volt_err = e
end

def __volt_fmt(e)
  "#{e.class}: #{e.message}\n#{(e.backtrace || []).first(3).join("\n")}"
end

def __volt(fn, arg)
  res = begin
    raise $__volt_err if $__volt_err
    a = JSON.parse(arg)
    if fn == '__cb'
      f = $__volt_cbs[a[0]]
      raise 'callback was released' unless f
      return JSON.generate({ 'value' => f.call(*a[1..-1]) })
    end
    if fn == '__release'
      $__volt_cbs.delete(a[0]); return '{"value":true}'
    end
    if fn == 'step'
      st = a['state']
      st = (respond_to?(:init, true) ? __send__(:init) : {}) if st.nil?
      if !a['event'].nil? && respond_to?(:update, true)
        r = __send__(:update, st, a['event']); st = r unless r.nil?
      end
      { 'state' => st, 'ui' => (respond_to?(:view, true) ? __send__(:view, st) : nil) }
    else
      raise "no function #{fn}" unless respond_to?(fn.to_sym, true)
      { 'value' => __send__(fn.to_sym, *(a.is_a?(Array) ? a : [a])) }
    end
  rescue Exception => e
    { 'error' => __volt_fmt(e) }
  end
  JSON.generate(res)
end
