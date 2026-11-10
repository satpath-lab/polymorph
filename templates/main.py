def init():
    return {"count": 0}

def update(s, e):
    if e["id"] == "inc": s["count"] += 1
    elif e["id"] == "dec": s["count"] -= 1
    elif e["id"] == "reset": s["count"] = 0; s["_toast"] = "Reset!"
    return s

def view(s):
    return {"title": "Counter", "accent": "#0EA5E9", "body": {"type": "column", "padding": 24, "gap": 16, "align": "center", "children": [
        {"type": "text", "text": "Python ⚡", "size": 14, "color": "#0EA5E9", "bold": True},
        {"type": "text", "text": str(s["count"]), "size": 72, "bold": True},
        {"type": "row", "gap": 12, "children": [
            {"type": "button", "id": "dec", "text": "−", "width": 80},
            {"type": "button", "id": "inc", "text": "+", "width": 80},
        ]},
        {"type": "button", "id": "reset", "text": "Reset", "bg": "#EEE", "color": "#333"},
    ]}}
