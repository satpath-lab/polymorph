def text(t, **k): return {"type": "text", "text": str(t), **k}
def button(id, t, **k): return {"type": "button", "id": id, "text": t, **k}
def row(*c, **k): return {"type": "row", "gap": 8, "children": list(c), **k}
def col(*c, **k): return {"type": "column", "gap": 10, "children": list(c), **k}
def card(title, *c): return {"type": "card", "gap": 10, "children": [text(title, size=18, bold=True), *c]}
def muted(t): return text(t, size=13, color="#6B7280")
