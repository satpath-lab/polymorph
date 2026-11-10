#!/usr/bin/env python3
"""VoltForge — live-scripted native Android apps in Lua, Python, PHP, JavaScript or Ruby."""
import argparse, collections, hashlib, json, os, re, shutil, socket, subprocess, sys, tempfile, threading, time, uuid, zipfile
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs, unquote

ROOT = os.path.dirname(os.path.abspath(__file__))
APPS, RT, WEB, TPL, BUILD, ANDROID = (os.path.join(ROOT, d) for d in ("apps", "runtimes", "web", "templates", "build", "android"))
LANGS = {".lua": (["lua"], "Lua"), ".py": ([sys.executable, "-u"], "Python"), ".php": (["php"], "PHP"), ".js": (["node"], "JavaScript"), ".rb": (["ruby"], "Ruby")}
EFFECTS = ("_toast", "_open", "_vibrate")
NAME_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,39}$")
TOKEN = os.environ.get("VOLT_TOKEN", "")
CALL_TIMEOUT = float(os.environ.get("VOLT_TIMEOUT", "10"))
changed = threading.Condition()


def available_langs():
    return {ext: name for ext, (cmd, name) in LANGS.items() if shutil.which(cmd[0])}


def lan_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.connect(("10.255.255.255", 1)); ip = s.getsockname()[0]; s.close(); return ip
    except OSError:
        return "127.0.0.1"


class Worker:
    def __init__(self, app):
        self.app = app
        cmd = LANGS[app.ext][0] + [os.path.join(RT, "harness" + app.ext), app.main]
        self.p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=app.dir)
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        for line in self.p.stderr:
            self.app.log(line.decode("utf-8", "replace").rstrip())

    def alive(self):
        return self.p.poll() is None

    def call(self, req):
        t = threading.Timer(CALL_TIMEOUT, self.kill); t.start()
        try:
            self.p.stdin.write((json.dumps(req) + "\n").encode()); self.p.stdin.flush()
            line = self.p.stdout.readline()
        finally:
            t.cancel()
        if not line:
            raise RuntimeError("script process exited (timeout after %ss or crash)" % CALL_TIMEOUT)
        return json.loads(line)

    def kill(self):
        try: self.p.kill()
        except OSError: pass


class App:
    def __init__(self, name):
        self.name, self.dir = name, os.path.join(APPS, name)
        self.version, self.worker, self.sig = 1, None, None
        self.lock = threading.Lock()
        self.sessions = collections.OrderedDict()
        self.logs, self.seq = collections.deque(maxlen=400), 0
        self.main = self.ext = None
        self.locate()

    def locate(self):
        for ext in LANGS:
            p = os.path.join(self.dir, "main" + ext)
            if os.path.exists(p):
                self.main, self.ext = p, ext; return
        self.main = self.ext = None

    def signature(self):
        out = []
        for d, _, files in os.walk(self.dir):
            for f in files:
                if not f.startswith("."):
                    try: st = os.stat(os.path.join(d, f)); out.append((d, f, st.st_mtime_ns, st.st_size))
                    except OSError: pass
        return tuple(sorted(out))

    def log(self, text):
        self.seq += 1; self.logs.append((self.seq, time.strftime("%H:%M:%S"), text))

    def bump(self):
        with self.lock:
            self.locate(); self.version += 1
            if self.worker: self.worker.kill(); self.worker = None
        self.log("⚡ reloaded v%d" % self.version)
        with changed: changed.notify_all()

    def run(self, session, event):
        with self.lock:
            if not self.main:
                return {"ui": error_screen("No main.<lua|py|php|js|rb> in app '%s'" % self.name), "v": self.version}
            if not self.worker or not self.worker.alive():
                self.worker = Worker(self)
            st = self.sessions.get(session)
            try:
                r = self.worker.call({"state": st, "event": event})
            except Exception as e:
                self.worker.kill(); self.worker = None; r = {"error": str(e)}
            if "error" in r:
                self.log("✖ " + str(r["error"]))
                tail = "\n".join(t for _, _, t in list(self.logs)[-6:])
                return {"ui": error_screen(str(r["error"]) + ("\n\n" + tail if tail else "")), "v": self.version}
            st, fx = r.get("state"), {}
            if isinstance(st, dict):
                for k in EFFECTS:
                    if k in st: fx[k[1:]] = st.pop(k)
            self.sessions[session] = st; self.sessions.move_to_end(session)
            while len(self.sessions) > 5000: self.sessions.popitem(last=False)
            return {"ui": r.get("ui"), "fx": fx, "v": self.version}


