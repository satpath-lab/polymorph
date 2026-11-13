# iPhone, iPad and Mac engines

`prebuilt/<platform>/libpoly_<lang>.a` are static libraries that `poly.py ios` / `poly.py mac` link into apps:

| platform folder | for |
|---|---|
| `macos` | Mac apps (Apple silicon) |
| `ios` | iPhones and iPads |
| `iossim` | the iOS Simulator on Apple-silicon Macs |

The language adapters and in-app runtimes are shared with Android (`../core`). `poly_apple.c` is the small layer that connects them to Swift.

## Python

Python uses BeeWare's [Python-Apple-support](https://github.com/beeware/Python-Apple-support) (3.14). Download the iOS and macOS packages, unpack them into one folder as `py-ios/` and `py-mac/`, then:

```bash
export POLY_PYTHON_APPLE=/path/to/that/folder
python3 poly.py mac counter-py
```

## Rebuilding

Needs Xcode. Download the sources (Lua, QuickJS-ng, mruby, PHP), then:

```bash
python3 build_apple.py lua=/src/lua js=/src/quickjs
cd /src/mruby && MRUBY_CONFIG=/path/to/mruby_apple.rb rake all && cd -
python3 build_apple.py ruby=/src/mruby
python3 build_apple.py python=$POLY_PYTHON_APPLE
```

PHP must be built as an embeddable library first (`--enable-embed=static --disable-all`, plus `--without-pcre-jit` for iOS). Put the build trees next to the source as `php-macos/`, `php-ios/` and `php-iossim/`, then run `build_apple.py php=/src/php`.
