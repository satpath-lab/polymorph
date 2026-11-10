function init() return {items = {}} end

function update(s, e)
  local id = e.id or ""
  if id == "add" or (e.type == "submit" and id == "new") then
    local t = (e.inputs and e.inputs.new or ""):match("^%s*(.-)%s*$")
    if #t > 0 then table.insert(s.items, {text = t, done = false}) s._toast = "Added" end
  elseif id:sub(1, 5) == "item:" then
    local it = s.items[tonumber(id:sub(6))]
    if it then it.done = not it.done end
  elseif id == "clear" then
    local keep = {}
    for _, it in ipairs(s.items) do if not it.done then keep[#keep+1] = it end end
    s.items = keep
    s._vibrate = 40
  end
  return s
end

function view(s)
  local done, rows = 0, {}
  for i, it in ipairs(s.items) do
    if it.done then done = done + 1 end
    rows[#rows+1] = {type = "row", id = "item:" .. i, padding = 14, bg = "#FFFFFF", radius = 12, gap = 12, children = {
      {type = "text", text = it.done and "✅" or "⬜", size = 18},
      {type = "text", text = it.text, size = 16, flex = 1, color = it.done and "#9CA3AF" or "#111827"},
    }}
  end
  if #rows == 0 then rows[1] = {type = "text", text = "Nothing yet — add your first task ✨", align = "center", color = "#9CA3AF", padding = 24} end
  local total = #s.items
  return {title = "Tasks", accent = "#6C3BFF", bg = "#F3F0FF", body = {type = "column", padding = 16, gap = 12, children = {
    {type = "card", bg = "#6C3BFF", children = {
      {type = "text", text = "Today", color = "#E9E3FF", size = 14},
      {type = "text", text = done .. " / " .. total .. " done", color = "#FFFFFF", size = 28, bold = true},
      {type = "progress", value = total > 0 and done / total or 0, color = "#FFFFFF"},
    }},
    {type = "row", gap = 8, children = {
      {type = "input", id = "new", hint = "What needs doing?", value = "", flex = 1},
      {type = "button", id = "add", text = "Add"},
    }},
    {type = "column", gap = 8, children = rows},
    done > 0 and {type = "button", id = "clear", text = "Clear completed", bg = "#FFFFFF", color = "#6C3BFF"} or {type = "spacer", size = 0},
  }}}
end
