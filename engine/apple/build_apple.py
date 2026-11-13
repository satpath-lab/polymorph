#!/usr/bin/env python3
"""Builds the script engines as static libraries for macOS, iOS devices and the iOS simulator.
Usage: python3 build_apple.py lua=/src/lua js=/src/quickjs ruby=/src/mruby php=/src (python uses BeeWare's Python-Apple-support, see README)"""
import os, sys, glob, subprocess, shutil, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
CORE = os.path.join(HERE, "..", "core")
OUT = os.path.join(HERE, "prebuilt")
PLATS = {"macos": ("macosx", "arm64-apple-macos13"), "ios": ("iphoneos", "arm64-apple-ios15.0"), "iossim": ("iphonesimulator", "arm64-apple-ios15.0-simulator")}
WANT = [p for p in os.environ.get("POLY_APPLE", "macos,ios,iossim").split(",") if p]

def sdk(p): return subprocess.check_output(["xcrun", "--sdk", PLATS[p][0], "--show-sdk-path"], text=True).strip()

def cc(p): return [subprocess.check_output(["xcrun", "-f", "clang"], text=True).strip(), "-target", PLATS[p][1], "-isysroot", sdk(p), "-O2", "-fPIC", "-w"]

def archive(lang, plat, sources, flags, extra_libs=()):
    d = os.path.join(OUT, plat); os.makedirs(d, exist_ok=True)
    out = os.path.join(d, "libpoly_%s.a" % lang)
    with tempfile.TemporaryDirectory() as t:
        objs = []
        srcs = [os.path.join(CORE, "lang_%s.c" % lang)] + list(sources)
        for i, s in enumerate(srcs):
            o = os.path.join(t, "%d.o" % i)
            extra = ["-DVOLT_LANG=VOLT_LANG_%s" % lang] if i == 0 else []
            subprocess.check_call(cc(plat) + ["-I" + CORE] + flags + extra + ["-c", s, "-o", o])
            objs.append(o)
        subprocess.check_call(["xcrun", "libtool", "-static", "-o", out] + objs + list(extra_libs))
    print("  %s %s %dKB" % (lang, plat, os.path.getsize(out) // 1024))

def lua(src):
    s = os.path.join(src, "src")
    files = [f for f in glob.glob(s + "/*.c") if os.path.basename(f) not in ("lua.c", "luac.c", "onelua.c", "ltests.c")]
    for p in WANT: archive("lua", p, files, ["-I" + s, "-DLUA_USE_IOS", "-DLUA_USE_POSIX"])

def js(src):
    files = [os.path.join(src, f) for f in ("quickjs.c", "libregexp.c", "libunicode.c", "dtoa.c") if os.path.exists(os.path.join(src, f))]
    for p in WANT: archive("js", p, files, ["-I" + src, "-D_GNU_SOURCE"])

def ruby(src):
    for p in WANT:
        b = os.path.join(src, "build", "apple-" + p)
        if not os.path.exists(os.path.join(b, "lib", "libmruby.a")): print("  ruby %s: build mruby first (mruby_apple.rb)" % p); continue
        archive("ruby", p, [], ["-I" + os.path.join(src, "include"), "-I" + os.path.join(b, "include")], [os.path.join(b, "lib", "libmruby.a")])

def php(src):
    for p in WANT:
        w = os.path.join(os.path.dirname(src), "php-" + p)
        if not os.path.exists(os.path.join(w, ".libs", "libphp.a")): print("  php %s: not built yet, skipped" % p); continue
        inc = ["-I" + os.path.join(w, x) for x in ("", "main", "Zend", "TSRM", "ext", "sapi/embed")]
        archive("php", p, [], inc, [os.path.join(w, ".libs", "libphp.a")])

def python(src):
    slices = {"macos": ("py-mac", "macos-arm64_x86_64"), "ios": ("py-ios", "ios-arm64"), "iossim": ("py-ios", "ios-arm64_x86_64-simulator")}
    for p in WANT:
        pkg, sl = slices[p]
        fw = os.path.join(src, pkg, "Python.xcframework", sl, "Python.framework")
        hdr = os.path.join(fw, "Headers") if os.path.isdir(os.path.join(fw, "Headers")) else os.path.join(fw, "Versions", "Current", "Headers")
        archive("python", p, [], ["-I" + hdr])

if __name__ == "__main__":
    for a in sys.argv[1:]:
        k, v = a.split("=", 1); globals()[k](os.path.abspath(v))
