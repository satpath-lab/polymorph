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

_CBS, _CBN = {}, [0]

def _default(x):
    if callable(x):
        _CBN[0] += 1; _CBS[_CBN[0]] = x
        return {"__volt_cb": _CBN[0]}
    return str(x)

def _enc(v): return json.dumps(v, default=_default, ensure_ascii=False)

def _hostcall(name, method, args):
    r = json.loads(_volt.host(_enc({"op": "call", "plugin": name, "method": method, "args": args})))
    if isinstance(r, dict) and r.get("error"): raise RuntimeError(r["error"])
    return r.get("value") if isinstance(r, dict) else r

class _Target:
    def __init__(self, name): self._name = name
    def __call__(self, method="", args=None): return _hostcall(self._name, method, args if args is not None else {})
    def __getattr__(self, method):
        if method.startswith("__"): raise AttributeError(method)
        return lambda *a: _hostcall(self._name, method, list(a))

class _Android:
    def call(self, name, method="", args=None): return _hostcall(name, method, args if args is not None else {})
    def emit(self, name, data=None): _volt.host(_enc({"op": "emit", "name": name, "data": data}))
    def __getattr__(self, name):
        if name.startswith("__"): raise AttributeError(name)
        return _Target(name)
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
        if fn == "__cb":
            f = _CBS.get(a[0])
            if f is None: raise RuntimeError("callback was released")
            return _enc({"value": f(*a[1:])})
        if fn == "__release":
            _CBS.pop(a[0], None); return '{"value": true}'
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
    return _enc(res)
