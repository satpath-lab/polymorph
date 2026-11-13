import json

def price(amount, country):
    rate = android.Tax.rate(country)
    return f"Python: {amount * (1 + rate):.2f} ({rate:.0%} tax, {android.hostVersion()})"

ticks = []

def start_ticks():
    def on_tick(n):
        ticks.append(n)
        android.emit("tick", {"n": n, "seen": len(ticks)})
    android.Clock.every(300, 3, on_tick)
    return "ticking"
