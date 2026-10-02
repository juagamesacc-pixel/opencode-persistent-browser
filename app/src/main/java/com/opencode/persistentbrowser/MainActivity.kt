package com.opencode.persistentbrowser

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.opencode.persistentbrowser.databinding.ActivityMainBinding
import com.opencode.persistentbrowser.service.SessionKeepaliveService
import com.opencode.persistentbrowser.session.ConnectionStatus
import com.opencode.persistentbrowser.session.SessionState
import com.opencode.persistentbrowser.util.EventSourceObserver
import com.opencode.persistentbrowser.util.Staleness
import com.opencode.persistentbrowser.util.UrlUtils
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val controller get() = App.instance.sessionController
    private val store get() = App.instance.sessionStore

    private var webView: WebView? = null
    private var currentUrl: String? = null
    private var isBrowserVisible = false
    private var reconciling = false

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        createWebView()
        setupHome()
        observeState()

        // Restore last URL into the input field.
        lifecycleScope.launch {
            val lastUrl = store.getLastUrl()
            if (!lastUrl.isNullOrEmpty()) {
                binding.urlInput.setText(lastUrl)
            }
            // Auto-resume an active session after process death.
            if (store.isSessionActive() && lastUrl != null) {
                loadUrl(lastUrl, autoResumed = true)
            }
        }

        // Track foreground/background for the service.
        viewModel.appForegrounded.observe(this) { foregrounded ->
            controller.setForegrounded(foregrounded)
            if (!foregrounded) {
                // App is backgrounded: tell the service to start monitoring.
                currentUrl?.let { url ->
                    lifecycleScope.launch {
                        val lastId = store.getLastEventId()
                        SessionKeepaliveService.start(this@MainActivity, url, lastId)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onBackground()
    }

    override fun onResume() {
        super.onResume()
        // Reconcile after returning to the foreground.
        if (isBrowserVisible && currentUrl != null) {
            reconcileOnResume()
        }
    }

    // ------------------------------------------------------------------
    // Home screen
    // ------------------------------------------------------------------

    private fun setupHome() {
        binding.urlInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) pasteUrlFromClipboard()
        }
        binding.loadButton.setOnClickListener {
            hideKeyboard()
            val text = binding.urlInput.text?.toString()?.trim().orEmpty()
            val normalized = UrlUtils.normalize(text)
            if (normalized == null) {
                binding.urlInput.error = "Enter a valid http(s) URL"
                return@setOnClickListener
            }
            binding.urlInput.error = null
            loadUrl(normalized, autoResumed = false)
        }
    }

    private fun pasteUrlFromClipboard() {
        val field = binding.urlInput
        if (!field.text.isNullOrEmpty()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val normalized = UrlUtils.normalize(text) ?: return
        field.setText(normalized)
        field.setSelection(normalized.length)
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    private fun loadUrl(url: String, autoResumed: Boolean) {
        currentUrl = url
        isBrowserVisible = true
        binding.homeScreen.visibility = View.GONE
        binding.browserScreen.visibility = View.VISIBLE
        binding.statusChip.visibility = View.VISIBLE

        lifecycleScope.launch {
            store.saveUrl(url)
            store.setSessionActive(true)
        }
        controller.onUrlLoaded(url)

        // Start the foreground service for process keepalive.
        lifecycleScope.launch {
            val lastId = store.getLastEventId()
            SessionKeepaliveService.start(this@MainActivity, url, lastId)
        }

        // Wire reconciliation callbacks.
        controller.onReloadRequested = { runOnUiThread { reloadPage() } }
        controller.onConnected = { runOnUiThread { reconciling = false } }

        webView?.loadUrl(url)
    }

    private fun reloadPage() {
        reconciling = true
        controller.onPageReloaded()
        webView?.reload()
    }

    // ------------------------------------------------------------------
    // WebView setup
    // ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        val wv = WebView(this)
        wv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        binding.webViewContainer.addView(wv)
        webView = wv

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            saveFormData = false
            savePassword = false
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        // Inject the EventSource observer at document start.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, EventSourceObserver.SCRIPT, setOf("*"))
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                controller.onPageStarted()
                // Fallback observer injection if document-start JS was unavailable.
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view?.evaluateJavascript(EventSourceObserver.SCRIPT, null)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                controller.onPageFinished()
                view?.postDelayed({ checkPageLiveness() }, 1500)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    val desc = error?.description?.toString() ?: "Failed to load page"
                    controller.onPageError(desc)
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.webkit.SslError?
            ) {
                // Do NOT auto-proceed — that would weaken TLS. Show a clear,
                // actionable message and cancel (secure default).
                val reason = when (error?.primaryError) {
                    android.webkit.SslError.SSL_UNTRUSTED -> "Certificate not trusted (private/self-signed CA)"
                    android.webkit.SslError.SSL_EXPIRED -> "Certificate expired"
                    android.webkit.SslError.SSL_IDMISMATCH -> "Certificate hostname mismatch"
                    android.webkit.SslError.SSL_NOTYETVALID -> "Certificate not yet valid"
                    android.webkit.SslError.SSL_DATE_INVALID -> "Certificate date invalid"
                    else -> "TLS handshake failed (protocol error)"
                }
                Log.e(TAG, "SSL error: ${error?.primaryError} url=${error?.url}")
                controller.onPageError("SSL: $reason. If this is a private endpoint, install its CA in Settings > Security > Install certificates, then retry.")
                handler?.cancel()
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                Log.e(TAG, "Renderer gone: ${detail?.didCrash()}")
                recreateWebView()
                return true
            }
        }

        wv.webChromeClient = WebChromeClient()
    }

    private fun recreateWebView() {
        // Recreate the WebView after a renderer crash and reload.
        binding.webViewContainer.removeAllViews()
        createWebView()
        currentUrl?.let { webView?.loadUrl(it) }
    }

    // ------------------------------------------------------------------
    // Liveness & reconciliation
    // ------------------------------------------------------------------

    private fun checkPageLiveness() {
        val wv = webView ?: return
        if (!isBrowserVisible) return
        wv.evaluateJavascript(EventSourceObserver.READ_STATE) { result ->
            val page = parseObserver(result)
            controller.pageObserver = page
            // If the page's live channel is open, we can show Connected.
            if (page != null && page.eventSourceUsed && page.anyOpen) {
                controller.onMonitorState(
                    connected = true,
                    lastEventTime = System.currentTimeMillis(),
                    eventsWhileAway = 0,
                    disconnectedWhileAway = false
                )
            }
        }
    }

    private fun parseObserver(json: String?): Staleness.PageObserver? {
        if (json == null || json == "null") return null
        return try {
            val obj = JSONObject(json)
            Staleness.PageObserver(
                eventSourceUsed = obj.optBoolean("eventSourceUsed", false),
                anyOpen = obj.optBoolean("anyOpen", false),
                lastMessageTime = obj.optLong("lastMessageTime", 0),
                lastErrorTime = obj.optLong("lastErrorTime", 0),
                openCount = obj.optInt("openCount", 0)
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun reconcileOnResume() {
        // Read the page observer, then let the controller decide.
        val wv = webView ?: return
        wv.evaluateJavascript(EventSourceObserver.READ_STATE) { result ->
            controller.pageObserver = parseObserver(result)
            // Trigger reconciliation.
            controller.setForegrounded(true)
        }
    }

    // ------------------------------------------------------------------
    // State observation
    // ------------------------------------------------------------------

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.state.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    private fun renderState(state: SessionState) {
        when (state.status) {
            ConnectionStatus.IDLE -> {
                if (isBrowserVisible) showHome()
            }
            ConnectionStatus.LOADING -> {
                binding.statusDot.setBackgroundResource(R.drawable.dot_yellow)
                binding.statusText.text = state.statusLabel
            }
            ConnectionStatus.CONNECTED -> {
                binding.statusDot.setBackgroundResource(R.drawable.dot_green)
                binding.statusText.text = state.statusLabel
            }
            ConnectionStatus.SYNCHRONIZING -> {
                binding.statusDot.setBackgroundResource(R.drawable.dot_yellow)
                binding.statusText.text = state.statusLabel
            }
            ConnectionStatus.RECONNECTING -> {
                binding.statusDot.setBackgroundResource(R.drawable.dot_orange)
                binding.statusText.text = state.statusLabel
            }
            ConnectionStatus.OFFLINE -> {
                binding.statusDot.setBackgroundResource(R.drawable.dot_red)
                binding.statusText.text = state.statusLabel
            }
            ConnectionStatus.ERROR -> {
                binding.statusDot.setBackgroundResource(R.drawable.dot_red)
                binding.statusText.text = state.statusLabel
            }
        }
    }

    private fun showHome() {
        isBrowserVisible = false
        binding.browserScreen.visibility = View.GONE
        binding.homeScreen.visibility = View.VISIBLE
        binding.statusChip.visibility = View.GONE
        currentUrl = null
        controller.stopSession()
        lifecycleScope.launch { store.setSessionActive(false) }
        SessionKeepaliveService.stop(this)
    }

    // ------------------------------------------------------------------
    // Lifecycle / state
    // ------------------------------------------------------------------

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView?.restoreState(savedInstanceState)
    }

    override fun onBackPressed() {
        val wv = webView
        if (isBrowserVisible && wv != null && wv.canGoBack()) {
            wv.goBack()
        } else if (isBrowserVisible) {
            showHome()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        CookieManager.getInstance().flush()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.urlInput.windowToken, 0)
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
