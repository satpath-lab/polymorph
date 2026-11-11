import sys, os, json, traceback, importlib.util, _volt
DIR, MAIN = __VOLT_DIR, __VOLT_MAIN
if DIR not in sys.path: sys.path.insert(0, DIR)
for k in [k for k, m in list(sys.modules.items()) if getattr(m, "__file__", None) and str(m.__file__).startswith(DIR + os.sep)]: del sys.modules[k]

class _Log:
    def __init__(self): self.buf = ""
    def write(self, s):
        self.buf += s
        while "\n" in self.buf:
            line, self.buf = self.buf.split("\n", 1)
            _volt.host(json.dumps({"op": "log", "text": line}))
        return len(s)
    def flush(self): pass
sys.stdout = sys.stderr = _Log()

class _Android:
    def call(self, name, method="", args=None):
        r = json.loads(_volt.host(json.dumps({"op": "call", "plugin": name, "method": method, "args": args or {}})))
        if isinstance(r, dict) and r.get("error"): raise RuntimeError(r["error"])
        return r.get("value") if isinstance(r, dict) else r
    def __getattr__(self, name): return lambda method="", args=None: self.call(name, method, args)
android = _Android()
sys.modules["android_bridge"] = android
import builtins; builtins.android = android

mod, load_err = None, None
try:
    spec = importlib.util.spec_from_file_location("volt_app", MAIN)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
except BaseException:
    load_err = traceback.format_exc(limit=4)

def __volt(fn, arg):
    try:
        if load_err: raise RuntimeError(load_err)
        a = json.loads(arg)
        if fn == "step":
            st = a.get("state")
            if st is None: st = mod.init() if hasattr(mod, "init") else {}
            if a.get("event") is not None and hasattr(mod, "update"):
                r = mod.update(st, a["event"])
                if r is not None: st = r
            res = {"state": st, "ui": mod.view(st) if hasattr(mod, "view") else None}
        else:
            f = getattr(mod, fn, None)
            if not callable(f): raise RuntimeError("no function " + fn)
            res = {"value": f(*(a if isinstance(a, list) else [a]))}
    except BaseException:
        res = {"error": traceback.format_exc(limit=4)}
    return json.dumps(res, default=str, ensure_ascii=False)
