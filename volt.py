#!/usr/bin/env python3
"""VoltForge — live-scripted native Android apps in Lua, Python, PHP, JavaScript or Ruby."""
import argparse, collections, getpass, hashlib, hmac, io, json, os, queue, re, secrets, shutil, socket, sqlite3, subprocess, sys, tarfile, tempfile, threading, time, zipfile
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs, unquote

ROOT = os.path.dirname(os.path.abspath(__file__))
APPS = os.environ.get("VOLT_APPS", os.path.join(ROOT, "apps"))
DATA = os.environ.get("VOLT_DATA", os.path.join(ROOT, "data"))
RELEASES = os.path.join(DATA, "releases")
RT, WEB, TPL, ANDROID = (os.path.join(ROOT, d) for d in ("runtimes", "web", "templates", "android"))
BUILD = os.path.join(DATA, "build")
LANGS = {".lua": (["lua"], "Lua"), ".py": ([sys.executable, "-u"], "Python"), ".php": (["php"], "PHP"), ".js": (["node"], "JavaScript"), ".rb": (["ruby"], "Ruby")}
EFFECTS = ("_toast", "_open", "_vibrate")
NAME_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,39}$")
ROLES = {"viewer": 1, "developer": 2, "admin": 3}
CHANNELS = ("dev", "prod")
CALL_TIMEOUT = float(os.environ.get("VOLT_TIMEOUT", "10"))
POOL = max(1, int(os.environ.get("VOLT_WORKERS", "4")))
DEVICE_KEY = os.environ.get("VOLT_DEVICE_KEY", "")
TOKEN_DAYS = int(os.environ.get("VOLT_SESSION_DAYS", "14"))
MAX_BODY = 50 * 1024 * 1024
SKIP = {"__pycache__", "node_modules", ".git"}
changed = threading.Condition()


def available_langs():
    return {ext: name for ext, (cmd, name) in LANGS.items() if shutil.which(cmd[0])}


def lan_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.connect(("10.255.255.255", 1)); ip = s.getsockname()[0]; s.close(); return ip
    except OSError:
        return "127.0.0.1"


def now():
    return int(time.time())


