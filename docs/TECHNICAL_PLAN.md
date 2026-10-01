# Technical Plan — OpenCode Persistent Browser

## 0. Executive summary

The OpenCode web UI goes "dead" in Chrome on Android because Chrome's renderer and
network stack are suspended or killed when the app is backgrounded, and the page's
live channel (Server-Sent Events) is not guaranteed to survive that suspension.

This app is a **single-URL persistent browser** whose primary job is to keep one
OpenCode session alive and synchronized. It does this with three cooperating layers:

1. **UI layer** — a WebView that renders the OpenCode web UI.
2. **Persistent connection layer** — a foreground service that holds the process
   alive and maintains its own SSE monitor against the OpenCode server while the
   app is backgrounded, tracking a sync checkpoint.
3. **Cache/state layer** — HTTP-correct WebView caching, DOM storage, cookies,
   and a DataStore checkpoint used to recover quickly after process death.

The key insight: **the WebView alone cannot survive Android's background
restrictions, but a foreground service can keep the process (and therefore the
WebView renderer) alive, and a service-side SSE monitor gives us a ground-truth
checkpoint to detect and repair a stale page.**

---

## 1. Investigation: how the OpenCode web UI actually communicates

Investigated the locally installed OpenCode 1.18.30 (`@opencode-ai/sdk` v2 and
the `opencode` binary).

| Mechanism | Endpoint | Purpose |
|---|---|---|
| **SSE (Server-Sent Events)** | `GET /event` | Primary live-state channel ("Subscribe to events using server-sent events") |
| SSE | `GET /global/event` | Global system events |
| SSE | `GET /api/event` | Native event payloads |
| SSE | `GET /api/session/{id}/event?after={seq}` | Durable session events — **replays events after a sequence, then streams new ones** |
| REST | `GET /api/session/{id}/history?after=&limit=` | Finite page of durable session events (catch-up) |
| REST | `GET /global/health`, `/config`, `/session`, … | State fetch / reconciliation |
| WebSocket | PTY endpoint | Terminal I/O only (not the main event channel) |

Critical findings:

- **SSE is the live mechanism**, not WebSockets. The web UI's EventSource
  connections carry the session state.
- The SDK's SSE client **sends `Last-Event-ID` on every reconnect** and parses
  `id:` lines from the stream. This means the server supports **event replay** —
  a reconnecting client can catch up on events it missed.
- The durable session endpoint (`/api/session/{id}/event?after=seq`) explicitly
  supports replay-after-sequence, so missed events are recoverable, not lost.
- The web UI is served from the **same origin** as the API (SDK uses relative
  URLs), so the monitor can derive API endpoints from the loaded URL's origin.

**Implication for the app:** the persistent connection layer must preserve SSE
semantics — maintain an SSE connection, track the last event id, reconnect with
`Last-Event-ID`, and use REST history for catch-up when the SSE stream cannot
replay.

---

## 2. Android lifecycle analysis

| State | What happens to a plain WebView app | What this app does |
|---|---|---|
| **Foreground** | Page live, SSE live, timers run | WebView renders; service idles (no duplicate SSE traffic); status = Connected |
| **Backgrounded (minimized)** | Process may be killed after minutes; renderer paused; SSE drops | Foreground service keeps process alive; service opens its own SSE monitor with checkpoint |
| **Screen locked** | Doze may suspend network after a while | Service reconnects on network-callback / maintenance windows; no wakelock (battery) |
| **Network change (Wi-Fi ↔ mobile)** | SSE drops; page may not recover | `ConnectivityManager` default-network callback triggers immediate reconnect with backoff reset |
| **Process killed (not force-stop)** | Everything gone | `START_STICKY` restarts service; DataStore restores URL + checkpoint; WebView `saveState` restores navigation |
| **Force-stop / swiped from recents** | Nothing runs; no restart | Documented limitation — user must reopen the app |
| **Reopened** | Cold start | Restore last URL, restore WebView state, reconnect, reconcile, then mark current |

---

## 3. Architecture

