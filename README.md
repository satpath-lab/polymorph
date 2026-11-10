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

## Quick start

```bash
python3 volt.py serve
```

Open **http://localhost:8787**, pick an app, and edit it. The phone preview updates as you type.

### Put it on a phone

1. In the IDE, set the server URL the phone can reach (your LAN IP, a public URL, or `http://10.0.2.2:8787` for the emulator).
2. Click **⬇ Build APK** and install it. From then on, edits appear on the phone instantly.

You can also build from the command line:

```bash
python3 volt.py apk todo-lua --server http://192.168.1.10:8787 --install
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

**Side effects:** set these keys on the state you return and they run once on the phone:
`_toast = "msg"`, `_vibrate = 40`, `_open = "https://…"`

**Screen:** `title`, `accent`, `bg`, `body`, `scroll` (default true), `hideBar`, `back` (the event id sent when the user presses the system back button)

### Components

| type | props |
|---|---|
| `column` / `row` / `card` / `hscroll` | `children`, `gap`, `align` (start/center/end) |
| `text` | `text`, `size`, `color`, `bold`, `italic`, `mono`, `align`, `lines`, `selectable` |
| `button` | `id`, `text`, `bg`, `color`, `size`, `radius`, `enabled` |
| `input` | `id`, `hint`, `value`, `kind` (text/number/email/password/phone), `multiline` |
| `switch` | `id`, `text`, `value` |
| `image` | `src` (a URL, or a file in `apps/<name>/assets/`), `fit` (cover/contain) |
| `progress` | `value` 0–1 (leave it out for a spinner), `color` |
| `spacer`, `divider` | `size` / `color` |

**Common props:** `padding` (a number, `[v,h]` or `[t,r,b,l]`), `margin`, `bg`, `radius`, `width`, `height` (a number, `"fill"` or `"wrap"`), `flex`, `opacity`, `visible`, and `id` on any container to make it tappable.

## Deploying (live, online)

Run `volt.py serve` on any server (VPS, Fly, Railway…) behind HTTPS. Set `VOLT_TOKEN=secret` so that only you can edit code, create apps or build APKs. Phones only need the public URL.

| env var | default | |
|---|---|---|
| `PORT` | 8787 | listen port |
| `VOLT_TOKEN` | – | required for edits, creating apps and building APKs |
| `VOLT_TIMEOUT` | 10 | maximum seconds per script call before the worker is restarted |
| `ANDROID_HOME` | auto-detected | needed only for building APKs (requires JDK + build-tools + a platform) |

## How it works

```
 phone (native renderer)  ──HTTP──▶  volt.py  ──stdin/stdout JSON──▶  warm worker (lua/python/php/node/ruby)
          ▲                             │ file watcher bumps the version
          └──────── long-poll /watch ◀──┘ every client re-renders instantly
```

Session state lives on the server, so a code reload swaps in the new logic while keeping each user's state. When a script fails, a red error screen with the traceback is pushed to every device, and it clears as soon as you fix the code.

## Layout

```
volt.py              server, CLI, APK builder
runtimes/harness.*   one tiny bridge per language
web/index.html       IDE + phone preview
android/             native renderer (plain Java, no AndroidX)
templates/           starter app for each language
apps/                your apps
```

MIT licensed.