def error_screen(msg):
    return {"title": "Script error", "bg": "#1A0B0E", "accent": "#B91C1C", "body": {"type": "column", "padding": 20, "gap": 12, "children": [
        {"type": "text", "text": "⚠ Script error", "size": 22, "bold": True, "color": "#FF6B6B"},
        {"type": "text", "text": msg, "size": 13, "mono": True, "color": "#FFD6D6"},
        {"type": "text", "text": "Fix the code — this screen reloads automatically.", "size": 12, "color": "#9CA3AF"}]}}


apps, apps_lock = {}, threading.Lock()


def scan():
    os.makedirs(APPS, exist_ok=True)
    names = {n for n in os.listdir(APPS) if NAME_RE.match(n) and os.path.isdir(os.path.join(APPS, n))}
    with apps_lock:
        for n in names - apps.keys():
            apps[n] = App(n); apps[n].sig = apps[n].signature()
        for n in apps.keys() - names:
            a = apps.pop(n)
            if a.worker: a.worker.kill()
    for a in list(apps.values()):
        sig = a.signature()
        if sig != a.sig:
            a.sig = sig; a.bump()


def watcher():
    while True:
        try: scan()
        except Exception as e: print("watch error:", e, file=sys.stderr)
        time.sleep(0.25)


def get_app(name):
    with apps_lock:
        return apps.get(name)


# ---------------------------------------------------------------- APK builder
def sdk_paths():
    cands = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"), os.path.expanduser("~/Library/Android/sdk"), os.path.expanduser("~/Android/Sdk")]
    home = next((c for c in cands if c and os.path.isdir(c)), None)
    if not home: raise RuntimeError("Android SDK not found — set ANDROID_HOME")
    vkey = lambda v: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", v)]
    bts = sorted((v for v in os.listdir(os.path.join(home, "build-tools")) if os.path.exists(os.path.join(home, "build-tools", v, "aapt2"))), key=vkey)
    plats = sorted((p for p in os.listdir(os.path.join(home, "platforms")) if os.path.exists(os.path.join(home, "platforms", p, "android.jar"))), key=lambda p: vkey(p.split("-")[-1]))
    if not bts or not plats: raise RuntimeError("Need Android build-tools and a platform installed")
    bt = os.path.join(home, "build-tools", bts[-1])
    return {"aapt2": os.path.join(bt, "aapt2"), "d8": os.path.join(bt, "d8"), "zipalign": os.path.join(bt, "zipalign"),
            "apksigner": os.path.join(bt, "apksigner"), "lambda": os.path.join(bt, "core-lambda-stubs.jar"), "jar": os.path.join(home, "platforms", plats[-1], "android.jar")}


