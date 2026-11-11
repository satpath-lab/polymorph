#!/usr/bin/env python3
"""VoltForge — live-scripted native Android apps in Lua, Python, PHP, JavaScript or Ruby."""
import argparse, collections, glob, getpass, hashlib, hmac, io, json, os, queue, re, secrets, shutil, socket, sqlite3, subprocess, sys, tarfile, tempfile, threading, time, zipfile
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs, unquote

ROOT = os.path.dirname(os.path.abspath(__file__))
APPS = os.environ.get("VOLT_APPS", os.path.join(ROOT, "apps"))
DATA = os.environ.get("VOLT_DATA", os.path.join(ROOT, "data"))
RELEASES = os.path.join(DATA, "releases")
RT, WEB, TPL, ANDROID = (os.path.join(ROOT, d) for d in ("runtimes", "web", "templates", "android"))
BUILD = os.path.join(DATA, "build")
LANGS = {".lua": (["lua"], "Lua"), ".py": ([sys.executable, "-u"], "Python"), ".php": (["php"], "PHP"), ".js": (["node"], "JavaScript"), ".rb": (["ruby"], "Ruby")}
EFFECTS_EXTRA = ("_pickDate", "_pickTime")
EFFECTS = EFFECTS_EXTRA + ("_toast", "_open", "_vibrate", "_camera", "_pick", "_location", "_notify", "_share", "_clipboard", "_contact", "_store", "_native", "_background")
UPLOAD_DAYS = float(os.environ.get("VOLT_UPLOAD_DAYS", "30"))
ABIS = [a for a in os.environ.get("VOLT_ABIS", "arm64-v8a,x86_64").split(",") if a]
UPLOADS = os.path.join(DATA, "uploads")
MAX_UPLOAD = int(os.environ.get("VOLT_MAX_UPLOAD_MB", "15")) * 1024 * 1024
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
    CREATE TABLE IF NOT EXISTS pushes(id INTEGER PRIMARY KEY AUTOINCREMENT, app TEXT, ch TEXT, sid TEXT, title TEXT, text TEXT, tag TEXT, created INTEGER);
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


SANDBOX = os.environ.get("VOLT_SANDBOX", "")
SANDBOX_NET = os.environ.get("VOLT_SANDBOX_NET", "1") == "1"


def sandboxed(cmd, app_d):
    if not SANDBOX: return cmd
    if sys.platform == "darwin" and shutil.which("sandbox-exec"):
        esc = lambda p: os.path.realpath(p).replace("\\", "\\\\").replace('"', '\\"')
        prof = ['(version 1)', '(allow default)', '(deny file-write*)',
                '(allow file-write* (subpath "/private/tmp") (subpath "/private/var/folders") (literal "/dev/null"))',
                '(deny file-read* (subpath "%s"))' % esc(DATA), '(deny file-read* (subpath "%s"))' % esc(APPS),
                '(allow file-read* (subpath "%s"))' % esc(app_d)]
        for k in ("HOME",):
            if os.environ.get(k): prof.append('(deny file-read* (subpath "%s/.ssh"))' % esc(os.environ[k]))
        if not SANDBOX_NET: prof.append("(deny network*)")
        return ["sandbox-exec", "-p", "\n".join(prof)] + cmd
    if shutil.which("bwrap"):
        b = ["bwrap", "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp", "--tmpfs", DATA, "--tmpfs", APPS,
             "--ro-bind", app_d, app_d, "--ro-bind", RT, RT, "--die-with-parent", "--new-session", "--unshare-pid", "--unshare-ipc"]
        if not SANDBOX_NET: b.append("--unshare-net")
        return b + cmd
    raise RuntimeError("VOLT_SANDBOX is set but neither sandbox-exec (macOS) nor bwrap (Linux) is available")


class Worker:
    def __init__(self, rt, gen=0):
        self.rt, self.gen = rt, gen
        cmd = sandboxed(LANGS[rt.ext][0] + [os.path.join(RT, "harness" + rt.ext), rt.main], rt.dir)
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
            if isinstance(st, dict) and "_push" in st:
                for m in (lambda x: x if isinstance(x, list) else [x])(st.pop("_push")):
                    if isinstance(m, dict): queue_push(self.name, self.channel, m.get("to", "*"), m)
            db.q("INSERT OR REPLACE INTO sessions VALUES(?,?,?,?,?)", self.name, self.channel, sid, json.dumps(st), now())
        return {"ui": r.get("ui"), "fx": fx, "v": self.version}


