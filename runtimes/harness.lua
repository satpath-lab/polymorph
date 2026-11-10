local path = arg[1]
package.path = "./?.lua;./?/init.lua;./lib/?.lua;" .. package.path
local out = io.stdout
print = function(...) local t = {} for i = 1, select('#', ...) do t[#t+1] = tostring((select(i, ...))) end io.stderr:write(table.concat(t, "\t"), "\n") io.stderr:flush() end
local esc = {['"']='\\"', ['\\']='\\\\', ['\b']='\\b', ['\f']='\\f', ['\n']='\\n', ['\r']='\\r', ['\t']='\\t'}
local function enc(v, b)
  local t = type(v)
  if t == "nil" then b[#b+1] = "null"
  elseif t == "boolean" then b[#b+1] = tostring(v)
  elseif t == "number" then
    if v ~= v or v == math.huge or v == -math.huge then b[#b+1] = "null"
    elseif math.type(v) == "integer" then b[#b+1] = string.format("%d", v)
    else b[#b+1] = string.format("%.14g", v) end
  elseif t == "string" then
    b[#b+1] = '"' .. (v:gsub('[%c"\\]', function(c) return esc[c] or string.format("\\u%04x", c:byte()) end)) .. '"'
  elseif t == "table" then
    local n, arr = #v, true
    for k in pairs(v) do if type(k) ~= "number" then arr = false break end end
    if arr then
      b[#b+1] = "[" for i = 1, n do if i > 1 then b[#b+1] = "," end enc(v[i], b) end b[#b+1] = "]"
    else
      b[#b+1] = "{" local first = true
      for k, x in pairs(v) do if type(x) ~= "function" then
        if not first then b[#b+1] = "," end first = false
        enc(tostring(k), b) b[#b+1] = ":" enc(x, b) end end
      b[#b+1] = "}"
    end
  else b[#b+1] = "null" end
end
local function encode(v) local b = {} enc(v, b) return table.concat(b) end
local function decode(s)
  local pos = 1
  local function ws() pos = s:find("[^ \t\r\n]", pos) or #s + 1 end
  local function str()
    pos = pos + 1 local b = {}
    while true do
      local c = s:sub(pos, pos)
      if c == '"' then pos = pos + 1 break
      elseif c == "\\" then
        local e = s:sub(pos+1, pos+1)
        if e == "u" then
          local cp = tonumber(s:sub(pos+2, pos+5), 16) pos = pos + 6
          if cp >= 0xD800 and cp <= 0xDBFF and s:sub(pos, pos+1) == "\\u" then
            cp = 0x10000 + (cp - 0xD800) * 0x400 + (tonumber(s:sub(pos+2, pos+5), 16) - 0xDC00) pos = pos + 6 end
          b[#b+1] = utf8.char(cp)
        else b[#b+1] = ({b="\b", f="\f", n="\n", r="\r", t="\t"})[e] or e pos = pos + 2 end
      elseif c == "" then error("bad json")
      else local j = s:find('["\\]', pos) or #s + 1 b[#b+1] = s:sub(pos, j-1) pos = j end
    end
    return table.concat(b)
  end
  local val
  val = function()
    ws() local c = s:sub(pos, pos)
    if c == "{" then
      pos = pos + 1 local t = {} ws()
      if s:sub(pos, pos) == "}" then pos = pos + 1 return t end
      while true do ws() local k = str() ws() pos = pos + 1 t[k] = val() ws()
        local d = s:sub(pos, pos) pos = pos + 1 if d == "}" then return t end end
    elseif c == "[" then
      pos = pos + 1 local t = {} ws()
      if s:sub(pos, pos) == "]" then pos = pos + 1 return t end
      local i = 0
      while true do i = i + 1 t[i] = val() ws()
        local d = s:sub(pos, pos) pos = pos + 1 if d == "]" then return t end end
    elseif c == '"' then return str()
    elseif s:sub(pos, pos+3) == "true" then pos = pos + 4 return true
    elseif s:sub(pos, pos+4) == "false" then pos = pos + 5 return false
    elseif s:sub(pos, pos+3) == "null" then pos = pos + 4 return nil
    else local n = s:match("^-?%d+%.?%d*[eE]?[-+]?%d*", pos) pos = pos + #n return tonumber(n) end
  end
  return val()
end
local env = setmetatable({json = {encode = encode, decode = decode}}, {__index = _G})
local chunk, lerr = loadfile(path, "t", env)
if chunk then local ok, e = pcall(chunk) if not ok then lerr = tostring(e) end end
for line in io.lines() do
  local ok, res = xpcall(function()
    if lerr then error(lerr, 0) end
    local req = decode(line)
    local st = req.state
    if st == nil then st = env.init and env.init() or {} end
    if req.event ~= nil and env.update then local r = env.update(st, req.event) if r ~= nil then st = r end end
    return {state = st, ui = env.view(st)}
  end, debug.traceback)
  out:write(encode(ok and res or {error = tostring(res)}), "\n") out:flush()
end
