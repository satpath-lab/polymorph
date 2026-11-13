import sys, json, traceback, importlib.util
out, sys.stdout = sys.stdout, sys.stderr
path = sys.argv[1]
import os; sys.path.insert(0, os.path.dirname(os.path.abspath(path)))
class _Android:
    mem = {}
    def call(self, name, method="", args=None):
        a = args or {}
        if name == "device": return {"platform": "web", "model": "preview", "sdk": 0}
        if name == "storage": return (self.mem.__setitem__(a.get("key"), a.get("value")) or True) if method == "set" else self.mem.get(a.get("key")) if method == "get" else self.mem
        if name == "clipboard": return (self.mem.__setitem__("_clip", a.get("text")) or True) if method == "set" else self.mem.get("_clip")
        return None
    def emit(self, name, data=None): pass
    def __getattr__(self, name):
        me = self
        class T:
            def __call__(self, method="", args=None): return me.call(name, method, args)
            def __getattr__(self, m): return lambda *a: me.call(name, m, list(a))
        return T()
import builtins; builtins.android = builtins.native = _Android()
mod, load_err = None, None
try:
    spec = importlib.util.spec_from_file_location("poly_app", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
except BaseException:
    load_err = traceback.format_exc(limit=3)
for line in sys.stdin:
    try:
        if load_err: raise RuntimeError(load_err)
        req = json.loads(line)
        st = req.get("state")
        if st is None: st = mod.init() if hasattr(mod, "init") else {}
        if req.get("event") is not None and hasattr(mod, "update"):
            r = mod.update(st, req["event"])
            if r is not None: st = r
        res = {"state": st, "ui": mod.view(st)}
    except BaseException:
        res = {"error": traceback.format_exc(limit=4)}
    out.write(json.dumps(res, default=str) + "\n"); out.flush()
