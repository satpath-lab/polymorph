# ⚡ VoltForge

**Build real native Android apps in Lua, Python, PHP, JavaScript or Ruby, and change them live from anywhere.**

You write three small functions in any of those languages. VoltForge runs your script on a server and turns its output into **real native Android views** (not a WebView). When you save, every installed phone updates in under a second, with no rebuild, no reinstall and no app-store review.

- 🧠 **5 languages**: Lua, Python, PHP, JavaScript (Node), Ruby
- 📱 **Native UI**: real `LinearLayout`, `Button`, `EditText`, `Switch`, `ImageView`…
- 🔥 **Hot reload over the network**: save, and every phone running the app re-renders. The app's state is kept.
- 🚀 **Fast**: each language runs in a persistent warm worker, so one event takes about 1 ms on the server
- 🧑‍💻 **Web IDE**: code editor, interactive phone preview, live logs, one-click **Build APK**
- 📦 **Signed APKs** with no Gradle and no Android Studio, built straight from the SDK tools
- 🪶 **Zero dependencies**: the server is one Python file that uses only the standard library
- 🧩 **Native plugins** in Java, Kotlin, C/C++ or any .jar, callable from every script language
- 🏢 **Built for teams**: roles, audit log, test-gated releases, instant deploy and rollback, CI/CD, Docker, metrics

## Why VoltForge

Teams that want an Android app usually either learn Kotlin and wait on Play Store review for every change, or ship a WebView that doesn't feel native. VoltForge sits in between:

- **Logic stays in the language your team already uses.** A Python data team, a PHP web shop, a Node backend team, or Lua and Ruby developers can ship a native app without learning Android.
- **Changes go live in seconds.** Screens and logic run on your server, so a bug fix or a new flow needs no new APK and no store review.
- **It stays native.** Screens are real Android views, and anything a script can't do can be written in Java, Kotlin or C and called from the script.

### Where it helps

| use case | why it fits |
|---|---|
| Internal company tools: field service, inventory, approvals, check-ins | camera, GPS, offline queue, roles and audit log built in; every employee's app updates instantly |
| Ops and admin dashboards on the phone | charts, big lists, push notifications, direct server-side data access |
| Prototypes and MVPs | one file per app, live browser preview, signed APK in one click |
| Apps whose rules change often (pricing, forms, workflows) | edit the script and every phone updates, no release needed |
| Teaching app development | five beginner-friendly languages with instant feedback |

It is **not** the right tool for games, heavy offline-first apps, or apps that must work without a server.

## Quick start

```bash
python3 volt.py serve
```

Open **http://localhost:8787**, pick an app, and edit it. The phone preview updates as you type.

### Put it on a phone

1. In the IDE, set the server URL the phone can reach (your LAN IP, a public URL, or `http://10.0.2.2:8787` for the emulator).
2. Choose `dev` (follows every save) or `prod` (runs only deployed releases), click **⬇ APK**, and install it.

You can also build from the command line:

```bash
python3 volt.py apk todo-lua --server https://volt.example.com --channel prod
python3 volt.py apk --server http://192.168.1.10:8787     # generic "player" app that can open any app on the server
```

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
| `_camera = "id"` | opens the camera; the photo is downscaled to ≤1600 px, rotated upright, and uploaded | `{url, type, size, name}`. Show it with `{type: "image", src: url}` |
| `_pick = {id, type}` | opens the system picker; `type` is `"image"`, `"any"` or a MIME type; the file is uploaded | `{url, type, size, name}` |
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

### Background jobs and server push (no Firebase)

