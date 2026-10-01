package com.opencode.persistentbrowser.util

/**
 * JavaScript injected at document start (via WebViewCompat.addDocumentStartJavaScript)
 * to observe the page's EventSource connections without altering page behavior.
 *
 * It wraps window.EventSource to record open/error/message events into
 * window.__kb, which the app reads via evaluateJavascript to determine whether
 * the page's live channel is alive.
 */
object EventSourceObserver {

    val SCRIPT: String = """
(function () {
  if (window.__kb) return;
  var kb = window.__kb = {
    eventSourceUsed: false,
    openCount: 0,
    anyOpen: false,
    lastMessageTime: 0,
    lastErrorTime: 0,
    instances: []
  };
  var Orig = window.EventSource;
  if (!Orig) return;
  function Wrapped(url, init) {
    kb.eventSourceUsed = true;
    var es = new Orig(url, init);
    kb.openCount++;
    kb.instances.push(es);
    es.addEventListener('open', function () {
      kb.anyOpen = true;
    });
    es.addEventListener('message', function (e) {
      kb.lastMessageTime = Date.now();
      try { kb.lastEventId = e.lastEventId; } catch (err) {}
    });
    es.addEventListener('error', function () {
      kb.lastErrorTime = Date.now();
      kb.anyOpen = false;
    });
    return es;
  }
  Wrapped.prototype = Orig.prototype;
  Wrapped.CONNECTING = Orig.CONNECTING;
  Wrapped.OPEN = Orig.OPEN;
  Wrapped.CLOSED = Orig.CLOSED;
  window.EventSource = Wrapped;
})();
""".trimIndent()

    /**
     * JS expression that returns the observer state as an object.
     * evaluateJavascript serializes the returned object to JSON automatically.
     */
    val READ_STATE: String = """
(function () {
  var kb = window.__kb;
  if (!kb) return { eventSourceUsed: false };
  var anyOpen = false;
  for (var i = 0; i < kb.instances.length; i++) {
    try { if (kb.instances[i].readyState === 1) { anyOpen = true; break; } } catch (e) {}
  }
  return {
    eventSourceUsed: !!kb.eventSourceUsed,
    openCount: kb.openCount || 0,
    anyOpen: anyOpen,
    lastMessageTime: kb.lastMessageTime || 0,
    lastErrorTime: kb.lastErrorTime || 0
  };
})();
""".trimIndent()
}