```
┌─────────────────────────────────────────────────────────┐
│  UI layer (MainActivity)                                │
│  ┌──────────────┐  ┌──────────────────────────────────┐ │
│  │ Home screen  │  │ Browser screen                   │ │
│  │ URL input +  │  │ status chip + WebView            │ │
│  │ LOAD button  │  │ (EventSource observer injected)  │ │
│  └──────────────┘  └──────────────────────────────────┘ │
└───────────────┬─────────────────────────────────────────┘
                │ StateFlow<SessionState>
┌───────────────▼─────────────────────────────────────────┐
│  Session controller (singleton)                         │
│  - owns URL, visibility flag, reconciliation decisions  │
│  - exposes state to UI and service                      │
└───────────────┬─────────────────────────────────────────┘
                │
┌───────────────▼─────────────────────────────────────────┐
│  Persistent connection layer                            │
│  SessionKeepaliveService (foreground, specialUse)       │
│  - SSE monitor (OkHttp) with Last-Event-ID + backoff    │
│  - network callback → reconnect / offline detection      │
│  - health probe fallback for non-SSE origins            │
│  - checkpoint → DataStore                               │
└───────────────┬─────────────────────────────────────────┘
                │
┌───────────────▼─────────────────────────────────────────┐
│  Cache / local state layer                              │
│  - WebView HTTP cache (LOAD_DEFAULT, ETag/304)          │
│  - DOM storage + cookies (CookieManager)                │
│  - OkHttp cache for monitor probes (conditional GET)    │
│  - DataStore: lastUrl, sessionActive, checkpoint         │
│  - WebView.saveState for navigation restore             │
└─────────────────────────────────────────────────────────┘
```

### Why a foreground service (and not WorkManager / a thread)

- **WorkManager** is for deferrable work; it cannot hold a live SSE stream and is
  subject to Doze and execution windows. Wrong tool.
- **A plain thread** dies with the process; it cannot prevent the process death
  that is the root cause.
- **A foreground service** is the only mechanism that (a) keeps the process in a
  protected state while backgrounded, (b) can hold a live network stream, and
  (c) is user-visible/transparent via a notification. It is justified here
  because the user's explicit requirement is "keep the session alive while
  backgrounded."

Service type: `specialUse` (Android 14+ requires a declared FGS type;
`specialUse` is the honest classification for a keepalive/monitor service).

### Why the service SSE is gated on backgrounded state

While the app is **foregrounded**, the WebView's own SSE is authoritative and
the service stays idle — zero duplicate traffic. When the app is
**backgrounded**, the service opens its own SSE monitor. This gives us:

- a ground-truth checkpoint (last event id/time) to detect a stale page,
- server-side connection warmth,
- immediate knowledge of disconnects and network loss.

---

## 4. Background persistence strategy

1. **Start** the foreground service when a URL is loaded; stop it when the user
   stops the session (notification action / back to home).
2. **Notification**: persistent, low-importance, with the URL (host only, no
   credentials) and a "Stop session" action.
3. **SSE monitor loop** (only while backgrounded):
   - connect to `{origin}/event` (fallback `/api/event`, `/global/event`),
   - send `Last-Event-ID` from the last checkpoint,
   - parse `id:`/`event:`/`data:` lines,
   - on stream end/error → exponential backoff (1s → 2s → … → cap 60s),
   - reset backoff on a fresh network callback,
   - cancel cleanly when the session stops.
4. **Network awareness**: `registerDefaultNetworkCallback` →
   - `onAvailable` → immediate reconnect attempt (backoff reset),
   - `onLost` → mark Offline, stop reconnecting until network returns.
5. **No wakelock**: in deep Doze the OS suspends network regardless; holding a
   wakelock would drain battery for no gain. We reconnect on Doze maintenance
   windows and on `onAvailable`.
6. **Fallback health probe**: if no SSE endpoint responds (non-OpenCode URL),
   use a conditional HTTP GET (`If-None-Match`/`If-Modified-Since`) on the main
   URL with adaptive backoff (30s → … → 10 min) only while disconnected, to
   detect server recovery and drive the status chip.

---

## 5. Smart caching strategy

The cache **follows HTTP semantics** rather than inventing a custom scheme, so
freshness is governed by the server's own headers.

