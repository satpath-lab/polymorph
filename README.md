# ⚡ VoltForge

**Build real native Android apps in Lua, Python, PHP, JavaScript or Ruby. The scripts run on the phone itself.**

You write three small functions in any of those languages. VoltForge puts a real interpreter for that language inside your APK. Your script runs on the device and its output becomes **real native Android views** (not a WebView). No server is needed: the app works offline, from the first launch.

- 🧠 **5 languages on the device**: Lua 5.4, JavaScript (QuickJS), Python 3.14 (official CPython), Ruby (mruby), PHP 8.4
- 📱 **Native UI**: real `LinearLayout`, `Button`, `EditText`, lists, charts, dialogs, tabs…
- 🤖 **Direct Android access from scripts**: `android.device()`, `android.storage(...)`, `android.http(...)`, `android.clipboard(...)`, plus any plugin you write in Java, Kotlin or C
- 🔌 **Your choice of where code lives**: bundled in the APK (fully offline), synced live from your computer while developing, or downloaded as **signed over-the-air updates** that you can push at any time
- 🔥 **Live reload while developing**: save a file and the phone reloads in about a second, keeping its state
- 🧩 **SDK for existing apps**: add the `.aar`, expose your Java/Kotlin objects, and write any part of the app (logic or whole screens) in a script that you can replace at runtime
- 🔁 **Two-way native bridge**: scripts call your native code, pass callbacks that native code calls later, and send events. Native code calls script functions
- 📦 **Signed APKs and AABs** with no Gradle and no Android Studio, built straight from the SDK tools
- 🏢 **Built for teams**: web IDE, tests, test-gated releases, roles, audit log, CI/CD

## Why VoltForge

- **Logic stays in the language your team already uses.** A Python data team, a PHP web shop, a Node team, or Lua and Ruby developers can ship a native app without learning Android.
- **It runs natively and offline.** The interpreter is compiled into the APK for each CPU type. Events are handled on the phone in microseconds to a few milliseconds.
- **You still control updates.** Turn on signed script updates and fix bugs without a store release. Updates are verified with your key before they run.
- **Nothing is out of reach.** Anything a script can't do directly, you write in Java, Kotlin or C and call from the script.

### Where it helps

| use case | why it fits |
|---|---|
| Internal company tools: field service, inventory, approvals, check-ins | camera, GPS, storage, works offline; push fixes to every employee with a signed update |
| Prototypes and MVPs | one file per app, live browser preview, signed APK in one click |
| Apps whose rules change often (pricing, forms, workflows) | ship new script logic over the air, no store review |
| Adding scripting to an existing Android app | add the `.aar`, expose your native classes, and ship rule or screen changes as signed script updates |
| Teaching app development | five beginner-friendly languages with instant feedback |

## Quick start

```bash
python3 volt.py serve          # web IDE with a live phone preview at http://localhost:8787
python3 volt.py apk todo-lua   # build an offline APK with the script inside
```

### Three ways to ship code

| build | command | where the script comes from |
|---|---|---|
| **Bundled** (default) | `volt.py apk myapp` | inside the APK. Works fully offline. A new version needs a new APK |
| **Live dev** | `volt.py apk myapp --dev http://192.168.1.10:8787` | synced from your computer. Saving a file reloads the phone. Use `http://10.0.2.2:8787` for the emulator |
| **Over-the-air** | add `"updates"` to `app.json`, then `volt.py apk myapp --server https://your-host` | bundled code first, then signed updates downloaded from your server or any static host (S3, GitHub Pages, a CDN) |

Every option runs the script **on the phone**. The dev and over-the-air options only download files; no screen ever comes from a server.

Over-the-air updates in `app.json`:

```json
{ "updates": { "url": "https://cdn.example.com/myapp.zip", "check": "launch" } }
```

- `check` is `"launch"` (every app start) or a number of minutes.
- Publish a version with `python3 volt.py bundle myapp --out public/`. It writes `myapp.zip` and `myapp.zip.sig`.
- Or let `volt.py serve` host them at `/b/<app>.zip`. It always serves the deployed release.
- Bundles are signed with an ECDSA P-256 key kept in `data/bundle-signing.pem`. Its public key is built into the APK, and a bundle with a bad signature is rejected (the app keeps running its current code). Back this key up: without it you can't ship updates to installed apps.

