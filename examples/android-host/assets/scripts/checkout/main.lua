function init() return {qty = 1} end
function update(s, e)
  if e.id == "plus" then s.qty = s.qty + 1 end
  if e.id == "minus" and s.qty > 1 then s.qty = s.qty - 1 end
  if e.id == "pay" then s._finish = {qty = s.qty, total = s.qty * 9.5} end
  return s
end
function view(s)
  return {title = "Checkout (Lua screen)", accent = "#7C3AED", body = {type = "column", padding = 24, gap = 16, children = {
    {type = "text", text = "Coffee beans", size = 22, bold = true},
    {type = "row", gap = 12, align = "center", children = {
      {type = "button", id = "minus", text = "−"}, {type = "text", text = tostring(s.qty), size = 28, bold = true}, {type = "button", id = "plus", text = "+"}}},
    {type = "text", text = string.format("Total: %.2f", s.qty * 9.5), size = 18},
    {type = "button", id = "pay", text = "Pay and return to host app", bg = "#7C3AED"},
  }}}
end
