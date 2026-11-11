local P = "#7C3AED"

function init()
  return {route = "home", torch = false, battery = nil, hash = nil, motion = nil, sensing = false,
          ble = nil, bg = false, bg_runs = 0, count = 100, last_error = nil}
end

local function native(plugin, method, id, args)
  return {plugin = plugin, method = method, id = id, args = args or {}}
end

function update(s, e)
  local id, t = e.id or "", e.type
  if t == "background" then
    s.bg_runs = s.bg_runs + 1
    s._notify = {title = "Background check", text = "Ran in the background " .. s.bg_runs .. " time(s)", tag = "bg"}
    return s
  end
  if t == "native" and id == "motion" then s.motion = e.value return s end
  if t == "result" then
    local v = e.value
    if type(v) == "table" and v.error then s.last_error = id .. ": " .. v.error s._toast = s.last_error return s end
    s.last_error = nil
    if id == "torch" then s.torch = v.on
    elseif id == "battery" then s.battery = v
    elseif id == "hash" then s.hash = v
    elseif id == "ble" then s.ble = v
    elseif id == "sensors" then s.sensing = (v == "started") end
    return s
  end
  if id == "torch" then s._native = native("Torch", s.torch and "off" or "on", "torch")
  elseif id == "battery" then s._native = native("Battery", "read", "battery")
  elseif id == "hash" then s._native = native("NativeHash", "fnv1a", "hash", {text = (e.inputs or {}).text or "", rounds = 100000})
  elseif id == "sensors" then s._native = native("Sensors", s.sensing and "stop" or "start", "sensors")
  elseif id == "ble" then s.ble = nil s._toast = "Scanning…" s._native = native("Bluetooth", "scan", "ble", {seconds = 4})
  elseif id == "bg" then s.bg = not s.bg s._background = s.bg and {every = 15} or {on = false}
  elseif id == "feed" then s.route = "feed"
  elseif id == "more" then s.count = s.count + 100
  elseif id == "back" then s.route = "home" end
  return s
end

local function btn(id, text, extra)
  local b = {type = "button", id = id, text = text, bg = P, flex = 1}
  for k, v in pairs(extra or {}) do b[k] = v end
  return b
end
local function card(title, ...) return {type = "card", gap = 10, children = {{type = "text", text = title, size = 18, bold = true}, ...}} end
local function muted(t) return {type = "text", text = t, size = 13, color = "#6B7280"} end

local function home(s)
  local b, h, m = s.battery, s.hash, s.motion
  local devices = {}
  for i, d in ipairs(s.ble or {}) do
    if i > 8 then break end
    devices[#devices + 1] = muted(d.name .. "  " .. d.address .. "  " .. d.rssi .. " dBm")
  end
  return {type = "column", padding = 16, gap = 12, children = {
    card("🔦 Flashlight · Java", {type = "row", children = {btn("torch", s.torch and "Turn off" or "Turn on")}}),
    card("🔋 Battery · Kotlin",
      muted(b and (b.percent .. "% · " .. (b.charging and "charging" or "on battery") .. " · " .. b.celsius .. " °C") or "Not read yet"),
      {type = "row", children = {btn("battery", "Read battery")}}),
    card("⚙️ C via JNI",
      {type = "input", id = "text", hint = "Text to hash 100,000×", value = ""},
      muted(h and ("fnv1a = " .. h.hash .. " in " .. h.micros .. " µs") or "Not run yet"),
      {type = "row", children = {btn("hash", "Hash in C")}}),
    card("📈 Motion sensor stream · Java",
      {type = "text", text = m and string.format("x %.2f   y %.2f   z %.2f", m.x, m.y, m.z) or "–", mono = true},
      {type = "row", children = {btn("sensors", s.sensing and "Stop" or "Start streaming")}}),
    card("📶 Bluetooth LE scan · Java",
      muted(s.ble and (#s.ble .. " device(s) found") or "Not scanned yet"),
      {type = "column", gap = 4, children = devices},
      {type = "row", children = {btn("ble", "Scan 4 s")}}),
    card("⏱ Background job",
      muted("Runs every 15 min (Android's minimum) even when the app is closed. Runs so far: " .. s.bg_runs),
      {type = "switch", id = "bg", text = "Enabled", value = s.bg}),
    card("📜 Big list", muted("Smooth scrolling with thousands of rows and infinite loading."),
      {type = "row", children = {btn("feed", "Open feed →")}}),
    s.last_error and {type = "text", text = "⚠ " .. s.last_error, color = "#B91C1C", size = 13} or {type = "spacer"},
  }}
end

local function feed(s)
  local items = {}
  for i = 1, s.count do
    items[i] = {type = "row", padding = 14, bg = "#FFFFFF", radius = 12, gap = 12, children = {
      {type = "text", text = "#" .. i, bold = true, color = P, width = 64},
      {type = "text", text = "Row " .. i .. " rendered natively", flex = 1}}}
  end
  return {type = "column", padding = 12, gap = 8, children = {
    muted(s.count .. " rows · more load when you reach the end"),
    {type = "list", id = "feed", items = items, onEnd = "more", gap = 8}}}
end

function view(s)
  if s.route == "feed" then
    return {title = "Feed", route = "feed", back = "back", accent = P, bg = "#F5F3FF", scroll = false, body = feed(s)}
  end
  return {title = "Native Demo", route = "home", accent = P, bg = "#F5F3FF", body = home(s)}
end
