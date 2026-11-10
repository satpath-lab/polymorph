import sys, json, traceback, importlib.util
out, sys.stdout = sys.stdout, sys.stderr
path = sys.argv[1]
import os; sys.path.insert(0, os.path.dirname(os.path.abspath(path)))
mod, load_err = None, None
try:
    spec = importlib.util.spec_from_file_location("volt_app", path)
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