## Use it as an SDK in any Android app

You don't have to build the whole app with VoltForge. Add the engine to an existing Kotlin or Java app and write only some parts in a script: pricing rules, validation, feature flags, whole screens. Your app can replace those scripts at any time by asking for a new one.

```bash
python3 volt.py aar --langs python,js,lua      # → data/build/voltengine.aar (pick only the languages you need)
```

In Android Studio, copy the `.aar` to `app/libs/` and add `implementation(files("libs/voltengine.aar"))`. Its manifest entries (the script screen activity and helpers) merge automatically.

**1. Expose native code to scripts** (Java, Kotlin, or C through your own JNI wrapper):

```kotlin
class Tax { fun rate(country: String) = if (country == "DE") 0.19 else 0.0 }
class Clock { fun every(ms: Int, times: Int, cb: VoltCallback) { /* later */ cb.call(1) } }

Volt.expose("Tax", Tax())                                   // available to every engine
Volt.function("hostVersion") { args -> "2.4.1" }            // a single function
```

Public methods are matched by name and argument count, and arguments are converted for you: numbers, strings, booleans, `Map`, `List`, arrays, `JSONObject`, `JSONArray`. A script function passed as an argument arrives as a `VoltCallback`. Return values go back to the script as JSON-compatible data.

**2. Open an engine and call script functions:**

```kotlin
val engine = Volt.engine(context)
    .source(ScriptSource.asset("scripts/pricing"))           // or .dir(file), .code("main.py", text), .url(zipUrl, publicKey)
    .expose("Clock", Clock())                                // only for this engine
    .on("tick") { name, data -> Log.i("app", "script says $data") }
    .open()                                                  // or .openAsync { engine, error -> }

val total = engine.invoke("price", 100, "DE")               // blocking; safe from any thread
engine.invokeAsync("price", { value, error -> show(value) }, 100, "DE")   // result delivered on the main thread
```

**3. In the script** (the same in every language):

```python
def price(amount, country):
    return amount * (1 + android.Tax.rate(country))          # call Java/Kotlin directly

def start_ticks():
    android.Clock.every(300, 3, lambda n: android.emit("tick", {"n": n}))   # pass a callback, send events
```

| language | call native code | pass a callback | send an event |
|---|---|---|---|
| Python | `android.Tax.rate("DE")` | any function or lambda | `android.emit("tick", data)` |
| JavaScript | `android.Tax.rate('DE')` | any function | `android.emit('tick', data)` |
| Lua | `android.Tax.rate("DE")` | any function | `android.emit("tick", data)` |
| Ruby | `Android.Tax.rate("DE")` | a lambda / proc | `Android.emit("tick", data)` |
| PHP | `Android::Tax()->rate('DE')` | a closure | `Android::emit('tick', $data)` |