def queue_push(app, ch, sid, m):
    db.q("INSERT INTO pushes(app,ch,sid,title,text,tag,created) VALUES(?,?,?,?,?,?,?)", app, ch, str(sid or "*")[:64],
         str(m.get("title", ""))[:200], str(m.get("text", ""))[:1000], str(m.get("tag", ""))[:100], now())


def clean_uploads():
    if UPLOAD_DAYS <= 0 or not os.path.isdir(UPLOADS): return
    cutoff = time.time() - UPLOAD_DAYS * 86400
    for base, _, files in os.walk(UPLOADS):
        for f in files:
            p = os.path.join(base, f)
            try:
                if os.path.getmtime(p) < cutoff: os.remove(p)
            except OSError: pass
    db.q("DELETE FROM pushes WHERE created < ?", now() - 7 * 86400)


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
    last_clean = 0
    while True:
        try:
            scan()
            if time.time() - last_clean > 3600: last_clean = time.time(); clean_uploads()
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


BUNDLE_SKIP = {"tests", "native", "__pycache__", "node_modules", ".git"}


def bundle_bytes(d):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for base, dirs, files in os.walk(d):
            dirs[:] = sorted(x for x in dirs if x not in BUNDLE_SKIP and not x.startswith("."))
            for f in sorted(files):
                if f.startswith(".") or f.endswith(".pyc"): continue
                full = os.path.join(base, f)
                zi = zipfile.ZipInfo(os.path.relpath(full, d).replace(os.sep, "/"), (2020, 1, 1, 0, 0, 0))
                zi.compress_type = zipfile.ZIP_DEFLATED
                z.writestr(zi, open(full, "rb").read())
    return buf.getvalue()


def bundle_key():
    k = os.path.join(DATA, "bundle-signing.pem")
    if not os.path.exists(k):
        os.makedirs(DATA, exist_ok=True)
        sh(["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", k]); os.chmod(k, 0o600)
    return k


def bundle_pubkey():
    import base64
    der = subprocess.run(["openssl", "ec", "-in", bundle_key(), "-pubout", "-outform", "DER"], capture_output=True, check=True).stdout
    return base64.b64encode(der).decode()


def bundle_sign(data):
    import base64
    with tempfile.NamedTemporaryFile(delete=False) as f: f.write(data); tmp = f.name
    try: sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", bundle_key(), tmp], capture_output=True, check=True).stdout
    finally: os.unlink(tmp)
    return base64.b64encode(sig).decode()


def release_dir(name):
    n = deployed(name)
    d = os.path.join(RELEASES, name, str(n)) if n else None
    return d if d and os.path.isdir(d) else app_dir(name)


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
            "jar": os.path.join(home, "platforms", plats[-1], "android.jar"), "home": home}


def ndk_root(sdk):
    env = os.environ.get("ANDROID_NDK_HOME") or os.environ.get("ANDROID_NDK_ROOT")
    if env and os.path.isdir(env): return env
    d = os.path.join(sdk["home"], "ndk")
    vs = sorted(os.listdir(d)) if os.path.isdir(d) else []
    return os.path.join(d, vs[-1]) if vs else None


def kotlin_stdlib():
    kc = shutil.which("kotlinc")
    if not kc: return None
    base = os.path.dirname(os.path.dirname(os.path.realpath(kc)))
    for cand in (os.path.join(base, "lib", "kotlin-stdlib.jar"), os.path.join(base, "libexec", "lib", "kotlin-stdlib.jar")):
        if os.path.exists(cand): return cand
    return None


def walk_ext(d, exts):
    out = []
    if d and os.path.isdir(d):
        for base, dirs, files in os.walk(d):
            dirs.sort()
            out += [os.path.join(base, f) for f in sorted(files) if f.endswith(exts)]
    return out


def native_inputs(app_d):
    nd = os.path.join(app_d, "native") if app_d else None
    java, kt, jars = walk_ext(nd and os.path.join(nd, "java"), (".java",)), walk_ext(nd and os.path.join(nd, "kotlin"), (".kt",)), walk_ext(nd and os.path.join(nd, "libs"), (".jar",))
    cunits = {}
    cd = nd and os.path.join(nd, "c")
    if cd and os.path.isdir(cd):
        for e in sorted(os.listdir(cd)):
            p = os.path.join(cd, e)
            if e.endswith((".c", ".cpp")): cunits[os.path.splitext(e)[0]] = [p]
            elif os.path.isdir(p): cunits[e] = walk_ext(p, (".c", ".cpp"))
    pf = nd and os.path.join(nd, "permissions.txt")
    perms = [l.strip() for l in open(pf) if l.strip() and not l.startswith("#")] if pf and os.path.exists(pf) else []
    return java, kt, jars, cunits, perms


