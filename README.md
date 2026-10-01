# OpenCode Persistent Browser

A single-URL Android browser whose primary purpose is to keep an **OpenCode web
UI session alive and synchronized** — even when the app is minimized, left in the
background for hours, loses network, or is killed by the OS.

## Why this exists

Opening the OpenCode web UI in Chrome on Android and then backgrounding it leads
to a stale/dead UI: Chrome's renderer and network stack are suspended or killed,
and the page's live channel (Server-Sent Events) does not survive. This app is
built specifically to prevent that.

## How it works

Three cooperating layers:

1. **UI layer** — a WebView rendering the OpenCode web UI, with a subtle status
   chip (`Connected` / `Synchronizing…` / `Reconnecting…` / `Offline`).
2. **Persistent connection layer** — a foreground service
   (`SessionKeepaliveService`) that keeps the process alive and maintains its own
   SSE monitor against the OpenCode server while the app is backgrounded,
   tracking a sync checkpoint (last event id/time).
3. **Cache/state layer** — HTTP-correct WebView caching, DOM storage, cookies,
   and a DataStore checkpoint for fast recovery after process death.

The service SSE monitor is **gated on backgrounded state**: while you watch, the
WebView's own SSE is authoritative and the service stays idle (no duplicate
traffic). When you leave, the service opens its own SSE monitor, so it always
knows the ground truth and can detect/repair a stale page on return.

## Features

- Single-URL persistent browser (no tabs, no bookmarks, no history UI).
- Dark, minimal, polished UI.
- Clipboard auto-paste: tapping the URL field pastes a valid URL from the
  clipboard.
- Foreground service keeps the process (and WebView renderer) alive in the
  background.
- Service-side SSE monitor with `Last-Event-ID` replay and exponential backoff.
- Network-change handling via `ConnectivityManager` (Wi-Fi ↔ mobile, offline →
  online).
- Smart caching: WebView HTTP cache (ETag/304), DOM storage, cookies, OkHttp
  cache for probes. Dynamic state is never shown as current without
  verification.
- Stale-state prevention: on resume, the app reconciles the page's live channel
  with the monitor checkpoint and reloads only if the page is stale.
- Process-death recovery: restores the last URL, WebView state, and reconnects.
- Renderer-crash recovery via `onRenderProcessGone`.
- Battery/data efficient: event-driven SSE, no polling loops, no wakelocks,
  conditional requests.

## Building

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Requirements

- Android 8.0+ (API 26+)
- JDK 21 to build
- Android SDK 35

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and
[docs/TECHNICAL_PLAN.md](docs/TECHNICAL_PLAN.md).

## Limitations (honest)

- **Force-stop / swipe from recents**: Android guarantees nothing; the app
  cannot run until reopened.
- **Deep Doze**: the OS suspends network; the monitor reconnects on maintenance
  windows and network callbacks.
- **OEM aggressive battery killers** may kill even foreground services on some
  devices.
- See the technical plan for the full matrix.