Native code calls a callback back with `cb.call(args…)` (queued on the script's thread) or `cb.callSync(args…)` (waits for the return value). Call `cb.release()` when it won't be called again. Each engine runs on its own thread, so scripts never block your UI thread.

**4. Replace scripts at any time:**

```kotlin
engine.load(ScriptSource.url("https://cdn.example.com/pricing.zip", PUBLIC_KEY))   // download, verify signature, swap
engine.load(ScriptSource.code("main.js", jsFromYourBackend))                       // or any code you have
engine.update()                                                                    // re-check the current URL (uses ETag)
```

A new script can even be in a different language from the old one. A bundle with a bad signature is rejected, and the engine keeps the script it had. Publish bundles with `volt.py bundle <app>`, and print the public key with `volt.py pubkey`.

**5. Show a screen written in a script** and get its result back:

```kotlin
Volt.openScreen(activity, ScriptSource.asset("scripts/checkout"), REQUEST_CHECKOUT)
// the script ends the screen with _finish = {...}; the data arrives in onActivityResult as intent.getStringExtra("volt.result")
```

Script screens use the full component set, styling and device features described below, and can call your exposed objects too.

A working example is in [examples/android-host](examples/android-host). It's a plain Java app that uses the `.aar` to do all five of the steps above. Build it with `python3 examples/android-host/build.py`; that script stands in for Gradle so the repo needs no Android Studio.

## Writing an app

Every app is `apps/<name>/main.<lua|py|php|js|rb>` and uses the same Elm-style contract:

| function | purpose |
|---|---|
| `init()` | returns the starting state for a new user session |
| `update(state, event)` | returns the new state after a tap, change or submit |
| `view(state)` | returns the screen as plain data (tables, dicts, arrays or objects) |

```lua
function init() return {count = 0} end
function update(s, e) if e.id == "inc" then s.count = s.count + 1 end return s end
function view(s)
  return {title = "Counter", body = {type = "column", padding = 24, gap = 12, children = {
    {type = "text", text = tostring(s.count), size = 64, bold = true},
    {type = "button", id = "inc", text = "+1"},
  }}}
end
```

**Event:** `{type: "tap"|"change"|"submit", id, value, inputs: {<inputId>: text}}`

**Event:** also includes `store` (this device's saved data) and `device` (`platform`, `sdk`, `model`, `brand`, `lang`, `dark`, `width`, `height`).

### Native device features

Set any of these keys on the state you return. The phone performs the action once. Anything that produces a value sends back an event `{type: "result", id, value}`, where `value` is either the data or `{error: "cancelled" | "denied" | ...}`. Runtime permissions are requested automatically.

| effect | what happens | result `value` |
|---|---|---|
| `_camera = "id"` | opens the camera; the photo is downscaled to ≤1600 px, rotated upright, and saved in app storage | `{url, path, type, size, name}` (`url` is a `file://` URL). Show it with `{type: "image", src: url}` |
| `_pick = {id, type}` | opens the system picker; `type` is `"image"`, `"any"` or a MIME type; the file is copied into app storage | `{url, path, type, size, name}` |
| `_location = "id"` | gets a GPS or network fix (asks permission, 15 s timeout) | `{lat, lng, accuracy, time}` |
| `_notify = {title, text, tag, at}` | shows a notification now, or `at` seconds later, even if the app is closed. A list sends several | tapping it sends `{type: "notification", id: tag}` |
| `_contact = "id"` | opens the contact picker (no contacts permission needed) | `{name, phone}` |
| `_share = "text"` | opens the system share sheet | – |
| `_clipboard = "text"` | copies to the clipboard | – |
| `_store = {key: value}` | saves values on the device (`null` deletes a key). They arrive in every event as `event.store` | – |
| `_open = "url"` | opens a URL or link: `https:`, `tel:`, `sms:`, `mailto:`, `geo:` | – |
| `_toast`, `_vibrate` | short message / vibration in ms | – |

Also on the phone:
- **Offline start:** the last screen is cached, so the app opens instantly and still shows something with no network.
- **Animated screen changes:** give each screen a `route`. When the route changes, the new screen slides in, and in from the left when the user went back.

The web preview fakes all of these: a file chooser stands in for the camera, it uses the browser's geolocation and notifications, and the contact picker returns a sample contact. See `apps/field-kit` for a demo that uses every feature, with tests.

### Big lists

`{type: "list", id: "feed", items: [...], onEnd: "more", gap: 8}` renders with Android's recycling `ListView`. Only the visible rows exist, so thousands of rows scroll smoothly. When the user reaches the end, `onEnd` is sent as a tap event so you can load more, and the scroll position is kept across updates. Use it on a screen with `scroll: false`; the list then fills the remaining space. On a scrolling screen it falls back to a plain column.

### Background jobs

`_background = {every: 15}` schedules a periodic job (Android's minimum is 15 minutes) that runs even when the app is closed or after a reboot. Each run calls your script on the phone with `{type: "background"}`, and any `_notify` / `_store` it returns is applied. `_background = {on: false}` cancels it. For instant push messages, add the Firebase SDK as a plugin jar.

### Direct Android calls

Scripts can call Android synchronously and get the result back right away. This is in addition to the `_effect` keys above:

| call | result |
|---|---|
| `android.device()` | `{platform, sdk, model, brand, lang, dark, width, height}` |
| `android.storage("set", {key, value})`, `("get", {key})`, `("remove", {key})`, `("all")` | device storage (encrypted when `encryptStorage` is on) |
| `android.http("", {url, method, headers, body, json: true, timeout})` | `{status, ok, body, headers, json}`. Certificate pins apply |
| `android.files("read" \| "write" \| "list" \| "delete" \| "exists", {path, text, append})` | files in the app's private storage |
| `android.clipboard("get")`, `android.clipboard("set", {text})` | clipboard text |
| `android.toast("", {text})`, `android.vibrate("", {ms})`, `android.open("", {url})` | – |
| `android.app("info")`, `android.app("update")` | app info / check for a signed script update now and reload if one arrives |
| `android.<YourPlugin>(method, args)` | any Java, Kotlin or C plugin in `native/` (see below) |

The syntax is the same idea in every language:
- **Lua and JavaScript:** `android.storage("get", {key = "x"})`, `android.storage('get', {key: 'x'})`
- **Python:** `android.storage("get", {"key": "x"})`
- **Ruby:** `Android.storage("get", {"key" => "x"})`
- **PHP:** `Android::storage('get', ['key' => 'x'])`

In the web preview, `device`, `storage` and `clipboard` are simulated and other calls return `null`.

## Native plugins (Java, Kotlin, C/C++, any .jar)

Anything the scripts can't do, you write natively and call from any language:

```
apps/<app>/native/
  java/com/acme/Torch.java     class Torch implements dev.volt.player.VoltPlugin
  kotlin/Battery.kt            class Battery : VoltPlugin
  c/fasthash.c                 JNI → libfasthash.so (one .c = one library; a folder = one library)
  libs/zxing-core.jar          third-party jars (e.g. QR scanning, crypto, SDKs)
  permissions.txt              extra manifest permissions, one per line
```

```java
public class Torch implements VoltPlugin {
    public void call(Host host, String method, JSONObject args, Result result) throws Exception {
        CameraManager cm = (CameraManager) host.activity().getSystemService(Context.CAMERA_SERVICE);
        cm.setTorchMode(cm.getCameraIdList()[0], method.equals("on"));
        result.ok(new JSONObject().put("on", method.equals("on")));
    }
}
```

```lua
s._native = {plugin = "Torch", method = "on", id = "torch", args = {}}   -- result arrives as {type="result", id="torch", value={on=true}}
```

`Host` gives a plugin the `activity()`, `requestPermissions(perms, cb)`, `startForResult(intent, cb)`, `emit(id, value)` for streams (sent as `{type: "native", id, value}` events, e.g. sensor readings), and `runOnUi` / `runInBackground`. Plugins are found automatically by scanning for classes that implement `VoltPlugin`.

**Build:** Java uses `javac`. Kotlin uses `kotlinc`, and the Kotlin standard library is bundled automatically. Each C/C++ file is compiled with the Android NDK for `VOLT_ABIS` (default `arm64-v8a,x86_64`); without the NDK, C files are skipped with a warning. Everything is turned into Android bytecode with `d8`. Builds are cached by content hash.

Native code ships in the APK, so changing it needs a new APK build. Scripts stay live-editable as before. The `apps/native-demo` app shows each kind:
- flashlight (Java)
- battery (Kotlin)
- FNV-1a hashing (C via JNI)
- live accelerometer stream (Java + `emit`)
- Bluetooth LE scan (Java + runtime permissions)
- background job and a 1,000+ row infinite list

### Components

| type | props |
|---|---|
| `column` / `row` / `card` / `hscroll` | `children`, `gap`, `align` (cross axis: start/center/end), `justify` (start/center/end/between/around/evenly) |
| `grid` | `columns`, `gap`, `children` |
| `stack` | layers children on top of each other; a child with `position: {top, left, right, bottom}` or `{center: true}` is placed absolutely |
| `text` | `text`, `size`, `color`, `bold`, `weight` (100–900), `italic`, `mono`, `font`, `align`, `lines`, `letterSpacing`, `lineHeight`, `underline`, `strike`, `uppercase`, `textShadow`, `selectable` |
| `button` | `id`, `text`, `bg` / `gradient`, `color`, `size`, `radius`, `border`, `enabled` |
| `input` | `id`, `hint`, `value`, `kind` (text/number/email/password/phone), `multiline` |
| `select` | `id`, `options` (strings or `{value, label}`), `value` → `change` event |
| `slider` | `id`, `min`, `max`, `step`, `value`, `color` → `change` event when released |
| `checkbox` / `switch` | `id`, `text`, `value` → `change` event |
| `radio` | `id`, `options`, `value`, `horizontal` → `change` event |
| `image` | `src` (a URL, or a file in `apps/<name>/assets/`), `fit` (cover/contain) |
| `chart` | `kind` (line/bar/pie/donut), `series: [{values, color}]` or `data`, `labels`, `colors`, `height` |
| `canvas` | `ops`: `rect` / `circle` / `line` / `path` / `arc` / `text` in dp, with `color`, `stroke`, `fill`, `opacity`. With an `id`, taps send `{x, y}` |
| `web` | `url` or `html`. Use it for maps (Leaflet / Google Maps JS), embeds, rich text, dashboards |
| `video` | `src`, `autoplay`, `loop`, `muted`, `controls`. A playback failure sends `{type: "error", id}` |
| `list` | recycled rows: `id`, `items`, `onEnd`, `gap` |
| `native` | `plugin`, `id`, `props`: a native Android view you write in Java/Kotlin (see plugins) |
| `progress`, `spacer`, `divider` | `value` / `size` / `color` |

**Common props on every element:**
- **Spacing:** `padding` (a number, `[v,h]` or `[t,r,b,l]`), `paddingTop/Right/Bottom/Left/H/V`, and `margin` / `marginTop/...` the same way.
- **Size:** `width`, `height` (a number, `"fill"` or `"wrap"`), `minWidth`, `minHeight`, `maxWidth`, `flex`, `alignSelf`.
- **Look:** `bg`, `gradient: [colors]` with `angle`, `radius` (a number or `[tl,tr,br,bl]`), `border`, `borderWidth`, `elevation`, `opacity`, `rotate`, `scale`, `visible`.
- **Behavior:** `id` makes any container tappable; `animate: {from: {alpha, scale, x, y, rotate}, duration, delay}` plays once when the element (identified by `id` or `key`) first appears.

**Screen props:**
- `title`, `accent`, `bg`, `body`, `route` (animated screen changes), `scroll`, `hideBar`, `back`, `secure` (blocks screenshots)
- `tabs: {selected, items: [{id, label, icon}]}` adds a native bottom navigation bar
- `dialog: {title, text, buttons: [{id, text}], cancel}` shows a native alert dialog
- `theme: {all: {...}, text: {...}, button: {...}}` sets defaults per element type
- `styles: {name: {...}}` defines named styles that any element can use with `style: "name"` or `style: ["a", "b"]`

**Custom fonts:** put `.ttf` files in `apps/<app>/assets/fonts/` and use `font: "Inter-Bold"`.

**Date and time pickers:** `_pickDate = {id, value: "2026-12-24"}` and `_pickTime = {id, value: "09:30"}` open native pickers and return `"YYYY-MM-DD"` / `"HH:MM"`.

## App configuration (`apps/<app>/app.json`)

Every security and behavior feature can be switched on or off per app. Any key you leave out uses the default shown here.

```json
{
  "label": "Showcase", "package": "com.acme.showcase", "version": "1.2.0", "versionCode": 12,
  "icon": "assets/icon.png", "orientation": "portrait", "minSdk": 24, "targetSdk": 35,
  "pins": ["sha256/…"],
  "features": {
    "requireHttps": true,       "encryptStorage": true,   "blockScreenshots": false,
    "cacheScreens": true,       "backgroundJobs": true,
    "location": true,           "notifications": true
  },
  "updates": { "url": "https://cdn.example.com/showcase.zip", "check": "60" }
  }
}
```

| feature | on | off |
|---|---|---|
| `requireHttps` | script updates must come from `https://`, and plain HTTP is disabled in the APK | allows HTTP (for internal networks) |
| `encryptStorage` | app state, cached screens and storage are encrypted with AES-256-GCM using a hardware-backed Android Keystore key | stored as plain text |
| `blockScreenshots` | screenshots and screen recording are blocked on every screen (`secure: true` does it per screen) | allowed |
| `cacheScreens` | the last screen is shown instantly while the engine starts | the screen is drawn once the script has run |
| `backgroundJobs` | scheduled background runs | disabled, and the boot permission is removed |
| `location`, `notifications` | the matching Android permissions are included | permissions are left out of the manifest |

`updates` is optional. Leave it out for a fully bundled app. Add `"publicKey"` to use your own signing key, or `"unsigned": true` to skip signatures (not recommended). Dev builds (`--dev`) may use `http://` so you can develop locally.

## Security

- **Code integrity:** over-the-air script bundles must carry a valid ECDSA P-256 signature from your key, or the phone refuses them and keeps its current code.
- **In transit:** HTTPS (required for script updates by default) plus optional **certificate pinning**. Run `python3 volt.py pin https://your-server` and put the printed pins in `app.json`. A server presenting a certificate that is not pinned is rejected before any data is sent. Pin your CA's intermediate certificate so routine certificate renewals keep working.
- **On the phone:** app state, cached screens and device storage are encrypted with an Android Keystore AES-256-GCM key that cannot be exported. Screenshots can be blocked. Plain HTTP is disabled in HTTPS builds.
- **On the dev/update server:** PBKDF2 password hashes, tokens stored only as hashes, roles, an audit log, login lockout after 10 failures per IP within 10 minutes, `X-Frame-Options` / `nosniff` / `Referrer-Policy` headers, HSTS (`VOLT_HSTS=1` or set automatically behind an HTTPS proxy), and an optional device key.
- **Preview sandbox:** with `VOLT_SANDBOX=1`, scripts running in the web preview run under `sandbox-exec` (macOS) or `bubblewrap` (Linux). A script can read its own app folder but not the database, other apps or `~/.ssh`, and cannot write files. `VOLT_SANDBOX_NET=0` also blocks network access.

## Play Store

- `app.json` sets the package ID, version, icon, orientation and SDK levels. `targetSdk` defaults to 35, which Google Play requires.
- Build an **Android App Bundle** with `python3 volt.py apk myapp --format aab`, or pick `.aab` in the IDE.
- Bundles need Google's `bundletool`. Download `bundletool-all-<version>.jar` from github.com/google/bundletool/releases and set `VOLT_BUNDLETOOL` to its path.
- Use your own signing key with `VOLT_KEYSTORE` / `VOLT_KEYSTORE_PASS`.

## Team workflow

```
 edit (IDE or your own editor + git)  ──▶  dev APKs and the preview reload on every save
                │
         tests/*.json pass?  ──no──▶  release blocked
                │ yes
        release rN (immutable snapshot)  ──admin deploys──▶  /b/<app>.zip (signed): apps with "updates" pick it up
                │                                    ◀── one-click rollback to any rN
                └──▶  volt.py apk myapp: release builds bundle the deployed release
```

- **Dev and release builds.** Dev APKs sync the working tree and reload on every save. Release APKs bundle the deployed release (or the working tree if nothing is deployed).
- **Releases.** A release copies the app into `data/releases/<app>/<n>`. It is created only if every test passes. An admin can force one, and that is recorded in the audit log.
- **Deploy and rollback** reach apps that use `"updates"` on their next check, with no store review. Each phone keeps its own state.
- **Multi-file projects.** Use any folder structure:
  - Lua: `require("util")`, which also searches `lib/`
  - Python: `from lib.util import x`
  - PHP: `require_once __DIR__.'/lib/x.php'`
  - JavaScript: `require('./lib/x')`
  - Ruby: `require_relative 'lib/x'`

  You can edit in the IDE's file tree or in your own editor. Every file in the app folder is watched.

### Tests

Put JSON cases in `apps/<app>/tests/*.json`. Each step sends an event, and `expect` checks dotted paths in `state`, `ui` and `fx`:

```json
[{ "name": "adds a task", "steps": [
  { "event": { "type": "submit", "id": "new", "inputs": { "new": "Ship it" } },
    "expect": { "state.items.0.text": "Ship it", "fx.toast": "Added", "ui.title": "Tasks" } }
]}]
```

Run them with `python3 volt.py test --all`, which exits with code 1 on failure (suitable for CI), or with the **Tests** tab in the IDE.

### Access control

| role | can |
|---|---|
| viewer | read code, logs, releases, audit log and metrics |
| developer | + edit files, create apps, run tests, create releases, build APKs, create API tokens |
| admin | + deploy/rollback, force releases, delete apps, manage users |

```bash
python3 volt.py user add alice --role admin      # prompts for a password (PBKDF2-hashed)
python3 volt.py token alice --label github-ci     # long-lived API token for CI
```

With no users, the server runs in **open mode** (everyone is admin) and shows a warning banner. That is meant for local development only. Session and API tokens are stored only as SHA-256 hashes. Every write is recorded in the **audit log** (IDE tab or `GET /api/audit`).

### CI/CD

[.github/workflows/voltforge.yml](.github/workflows/voltforge.yml) runs every app's tests on each push and pull request. On `main`, it uploads each changed app (`POST /api/sync/<app>` with a tar.gz) and creates a release. An admin then deploys it from the IDE. Add `"deploy": true` to the release call if you want continuous deployment. Set the repository secrets `VOLT_URL` and `VOLT_API_TOKEN`.

## Running in production

```bash
VOLT_ADMIN_PASSWORD='change-me-now' docker compose up -d
```

The server is optional: release apps run without it. Host it when your team wants the shared IDE, releases and over-the-air updates. Put it behind HTTPS (Caddy, nginx, or a cloud load balancer). The container runs as a non-root user on a read-only filesystem with CPU and memory limits. Data (SQLite database, releases, signing key) lives in the `/data` volume, so back that up.

| env var | default | |
|---|---|---|
| `PORT` | 8787 | listen port |
| `VOLT_DATA` | `./data` | SQLite DB, releases, keystore, build cache |
| `VOLT_ADMIN_PASSWORD` / `VOLT_ADMIN_USER` | – | creates the first admin on startup if there are no users |
| `VOLT_WORKERS` | 4 | warm interpreter processes per app for the web preview |
| `VOLT_TIMEOUT` | 10 | seconds per script call before the worker is killed |
| `VOLT_DEVICE_KEY` | – | shared key baked into APKs. Without it, dev sync and update downloads are rejected |
| `VOLT_SESSION_DAYS` | 14 | IDE login lifetime |
| `VOLT_MAX_UPLOAD_MB` | 15 | maximum size of a camera or file upload |
| `VOLT_UPLOAD_DAYS` | 30 | delete uploads older than this (0 = never) |
| `VOLT_SANDBOX` / `VOLT_SANDBOX_NET` | off / 1 | run scripts in an OS sandbox / allow network inside it |
| `VOLT_PINS` | – | default certificate pins for every app |
| `VOLT_BUNDLETOOL` | – | path to bundletool, for `.aab` builds |
| `VOLT_HSTS` | – | send the HSTS header |
| `VOLT_ABIS` | `arm64-v8a,x86_64` | CPU types to package engines and C/C++ plugins for |
| `ANDROID_NDK_HOME` | auto | NDK location for C/C++ plugins |
| `VOLT_APP_*` | – | passed to scripts (secrets, API URLs). All other server env vars are hidden from scripts |
| `VOLT_KEYSTORE` / `VOLT_KEYSTORE_PASS` | generated | your release signing key for APKs |
| `ANDROID_HOME` | auto | only needed for building APKs (JDK + build-tools + a platform) |

**Ops endpoints:** `GET /healthz` (no auth), `GET /metrics` (Prometheus format: requests, errors, latency and workers per app and channel; needs a viewer token).

**Performance:** on the phone, an event (script `update` + `view` + native render) takes well under a frame for typical screens, and the engine starts in tens of milliseconds (Python's first launch also unpacks its standard library once).

### Known limits (be honest with your security team)

- APK size depends on the language, per CPU type: Lua +0.3 MB, JavaScript +1 MB, Ruby +1 MB, PHP +6.9 MB, Python +12 MB plus a 7 MB standard library. Set `VOLT_ABIS=arm64-v8a` to ship only for modern phones, or build an `.aab` so Play delivers one CPU type per device.
- Python apps can use the standard library. Packages with C extensions (numpy and others) need to be built for Android first. Pure-Python packages can be copied into the app folder.
- PHP is built with a minimal extension set (json, mbstring, ctype, filter, tokenizer, date, pcre). It has no database drivers or curl; use `android.http(...)` instead.
- Ruby is mruby, which has no gems from rubygems.org and no regular expressions. The core language, `require_relative` and JSON work.
- Script code inside an APK can be read by anyone who unpacks it, as with any app. Keep secrets on a server.
- Native code (plugins, engines) can't be updated over the air. That needs a new APK.
- Not built in, but available through plugins: NFC, QR scanning (drop a ZXing jar into `native/libs`), payments, maps SDKs, Firebase push.

## Test status

Verified on an Android 16 emulator (arm64), with the network turned off:
- **All five languages:** each app rendered and handled taps: Lua to-do, JavaScript calculator with a multi-file `require`, Python counter, Ruby counter and PHP tip splitter.
- **State:** the Lua app's state survived a full app restart.
- **Direct Android calls from JavaScript:** device, storage, clipboard and toast.

With the network on:
- **Live dev reload:** an edit appeared on the phone in about 2 seconds.
- **Over-the-air update:** a signed bundle was accepted and its new code ran.
- **Wrong key:** a bundle signed with a different key was rejected, and the app kept its built-in code.

Earlier in the project, verified on the emulator: components, styling, tabs, dialogs, charts, canvas, web views, Java/Kotlin/C plugins, camera, GPS, notifications and reminders, background jobs, Bluetooth permissions and infinite lists. `.aab` builds pass `bundletool validate`.

**SDK, verified on the emulator with the example host app** (plain Java + `.aar`):
- **Native ↔ script calls:** Java called a Python function, and Python called an exposed Java object and a Java lambda.
- **Callbacks and events:** Python passed a callback that Java called three times, and Python sent events to a Java listener.
- **Runtime swaps:** the script was replaced at runtime with JavaScript from a string, then with a signed Lua bundle from a URL. Python, JavaScript and Lua ran side by side in one process.
- **Script screen:** a Lua screen was opened from Java and returned its result to `onActivityResult`.

**Not yet verified:**
- A physical phone and a 32-bit (armeabi-v7a) build.
- The `.aar` inside a real Gradle / Android Studio project. The example app uses a script that does the same steps as Gradle.
- After the move to on-device engines: camera, GPS and background jobs (re-tested only before the move), and Python packages beyond the standard library.
- A Play Console upload, the Docker image and the GitHub Actions pipeline.

## How it works

```
 APK: native renderer (Java)  ◀── JSON screen ──  engine: libvolt_<lang>.so  ──▶  your main.lua / .js / .py / .rb / .php
        │                                              ▲          │
        └── tap / change / result event ───────────────┘          └── android.* ──▶ device APIs and your plugins (JNI)

 optional: dev server ── code files ──▶ phone (live reload)        update host ── signed .zip ──▶ phone (OTA)
```

Each event calls your script's `update` and `view` inside the engine on the phone. The returned JSON becomes native views, and `_effect` keys trigger device actions. State is saved encrypted on the device after every event. When a script fails, a red error screen with the traceback appears. In dev builds, it clears as soon as you fix the code and save.

## Layout

```
volt.py              CLI, APK/AAB/AAR builder, dev server, releases, signed bundles
android/engine/      on-device engines: JNI bridge, per-language adapters, harnesses, prebuilt .so files
runtimes/harness.*   preview bridges used by the web IDE and tests
web/index.html       IDE + phone preview
android/src/         native renderer + the SDK (dev.volt.engine: Volt, VoltEngine, ScriptSource, VoltCallback)
android/stub/        tiny per-language JNI binding class (lets several languages run in one app)
examples/            android-host: an existing-app integration example
templates/           starter app for each language
apps/<app>/          main.<ext>, lib/, assets/, tests/
data/                runtime data (gitignored)
.github/workflows/   CI/CD pipeline
```

MIT licensed.
