from lib.ui import text, button, row, col, card, muted

ACCENT = "#0F766E"


def init():
    return {"route": "home", "photos": [], "files": [], "loc": None, "contact": None, "device": {}, "note": None}


def update(s, e):
    t, id, v = e.get("type"), e.get("id", ""), e.get("value")
    s["device"] = e.get("device") or s["device"]
    s["note"] = (e.get("store") or {}).get("note", s["note"])
    inputs = e.get("inputs") or {}

    if t == "result":
        if isinstance(v, dict) and v.get("error"):
            s["_toast"] = f"{id}: {v['error']}"
        elif id == "photo":
            s["photos"].insert(0, v["url"])
        elif id == "file":
            s["files"].insert(0, v)
        elif id == "where":
            s["loc"] = v
        elif id == "who":
            s["contact"] = v
        return s
    if t == "notification":
        s["route"] = "home"
        s["_toast"] = f"Opened from notification “{id}”"
        return s

    if id == "camera": s["_camera"] = "photo"
    elif id == "gallery-pick": s["_pick"] = {"id": "photo", "type": "image"}
    elif id == "file-pick": s["_pick"] = {"id": "file", "type": "any"}
    elif id == "locate": s["_location"] = "where"
    elif id == "maps" and s["loc"]: s["_open"] = f"geo:{s['loc']['lat']},{s['loc']['lng']}?q={s['loc']['lat']},{s['loc']['lng']}"
    elif id == "notify": s["_notify"] = {"title": "Field Kit", "text": "Hello from a script! Tap me.", "tag": "hello"}
    elif id == "remind": s["_notify"] = {"title": "Reminder", "text": "10 seconds are up ⏰", "at": 10, "tag": "reminder"}
    elif id == "contact": s["_contact"] = "who"
    elif id == "share": s["_share"] = inputs.get("msg") or "Built with VoltForge ⚡"
    elif id == "copy": s["_clipboard"] = inputs.get("msg") or "Built with VoltForge ⚡"; s["_toast"] = "Copied"
    elif id == "save-note":
        s["_store"] = {"note": inputs.get("note", "")}; s["note"] = inputs.get("note", ""); s["_toast"] = "Saved on this device"
    elif id == "call" and s["contact"]: s["_open"] = "tel:" + s["contact"]["phone"]
    elif id == "open-gallery": s["route"] = "gallery"
    elif id == "back": s["route"] = "home"
    return s


def home(s):
    loc, c, d = s["loc"], s["contact"], s["device"]
    thumbs = [{"type": "image", "src": u, "width": 96, "height": 96, "radius": 10} for u in s["photos"][:10]]
    return col(
        card("📷 Camera & files",
             row(button("camera", "Take photo", flex=1, bg=ACCENT), button("gallery-pick", "Pick image", flex=1, bg=ACCENT)),
             {"type": "hscroll", "gap": 8, "children": thumbs} if thumbs else muted("No photos yet."),
             row(button("file-pick", "Pick any file", flex=1, bg="#E6F4F1", color=ACCENT),
                 button("open-gallery", f"Gallery ({len(s['photos'])}) →", flex=1, bg="#E6F4F1", color=ACCENT)),
             *[muted(f"📎 {f['name']} · {f['size'] // 1024} KB · {f['type']}") for f in s["files"][:3]]),
        card("📍 Location",
             text(f"{loc['lat']:.5f}, {loc['lng']:.5f}  (±{loc['accuracy']:.0f} m)" if loc else "Unknown", mono=True),
             row(button("locate", "Get location", flex=1, bg=ACCENT), button("maps", "Open in Maps", flex=1, bg="#E6F4F1", color=ACCENT, enabled=bool(loc)))),
        card("🔔 Notifications",
             row(button("notify", "Notify now", flex=1, bg=ACCENT), button("remind", "Remind in 10 s", flex=1, bg=ACCENT)),
             muted("Scheduled reminders fire even if the app is closed.")),
        card("👤 Contacts",
             text(f"{c['name']} · {c['phone']}" if c else "None picked"),
             row(button("contact", "Pick contact", flex=1, bg=ACCENT), button("call", "Call", flex=1, bg="#E6F4F1", color=ACCENT, enabled=bool(c)))),
        card("📤 Share & clipboard",
             {"type": "input", "id": "msg", "hint": "Message"},
             row(button("share", "Share…", flex=1, bg=ACCENT), button("copy", "Copy", flex=1, bg=ACCENT))),
        card("💾 Stored on this device",
             muted(f"Saved note: {s['note']!r}" if s["note"] else "Nothing saved yet."),
             row({"type": "input", "id": "note", "hint": "Note to keep on the phone", "flex": 1}, button("save-note", "Save", bg=ACCENT))),
        card("📱 Device",
             muted(" · ".join(f"{k}: {v}" for k, v in d.items()) or "Interact once to see device info.")),
        padding=16)


def gallery(s):
    imgs = [{"type": "image", "src": u, "height": 240, "radius": 14} for u in s["photos"]]
    return col(*(imgs or [muted("No photos yet. Go back and take one.")]), button("back", "← Back", bg=ACCENT), padding=16)


def view(s):
    if s["route"] == "gallery":
        return {"title": "Gallery", "route": "gallery", "back": "back", "accent": ACCENT, "bg": "#F0FDFA", "body": gallery(s)}
    return {"title": "Field Kit", "route": "home", "accent": ACCENT, "bg": "#F0FDFA", "body": home(s)}