- `_background = {every: 15}` schedules a periodic job (Android's minimum is 15 minutes) that runs even when the app is closed or after a reboot. Each run sends your script `{type: "background"}`, and any `_notify` / `_store` it returns is applied on the phone. `_background = {on: false}` cancels it.
- **Push:** from the IDE's Push tab, `POST /api/push/<app> {title, text, ch, session?}` (CI and backends with an API token), or from any script with `_push = {to: "*" | sessionId, title, text}`. Messages reach installed apps as notifications within seconds while the app is open, and on the next background run while it is closed.

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
    "cacheScreens": true,       "offlineQueue": true,     "push": true,
    "backgroundJobs": true,     "location": true,         "notifications": true
  }
}
```

| feature | on | off |
|---|---|---|
| `requireHttps` | production builds refuse `http://` servers, and plain HTTP is disabled in the APK | allows HTTP (for internal networks) |
| `encryptStorage` | screens cached on the phone, `_store` data and queued offline actions are encrypted with AES-256-GCM using a hardware-backed Android Keystore key | stored as plain text |
| `blockScreenshots` | screenshots and screen recording are blocked on every screen (`secure: true` does it per screen) | allowed |
| `cacheScreens` | the last screen is shown instantly on launch, even offline | nothing is kept on the phone |
| `offlineQueue` | taps made while offline are saved and replayed in order when the connection returns | offline taps show an error |
| `push`, `backgroundJobs` | inbox polling / scheduled background runs | disabled, and the boot permission is removed |
| `location`, `notifications` | the matching Android permissions are included | permissions are left out of the manifest |

Dev builds (`--channel dev`) may use `http://` so you can develop locally.

## Security

- **In transit:** HTTPS (required for production builds by default) plus optional **certificate pinning**. Run `python3 volt.py pin https://your-server` and put the printed pins in `app.json`. A server presenting a certificate that is not pinned is rejected before any data is sent. Pin your CA's intermediate certificate so routine certificate renewals keep working.
- **On the phone:** cached screens, device storage and the offline queue are encrypted with an Android Keystore AES-256-GCM key that cannot be exported. Screenshots can be blocked. Plain HTTP is disabled in HTTPS builds.
- **On the server:** PBKDF2 password hashes, tokens stored only as hashes, roles, an audit log, login lockout after 10 failures per IP within 10 minutes, `X-Frame-Options` / `nosniff` / `Referrer-Policy` headers, HSTS (`VOLT_HSTS=1` or set automatically behind an HTTPS proxy), and an optional device key.
- **Script sandbox:** with `VOLT_SANDBOX=1`, scripts run under `sandbox-exec` (macOS) or `bubblewrap` (Linux). A script can read its own app folder but not the database, other apps or `~/.ssh`, and cannot write files. `VOLT_SANDBOX_NET=0` also blocks network access.

## Play Store

- `app.json` sets the package ID, version, icon, orientation and SDK levels. `targetSdk` defaults to 35, which Google Play requires.
- Build an **Android App Bundle** with `python3 volt.py apk myapp --server https://… --format aab`, or pick `.aab` in the IDE.
- Bundles need Google's `bundletool`. Download `bundletool-all-<version>.jar` from github.com/google/bundletool/releases and set `VOLT_BUNDLETOOL` to its path.
- Use your own signing key with `VOLT_KEYSTORE` / `VOLT_KEYSTORE_PASS`.

## Team workflow

```
 edit (IDE or your own editor + git)  ──▶  dev channel: live on dev APKs and the preview
                │
         tests/*.json pass?  ──no──▶  release blocked
                │ yes
        release rN (immutable snapshot)  ──admin deploys──▶  prod channel: every prod APK updates
                                                     ◀── one-click rollback to any rN
```

- **Two channels.** `dev` runs the working tree and reloads on every save. `prod` runs only an immutable release snapshot. Build a dev APK for your team and a prod APK for users.
- **Releases.** A release copies the app into `data/releases/<app>/<n>`. It is created only if every test passes. An admin can force one, and that is recorded in the audit log.
- **Deploy and rollback** happen instantly over the air, with no store review. State is kept per user.
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

Put it behind HTTPS (Caddy, nginx, or a cloud load balancer). The container runs as a non-root user on a read-only filesystem with CPU and memory limits. Data (SQLite database, releases, signing key) lives in the `/data` volume, so back that up.

