function price(amount, country)
  local rate = android.Tax.rate(country)
  return string.format("Lua from server: %.2f (updated over the air)", amount * (1 + rate))
end
