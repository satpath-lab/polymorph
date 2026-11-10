function init() return {count = 0} end

function update(s, e)
  if e.id == "inc" then s.count = s.count + 1
  elseif e.id == "dec" then s.count = s.count - 1
  elseif e.id == "reset" then s.count = 0; s._toast = "Reset!" end
  return s
end

function view(s)
  return {title = "Counter", accent = "#6C3BFF", body = {type = "column", padding = 24, gap = 16, align = "center", children = {
    {type = "text", text = "Lua ⚡", size = 14, color = "#6C3BFF", bold = true},
    {type = "text", text = tostring(s.count), size = 72, bold = true},
    {type = "row", gap = 12, children = {
      {type = "button", id = "dec", text = "−", width = 80},
      {type = "button", id = "inc", text = "+", width = 80},
    }},
    {type = "button", id = "reset", text = "Reset", bg = "#EEE", color = "#333"},
  }}}
end
