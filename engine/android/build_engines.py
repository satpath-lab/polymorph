#!/usr/bin/env python3
import os, sys, subprocess, glob, shutil, platform
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "prebuilt")
ABIS = os.environ.get("POLY_ABIS", "arm64-v8a,x86_64").split(",")
TRIPLE = {"arm64-v8a": "aarch64-linux-android", "x86_64": "x86_64-linux-android", "armeabi-v7a": "armv7a-linux-androideabi"}
API = "24"

def ndk():
    for d in [os.environ.get("ANDROID_NDK_HOME"), os.environ.get("ANDROID_NDK_ROOT")] + sorted(glob.glob(os.path.expanduser("~/Library/Android/sdk/ndk/*")) + glob.glob(os.path.expanduser("~/Android/Sdk/ndk/*")), reverse=True):
        if d and os.path.isdir(d): return d
    sys.exit("Android NDK not found (set ANDROID_NDK_HOME)")

def cc(abi, cpp=False):
    host = "darwin-x86_64" if platform.system() == "Darwin" else "linux-x86_64"
    return os.path.join(ndk(), "toolchains/llvm/prebuilt", host, "bin", TRIPLE[abi] + API + ("-clang++" if cpp else "-clang"))

def link(lang, abi, sources, cflags=(), ldflags=()):
    d = os.path.join(OUT, lang, abi); os.makedirs(d, exist_ok=True)
    out = os.path.join(d, "libvolt_%s.so" % lang)
    core = os.path.join(HERE, "..", "core")
    cmd = [cc(abi), "-shared", "-fPIC", "-O2", "-fvisibility=hidden", "-I" + HERE, "-I" + core, *cflags,
           os.path.join(HERE, "volt_jni.c"), os.path.join(core, "lang_%s.c" % lang), *sources,
           "-o", out, "-Wl,-z,max-page-size=16384", "-Wl,--gc-sections", "-ffunction-sections", "-fdata-sections", "-s", "-lm", "-llog", *ldflags]
    subprocess.check_call(cmd)
    print("  %s %s %dKB" % (lang, abi, os.path.getsize(out) // 1024))

def lua(src):
    s = os.path.join(src, "src")
    files = [f for f in glob.glob(s + "/*.c") if os.path.basename(f) not in ("lua.c", "luac.c")]
    for abi in ABIS: link("lua", abi, files, ["-I" + s, "-DLUA_USE_LINUX", "-Wno-everything"])

def js(src):
    files = [os.path.join(src, f) for f in ("quickjs.c", "libregexp.c", "libunicode.c", "dtoa.c") if os.path.exists(os.path.join(src, f))]
    for abi in ABIS: link("js", abi, files, ["-I" + src, "-D_GNU_SOURCE", "-Wno-everything"])

def ruby(src):
    for abi in ABIS:
        b = os.path.join(src, "build", "android-" + abi)
        link("ruby", abi, [os.path.join(b, "lib", "libmruby.a")], ["-I" + os.path.join(src, "include"), "-I" + os.path.join(b, "include")])

def php(src):
    for abi in ABIS:
        w = os.path.join(os.path.dirname(src), "php-" + abi)
        if not os.path.exists(os.path.join(w, ".libs", "libphp.a")): print("  php %s: not built yet, skipped" % abi); continue
        inc = ["-I" + os.path.join(w, x) for x in ("", "main", "Zend", "TSRM", "ext", "sapi/embed")]
        link("php", abi, [os.path.join(w, ".libs", "libphp.a")], inc + ["-Wno-everything"], ["-ldl"])

PY_SKIP = {"test", "idlelib", "tkinter", "turtledemo", "ensurepip", "lib2to3", "pydoc_data", "__pycache__", "venv", "sqlite3/test", "unittest/test", "config-3.14-aarch64-linux-android", "config-3.14-x86_64-linux-android", "site-packages"}

def python(src):
    import zipfile
    ver = "python3.14"
    for abi in ABIS:
        pre = os.path.join(src, abi, "prefix")
        link("python", abi, [], ["-I" + os.path.join(pre, "include", ver)], ["-L" + os.path.join(pre, "lib"), "-l" + ver])
        d = os.path.join(OUT, "python", abi)
        for so in glob.glob(os.path.join(pre, "lib", "lib*.so")):
            b = os.path.basename(so)
            if b.startswith("libpython3.") and b != "libpython3.so" or b.endswith("_python.so"): shutil.copy(so, d)
    out = os.path.join(OUT, "python", "python.zip")
    first = os.path.join(src, ABIS[0], "prefix", "lib", ver)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for base, dirs, files in os.walk(first):
            rel = os.path.relpath(base, first)
            dirs[:] = [x for x in dirs if x not in PY_SKIP and os.path.join(rel, x).lstrip("./") not in PY_SKIP and x != "lib-dynload"]
            for f in files:
                if not f.endswith((".pyc",)): z.write(os.path.join(base, f), os.path.join("lib", ver, rel, f).replace("/./", "/"))
        for abi in ABIS:
            dyn = os.path.join(src, abi, "prefix", "lib", ver, "lib-dynload")
            for f in os.listdir(dyn):
                if f.endswith(".so") and not f.startswith(("_test", "xxlimited", "_ctypes_test", "_tkinter")): z.write(os.path.join(dyn, f), "dynload/%s/%s" % (abi, f))
    print("  python stdlib %dKB" % (os.path.getsize(out) // 1024))

if __name__ == "__main__":
    langs = dict(a.split("=", 1) for a in sys.argv[1:])
    for k, v in langs.items(): globals()[k](os.path.abspath(v))
