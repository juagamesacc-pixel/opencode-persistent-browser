# Architecture

## Overview

```
┌─────────────────────────────────────────────────────────┐
│  UI layer (MainActivity)                                │
│  Home screen (URL input + LOAD) │ Browser screen        │
│  (status chip + WebView + EventSource observer)         │
└───────────────┬─────────────────────────────────────────┘
                │ StateFlow<SessionState>
┌───────────────▼─────────────────────────────────────────┐
│  Session controller (singleton)                         │
│  URL, visibility flag, reconciliation decisions         │
└───────────────┬─────────────────────────────────────────┘
                │
┌───────────────▼─────────────────────────────────────────┐
│  Persistent connection layer                            │
│  SessionKeepaliveService (foreground, specialUse)       │
│  SSE monitor (OkHttp) + Last-Event-ID + backoff         │
│  network callback → reconnect / offline detection        │
│  health probe fallback for non-SSE origins              │
└───────────────┬─────────────────────────────────────────┘
                │
┌───────────────▼─────────────────────────────────────────┐
│  Cache / local state layer                              │
│  WebView HTTP cache (LOAD_DEFAULT, ETag/304)            │
│  DOM storage + cookies (CookieManager)                  │
│  OkHttp cache for monitor probes (conditional GET)      │
│  DataStore: lastUrl, sessionActive, checkpoint           │
│  WebView.saveState for navigation restore             │
└─────────────────────────────────────────────────────────┘
```

## Components

### MainActivity
- Home screen: URL input (with clipboard auto-paste) + LOAD URL button.
- Browser screen: status chip + WebView.
- Configures the WebView (JS, DOM storage, cookies, LOAD_DEFAULT cache).
- Injects the EventSource observer at document start.
- Drives foreground/background tracking and reconciliation on resume.
- Handles renderer crashes and process-death state restore.

### SessionController (singleton)
- Single source of truth for `SessionState`, exposed as `StateFlow`.
- Tracks app visibility (gates the service SSE monitor).
- Accepts monitor updates from the service.
- Runs reconciliation when the app returns to the foreground.

### SessionKeepaliveService (foreground, specialUse)
- Keeps the process alive while a session is active.
- While backgrounded: maintains an SSE monitor against the OpenCode server,
  tracking the last event id/time as a sync checkpoint.
- Reconnects with exponential backoff; resets backoff on network change.
- Falls back to a slow conditional health probe for origins without SSE.
- Reports state to the SessionController.

### SseParser
- Incremental SSE wire-format parser (id/event/data, multi-line data, comments).

### Staleness (pure logic)
- Decides `PAGE_LITE` / `PAGE_UNKNOWN` / `RELOAD_REQUIRED` from the page
  observer + monitor checkpoint, so the UI is never marked current while stale.

### EventSourceObserver
- JS injected at document start to observe the page's EventSource connections
  (open/error/message) without altering page behavior.

### SessionStore (DataStore)
- Persists lastUrl, sessionActive, and the monitor checkpoint. No secrets.

## Data flow

1. User loads a URL → WebView renders the OpenCode UI; service starts.
2. App backgrounded → service opens its SSE monitor; checkpoint advances.
3. App foregrounded → controller reconciles:
   - page's EventSource open + monitor agrees → `Connected`
   - page stale/dead → reload (warm cache) → wait for live channel → `Connected`
   - page unknown → `Synchronizing…` (grace period)
4. Network lost → `Offline`; network returns → `Reconnecting…` → `Connected`.

## Why this solves the problem

- The **foreground service** prevents the process death that kills the WebView
  renderer (the root cause of the "dead UI").
- The **service-side SSE monitor** provides a ground-truth checkpoint so a
  stale page is detected and repaired, not shown as current.
- **HTTP-correct caching** makes reloads cheap without risking stale dynamic
  state.
- **Event-driven design** (SSE + network callbacks) avoids battery-draining
  polling loops.
