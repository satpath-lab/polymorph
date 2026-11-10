require 'json'
out = $stdout.dup; out.sync = true; $stdout = $stderr
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
