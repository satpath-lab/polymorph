require 'json'
out = $stdout.dup; out.sync = true; $stdout = $stderr
module Android
  MEM = {}
  def self.call(name, method = '', args = {})
    case name.to_s
    when 'device' then { 'platform' => 'web', 'model' => 'preview', 'sdk' => 0 }
    when 'storage' then method == 'set' ? (MEM[args['key'] || args[:key]] = args['value'] || args[:value]; true) : method == 'get' ? MEM[args['key'] || args[:key]] : MEM
    when 'clipboard' then method == 'set' ? (MEM['_clip'] = args['text'] || args[:text]; true) : MEM['_clip']
    end
  end
  def self.method_missing(n, m = '', a = {}) call(n, m, a) end
  def self.respond_to_missing?(*) true end
end
load_err = nil
begin; load ARGV[0]; rescue Exception => e; load_err = e; end
$stdin.each_line do |line|
  res = begin
    raise load_err if load_err
    req = JSON.parse(line); st = req['state']
    st = (respond_to?(:init, true) ? send(:init) : {}) if st.nil?
    if !req['event'].nil? && respond_to?(:update, true)
      r = send(:update, st, req['event']); st = r unless r.nil?
    end
    { state: st, ui: send(:view, st) }
  rescue Exception => e
    { error: "#{e.class}: #{e.message}\n#{(e.backtrace || []).first(3).join("\n")}" }
  end
  out.write(JSON.generate(res) + "\n")
end