| Layer | Policy |
|---|---|
| **WebView HTTP cache** | `LOAD_DEFAULT` — static assets (JS/CSS/images) cached per `Cache-Control`/`ETag`; dynamic responses revalidated or bypassed per `no-store`. This is the correct default and requires no custom interception. |
| **DOM storage / cookies** | Enabled. OpenCode stores prefs/state in `localStorage`; session cookies keep the session authenticated. `CookieManager.flush()` on pause. |
| **Monitor probe cache** | OkHttp `Cache` (10 MB) for conditional GETs → 304s cost almost nothing. |
| **App state checkpoint** | DataStore: `lastUrl`, `sessionActive`, `lastEventId`, `lastEventTime`. No secrets. |
| **Navigation state** | `WebView.saveState()` → restored after process death. |

**Stale-state prevention** (the critical requirement):

- The app never renders cached HTML itself and never claims "current" without
  verification.
- Status chip states: `Connected`, `Synchronizing…`, `Reconnecting…`, `Offline`.
- `Connected` is shown only when the page's live channel is confirmed open
  (via an injected EventSource observer) **and** the service monitor agrees.
- On resume after backgrounding, the app reconciles: if the service recorded
  events/disconnects while away, it checks the page's observer; if the page's
  EventSource is not open, it reloads the page (warm HTTP cache makes this
  cheap) and waits for the live channel before flipping to `Connected`.

---

## 6. WebView configuration

- JavaScript enabled, DOM storage enabled, cookies enabled (incl. third-party).
- `LOAD_DEFAULT` cache mode; `setSaveFormData`/`setSavePassword` off.
- `onRenderProcessGone` → recreate the WebView and reload (renderer crash
  recovery).
- SSL errors → default cancel + concise user message (no certificate override).
- Mixed content → default (blocked for https pages); cleartext permitted only
  because local OpenCode instances are typically `http://` on a LAN — TLS
  validation is never weakened.
- EventSource observer injected at document start via
  `WebViewCompat.addDocumentStartJavaScript` (guarded by feature check) to
  observe page connection freshness without altering page behavior.

---

## 7. Session persistence & process death

- **DataStore** persists `lastUrl`, `sessionActive`, and the monitor checkpoint.
- **Cold start**: if `sessionActive`, auto-resume the browser (status
  `Synchronizing…`) instead of showing the home screen.
- **Process death**: `WebView.saveState()` restores the navigation stack;
  service `START_STICKY` restarts the monitor; reconciliation runs on resume.
- **Force-stop**: no automatic recovery (documented).

---

## 8. Battery & data efficiency

- Event-driven SSE; no polling loops.
- Service SSE only while backgrounded (no duplicate stream while foregrounded).
- No wakelocks.
- Backoff with caps; no reconnect attempts while offline.
- Conditional requests for probes; HTTP cache for statics.

---

## 9. Error handling

Malformed URL, unreachable server, DNS failure, timeout, TLS error, renderer
crash, WebSocket/SSE failure, 4xx/5xx, no internet → concise human-readable
message in the UI; detailed diagnostics in logcat only. No stack traces in UI.

---

## 10. Security

- HTTPS preferred; cleartext allowed only for local/LAN OpenCode instances.
- TLS validation never weakened; SSL errors cancel by default.
- No logging of cookies, headers, or tokens.
- Only the launcher activity is exported; service is not exported.
- DataStore holds no secrets (URL + checkpoint only).

---

## 11. Tech stack

Kotlin, AGP 8.6.1, Gradle 8.9, Kotlin 2.0.20, compileSdk/targetSdk 35, minSdk 26.
Views + XML (Material 3), ViewModel + StateFlow + coroutines, DataStore,
OkHttp (monitor), androidx.webkit (document-start JS), ConnectivityManager.

---

## 12. Testing & CI

- Unit tests: URL validation/normalization, SSE parser, backoff calculator,
  staleness/reconciliation decision logic.
- GitHub Actions: checkout → JDK 21 → Gradle → lint + unit tests + assembleDebug
  → upload APK artifact.

---

## 13. Honest limitations

- **Force-stop / "swipe away"**: Android guarantees nothing; the app cannot run
  until reopened.
- **Deep Doze**: network is suspended by the OS; the monitor reconnects on
  maintenance windows and on network callbacks.
- **OEM aggressive battery killers**: may kill even foreground services on some
  devices; the notification and `START_STICKY` mitigate but cannot guarantee.
- **Server-side**: if the OpenCode server does not honor `Last-Event-ID` replay,
  missed events are recovered via REST history catch-up instead.
