# Android engines

Each language runs inside the APK as a native library: `libvolt_<lang>.so`. The prebuilt libraries in `prebuilt/` are what `poly.py apk` packages, so you only need this guide to rebuild or upgrade an engine.

| language | engine | size per CPU | source |
|---|---|---|---|
| Lua | Lua 5.4.9 | 0.26 MB | lua.org |
| JavaScript | QuickJS-ng 0.18 | 1 MB | github.com/quickjs-ng/quickjs |
| Ruby | mruby 3.4 | 1 MB | github.com/mruby/mruby |
| PHP | PHP 8.4 (embed SAPI) | 6.9 MB | php.net |
| Python | CPython 3.14 (official Android build) | 12 MB + 7 MB stdlib | python.org/ftp/python/3.14.x/ |

## Files

- `../core/volt.h`, `volt_jni.c`: the shared bridge between Android and the engines. Every engine implements `open`, `call` and `close`, and gets `volt_host()` to call into Android.
- `../core/lang_<lang>.c`: one small adapter per language (shared with iOS/macOS).
- `../core/harness/harness.<ext>`: the on-device runtime for each language. It loads `main.*`, handles `require` for multi-file apps, runs `init` / `update` / `view`, and exposes `android.*`.

## Rebuilding

You need the Android NDK (r27 or newer).

```bash
python3 build_engines.py lua=/path/to/lua-5.4.9 js=/path/to/quickjs
python3 build_engines.py python=/path/to/py    # contains arm64-v8a/ and x86_64/ from the python.org Android tarballs
```

- **Ruby:** build mruby first with `MRUBY_CONFIG=$PWD/mruby_config.rb rake all` inside the mruby source, then run `build_engines.py ruby=/path/to/mruby`.
- **PHP:** cross-compile `libphp.a` with `--enable-embed=static --disable-all`. Undefine `HAVE_RES_NSEARCH`, `HAVE_RES_SEARCH`, `HAVE_DN_EXPAND` and `HAVE_DN_SKIPNAME` in `main/php_config.h`, then add `#define getdtablesize() sysconf(_SC_OPEN_MAX)`. Put the tree next to the source as `php-<abi>/`, then run `build_engines.py php=/path/to/php-src`.

Set `POLY_ABIS` to choose CPU types (default `arm64-v8a,x86_64`; add `armeabi-v7a` for old 32-bit phones).