def sh(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode: raise RuntimeError("%s failed:\n%s%s" % (os.path.basename(cmd[0]), r.stdout, r.stderr))
    return r.stdout


build_lock = threading.Lock()


def compile_dex(sdk):
    src = sorted(os.path.join(d, f) for d, _, fs in os.walk(os.path.join(ANDROID, "src")) for f in fs if f.endswith(".java"))
    h = hashlib.sha1(b"".join(open(f, "rb").read() for f in src) + sdk["jar"].encode()).hexdigest()[:12]
    out = os.path.join(BUILD, "dex-" + h)
    if os.path.exists(os.path.join(out, "classes.dex")): return os.path.join(out, "classes.dex")
    cls = os.path.join(BUILD, "classes-" + h); shutil.rmtree(cls, ignore_errors=True); os.makedirs(cls); os.makedirs(out, exist_ok=True)
    sh(["javac", "-nowarn", "-Xlint:-options", "-source", "8", "-target", "8", "-bootclasspath", sdk["jar"] + os.pathsep + sdk["lambda"], "-d", cls] + src)
    classes = [os.path.join(d, f) for d, _, fs in os.walk(cls) for f in fs if f.endswith(".class")]
    sh([sdk["d8"], "--release", "--min-api", "24", "--lib", sdk["jar"], "--output", out] + classes)
    shutil.rmtree(cls, ignore_errors=True)
    return os.path.join(out, "classes.dex")


def keystore():
    ks = os.path.join(BUILD, "volt.keystore")
    if not os.path.exists(ks):
        sh(["keytool", "-genkeypair", "-keystore", ks, "-alias", "volt", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
            "-storepass", "voltforge", "-keypass", "voltforge", "-dname", "CN=VoltForge"])
    return ks


def xml_escape(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;").replace("'", "\\'")


def build_apk(app, server, label=None, out=None):
    with build_lock:
        os.makedirs(BUILD, exist_ok=True)
        sdk = sdk_paths(); dex = compile_dex(sdk)
        pkg = "dev.volt.player" if not app else "dev.volt.app." + (re.sub(r"[^a-z0-9]", "", app) or "x")
        label = label or (app.replace("-", " ").title() if app else "VoltForge")
        out = out or os.path.join(BUILD, (app or "voltforge-player") + ".apk")
        with tempfile.TemporaryDirectory() as tmp:
            man = open(os.path.join(ANDROID, "AndroidManifest.xml")).read().replace("{{PKG}}", pkg).replace("{{LABEL}}", xml_escape(label))
            open(os.path.join(tmp, "AndroidManifest.xml"), "w").write(man)
            os.makedirs(os.path.join(tmp, "assets"))
            json.dump({"server": server.rstrip("/"), "app": app or "", "token": ""}, open(os.path.join(tmp, "assets", "volt.json"), "w"))
            sh([sdk["aapt2"], "compile", "--dir", os.path.join(ANDROID, "res"), "-o", os.path.join(tmp, "res.zip")])
            un = os.path.join(tmp, "un.apk")
            sh([sdk["aapt2"], "link", "-o", un, "-I", sdk["jar"], "--manifest", os.path.join(tmp, "AndroidManifest.xml"), "--min-sdk-version", "24",
                "--target-sdk-version", "34", "--version-code", str(int(time.time()) // 60), "--version-name", "1.0", "-A", os.path.join(tmp, "assets"), os.path.join(tmp, "res.zip")])
            with zipfile.ZipFile(un, "a") as z: z.write(dex, "classes.dex")
            al = os.path.join(tmp, "al.apk")
            sh([sdk["zipalign"], "-p", "-f", "4", un, al])
            sh([sdk["apksigner"], "sign", "--ks", keystore(), "--ks-pass", "pass:voltforge", "--key-pass", "pass:voltforge", "--out", out, al])
        return out


# ---------------------------------------------------------------- HTTP
class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "VoltForge/1.0"

    def log_message(self, *a): pass

    def send(self, code, body, ctype="application/json", extra=None):
        if not isinstance(body, (bytes, bytearray)):
            body = json.dumps(body, ensure_ascii=False).encode() if ctype == "application/json" else body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype + ("; charset=utf-8" if not ctype.startswith(("image", "application/vnd")) else ""))
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items(): self.send_header(k, v)
        self.end_headers(); self.wfile.write(body)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}") if n else {}

    def authed(self, q):
        if not TOKEN or self.headers.get("X-Volt-Token") == TOKEN or q.get("token", [""])[0] == TOKEN: return True
        self.send(401, {"error": "token required"}); return False

    def do_OPTIONS(self):
        self.send(204, b"", extra={"Access-Control-Allow-Methods": "GET,POST,PUT,DELETE", "Access-Control-Allow-Headers": "Content-Type,X-Volt-Token"})

    def do_GET(self): self.route("GET")
    def do_POST(self): self.route("POST")
    def do_PUT(self): self.route("PUT")
    def do_DELETE(self): self.route("DELETE")

    def route(self, m):
        u = urlparse(self.path); q = parse_qs(u.query); p = [unquote(x) for x in u.path.strip("/").split("/") if x]
        try:
            if m == "GET" and not p:
                return self.send(200, open(os.path.join(WEB, "index.html"), "rb").read(), "text/html")
            if p[:1] == ["apps"] and len(p) >= 4 and p[2] == "assets":
                return self.static(p[1], p[3:])
            if p[:1] != ["api"]: return self.send(404, {"error": "not found"})
            r, name = p[1] if len(p) > 1 else "", p[2] if len(p) > 2 else ""
            a = get_app(name) if name else None
            if r == "meta":
                return self.send(200, {"langs": available_langs(), "ip": lan_ip(), "port": self.server.server_port, "auth": bool(TOKEN)})
            if r == "apps" and m == "GET" and not name:
                with apps_lock: lst = sorted(apps.values(), key=lambda x: x.name)
                return self.send(200, [{"name": x.name, "lang": LANGS[x.ext][1] if x.ext else None, "ext": x.ext, "v": x.version} for x in lst])
            if r == "apps" and m == "POST":
                if not self.authed(q): return
                b = self.body(); n, ext = b.get("name", ""), b.get("ext", ".lua")
                if not NAME_RE.match(n) or ext not in LANGS: return self.send(400, {"error": "name must be lowercase a-z 0-9 - _ ; ext one of " + ", ".join(LANGS)})
                d = os.path.join(APPS, n)
                if os.path.exists(d): return self.send(409, {"error": "app exists"})
                os.makedirs(os.path.join(d, "assets")); shutil.copy(os.path.join(TPL, "main" + ext), os.path.join(d, "main" + ext)); scan()
                return self.send(201, {"name": n})
            if not a: return self.send(404, {"error": "unknown app"})
            if r == "apps" and m == "DELETE":
                if not self.authed(q): return
                shutil.rmtree(a.dir); scan(); return self.send(200, {"ok": True})
            if r == "source" and m == "GET":
                return self.send(200, {"file": os.path.basename(a.main or ""), "ext": a.ext, "code": open(a.main, encoding="utf-8").read() if a.main else "", "v": a.version})
            if r == "source" and m == "PUT":
                if not self.authed(q): return
                code = self.body().get("code", "")
                with open(a.main, "w", encoding="utf-8") as f: f.write(code)
                a.sig = a.signature(); a.bump()
                return self.send(200, {"v": a.version})
            if r == "run" and m == "POST":
                b = self.body()
                return self.send(200, a.run(str(b.get("session") or "anon")[:64], b.get("event")))
            if r == "reset" and m == "POST":
                sid = self.body().get("session")
                with a.lock:
                    if sid: a.sessions.pop(sid, None)
                    else: a.sessions.clear()
                return self.send(200, {"ok": True})
            if r == "watch":
                v, end = int(q.get("v", ["0"])[0]), time.time() + 25
                with changed:
                    while a.version == v and time.time() < end and get_app(name) is a:
                        changed.wait(end - time.time())
                return self.send(200, {"v": a.version})
            if r == "logs":
                after = int(q.get("after", ["0"])[0])
                return self.send(200, {"logs": [l for l in list(a.logs) if l[0] > after]})
            if r == "apk":
                if not self.authed(q): return
                host = self.headers.get("Host", "%s:%d" % (lan_ip(), self.server.server_port))
                server = q.get("server", ["http://" + host])[0]
                path = build_apk(name, server, q.get("label", [None])[0])
                return self.send(200, open(path, "rb").read(), "application/vnd.android.package-archive",
                                 {"Content-Disposition": 'attachment; filename="%s.apk"' % name})
            self.send(404, {"error": "not found"})
        except Exception as e:
            self.send(500, {"error": str(e)})

    def static(self, name, rest):
        a = get_app(name)
        base = os.path.realpath(os.path.join(APPS, name, "assets")) if a else None
        f = os.path.realpath(os.path.join(base, *rest)) if base else None
        if not f or not f.startswith(base + os.sep) or not os.path.isfile(f): return self.send(404, {"error": "not found"})
        ext = os.path.splitext(f)[1].lower()
        ct = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".gif": "image/gif", ".webp": "image/webp", ".svg": "image/svg+xml"}.get(ext, "application/octet-stream")
        self.send(200, open(f, "rb").read(), ct, {"Cache-Control": "max-age=60"})


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, addr):
        if not isinstance(sys.exc_info()[1], (BrokenPipeError, ConnectionResetError)): super().handle_error(request, addr)


def serve(port):
    scan(); threading.Thread(target=watcher, daemon=True).start()
    srv = Server(("0.0.0.0", port), H)
    ip = lan_ip()
    print("⚡ VoltForge running\n   editor : http://localhost:%d\n   devices: http://%s:%d   (emulator: http://10.0.2.2:%d)\n   langs  : %s%s" % (
        port, ip, port, port, ", ".join(available_langs().values()), "\n   auth   : VOLT_TOKEN required for edits" if TOKEN else ""), flush=True)
    try: srv.serve_forever()
    except KeyboardInterrupt: pass


def main():
    ap = argparse.ArgumentParser(prog="volt", description="VoltForge — live-scripted native Android apps")
    sub = ap.add_subparsers(dest="cmd")
    s = sub.add_parser("serve", help="run dev/production server + web editor"); s.add_argument("--port", type=int, default=int(os.environ.get("PORT", 8787)))
    n = sub.add_parser("new", help="create an app"); n.add_argument("name"); n.add_argument("lang", choices=[e[1:] for e in LANGS])
    b = sub.add_parser("apk", help="build a signed APK"); b.add_argument("name", nargs="?", help="app name (omit for the generic player)")
    b.add_argument("--server", required=True, help="URL the phone uses to reach this server"); b.add_argument("--label"); b.add_argument("--out"); b.add_argument("--install", action="store_true")
    sub.add_parser("ls", help="list apps")
    a = ap.parse_args()
    if a.cmd in (None, "serve"):
        serve(getattr(a, "port", int(os.environ.get("PORT", 8787))))
    elif a.cmd == "new":
        if not NAME_RE.match(a.name): sys.exit("bad name")
        d = os.path.join(APPS, a.name); os.makedirs(os.path.join(d, "assets"))
        shutil.copy(os.path.join(TPL, "main." + a.lang), os.path.join(d, "main." + a.lang)); print("created", os.path.relpath(d))
    elif a.cmd == "ls":
        scan()
        for x in sorted(apps.values(), key=lambda x: x.name): print("%-24s %s" % (x.name, LANGS[x.ext][1] if x.ext else "?"))
    elif a.cmd == "apk":
        out = build_apk(a.name, a.server, a.label, a.out); print("built", out)
        if a.install: print(sh(["adb", "install", "-r", out]).strip())


if __name__ == "__main__":
    main()
