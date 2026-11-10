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
- 🏢 **Built for teams**: roles, audit log, test-gated releases, instant deploy and rollback, CI/CD, Docker, metrics

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
| `VOLT_APP_*` | – | passed to scripts (secrets, API URLs). All other server env vars are hidden from scripts |
| `VOLT_KEYSTORE` / `VOLT_KEYSTORE_PASS` | generated | your release signing key for APKs |
| `ANDROID_HOME` | auto | only needed for building APKs (JDK + build-tools + a platform) |

**Ops endpoints:** `GET /healthz` (no auth), `GET /metrics` (Prometheus format: requests, errors, latency and workers per app and channel; needs a viewer token).

**Performance:** on a laptop, about 2,400 events/s with 50 concurrent clients, p50 15 ms, and no lost updates (each user session is serialized).

### Known limits (be honest with your security team)

- Scripts run as separate processes with a scrubbed environment and timeouts, but they are **not sandboxed** from the host filesystem. Treat app authors as trusted, and use the container setup for isolation.
- State lives in SQLite on a single node. Scale vertically or shard apps across nodes. Running several replicas of one app needs a shared state store, which is not built in yet.
- App logic runs on the server, so apps need a network connection. Native device APIs beyond toast, vibrate and open-URL are not exposed yet.

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