# ---------------------------------------------------------------- storage
class DB:
    SCHEMA = """
    CREATE TABLE IF NOT EXISTS users(name TEXT PRIMARY KEY, pw TEXT NOT NULL, role TEXT NOT NULL, created INTEGER);
    CREATE TABLE IF NOT EXISTS tokens(hash TEXT PRIMARY KEY, user TEXT NOT NULL, kind TEXT, label TEXT, created INTEGER, expires INTEGER);
    CREATE TABLE IF NOT EXISTS sessions(app TEXT, ch TEXT, sid TEXT, state TEXT, updated INTEGER, PRIMARY KEY(app, ch, sid));
    CREATE TABLE IF NOT EXISTS releases(app TEXT, n INTEGER, created INTEGER, user TEXT, note TEXT, tests TEXT, PRIMARY KEY(app, n));
    CREATE TABLE IF NOT EXISTS deploys(app TEXT PRIMARY KEY, n INTEGER, at INTEGER, user TEXT);
    CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, user TEXT, action TEXT, app TEXT, detail TEXT);
    """

    def __init__(self, path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        self.c = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self.l = threading.Lock()
        for p in ("journal_mode=WAL", "synchronous=NORMAL", "busy_timeout=5000"): self.c.execute("PRAGMA " + p)
        self.c.executescript(self.SCHEMA)

    def q(self, sql, *a):
        with self.l: return self.c.execute(sql, a).fetchall()

    def one(self, sql, *a):
        r = self.q(sql, *a); return r[0] if r else None


db = None


def init_db():
    global db
    if db is None:
        db = DB(os.path.join(DATA, "volt.db"))
        pw = os.environ.get("VOLT_ADMIN_PASSWORD")
        if pw and not db.one("SELECT 1 FROM users"):
            add_user(os.environ.get("VOLT_ADMIN_USER", "admin"), pw, "admin"); print("created admin user from VOLT_ADMIN_PASSWORD")
    return db


def hash_pw(pw, salt=None):
    salt = salt or secrets.token_hex(16)
    return salt + "$" + hashlib.pbkdf2_hmac("sha256", pw.encode(), bytes.fromhex(salt), 200_000).hex()


def check_pw(pw, stored):
    salt = stored.split("$")[0]
    return hmac.compare_digest(hash_pw(pw, salt), stored)


def add_user(name, pw, role):
    if not NAME_RE.match(name) or role not in ROLES or len(pw) < 8: raise ValueError("name a-z0-9-_, role viewer|developer|admin, password ≥ 8 chars")
    db.q("INSERT OR REPLACE INTO users VALUES(?,?,?,?)", name, hash_pw(pw), role, now())


def new_token(user, kind="session", label=""):
    tok = "vf_" + secrets.token_urlsafe(32)
    exp = None if kind == "api" else now() + TOKEN_DAYS * 86400
    db.q("INSERT INTO tokens VALUES(?,?,?,?,?,?)", hashlib.sha256(tok.encode()).hexdigest(), user, kind, label, now(), exp)
    return tok


def open_mode():
    return not db.one("SELECT 1 FROM users")


def user_for(tok):
    if open_mode(): return ("local", "admin")
    if not tok: return None
    r = db.one("SELECT u.name, u.role FROM tokens t JOIN users u ON u.name = t.user WHERE t.hash = ? AND (t.expires IS NULL OR t.expires > ?)",
               hashlib.sha256(tok.encode()).hexdigest(), now())
    return tuple(r) if r else None


def audit(user, action, app="", detail=""):
    db.q("INSERT INTO audit(ts,user,action,app,detail) VALUES(?,?,?,?,?)", now(), user, action, app, detail[:500])


# ---------------------------------------------------------------- workers
def worker_env(channel):
    keep = {k: v for k, v in os.environ.items() if k in ("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "SYSTEMROOT", "TZ") or k.startswith("VOLT_APP_")}
    keep["VOLT_CHANNEL"] = channel
    return keep


class Worker:
    def __init__(self, rt, gen=0):
        self.rt, self.gen = rt, gen
        cmd = LANGS[rt.ext][0] + [os.path.join(RT, "harness" + rt.ext), rt.main]
        self.p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=rt.dir, env=worker_env(rt.channel))
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        for line in self.p.stderr:
            self.rt.log(line.decode("utf-8", "replace").rstrip())

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


def split_effects(st):
    fx = {}
    if isinstance(st, dict):
        for k in EFFECTS:
            if k in st: fx[k[1:]] = st.pop(k)
    return st, fx


def locate_main(d):
    for ext in LANGS:
        p = os.path.join(d, "main" + ext) if d else None
        if p and os.path.exists(p): return p, ext
    return None, None


SLOCKS = [threading.Lock() for _ in range(256)]


class Runtime:
    def __init__(self, name, channel, d):
        self.name, self.channel, self.dir = name, channel, d
        self.version, self.gen, self.count, self.sig = 1, 0, 0, None
        self.idle = queue.LifoQueue(); self.plock = threading.Lock()
        self.logs, self.seq = collections.deque(maxlen=500), 0
        self.reqs = self.errs = 0; self.ms = 0.0
        self.main, self.ext = locate_main(d)

    def log(self, text):
        self.seq += 1; self.logs.append((self.seq, time.strftime("%H:%M:%S"), text))

    def set_dir(self, d):
        self.dir = d; self.bump("deployed " + os.path.basename(d or ""))

    def bump(self, why="reloaded"):
        with self.plock:
            self.gen += 1; self.version += 1; self.main, self.ext = locate_main(self.dir)
            while True:
                try: w = self.idle.get_nowait()
                except queue.Empty: break
                w.kill(); self.count -= 1
        self.log("⚡ %s v%d" % (why, self.version))
        with changed: changed.notify_all()

    def shutdown(self):
        self.dir = None; self.bump("removed")

    def acquire(self):
        while True:
            try:
                w = self.idle.get_nowait()
            except queue.Empty:
                spawn = False
                with self.plock:
                    if self.count < POOL: self.count += 1; spawn = True
                if spawn:
                    try: return Worker(self, self.gen)
                    except Exception:
                        with self.plock: self.count -= 1
                        raise
                w = self.idle.get(timeout=CALL_TIMEOUT + 5)
            if w.gen == self.gen and w.alive(): return w
            w.kill()
            with self.plock: self.count -= 1

    def release(self, w, ok):
        if ok and w.alive() and w.gen == self.gen: self.idle.put(w)
        else:
            w.kill()
            with self.plock: self.count -= 1

    def run(self, sid, event):
        if not self.main:
            msg = "No production release deployed yet for '%s'." % self.name if self.channel == "prod" else "No main.<lua|py|php|js|rb> in app '%s'" % self.name
            return {"ui": error_screen(msg), "v": self.version}
        t0 = time.perf_counter()
        with SLOCKS[hash((self.name, self.channel, sid)) % 256]:
            row = db.one("SELECT state FROM sessions WHERE app=? AND ch=? AND sid=?", self.name, self.channel, sid)
            st = json.loads(row[0]) if row else None
            ok = False
            try:
                w = self.acquire()
                try:
                    r = w.call({"state": st, "event": event}); ok = True
                finally:
                    self.release(w, ok)
            except Exception as e:
                r = {"error": str(e)}
            self.reqs += 1; self.ms += (time.perf_counter() - t0) * 1000
            if "error" in r:
                self.errs += 1; self.log("✖ " + str(r["error"]))
                tail = "\n".join(t for _, _, t in list(self.logs)[-6:]) if self.channel == "dev" else ""
                msg = str(r["error"]) if self.channel == "dev" else "Something went wrong. Please try again."
                return {"ui": error_screen(msg + ("\n\n" + tail if tail else "")), "v": self.version}
            st, fx = split_effects(r.get("state"))
            db.q("INSERT OR REPLACE INTO sessions VALUES(?,?,?,?,?)", self.name, self.channel, sid, json.dumps(st), now())
        return {"ui": r.get("ui"), "fx": fx, "v": self.version}


def error_screen(msg):
    return {"title": "Error", "bg": "#1A0B0E", "accent": "#B91C1C", "body": {"type": "column", "padding": 20, "gap": 12, "children": [
        {"type": "text", "text": "⚠ Script error", "size": 22, "bold": True, "color": "#FF6B6B"},
        {"type": "text", "text": msg, "size": 13, "mono": True, "color": "#FFD6D6"},
        {"type": "text", "text": "This screen reloads automatically when fixed.", "size": 12, "color": "#9CA3AF"}]}}


runtimes, rt_lock = {}, threading.Lock()


def app_dir(name):
    return os.path.join(APPS, name)


def deployed(name):
    r = db.one("SELECT n FROM deploys WHERE app=?", name)
    return r[0] if r else None


def get_rt(name, ch="dev"):
    if ch not in CHANNELS or not NAME_RE.match(name or ""): return None
    with rt_lock:
        rt = runtimes.get((name, ch))
        if rt: return rt
        if ch == "dev":
            if not os.path.isdir(app_dir(name)): return None
            rt = Runtime(name, ch, app_dir(name)); rt.sig = signature(app_dir(name))
        else:
            n = deployed(name)
            if n is None and not os.path.isdir(app_dir(name)): return None
            rt = Runtime(name, ch, os.path.join(RELEASES, name, str(n)) if n else None)
        runtimes[(name, ch)] = rt
        return rt


def signature(d):
    out = []
    for base, dirs, files in os.walk(d):
        dirs[:] = [x for x in dirs if x not in SKIP and not x.startswith(".")]
        for f in files:
            if not f.startswith("."):
                try: st = os.stat(os.path.join(base, f)); out.append((base, f, st.st_mtime_ns, st.st_size))
                except OSError: pass
    return tuple(sorted(out))


def list_apps():
    os.makedirs(APPS, exist_ok=True)
    return sorted(n for n in os.listdir(APPS) if NAME_RE.match(n) and os.path.isdir(app_dir(n)))


def scan():
    names = set(list_apps())
    for n in names: get_rt(n, "dev")
    with rt_lock:
        gone = [k for k in runtimes if k[1] == "dev" and k[0] not in names]
        dead = [runtimes.pop(k) for k in gone]
        devs = [r for k, r in runtimes.items() if k[1] == "dev"]
    for r in dead: r.shutdown()
    with rt_lock: prods = [r for k, r in runtimes.items() if k[1] == "prod"]
    for r in prods:
        n = deployed(r.name)
        want = os.path.join(RELEASES, r.name, str(n)) if n else None
        if want != r.dir: r.set_dir(want)
    for r in devs:
        sig = signature(r.dir)
        if sig != r.sig: r.sig = sig; r.bump()


def watcher():
    while True:
        try: scan()
        except Exception as e: print("watch error:", e, file=sys.stderr)
        time.sleep(0.25)


# ---------------------------------------------------------------- tests & releases
class TestRT:
    def __init__(self, d):
        self.dir, self.channel = d, "test"; self.main, self.ext = locate_main(d); self.out = []

    def log(self, t): self.out.append(t)


def dig(obj, path):
    for part in path.split("."):
        if isinstance(obj, list) and part.lstrip("-").isdigit():
            i = int(part); obj = obj[i] if -len(obj) <= i < len(obj) else None
        elif isinstance(obj, dict): obj = obj.get(part)
        else: return None
    return obj


def run_tests(d):
    tdir = os.path.join(d, "tests")
    files = sorted(f for f in os.listdir(tdir) if f.endswith(".json")) if os.path.isdir(tdir) else []
    rt = TestRT(d)
    if not rt.main: return {"ok": False, "results": [{"name": "load", "ok": False, "error": "no main file"}]}
    results = []
    for f in files:
        try: spec = json.load(open(os.path.join(tdir, f), encoding="utf-8"))
        except Exception as e:
            results.append({"name": f, "ok": False, "error": "invalid JSON: %s" % e}); continue
        for i, case in enumerate(spec if isinstance(spec, list) else [spec]):
            name = "%s › %s" % (f, case.get("name", "case %d" % (i + 1)))
            w = Worker(rt); err = None; st = None
            try:
                for si, step in enumerate(case.get("steps", [])):
                    r = w.call({"state": st, "event": step.get("event")})
                    if "error" in r: err = "step %d: %s" % (si + 1, r["error"]); break
                    st, fx = split_effects(r.get("state"))
                    ctx = {"state": st, "ui": r.get("ui"), "fx": fx}
                    for path, exp in (step.get("expect") or {}).items():
                        got = dig(ctx, path)
                        if got != exp: err = "step %d: %s = %s, expected %s" % (si + 1, path, json.dumps(got), json.dumps(exp)); break
                    if err: break
            except Exception as e:
                err = str(e)
            finally:
                w.kill()
            results.append({"name": name, "ok": err is None, "error": err})
    return {"ok": all(r["ok"] for r in results), "results": results, "count": len(results)}


def copy_tree(src, dst):
    shutil.copytree(src, dst, ignore=lambda d, names: [n for n in names if n in SKIP or n.startswith(".")])


def create_release(name, user, note="", force=False):
    src = app_dir(name)
    if not os.path.isdir(src): raise LookupError("unknown app")
    tests = run_tests(src)
    if not tests["ok"] and not force: return None, tests
    n = (db.one("SELECT MAX(n) FROM releases WHERE app=?", name)[0] or 0) + 1
    dst = os.path.join(RELEASES, name, str(n))
    if os.path.exists(dst): shutil.rmtree(dst)
    copy_tree(src, dst)
    db.q("INSERT INTO releases VALUES(?,?,?,?,?,?)", name, n, now(), user, note[:300], json.dumps({"ok": tests["ok"], "count": tests.get("count", 0)}))
    audit(user, "release", name, "r%d %s%s" % (n, note, " (FORCED, tests failing)" if not tests["ok"] else ""))
    return n, tests


def deploy(name, n, user):
    d = os.path.join(RELEASES, name, str(n))
    if not db.one("SELECT 1 FROM releases WHERE app=? AND n=?", name, n) or not os.path.isdir(d): raise LookupError("release not found")
    prev = deployed(name)
    db.q("INSERT OR REPLACE INTO deploys VALUES(?,?,?,?)", name, n, now(), user)
    rt = get_rt(name, "prod")
    if rt: rt.set_dir(d)
    audit(user, "deploy" if prev is None or n > prev else "rollback", name, "r%s → r%d" % (prev or "-", n))


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
            "apksigner": os.path.join(bt, "apksigner"), "lambda": os.path.join(bt, "core-lambda-stubs.jar"),
            "jar": os.path.join(home, "platforms", plats[-1], "android.jar")}


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
    ks = os.environ.get("VOLT_KEYSTORE") or os.path.join(DATA, "volt.keystore")
    pw = os.environ.get("VOLT_KEYSTORE_PASS", "voltforge")
    if not os.path.exists(ks):
        os.makedirs(os.path.dirname(ks), exist_ok=True)
        sh(["keytool", "-genkeypair", "-keystore", ks, "-alias", "volt", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
            "-storepass", pw, "-keypass", pw, "-dname", "CN=VoltForge"])
    return ks, pw


def xml_escape(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;").replace("'", "\\'")


def build_apk(app, server, label=None, out=None, channel="prod"):
    with build_lock:
        os.makedirs(BUILD, exist_ok=True)
        sdk = sdk_paths(); dex = compile_dex(sdk)
        pkg = "dev.volt.player" if not app else "dev.volt.app." + (re.sub(r"[^a-z0-9]", "", app) or "x") + ("" if channel == "prod" else "." + channel)
        label = label or ((app.replace("-", " ").title() + ("" if channel == "prod" else " (dev)")) if app else "VoltForge")
        out = out or os.path.join(BUILD, "%s-%s.apk" % (app or "voltforge-player", channel))
        with tempfile.TemporaryDirectory() as tmp:
            man = open(os.path.join(ANDROID, "AndroidManifest.xml")).read().replace("{{PKG}}", pkg).replace("{{LABEL}}", xml_escape(label))
            open(os.path.join(tmp, "AndroidManifest.xml"), "w").write(man)
            os.makedirs(os.path.join(tmp, "assets"))
            json.dump({"server": server.rstrip("/"), "app": app or "", "channel": channel, "token": DEVICE_KEY}, open(os.path.join(tmp, "assets", "volt.json"), "w"))
            sh([sdk["aapt2"], "compile", "--dir", os.path.join(ANDROID, "res"), "-o", os.path.join(tmp, "res.zip")])
            un = os.path.join(tmp, "un.apk")
            sh([sdk["aapt2"], "link", "-o", un, "-I", sdk["jar"], "--manifest", os.path.join(tmp, "AndroidManifest.xml"), "--min-sdk-version", "24",
                "--target-sdk-version", "34", "--version-code", str(now() // 60), "--version-name", "1.0", "-A", os.path.join(tmp, "assets"), os.path.join(tmp, "res.zip")])
            with zipfile.ZipFile(un, "a") as z: z.write(dex, "classes.dex")
            al = os.path.join(tmp, "al.apk")
            sh([sdk["zipalign"], "-p", "-f", "4", un, al])
            ks, pw = keystore()
            sh([sdk["apksigner"], "sign", "--ks", ks, "--ks-pass", "pass:" + pw, "--key-pass", "pass:" + pw, "--out", out, al])
        return out


# ---------------------------------------------------------------- HTTP
def safe_path(base, rel):
    rel = (rel or "").strip().lstrip("/")
    if not rel or any(p in ("", ".", "..") or p.startswith(".") for p in rel.split("/")): raise ValueError("bad path")
    p = os.path.realpath(os.path.join(base, rel))
    if not p.startswith(os.path.realpath(base) + os.sep): raise ValueError("bad path")
    return p


def list_files(d):
    out = []
    for base, dirs, files in os.walk(d):
        dirs[:] = sorted(x for x in dirs if x not in SKIP and not x.startswith("."))
        for f in sorted(files):
            if not f.startswith("."):
                p = os.path.join(base, f); out.append({"path": os.path.relpath(p, d).replace(os.sep, "/"), "size": os.path.getsize(p)})
    return out


class Api(Exception):
    def __init__(self, code, msg): self.code, self.msg = code, msg


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "VoltForge/2.0"

    def log_message(self, *a): pass

    def send(self, code, body, ctype="application/json", extra=None):
        if not isinstance(body, (bytes, bytearray)):
            body = json.dumps(body, ensure_ascii=False).encode() if ctype == "application/json" else body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype + ("; charset=utf-8" if ctype.startswith(("text", "application/json")) else ""))
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("X-Content-Type-Options", "nosniff")
        if "Cache-Control" not in (extra or {}): self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items(): self.send_header(k, v)
        self.end_headers(); self.wfile.write(body)

    def raw(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > MAX_BODY: raise Api(413, "body too large")
        return self.rfile.read(n) if n else b""

    def body(self):
        b = self.raw()
        try: return json.loads(b) if b else {}
        except ValueError: raise Api(400, "invalid JSON")

    def token(self):
        a = self.headers.get("Authorization", "")
        return a[7:] if a.startswith("Bearer ") else self.headers.get("X-Volt-Token") or self.q.get("token", [""])[0]

    def need(self, role):
        u = user_for(self.token())
        if not u: raise Api(401, "login required")
        if ROLES[u[1]] < ROLES[role]: raise Api(403, "requires role: " + role)
        return u[0]

    def device(self):
        if DEVICE_KEY and not hmac.compare_digest(self.token() or "", DEVICE_KEY) and not user_for(self.token()): raise Api(401, "device key required")

    def do_OPTIONS(self):
        self.send(204, b"", extra={"Access-Control-Allow-Methods": "GET,POST,PUT,DELETE", "Access-Control-Allow-Headers": "Content-Type,X-Volt-Token,Authorization"})

    def do_GET(self): self.route("GET")
    def do_POST(self): self.route("POST")
    def do_PUT(self): self.route("PUT")
    def do_DELETE(self): self.route("DELETE")

    def route(self, m):
        u = urlparse(self.path); self.q = parse_qs(u.query)
        p = [unquote(x) for x in u.path.strip("/").split("/") if x]
        try:
            self.dispatch(m, p)
        except Api as e:
            self.send(e.code, {"error": e.msg})
        except (ValueError, LookupError) as e:
            self.send(400, {"error": str(e)})
        except Exception as e:
            print("error %s %s: %r" % (m, self.path, e), file=sys.stderr); self.send(500, {"error": str(e)})

    def dispatch(self, m, p):
        q = self.q
        ch = q.get("ch", ["dev"])[0]
        if m == "GET" and not p:
            return self.send(200, open(os.path.join(WEB, "index.html"), "rb").read(), "text/html")
        if p == ["healthz"]:
            db.one("SELECT 1"); return self.send(200, {"ok": True, "apps": len(list_apps())})
        if p == ["metrics"]:
            self.need("viewer"); return self.send(200, metrics(), "text/plain")
        if p[:1] == ["apps"] and len(p) >= 4 and p[2] == "assets":
            rt = get_rt(p[1], ch)
            if not rt or not rt.dir: raise Api(404, "not found")
            f = safe_path(os.path.join(rt.dir, "assets"), "/".join(p[3:]))
            if not os.path.isfile(f): raise Api(404, "not found")
            ct = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".gif": "image/gif", ".webp": "image/webp", ".svg": "image/svg+xml"}.get(os.path.splitext(f)[1].lower(), "application/octet-stream")
            return self.send(200, open(f, "rb").read(), ct, {"Cache-Control": "max-age=60"})
        if p[:1] != ["api"]: raise Api(404, "not found")
        r, name = (p[1] if len(p) > 1 else ""), (p[2] if len(p) > 2 else "")

        if r == "meta":
            return self.send(200, {"langs": available_langs(), "ip": lan_ip(), "port": self.server.server_port, "open": open_mode(), "deviceKey": bool(DEVICE_KEY)})
        if r == "login" and m == "POST":
            b = self.body(); row = db.one("SELECT pw, role FROM users WHERE name=?", b.get("user", ""))
            if not row or not check_pw(b.get("password", ""), row[0]):
                time.sleep(0.5); raise Api(401, "invalid credentials")
            audit(b["user"], "login"); return self.send(200, {"token": new_token(b["user"]), "user": b["user"], "role": row[1]})
        if r == "logout" and m == "POST":
            db.q("DELETE FROM tokens WHERE hash=? AND kind='session'", hashlib.sha256((self.token() or "").encode()).hexdigest()); return self.send(200, {"ok": True})
        if r == "me":
            u = user_for(self.token())
            if not u: raise Api(401, "login required")
            return self.send(200, {"user": u[0], "role": u[1], "open": open_mode()})
        if r == "apps" and m == "GET" and not name:
            out = []
            for n in list_apps():
                rt = get_rt(n, "dev"); out.append({"name": n, "lang": LANGS[rt.ext][1] if rt and rt.ext else None, "ext": rt.ext if rt else None, "prod": deployed(n)})
            return self.send(200, out)
        if r == "users":
            user = self.need("admin")
            if m == "GET": return self.send(200, [{"name": a, "role": b, "created": c} for a, b, c in db.q("SELECT name, role, created FROM users ORDER BY name")])
            if m == "POST":
                b = self.body(); add_user(b.get("name", ""), b.get("password", ""), b.get("role", "developer"))
                audit(user, "user.save", "", "%s as %s" % (b["name"], b.get("role"))); return self.send(201, {"ok": True})
            if m == "DELETE":
                if name == user and len(db.q("SELECT 1 FROM users WHERE role='admin'")) < 2: raise Api(400, "cannot delete the last admin")
                db.q("DELETE FROM users WHERE name=?", name); db.q("DELETE FROM tokens WHERE user=?", name)
                audit(user, "user.delete", "", name); return self.send(200, {"ok": True})
        if r == "tokens" and m == "POST":
            user = self.need("developer"); label = self.body().get("label", "api")
            audit(user, "token.create", "", label); return self.send(201, {"token": new_token(user, "api", label)})
        if r == "audit":
            self.need("viewer"); lim = min(int(q.get("limit", ["200"])[0]), 1000)
            rows = db.q("SELECT ts, user, action, app, detail FROM audit " + ("WHERE app=? " if name else "") + "ORDER BY id DESC LIMIT ?", *([name] if name else []), lim)
            return self.send(200, [dict(zip(("ts", "user", "action", "app", "detail"), x)) for x in rows])

        if r == "apps" and m == "POST":
            user = self.need("developer"); b = self.body(); n, ext = b.get("name", ""), b.get("ext", ".lua")
            if not NAME_RE.match(n) or ext not in LANGS: raise Api(400, "name must be lowercase a-z 0-9 - _ ; ext one of " + ", ".join(LANGS))
            if os.path.exists(app_dir(n)): raise Api(409, "app exists")
            os.makedirs(os.path.join(app_dir(n), "assets")); shutil.copy(os.path.join(TPL, "main" + ext), os.path.join(app_dir(n), "main" + ext))
            scan(); audit(user, "app.create", n, ext); return self.send(201, {"name": n})

        if not NAME_RE.match(name): raise Api(404, "unknown app")
        if r in ("run", "watch", "reset"):
            self.device()
            rt = get_rt(name, ch)
            if not rt: raise Api(404, "unknown app")
            if r == "run" and m == "POST":
                b = self.body(); return self.send(200, rt.run(str(b.get("session") or "anon")[:64], b.get("event")))
            if r == "reset" and m == "POST":
                db.q("DELETE FROM sessions WHERE app=? AND ch=? AND sid=?", name, ch, str(self.body().get("session", ""))); return self.send(200, {"ok": True})
            if r == "watch":
                v, end = int(q.get("v", ["0"])[0]), time.time() + 25
                with changed:
                    while rt.version == v and time.time() < end and runtimes.get((name, ch)) is rt:
                        changed.wait(end - time.time())
                return self.send(200, {"v": rt.version})
        if r == "logs":
            self.need("viewer"); rt = get_rt(name, ch)
            if not rt: raise Api(404, "unknown app")
            after = int(q.get("after", ["0"])[0]); return self.send(200, {"logs": [l for l in list(rt.logs) if l[0] > after]})
        if r == "releases" and m == "GET":
            self.need("viewer")
            rows = db.q("SELECT n, created, user, note, tests FROM releases WHERE app=? ORDER BY n DESC", name)
            return self.send(200, {"current": deployed(name), "releases": [{"n": a, "created": b, "user": c, "note": d, "tests": json.loads(e or "{}")} for a, b, c, d, e in rows]})

        if not os.path.isdir(app_dir(name)): raise Api(404, "unknown app")
        d = app_dir(name)
        if r == "apps" and m == "DELETE":
            user = self.need("admin"); shutil.rmtree(d); scan(); audit(user, "app.delete", name); return self.send(200, {"ok": True})
        if r == "files":
            self.need("viewer"); return self.send(200, list_files(d))
        if r == "file":
            path = q.get("path", [""])[0]; f = safe_path(d, path)
            if m == "GET":
                self.need("viewer")
                if not os.path.isfile(f): raise Api(404, "no such file")
                data = open(f, "rb").read()
                try: return self.send(200, {"path": path, "code": data.decode("utf-8"), "binary": False})
                except UnicodeDecodeError: return self.send(200, {"path": path, "binary": True, "size": len(data)})
            user = self.need("developer")
            if m == "PUT":
                b = self.body(); os.makedirs(os.path.dirname(f), exist_ok=True)
                if "b64" in b:
                    import base64; open(f, "wb").write(base64.b64decode(b["b64"]))
                else:
                    with open(f, "w", encoding="utf-8") as fh: fh.write(b.get("code", ""))
                rt = get_rt(name, "dev"); rt.sig = signature(d); rt.bump("saved " + path)
                if b.get("audit", True): audit(user, "file.save", name, path)
                return self.send(200, {"v": rt.version})
            if m == "DELETE":
                if os.path.basename(f).startswith("main.") and os.path.dirname(f) == os.path.realpath(d): raise Api(400, "cannot delete the main file")
                os.remove(f); audit(user, "file.delete", name, path); scan(); return self.send(200, {"ok": True})
        if r == "sync" and m == "POST":
            user = self.need("developer"); data = self.raw()
            with tempfile.TemporaryDirectory() as tmp:
                with tarfile.open(fileobj=io.BytesIO(data), mode="r:*") as tf: tf.extractall(tmp, filter="data")
                if not locate_main(tmp)[0]: raise Api(400, "archive has no main.<ext> at its root")
                stage = d + ".sync"; shutil.rmtree(stage, ignore_errors=True); copy_tree(tmp, stage)
                old = d + ".old"; os.rename(d, old); os.rename(stage, d); shutil.rmtree(old, ignore_errors=True)
            scan(); audit(user, "sync", name, "%d bytes" % len(data)); return self.send(200, {"ok": True, "files": len(list_files(d))})
        if r == "test" and m == "POST":
            self.need("developer"); return self.send(200, run_tests(d))
        if r == "release" and m == "POST":
            user = self.need("developer"); b = self.body()
            force = bool(b.get("force"))
            if force or b.get("deploy"): self.need("admin")
            n, tests = create_release(name, user, b.get("note", ""), force)
            if n is None: return self.send(422, {"error": "tests failed — release blocked", "tests": tests})
            if b.get("deploy"): deploy(name, n, user)
            return self.send(201, {"n": n, "tests": tests, "deployed": bool(b.get("deploy"))})
        if r == "deploy" and m == "POST":
            user = self.need("admin"); deploy(name, int(self.body().get("n", 0)), user); return self.send(200, {"ok": True, "current": deployed(name)})
        if r == "apk":
            user = self.need("developer")
            host = self.headers.get("Host", "%s:%d" % (lan_ip(), self.server.server_port))
            chan = q.get("channel", ["prod"])[0]
            if chan not in CHANNELS: raise Api(400, "bad channel")
            path = build_apk(name, q.get("server", ["http://" + host])[0], q.get("label", [None])[0], None, chan)
            audit(user, "apk.build", name, chan)
            return self.send(200, open(path, "rb").read(), "application/vnd.android.package-archive", {"Content-Disposition": 'attachment; filename="%s-%s.apk"' % (name, chan)})
        raise Api(404, "not found")


def metrics():
    out = ["# TYPE volt_requests_total counter", "# TYPE volt_errors_total counter", "# TYPE volt_latency_ms_sum counter", "# TYPE volt_workers gauge"]
    with rt_lock: items = list(runtimes.items())
    for (n, ch), r in items:
        lab = '{app="%s",channel="%s"}' % (n, ch)
        out += ["volt_requests_total%s %d" % (lab, r.reqs), "volt_errors_total%s %d" % (lab, r.errs), "volt_latency_ms_sum%s %.3f" % (lab, r.ms), "volt_workers%s %d" % (lab, r.count)]
    return "\n".join(out) + "\n"


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 256

    def handle_error(self, request, addr):
        if not isinstance(sys.exc_info()[1], (BrokenPipeError, ConnectionResetError)): super().handle_error(request, addr)


def serve(port, host="0.0.0.0"):
    init_db(); scan(); threading.Thread(target=watcher, daemon=True).start()
    srv = Server((host, port), H)
    ip = lan_ip()
    print("⚡ VoltForge running\n   editor : http://localhost:%d\n   devices: http://%s:%d   (emulator: http://10.0.2.2:%d)\n   langs  : %s\n   workers: %d per app/channel" % (
        port, ip, port, port, ", ".join(available_langs().values()), POOL), flush=True)
    if open_mode(): print("   ⚠ OPEN MODE: no users exist, everyone is admin. Run: volt.py user add <name> --role admin", flush=True)
    try: srv.serve_forever()
    except KeyboardInterrupt: pass


def main():
    ap = argparse.ArgumentParser(prog="volt", description="VoltForge — live-scripted native Android apps")
    sub = ap.add_subparsers(dest="cmd")
    s = sub.add_parser("serve", help="run the server + web IDE"); s.add_argument("--port", type=int, default=int(os.environ.get("PORT", 8787))); s.add_argument("--host", default="0.0.0.0")
    n = sub.add_parser("new", help="create an app"); n.add_argument("name"); n.add_argument("lang", choices=[e[1:] for e in LANGS])
    sub.add_parser("ls", help="list apps")
    t = sub.add_parser("test", help="run an app's tests (exit 1 on failure)"); t.add_argument("name", nargs="?"); t.add_argument("--all", action="store_true")
    r = sub.add_parser("release", help="snapshot an app as a release (tests must pass)"); r.add_argument("name"); r.add_argument("--note", default=""); r.add_argument("--deploy", action="store_true"); r.add_argument("--force", action="store_true")
    d = sub.add_parser("deploy", help="point production at a release (deploy or rollback)"); d.add_argument("name"); d.add_argument("n", type=int)
    b = sub.add_parser("apk", help="build a signed APK"); b.add_argument("name", nargs="?", help="app name (omit for the generic player)")
    b.add_argument("--server", required=True); b.add_argument("--channel", default="prod", choices=CHANNELS); b.add_argument("--label"); b.add_argument("--out"); b.add_argument("--install", action="store_true")
    u = sub.add_parser("user", help="manage users"); u.add_argument("action", choices=["add", "del", "ls"]); u.add_argument("name", nargs="?")
    u.add_argument("--role", default="developer", choices=list(ROLES)); u.add_argument("--password")
    k = sub.add_parser("token", help="create an API token for CI"); k.add_argument("user"); k.add_argument("--label", default="ci")
    a = ap.parse_args()
    if a.cmd in (None, "serve"):
        return serve(getattr(a, "port", int(os.environ.get("PORT", 8787))), getattr(a, "host", "0.0.0.0"))
    init_db()
    if a.cmd == "new":
        if not NAME_RE.match(a.name): sys.exit("bad name")
        dd = app_dir(a.name); os.makedirs(os.path.join(dd, "assets")); os.makedirs(os.path.join(dd, "tests"))
        shutil.copy(os.path.join(TPL, "main." + a.lang), os.path.join(dd, "main." + a.lang))
        shutil.copy(os.path.join(TPL, "smoke.json"), os.path.join(dd, "tests", "smoke.json")); print("created", os.path.relpath(dd))
    elif a.cmd == "ls":
        for x in list_apps(): rt = get_rt(x); print("%-24s %-11s prod=%s" % (x, LANGS[rt.ext][1] if rt.ext else "?", deployed(x) or "-"))
    elif a.cmd == "test":
        names = list_apps() if a.all or not a.name else [a.name]; bad = 0
        for x in names:
            res = run_tests(app_dir(x))
            for t_ in res["results"]: print(("  ✔ " if t_["ok"] else "  ✖ ") + x + " :: " + t_["name"] + ("" if t_["ok"] else "\n      " + str(t_["error"])))
            if not res["results"]: print("  · %s :: no tests" % x)
            bad += not res["ok"]
        sys.exit(1 if bad else 0)
    elif a.cmd == "release":
        nn, res = create_release(a.name, os.environ.get("USER", "cli"), a.note, a.force)
        if nn is None:
            for t_ in res["results"]:
                if not t_["ok"]: print("✖", t_["name"], t_["error"])
            sys.exit("tests failed — release blocked")
        print("released r%d" % nn)
        if a.deploy: deploy(a.name, nn, os.environ.get("USER", "cli")); print("deployed r%d to prod" % nn)
    elif a.cmd == "deploy":
        deploy(a.name, a.n, os.environ.get("USER", "cli")); print("prod → r%d (running servers switch within a second)" % a.n)
    elif a.cmd == "apk":
        out = build_apk(a.name, a.server, a.label, a.out, a.channel); print("built", out)
        if a.install: print(sh(["adb", "install", "-r", out]).strip())
    elif a.cmd == "user":
        if a.action == "ls":
            for nm, role in db.q("SELECT name, role FROM users ORDER BY name"): print("%-20s %s" % (nm, role))
        elif a.action == "add":
            pw = a.password or getpass.getpass("password for %s: " % a.name)
            add_user(a.name, pw, a.role); audit("cli", "user.save", "", "%s as %s" % (a.name, a.role)); print("saved", a.name, a.role)
        else:
            db.q("DELETE FROM users WHERE name=?", a.name); db.q("DELETE FROM tokens WHERE user=?", a.name); print("deleted", a.name)
    elif a.cmd == "token":
        if not db.one("SELECT 1 FROM users WHERE name=?", a.user): sys.exit("no such user")
        print(new_token(a.user, "api", a.label))


if __name__ == "__main__":
    main()