def find_plugins(files):
    out = []
    for f in files:
        src = open(f, encoding="utf-8").read()
        pkg = re.search(r"^\s*package\s+([\w.]+)", src, re.M)
        for cls in re.findall(r"class\s+(\w+)[^{]*?\bVolt(?:View)?Plugin\b", src):
            out.append({"name": cls, "class": (pkg.group(1) + "." if pkg else "") + cls})
    return out


def sh(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode: raise RuntimeError("%s failed:\n%s%s" % (os.path.basename(cmd[0]), r.stdout, r.stderr))
    return r.stdout


build_lock = threading.Lock()


def compile_native(sdk, app_d=None):
    core = walk_ext(os.path.join(ANDROID, "src"), (".java",))
    java, kt, jars, cunits, perms = native_inputs(app_d)
    every = core + java + kt + jars + [f for fs in cunits.values() for f in fs]
    h = hashlib.sha1(b"".join(f.encode() + open(f, "rb").read() for f in every) + (sdk["jar"] + ",".join(ABIS) + str(perms) + str(ndk_root(sdk) if cunits else "")).encode()).hexdigest()[:16]
    out = os.path.join(BUILD, "native-" + h)
    done = os.path.join(out, "done.json")
    if os.path.exists(done): return json.load(open(done))
    shutil.rmtree(out, ignore_errors=True)
    cls, kcls, dexd = (os.path.join(out, x) for x in ("classes", "kclasses", "dex"))
    for x in (cls, kcls, dexd): os.makedirs(x)
    cp = os.pathsep.join([sdk["jar"]] + jars)
    sh(["javac", "-nowarn", "-Xlint:-options", "-encoding", "UTF-8", "-source", "8", "-target", "8", "-bootclasspath", sdk["jar"] + os.pathsep + sdk["lambda"],
        "-cp", os.pathsep.join(jars) or ".", "-d", cls] + core + java)
    inputs = [os.path.join(d, f) for d, _, fs in os.walk(cls) for f in fs if f.endswith(".class")] + jars
    if kt:
        if not shutil.which("kotlinc"): raise RuntimeError("Kotlin plugins need kotlinc on PATH")
        sh(["kotlinc", "-nowarn", "-jvm-target", "1.8", "-cp", cp + os.pathsep + cls, "-d", kcls] + kt)
        inputs += [os.path.join(d, f) for d, _, fs in os.walk(kcls) for f in fs if f.endswith(".class")]
        std = kotlin_stdlib()
        if std: inputs.append(std)
    sh([sdk["d8"], "--release", "--min-api", "24", "--lib", sdk["jar"], "--output", dexd] + inputs)
    sos, warnings = [], []
    if cunits:
        ndk = ndk_root(sdk)
        if not ndk:
            warnings.append("C sources skipped: Android NDK not found (install it or set ANDROID_NDK_HOME)")
        else:
            host = "darwin-x86_64" if sys.platform == "darwin" else "linux-x86_64"
            triples = {"arm64-v8a": "aarch64-linux-android", "x86_64": "x86_64-linux-android", "armeabi-v7a": "armv7a-linux-androideabi", "x86": "i686-linux-android"}
            for abi in ABIS:
                cc = os.path.join(ndk, "toolchains", "llvm", "prebuilt", host, "bin", triples[abi] + "24-clang")
                os.makedirs(os.path.join(out, "lib", abi), exist_ok=True)
                for lib, files in cunits.items():
                    so = os.path.join(out, "lib", abi, "lib%s.so" % lib)
                    cpp = any(f.endswith(".cpp") for f in files)
                    sh([cc + ("++" if cpp else ""), "-shared", "-fPIC", "-O2", "-Wall", "-o", so] + files + ["-llog"] + (["-static-libstdc++"] if cpp else []))
                    sos.append([abi, so])
    res = {"dex": sorted(os.path.join(dexd, f) for f in os.listdir(dexd) if f.endswith(".dex")), "so": sos,
           "plugins": find_plugins(java + kt), "perms": perms, "warnings": warnings}
    json.dump(res, open(done, "w"))
    return res


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


DEFAULT_FEATURES = {"encryptStorage": True, "cacheScreens": True, "offlineQueue": True, "push": True, "backgroundJobs": True,
                    "blockScreenshots": False, "location": True, "notifications": True, "requireHttps": True}


def app_config(app):
    cfg = {}
    f = os.path.join(app_dir(app), "app.json") if app else None
    if f and os.path.exists(f):
        try: cfg = json.load(open(f, encoding="utf-8"))
        except ValueError as e: raise RuntimeError("app.json is not valid JSON: %s" % e)
    feats = dict(DEFAULT_FEATURES); feats.update(cfg.get("features") or {})
    cfg["features"] = feats
    return cfg


def cert_pins(url):
    import ssl
    u = urlparse(url)
    ctx = ssl.create_default_context()
    with socket.create_connection((u.hostname, u.port or 443), timeout=10) as sock, ctx.wrap_socket(sock, server_hostname=u.hostname) as tls:
        chain = tls.get_verified_chain() if hasattr(tls, "get_verified_chain") else [tls.getpeercert(True)]
        out = []
        for c in chain:
            der = c if isinstance(c, bytes) else c.public_bytes(ssl._ssl.ENCODING_DER) if hasattr(c, "public_bytes") else bytes(c)
            import base64
            out.append("sha256/" + base64.b64encode(hashlib.sha256(der).digest()).decode())
        return out


def build_aab(sdk, tmp, linked_args_apk, nat, out):
    bt = os.environ.get("VOLT_BUNDLETOOL") or os.path.join(DATA, "tools", "bundletool.jar")
    if not os.path.exists(bt):
        raise RuntimeError("AAB builds need bundletool: download bundletool-all-<version>.jar from github.com/google/bundletool/releases and set VOLT_BUNDLETOOL=/path/to/it")
    proto = os.path.join(tmp, "proto.zip")
    args = json.load(open(os.path.join(tmp, "link-args.json")))
    sh([sdk["aapt2"], "link", "--proto-format", "-o", proto] + args)
    mod = os.path.join(tmp, "base.zip")
    with zipfile.ZipFile(proto) as zin, zipfile.ZipFile(mod, "w", zipfile.ZIP_DEFLATED) as z:
        for item in zin.infolist():
            name = item.filename
            if name == "AndroidManifest.xml": name = "manifest/AndroidManifest.xml"
            elif name.startswith("res/") or name == "resources.pb" or name.startswith("assets/"): pass
            else: continue
            z.writestr(name, zin.read(item.filename))
        for i, dx in enumerate(nat["dex"]): z.write(dx, "dex/classes.dex" if i == 0 else "dex/classes%d.dex" % (i + 1))
        for abi, so in nat["so"]: z.write(so, "lib/%s/%s" % (abi, os.path.basename(so)))
    unsigned = os.path.join(tmp, "app.aab")
    sh(["java", "-jar", bt, "build-bundle", "--modules=" + mod, "--output=" + unsigned, "--overwrite"])
    ks, pw = keystore()
    shutil.copy(unsigned, out)
    sh(["jarsigner", "-keystore", ks, "-storepass", pw, "-keypass", pw, "-sigalg", "SHA256withRSA", "-digestalg", "SHA-256", out, "volt"])
    return out


ENGINE_LANG = {".lua": "lua", ".js": "js", ".py": "python", ".rb": "ruby", ".php": "php"}
ENGINE_EXT = {"lua": "lua", "js": "js", "python": "py", "ruby": "rb", "php": "php"}


def app_main(d):
    for ext in ENGINE_LANG:
        if os.path.exists(os.path.join(d, "main" + ext)): return "main" + ext
    return None


def engine_parts(langs):
    so, assets = [], {}
    for lang in langs:
        d = os.path.join(ANDROID, "engine", "prebuilt", lang)
        for abi in ABIS:
            libs = sorted(glob.glob(os.path.join(d, abi, "*.so")))
            if not libs: raise RuntimeError("the %s engine is not built for %s. Run: python3 android/engine/build_engines.py (see android/engine/README.md)" % (lang, abi))
            so += [(abi, f) for f in libs]
        assets["volt/harness." + ENGINE_EXT[lang]] = os.path.join(ANDROID, "engine", "harness", "harness." + ENGINE_EXT[lang])
        if lang == "python": assets["volt/python.zip"] = os.path.join(d, "python.zip")
    return so, assets


def build_apk(app, dev=None, server=None, label=None, out=None, fmt="apk"):
    with build_lock:
        os.makedirs(BUILD, exist_ok=True)
        cfg = app_config(app); feats = cfg["features"]
        upd = cfg.get("updates")
        source = "dev" if dev or not app else "remote" if upd else "bundled"
        url = (upd.get("url") if isinstance(upd, dict) else None) or (server and app and "%s/b/%s.zip" % (server.rstrip("/"), app)) or ""
        if source == "remote":
            if not url: raise RuntimeError('app.json "updates" needs a server: pass --server https://… or set updates.url')
            if feats.get("requireHttps", True) and not url.startswith("https://") and not os.environ.get("VOLT_ALLOW_HTTP"):
                raise RuntimeError("script updates must come from https:// (set features.requireHttps=false in app.json or VOLT_ALLOW_HTTP=1 to override)")
        channel = "dev" if source == "dev" else "prod"
        code = None
        if app and source != "dev":
            code = release_dir(app)
            if not app_main(code): raise RuntimeError("no main.lua/js/py/rb/php in " + code)
        langs = [ENGINE_LANG["." + app_main(code or app_dir(app)).split(".")[1]]] if app else [l for l in ("lua", "js", "python", "ruby", "php") if os.path.isdir(os.path.join(ANDROID, "engine", "prebuilt", l))]
        eso, eassets = engine_parts(langs)
        sdk = sdk_paths(); nat = compile_native(sdk, app_dir(app) if app else None)
        nat = dict(nat, so=nat["so"] + eso)
        for w in nat["warnings"]: print("⚠", w, file=sys.stderr)
        base_pkg = cfg.get("package") or ("dev.volt.player" if not app else "dev.volt.app." + (re.sub(r"[^a-z0-9]", "", app) or "x"))
        if not re.match(r"^[a-zA-Z][\w]*(\.[a-zA-Z][\w]*)+$", base_pkg): raise RuntimeError("invalid package id: " + base_pkg)
        pkg = base_pkg + ("" if channel == "prod" or not app else "." + channel)
        label = label or cfg.get("label") or ((app.replace("-", " ").title()) if app else "VoltForge")
        if channel != "prod" and app: label += " (dev)"
        out = out or os.path.join(BUILD, "%s-%s.%s" % (app or "voltforge-player", channel, fmt))
        perm = lambda names: "\n    ".join('<uses-permission android:name="android.permission.%s" />' % x for x in names)
        orient = cfg.get("orientation")
        with tempfile.TemporaryDirectory() as tmp:
            man = open(os.path.join(ANDROID, "AndroidManifest.xml")).read().replace("{{PKG}}", pkg).replace("{{LABEL}}", xml_escape(label)) \
                .replace("{{LOCATION}}", perm(["ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION"]) if feats.get("location") else "") \
                .replace("{{NOTIFICATIONS}}", perm(["POST_NOTIFICATIONS"]) if feats.get("notifications") else "") \
                .replace("{{BOOT}}", perm(["RECEIVE_BOOT_COMPLETED"]) if feats.get("backgroundJobs") else "") \
                .replace("{{CLEARTEXT}}", "true" if (dev or "").startswith("http://") or url.startswith("http://") else "false") \
                .replace("{{ORIENTATION}}", 'android:screenOrientation="%s" ' % orient if orient in ("portrait", "landscape", "sensor") else "")
            icon = cfg.get("icon") and os.path.join(app_dir(app), cfg["icon"])
            res_dir = os.path.join(tmp, "res"); shutil.copytree(os.path.join(ANDROID, "res"), res_dir)
            if icon:
                if not os.path.exists(icon) or not icon.lower().endswith(".png"): raise RuntimeError("app.json icon must be a .png inside the app folder")
                os.makedirs(os.path.join(res_dir, "mipmap-xxxhdpi")); shutil.copy(icon, os.path.join(res_dir, "mipmap-xxxhdpi", "ic_launcher.png"))
                man = man.replace('android:icon="@drawable/ic_volt"', 'android:icon="@mipmap/ic_launcher"').replace("{{PERMS}}", "\n    ".join('<uses-permission android:name="%s" />' % xml_escape(p) for p in nat["perms"]))
            open(os.path.join(tmp, "AndroidManifest.xml"), "w").write(man)
            os.makedirs(os.path.join(tmp, "assets"))
            pins = cfg.get("pins") or [p for p in os.environ.get("VOLT_PINS", "").split(",") if p]
            vcfg = {"app": app or "", "source": source, "dev": (dev or "").rstrip("/"), "token": DEVICE_KEY, "pins": pins, "features": feats}
            if source == "remote":
                vcfg.update(url=url, check=str((upd.get("check") if isinstance(upd, dict) else None) or "launch"))
                if not (isinstance(upd, dict) and upd.get("unsigned")): vcfg["publicKey"] = (upd.get("publicKey") if isinstance(upd, dict) else None) or bundle_pubkey()
            json.dump(vcfg, open(os.path.join(tmp, "assets", "volt.json"), "w"))
            for dst, src in eassets.items():
                os.makedirs(os.path.dirname(os.path.join(tmp, "assets", dst)), exist_ok=True); shutil.copy(src, os.path.join(tmp, "assets", dst))
            if code:
                with zipfile.ZipFile(io.BytesIO(bundle_bytes(code))) as z: z.extractall(os.path.join(tmp, "assets", "app"))
            json.dump(nat["plugins"], open(os.path.join(tmp, "assets", "plugins.json"), "w"))
            sh([sdk["aapt2"], "compile", "--dir", res_dir, "-o", os.path.join(tmp, "res.zip")])
            un = os.path.join(tmp, "un.apk")
            link_args = ["-I", sdk["jar"], "--manifest", os.path.join(tmp, "AndroidManifest.xml"), "--min-sdk-version", str(cfg.get("minSdk", 24)),
                "--target-sdk-version", str(cfg.get("targetSdk", 35)), "--version-code", str(cfg.get("versionCode") or now() // 60), "--version-name", str(cfg.get("version", "1.0")),
                "-A", os.path.join(tmp, "assets"), os.path.join(tmp, "res.zip")]
            json.dump(link_args, open(os.path.join(tmp, "link-args.json"), "w"))
            sh([sdk["aapt2"], "link", "-o", un, "-I", sdk["jar"], "--manifest", os.path.join(tmp, "AndroidManifest.xml"), "--min-sdk-version", "24",
                "--target-sdk-version", str(cfg.get("targetSdk", 35)), "--version-code", str(cfg.get("versionCode") or now() // 60), "--version-name", str(cfg.get("version", "1.0")), "-A", os.path.join(tmp, "assets"), os.path.join(tmp, "res.zip")])
            if fmt == "aab":
                return build_aab(sdk, tmp, un, nat, out)
            with zipfile.ZipFile(un, "a", zipfile.ZIP_DEFLATED) as z:
                for i, dx in enumerate(nat["dex"]): z.write(dx, "classes.dex" if i == 0 else "classes%d.dex" % (i + 1))
                for abi, so in nat["so"]: z.write(so, "lib/%s/%s" % (abi, os.path.basename(so)))
            al = os.path.join(tmp, "al.apk")
            sh([sdk["zipalign"], "-p", "-f", "4", un, al])
            ks, pw = keystore()
            sh([sdk["apksigner"], "sign", "--ks", ks, "--ks-pass", "pass:" + pw, "--key-pass", "pass:" + pw, "--out", out, al])
        return out


def build_aar(langs, out=None):
    sdk = sdk_paths()
    so, assets = engine_parts(langs)
    out = out or os.path.join(BUILD, "voltengine.aar")
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        src = sorted(glob.glob(os.path.join(ANDROID, "src", "dev", "volt", "engine", "*.java")))
        sh(["javac", "-nowarn", "-Xlint:-options", "-encoding", "UTF-8", "-source", "8", "-target", "8", "-bootclasspath", sdk["jar"], "-d", os.path.join(tmp, "c")] + src)
        jar = os.path.join(tmp, "classes.jar")
        with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as z:
            for base, _, files in os.walk(os.path.join(tmp, "c")):
                for f in files: z.write(os.path.join(base, f), os.path.relpath(os.path.join(base, f), os.path.join(tmp, "c")))
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("AndroidManifest.xml", '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.volt.engine"><uses-sdk android:minSdkVersion="24"/></manifest>')
            z.writestr("R.txt", "")
            z.write(jar, "classes.jar")
            for abi, f in so: z.write(f, "jni/%s/%s" % (abi, os.path.basename(f)))
            for dst, f in assets.items(): z.write(f, "assets/" + dst)
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


FAILS = {}


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
        self.send_header("X-Frame-Options", "SAMEORIGIN")
        self.send_header("Referrer-Policy", "no-referrer")
        if os.environ.get("VOLT_HSTS") or self.headers.get("X-Forwarded-Proto") == "https":
            self.send_header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
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
        if p[:1] == ["uploads"] and len(p) == 3 and NAME_RE.match(p[1]) and re.match(r"^[a-f0-9]{32}\.[a-z0-9]{1,5}$", p[2]):
            f = os.path.join(UPLOADS, p[1], p[2])
            if not os.path.isfile(f): raise Api(404, "not found")
            ext = p[2].rsplit(".", 1)[1]
            ct = {"jpg": "image/jpeg", "png": "image/png", "gif": "image/gif", "webp": "image/webp", "pdf": "application/pdf", "txt": "text/plain"}.get(ext, "application/octet-stream")
            return self.send(200, open(f, "rb").read(), ct, {"Cache-Control": "private, max-age=86400", "Content-Disposition": "inline" if ct.startswith("image") else "attachment"})
        if p[:1] == ["apps"] and len(p) >= 4 and p[2] == "assets":
            rt = get_rt(p[1], ch)
            if not rt or not rt.dir: raise Api(404, "not found")
            f = safe_path(os.path.join(rt.dir, "assets"), "/".join(p[3:]))
            if not os.path.isfile(f): raise Api(404, "not found")
            ct = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".gif": "image/gif", ".webp": "image/webp", ".svg": "image/svg+xml"}.get(os.path.splitext(f)[1].lower(), "application/octet-stream")
            return self.send(200, open(f, "rb").read(), ct, {"Cache-Control": "max-age=60"})
        if p[:1] == ["b"] and len(p) == 2 and re.match(r"^[a-z0-9][a-z0-9_-]*\.zip(\.sig)?$", p[1]):
            self.device()
            name = p[1].split(".")[0]
            if not os.path.isdir(app_dir(name)): raise Api(404, "not found")
            data = bundle_bytes(release_dir(name))
            if p[1].endswith(".sig"): return self.send(200, bundle_sign(data), "text/plain", {"Cache-Control": "no-cache"})
            tag = '"%s"' % hashlib.sha256(data).hexdigest()[:32]
            if self.headers.get("If-None-Match") == tag: return self.send(304, b"", "application/zip", {"ETag": tag})
            return self.send(200, data, "application/zip", {"ETag": tag, "Cache-Control": "no-cache"})
        if p[:1] != ["api"]: raise Api(404, "not found")
        r, name = (p[1] if len(p) > 1 else ""), (p[2] if len(p) > 2 else "")

        if r == "meta":
            return self.send(200, {"langs": available_langs(), "ip": lan_ip(), "port": self.server.server_port, "open": open_mode(), "deviceKey": bool(DEVICE_KEY)})
        if r == "login" and m == "POST":
            ip = self.headers.get("X-Forwarded-For", self.client_address[0]).split(",")[0].strip()
            recent = [t for t in FAILS.get(ip, []) if t > time.time() - 600]
            if len(recent) >= 10: raise Api(429, "too many failed logins, try again in 10 minutes")
            b = self.body(); row = db.one("SELECT pw, role FROM users WHERE name=?", b.get("user", ""))
            if not row or not check_pw(b.get("password", ""), row[0]):
                FAILS[ip] = recent + [time.time()]; time.sleep(0.5); raise Api(401, "invalid credentials")
            FAILS.pop(ip, None)
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
        if r == "upload" and m == "POST":
            self.device()
            if not get_rt(name, ch): raise Api(404, "unknown app")
            n = int(self.headers.get("Content-Length") or 0)
            if n <= 0 or n > MAX_UPLOAD: raise Api(413, "upload must be 1 byte – %d MB" % (MAX_UPLOAD // 1048576))
            data = self.rfile.read(n)
            ctype = (self.headers.get("Content-Type") or "application/octet-stream").split(";")[0].strip().lower()
            ext = {"image/jpeg": "jpg", "image/png": "png", "image/gif": "gif", "image/webp": "webp", "application/pdf": "pdf", "text/plain": "txt"}.get(ctype, "bin")
            fname = secrets.token_hex(16) + "." + ext
            os.makedirs(os.path.join(UPLOADS, name), exist_ok=True)
            with open(os.path.join(UPLOADS, name, fname), "wb") as fh: fh.write(data)
            return self.send(201, {"url": "/uploads/%s/%s" % (name, fname), "type": ctype, "size": n, "name": q.get("name", [fname])[0][:200]})
        if r == "inbox" and m == "GET":
            self.device()
            since, sid = int(q.get("since", ["0"])[0]), q.get("session", [""])[0]
            top = db.one("SELECT MAX(id) FROM pushes WHERE app=? AND ch=?", name, ch)[0] or 0
            if since <= 0: return self.send(200, {"messages": [], "cursor": top})
            rows = db.q("SELECT id, title, text, tag FROM pushes WHERE app=? AND ch=? AND id>? AND (sid='*' OR sid=?) ORDER BY id LIMIT 20", name, ch, since, sid)
            return self.send(200, {"messages": [dict(zip(("id", "title", "text", "tag"), x)) for x in rows], "cursor": rows[-1][0] if rows else max(since, top)})
        if r == "push" and m == "POST":
            user = self.need("developer"); b = self.body()
            if not b.get("text") and not b.get("title"): raise Api(400, "title or text required")
            queue_push(name, b.get("ch", "prod") if b.get("ch") in CHANNELS else "prod", b.get("session") or "*", b)
            audit(user, "push", name, "%s → %s: %s" % (b.get("ch", "prod"), b.get("session") or "all", b.get("title", ""))); return self.send(201, {"ok": True})
        if r in ("run", "watch", "reset", "bundle"):
            self.device()
            rt = get_rt(name, ch)
            if not rt: raise Api(404, "unknown app")
            if r == "bundle":
                return self.send(200, bundle_bytes(rt.dir), "application/zip", {"Cache-Control": "no-cache", "X-Volt-Version": str(rt.version)})
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
            fmt = q.get("format", ["apk"])[0]
            if fmt not in ("apk", "aab"): raise Api(400, "format must be apk or aab")
            srv = q.get("server", ["http://" + host])[0].rstrip("/")
            path = build_apk(name, dev=srv if chan == "dev" else None, server=srv, label=q.get("label", [None])[0], fmt=fmt)
            audit(user, "%s.build" % fmt, name, chan)
            return self.send(200, open(path, "rb").read(), "application/vnd.android.package-archive" if fmt == "apk" else "application/octet-stream",
                             {"Content-Disposition": 'attachment; filename="%s-%s.%s"' % (name, chan, fmt)})
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
    pn = sub.add_parser("pin", help="print TLS certificate pins for a server (for app.json \"pins\")"); pn.add_argument("url")
    t = sub.add_parser("test", help="run an app's tests (exit 1 on failure)"); t.add_argument("name", nargs="?"); t.add_argument("--all", action="store_true")
    r = sub.add_parser("release", help="snapshot an app as a release (tests must pass)"); r.add_argument("name"); r.add_argument("--note", default=""); r.add_argument("--deploy", action="store_true"); r.add_argument("--force", action="store_true")
    d = sub.add_parser("deploy", help="point production at a release (deploy or rollback)"); d.add_argument("name"); d.add_argument("n", type=int)
    b = sub.add_parser("apk", help="build a signed APK"); b.add_argument("name", nargs="?", help="app name (omit for the generic player)")
    b.add_argument("--dev", metavar="URL", help="live-reload build: the phone syncs code from this dev server (e.g. http://192.168.1.10:8787)")
    b.add_argument("--server", metavar="URL", help='server that hosts signed script updates (used when app.json has "updates")')
    b.add_argument("--format", default="apk", choices=["apk", "aab"]); b.add_argument("--label"); b.add_argument("--out"); b.add_argument("--install", action="store_true")
    bu = sub.add_parser("bundle", help="write a signed script bundle (<app>.zip + .sig) to host anywhere for over-the-air updates"); bu.add_argument("name"); bu.add_argument("--out", default=".")
    sub.add_parser("pubkey", help="print the public key apps use to verify script bundles")
    ar = sub.add_parser("aar", help="build the engine as an .aar library for any Android Studio project"); ar.add_argument("--langs", default="lua,js,python,ruby,php"); ar.add_argument("--out")
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
    elif a.cmd == "pin":
        for pin in cert_pins(a.url): print(pin)
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
        out = build_apk(a.name, a.dev, a.server, a.label, a.out, a.format); print("built", out)
        if a.install: print(sh(["adb", "install", "-r", out]).strip())
    elif a.cmd == "bundle":
        data = bundle_bytes(release_dir(a.name)); os.makedirs(a.out, exist_ok=True)
        z = os.path.join(a.out, a.name + ".zip"); open(z, "wb").write(data); open(z + ".sig", "w").write(bundle_sign(data))
        print("wrote %s (%d KB) and %s.sig" % (z, len(data) // 1024, z))
    elif a.cmd == "pubkey":
        print(bundle_pubkey())
    elif a.cmd == "aar":
        print("built", build_aar([x for x in a.langs.split(",") if x], a.out))
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