| env var | default | |
|---|---|---|
| `PORT` | 8787 | listen port |
| `VOLT_DATA` | `./data` | SQLite DB, releases, keystore, build cache |
| `VOLT_ADMIN_PASSWORD` / `VOLT_ADMIN_USER` | – | creates the first admin on startup if there are no users |
| `VOLT_WORKERS` | 4 | warm interpreter processes per app per channel |
| `VOLT_TIMEOUT` | 10 | seconds per script call before the worker is killed |
| `VOLT_DEVICE_KEY` | – | shared key baked into APKs. Without it, run/watch calls are rejected |
| `VOLT_SESSION_DAYS` | 14 | IDE login lifetime |
| `VOLT_MAX_UPLOAD_MB` | 15 | maximum size of a camera or file upload |
| `VOLT_UPLOAD_DAYS` | 30 | delete uploads older than this (0 = never) |
| `VOLT_SANDBOX` / `VOLT_SANDBOX_NET` | off / 1 | run scripts in an OS sandbox / allow network inside it |
| `VOLT_PINS` | – | default certificate pins for every app |
| `VOLT_BUNDLETOOL` | – | path to bundletool, for `.aab` builds |
| `VOLT_HSTS` | – | send the HSTS header |
| `VOLT_ABIS` | `arm64-v8a,x86_64` | CPU architectures to compile C/C++ plugins for |
| `ANDROID_NDK_HOME` | auto | NDK location for C/C++ plugins |
| `VOLT_APP_*` | – | passed to scripts (secrets, API URLs). All other server env vars are hidden from scripts |
| `VOLT_KEYSTORE` / `VOLT_KEYSTORE_PASS` | generated | your release signing key for APKs |
| `ANDROID_HOME` | auto | only needed for building APKs (JDK + build-tools + a platform) |

**Ops endpoints:** `GET /healthz` (no auth), `GET /metrics` (Prometheus format: requests, errors, latency and workers per app and channel; needs a viewer token).

**Performance:** on a laptop, about 2,400 events/s with 50 concurrent clients, p50 15 ms, and no lost updates (each user session is serialized).

### Known limits (be honest with your security team)

- The script sandbox is opt-in (`VOLT_SANDBOX=1`). Without it, scripts can read anything the server user can.
- Encryption between the app and server is TLS. There is no separate payload encryption layer, because a key baked into an APK can be extracted, so pinning adds more real protection.
- State lives in SQLite on a single node. Scale vertically or shard apps across nodes. Running several replicas of one app needs a shared state store, which is not built in yet.
- App logic runs on the server. Offline, the app shows the last cached screen and queues taps (`offlineQueue`), but nothing new is computed until it reconnects.
- Push without Firebase is polling-based. Delivery to a closed app waits for the next background run (Android allows one every 15 minutes or more), so it is not instant like FCM. For instant delivery, add the Firebase SDK as a plugin jar.
- Not built in, but available through plugins: NFC, QR scanning (drop a ZXing jar into `native/libs`), payments, maps SDKs and other vendor SDKs.
- Uploads are stored in `data/uploads/` under unguessable names and deleted after `VOLT_UPLOAD_DAYS` (default 30; `0` keeps them forever).

## Test status

- **Verified on an Android 15 emulator:** every component, styling, tabs, dialogs, charts, canvas, web views, Java/Kotlin/C plugins, camera, GPS, notifications and reminders, device storage, offline cache and offline queue replay, background jobs, push to a closed app, Bluetooth permissions, infinite lists, live reload.
- **Verified `.aab` builds:** a bundle built with bundletool 1.18.3 passes `bundletool validate`, is signed, and converts to an installable APK with the right package, version and SDK levels.
- **Verified on the server:** all app tests (`volt.py test --all`), sandbox isolation, HTTPS enforcement for production builds, about 2,400 events/s under load.
- **Not yet verified:** video playback on a real network, a Play Console upload, the Docker image, and the GitHub Actions pipeline. Reports and pull requests welcome.

## How it works

```
 phone (native renderer)  ──HTTP──▶  volt.py  ──stdin/stdout JSON──▶  warm worker (lua/python/php/node/ruby)
          ▲                             │ file watcher bumps the version
          └──────── long-poll /watch ◀──┘ every client re-renders instantly
```

Session state lives on the server, so a code reload swaps in the new logic while keeping each user's state. When a script fails, a red error screen with the traceback is pushed to every device, and it clears as soon as you fix the code.

## Layout

```
volt.py              server, CLI, APK builder, releases, auth
runtimes/harness.*   one tiny bridge per language
web/index.html       IDE + phone preview
android/             native renderer (plain Java, no AndroidX)
templates/           starter app for each language
apps/<app>/          main.<ext>, lib/, assets/, tests/
data/                runtime data (gitignored)
.github/workflows/   CI/CD pipeline
```

MIT licensed.
