package com.opencode.persistentbrowser.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.opencode.persistentbrowser.App
import com.opencode.persistentbrowser.MainActivity
import com.opencode.persistentbrowser.R
import com.opencode.persistentbrowser.net.SseParser
import com.opencode.persistentbrowser.util.Backoff
import com.opencode.persistentbrowser.util.UrlUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Foreground service that keeps the OpenCode session alive while the app is
 * backgrounded.
 *
 * Responsibilities:
 *  - hold the process in a protected (foreground) state so the WebView renderer
 *    and its SSE connections survive backgrounding
 *  - maintain a service-side SSE monitor against the OpenCode server while
 *    backgrounded, tracking a sync checkpoint (last event id/time)
 *  - detect network loss / recovery via ConnectivityManager and reconnect with
 *    exponential backoff
 *  - fall back to a slow conditional health probe for origins without SSE
 *  - report state to the SessionController
 *
 * The SSE monitor is gated on the app being backgrounded: while foregrounded the
 * WebView's own SSE is authoritative and the service stays idle (no duplicate
 * traffic).
 */
class SessionKeepaliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var probeJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var client: OkHttpClient
    private val backoff = Backoff(baseDelayMs = 1_000, maxDelayMs = 60_000)

    private var currentUrl: String? = null
    private var sseEndpoint: String? = null
    private var lastEventId: String? = null
    private var lastEventTime: Long = 0L
    private var eventsWhileAway: Int = 0
    private var disconnectedWhileAway: Boolean = false
    private var monitorConnected = false

    /** The in-flight OkHttp call, cancelled to unblock a blocked SSE read. */
    @Volatile
    private var currentCall: Call? = null

    private val controller get() = App.instance.sessionController
    private val store get() = App.instance.sessionStore

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cacheDir = File(cacheDir, "okhttp").apply { mkdirs() }
        client = OkHttpClient.Builder()
            .cache(Cache(cacheDir, 10L * 1024 * 1024))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // SSE: no read timeout
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
        createChannel()
        registerNetworkCallback()
        // Gate the SSE monitor on backgrounded state.
        scope.launch {
            controller.foregrounded.collect { foregrounded ->
                if (foregrounded) {
                    onAppForegrounded()
                } else {
                    onAppBackgrounded()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentUrl = intent.getStringExtra(EXTRA_URL)
                lastEventId = intent.getStringExtra(EXTRA_LAST_EVENT_ID)
                startForegroundCompat()
                // If the app is already backgrounded, start monitoring immediately.
                if (!controller.appForegrounded) startMonitoring()
            }
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopEverything()
        unregisterNetworkCallback()
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // Foreground notification
    // ------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Session keepalive",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the OpenCode session alive while backgrounded"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SessionKeepaliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val host = currentUrl?.let { UrlUtils.displayHost(it) } ?: "session"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenCode session active")
            .setContentText("Keeping $host alive")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Stop session", stopIntent)
            .build()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ------------------------------------------------------------------
    // Network awareness
    // ------------------------------------------------------------------

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    backoff.reset()
                    if (!controller.appForegrounded) reconnectNow()
                }
            }

            override fun onLost(network: Network) {
                disconnectedWhileAway = true
                monitorConnected = false
                controller.onNetworkLost()
            }
        }
        networkCallback = cb
        connectivityManager.registerDefaultNetworkCallback(cb)
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        networkCallback = null
    }

    // ------------------------------------------------------------------
    // Monitoring
    // ------------------------------------------------------------------

    private fun startMonitoring() {
        if (monitorJob?.isActive == true || probeJob?.isActive == true) return
        scope.launch {
            val url = currentUrl ?: return@launch
            val parsed = UrlUtils.parse(url) ?: return@launch
            // Try to discover an SSE endpoint on the same origin.
            sseEndpoint = discoverSseEndpoint(parsed.origin)
            if (sseEndpoint != null) {
                monitorJob = launch { runSseLoop(sseEndpoint!!) }
                monitorJob?.invokeOnCompletion { currentCall?.cancel() }
            } else {
                // No SSE endpoint: fall back to a slow conditional health probe.
                probeJob = launch { runProbeLoop(url) }
            }
        }
    }

    private fun reconnectNow() {
        scope.launch {
            if (sseEndpoint != null) {
                monitorJob?.cancel()
                monitorJob = launch { runSseLoop(sseEndpoint!!) }
            } else if (currentUrl != null) {
                probeJob?.cancel()
                probeJob = launch { runProbeLoop(currentUrl!!) }
            }
        }
    }

    /** Called by the controller when the app is backgrounded/foregrounded. */
    fun onAppBackgrounded() {
        backoff.reset()
        startMonitoring()
    }

    fun onAppForegrounded() {
        monitorJob?.cancel()
        probeJob?.cancel()
        monitorJob = null
        probeJob = null
    }

    private fun stopEverything() {
        monitorJob?.cancel()
        probeJob?.cancel()
        monitorJob = null
        probeJob = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    /**
     * Probe candidate SSE endpoints on the origin. Returns the first that
     * responds with text/event-stream, or null if none do.
     */
    private suspend fun discoverSseEndpoint(origin: String): String? {
        val candidates = listOf("$origin/event", "$origin/api/event", "$origin/global/event")
        for (endpoint in candidates) {
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .header("Accept", "text/event-stream")
                    .header("Cache-Control", "no-cache")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    val contentType = response.header("Content-Type") ?: ""
                    if (response.isSuccessful && contentType.contains("text/event-stream")) {
                        Log.i(TAG, "SSE endpoint discovered: $endpoint")
                        return endpoint
                    }
                }
            } catch (e: Exception) {
                // Try next candidate.
            }
        }
        return null
    }

    /**
     * SSE monitor loop. Connects, reads the stream, parses events, updates the
     * checkpoint, and reconnects with backoff on failure.
     */
    private suspend fun runSseLoop(endpoint: String) {
        val parser = SseParser()
        while (currentCoroutineContext().isActive) {
            if (!isNetworkAvailable()) {
                delay(backoff.nextDelayMs())
                continue
            }
            monitorConnected = false
            val request = Request.Builder()
                .url(endpoint)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .apply { lastEventId?.let { header("Last-Event-ID", it) } }
                .get()
                .build()
            val call = client.newCall(request)
            currentCall = call
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "SSE connect failed: ${response.code}")
                        disconnectedWhileAway = true
                        delay(backoff.nextDelayMs())
                        return@use
                    }
                    monitorConnected = true
                    backoff.reset()
                    store.saveMonitorConnectedAt(System.currentTimeMillis())
                    controller.onMonitorState(true, lastEventTime, eventsWhileAway, disconnectedWhileAway)
                    val body = response.body ?: return@use
                    val source = body.source()
                    val buf = ByteArray(8192)
                    while (currentCoroutineContext().isActive) {
                        val read = source.read(buf)
                        if (read == -1) break
                        val events = parser.feed(String(buf, 0, read, Charsets.UTF_8))
                        for (ev in events) {
                            lastEventTime = System.currentTimeMillis()
                            ev.id?.let { lastEventId = it }
                            eventsWhileAway++
                            store.saveCheckpoint(ev.id, lastEventTime)
                        }
                        if (events.isNotEmpty()) {
                            controller.onMonitorState(
                                true, lastEventTime, eventsWhileAway, disconnectedWhileAway
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                if (currentCoroutineContext().isActive) {
                    Log.w(TAG, "SSE error: ${e.javaClass.simpleName}")
                    monitorConnected = false
                    disconnectedWhileAway = true
                    controller.onMonitorState(false, lastEventTime, eventsWhileAway, disconnectedWhileAway)
                }
            } finally {
                currentCall = null
            }
            if (currentCoroutineContext().isActive) {
                delay(backoff.nextDelayMs())
            }
        }
    }

    /**
     * Fallback health probe for origins without an SSE endpoint. Uses a
     * conditional GET (If-None-Match / If-Modified-Since) so a 304 costs almost
     * nothing. Runs only while disconnected, with adaptive backoff.
     */
    private suspend fun runProbeLoop(url: String) {
        var etag: String? = null
        var lastModified: String? = null
        var delayMs = 30_000L
        while (currentCoroutineContext().isActive) {
            if (!isNetworkAvailable()) {
                delay(delayMs)
                continue
            }
            try {
                val request = Request.Builder()
                    .url(url)
                    .apply {
                        etag?.let { header("If-None-Match", it) }
                        lastModified?.let { header("If-Modified-Since", it) }
                    }
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> {
                            monitorConnected = true
                            disconnectedWhileAway = false
                            backoff.reset()
                            delayMs = 30_000L
                            etag = response.header("ETag")
                            lastModified = response.header("Last-Modified")
                            controller.onMonitorState(true, System.currentTimeMillis(), 0, false)
                        }
                        304 -> {
                            monitorConnected = true
                            disconnectedWhileAway = false
                            backoff.reset()
                            controller.onMonitorState(true, System.currentTimeMillis(), 0, false)
                        }
                        else -> {
                            monitorConnected = false
                            disconnectedWhileAway = true
                            controller.onMonitorState(false, lastEventTime, 0, true)
                        }
                    }
                }
            } catch (e: Exception) {
                monitorConnected = false
                disconnectedWhileAway = true
                controller.onMonitorState(false, lastEventTime, 0, true)
            }
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(600_000L)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        private const val TAG = "SessionKeepalive"
        private const val CHANNEL_ID = "session_keepalive"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.opencode.persistentbrowser.START"
        const val ACTION_STOP = "com.opencode.persistentbrowser.STOP"
        const val EXTRA_URL = "url"
        const val EXTRA_LAST_EVENT_ID = "last_event_id"

        fun start(context: Context, url: String, lastEventId: String?) {
            val intent = Intent(context, SessionKeepaliveService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_LAST_EVENT_ID, lastEventId)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SessionKeepaliveService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
