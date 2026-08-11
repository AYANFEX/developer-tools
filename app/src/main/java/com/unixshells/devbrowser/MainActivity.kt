package com.unixshells.devbrowser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import android.util.Log
import java.net.URLEncoder

open class MainActivity : AppCompatActivity() {

    open val baseCdpPort: Int = 9222

    companion object {
        private const val TAG = "DevBrowser"
        private const val CDP_HTTP_PORT = 9222
        private const val CDP_WS_PORT = 9223
        private const val DEVTOOLS_PORT = 9224
    }

    // Views
    private lateinit var browserContainer: FrameLayout
    private lateinit var devtoolsWebView: WebView
    private lateinit var urlBar: EditText
    private lateinit var contentLayout: LinearLayout
    private lateinit var divider: View
    private lateinit var btnDevTools: ImageButton
    private lateinit var btnDesktopToggle: ImageButton
    private lateinit var btnTabs: ImageButton
    private lateinit var tabStrip: HorizontalScrollView
    private lateinit var tabContainer: LinearLayout
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView
    private lateinit var progressBar: ProgressBar

    // State
    private lateinit var tabManager: TabManager
    private lateinit var profileManager: ProfileManager
    private var cdpBridge: CDPBridge? = null
    private var devToolsServer: DevToolsServer? = null
    private var isDevToolsVisible = false
    private var isTabStripVisible = false
    private var dockMode = DockMode.BOTTOM
    private lateinit var prefs: SharedPreferences

    enum class DockMode { BOTTOM, RIGHT }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("devbrowser_settings", Context.MODE_PRIVATE)
        profileManager = ProfileManager(this)

        findViews()
        setupWindowInsets()
        setupTabManager()
        setupDevToolsWebView()
        setupToolbar()
        setupFindInPage()
        setupDividerDrag()

        // Enable WebView debugging for CDP
        WebView.setWebContentsDebuggingEnabled(true)

        startServers()

        // Restore saved tabs or create first tab
        val intentUrl = intent?.dataString
        val restored = tabManager.restoreState(profileManager)
        if (intentUrl != null) {
            tabManager.createTab(intentUrl, profileManager.getProfiles().firstOrNull())
        } else if (!restored) {
            tabManager.createTab("https://www.google.com", profileManager.getProfiles().firstOrNull())
        }
    }

    private fun setupWindowInsets() {
        val rootLayout = findViewById<LinearLayout>(R.id.rootLayout)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun findViews() {
        browserContainer = findViewById(R.id.browserContainer)
        devtoolsWebView = findViewById(R.id.devtoolsWebView)
        urlBar = findViewById(R.id.urlBar)
        contentLayout = findViewById(R.id.contentLayout)
        divider = findViewById(R.id.divider)
        btnDevTools = findViewById(R.id.btnDevTools)
        btnDesktopToggle = findViewById(R.id.btnDesktopToggle)
        btnTabs = findViewById(R.id.btnTabs)
        tabStrip = findViewById(R.id.tabStrip)
        tabContainer = findViewById(R.id.tabContainer)
        findBar = findViewById(R.id.findBar)
        findInput = findViewById(R.id.findInput)
        findCount = findViewById(R.id.findCount)
        progressBar = findViewById(R.id.progressBar)
    }

    private fun setupTabManager() {
        tabManager = TabManager(
            context = this,
            onTabChanged = { tab ->
                // Swap the WebView shown in the browser container
                browserContainer.removeAllViews()
                (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
                browserContainer.addView(
                    tab.webView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                urlBar.setText(tab.url)
                setupDownloadListener(tab.webView)
                updateTabStrip()
                updateProgressBar(tab.progress)
            },
            onTabListChanged = { updateTabStrip() },
            onPageStarted = { url ->
                urlBar.setText(url)
                tabManager.activeTab?.let { updateProgressBar(it.progress) }
            },
            onPageFinished = { url ->
                urlBar.setText(url)
                tabManager.activeTab?.let { updateProgressBar(it.progress) }
            },
            onTitleChanged = { _ -> updateTabStrip() },
            onProgressChanged = { tab, progress ->
                if (tab == tabManager.activeTab) {
                    updateProgressBar(progress)
                }
            }
        )

        tabManager.updateDesktopMode(prefs.getBoolean("desktop_mode_default", true))
    }

    private fun updateProgressBar(progress: Int) {
        if (progress in 1..99) {
            progressBar.visibility = View.VISIBLE
            progressBar.progress = progress
        } else {
            progressBar.progress = 100
            progressBar.visibility = View.GONE
        }
    }

    private fun setupDownloadListener(webView: WebView) {
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)
                request.addRequestHeader("User-Agent", userAgent)

                val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
                request.setTitle(filename)
                request.setDescription("Downloading $filename")
                request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, filename
                )

                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "Downloading: $filename", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}")
                Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupDevToolsWebView() {
        devtoolsWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        devtoolsWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean = false

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript("""
                    (function() {
                        var style = document.createElement('style');
                        style.type = 'text/css';
                        style.innerHTML = 'input, select, textarea { font-size: 16px !important; }';
                        document.head.appendChild(style);
                    })();
                """.trimIndent(), null)
            }
        }

        devtoolsWebView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(TAG, "DevTools: ${consoleMessage.message()}")
                return true
            }
        }
    }

    private fun setupToolbar() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            tabManager.activeTab?.webView?.let { wv ->
                if (wv.canGoBack()) wv.goBack()
            }
        }

        findViewById<ImageButton>(R.id.btnForward).setOnClickListener {
            tabManager.activeTab?.webView?.let { wv ->
                if (wv.canGoForward()) wv.goForward()
            }
        }

        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener {
            tabManager.activeTab?.webView?.reload()
        }

        btnDesktopToggle.setOnClickListener { toggleDesktopMode() }
        btnDevTools.setOnClickListener { toggleDevTools() }
        btnTabs.setOnClickListener { toggleTabStrip() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showMenu(it) }

        urlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                 event.action == KeyEvent.ACTION_DOWN)
            ) {
                navigateToUrl(urlBar.text.toString())
                urlBar.clearFocus()
                true
            } else false
        }

        // Update desktop mode button state
        updateDesktopToggleIcon()
    }

    private fun setupFindInPage() {
        findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                if (query.isNotEmpty()) {
                    tabManager.activeTab?.webView?.findAllAsync(query)
                } else {
                    tabManager.activeTab?.webView?.clearMatches()
                    findCount.text = ""
                }
            }
        })

        tabManager.activeTab?.webView?.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            findCount.text = if (numberOfMatches > 0) {
                "${activeMatchOrdinal + 1}/$numberOfMatches"
            } else {
                "0/0"
            }
        }

        findViewById<ImageButton>(R.id.findNext).setOnClickListener {
            tabManager.activeTab?.webView?.findNext(true)
        }

        findViewById<ImageButton>(R.id.findPrev).setOnClickListener {
            tabManager.activeTab?.webView?.findNext(false)
        }

        findViewById<ImageButton>(R.id.findClose).setOnClickListener {
            hideFindBar()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDividerDrag() {
        divider.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE) {
                val containerParams = browserContainer.layoutParams as LinearLayout.LayoutParams
                val devParams = devtoolsWebView.layoutParams as LinearLayout.LayoutParams

                if (dockMode == DockMode.BOTTOM) {
                    val totalHeight = contentLayout.height - divider.height
                    val browserHeight = (event.rawY - contentLayout.top).toInt()
                        .coerceIn(totalHeight / 5, totalHeight * 4 / 5)
                    containerParams.weight = 0f
                    containerParams.height = browserHeight
                    devParams.weight = 0f
                    devParams.height = totalHeight - browserHeight
                } else {
                    val totalWidth = contentLayout.width - divider.width
                    val browserWidth = (event.rawX - contentLayout.left).toInt()
                        .coerceIn(totalWidth / 5, totalWidth * 4 / 5)
                    containerParams.weight = 0f
                    containerParams.width = browserWidth
                    devParams.weight = 0f
                    devParams.width = totalWidth - browserWidth
                }

                browserContainer.layoutParams = containerParams
                devtoolsWebView.layoutParams = devParams
                contentLayout.requestLayout()
            }
            true
        }
    }

    private fun startServers() {
        val pid = Process.myPid()
        Log.d(TAG, "PID: $pid")

        val httpPort = prefs.getInt("cdp_http_port", baseCdpPort)
        val wsPort = prefs.getInt("cdp_ws_port", baseCdpPort + 1)
        val devtoolsPort = baseCdpPort + 2

        cdpBridge = CDPBridge(httpPort, wsPort).apply { start(pid) }
        devToolsServer = DevToolsServer(this@MainActivity, devtoolsPort).apply { start() }

        Log.d(TAG, "Servers started - HTTP:$httpPort WS:$wsPort DevTools:$devtoolsPort")
    }

    // ─── DevTools ────────────────────────────────────────

    private fun toggleDevTools() {
        if (isDevToolsVisible) hideDevTools() else showDevTools()
    }

    private fun showDevTools() {
        val containerParams = browserContainer.layoutParams as LinearLayout.LayoutParams
        val devtoolsParams = devtoolsWebView.layoutParams as LinearLayout.LayoutParams

        if (dockMode == DockMode.BOTTOM) {
            contentLayout.orientation = LinearLayout.VERTICAL
            containerParams.apply { weight = 1f; height = 0; width = ViewGroup.LayoutParams.MATCH_PARENT }
            devtoolsParams.apply { weight = 1f; height = 0; width = ViewGroup.LayoutParams.MATCH_PARENT }
            divider.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (6 * resources.displayMetrics.density).toInt()
            )
        } else {
            contentLayout.orientation = LinearLayout.HORIZONTAL
            containerParams.apply { weight = 1f; width = 0; height = ViewGroup.LayoutParams.MATCH_PARENT }
            devtoolsParams.apply { weight = 1f; width = 0; height = ViewGroup.LayoutParams.MATCH_PARENT }
            divider.layoutParams = LinearLayout.LayoutParams(
                (6 * resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        browserContainer.layoutParams = containerParams
        devtoolsWebView.layoutParams = devtoolsParams
        divider.visibility = View.VISIBLE
        devtoolsWebView.visibility = View.VISIBLE

        val cdpPort = prefs.getInt("cdp_http_port", baseCdpPort)
        val devtoolsPort = baseCdpPort + 2

        // Discover the browser WebView's page ID from CDP /json endpoint
        // Both HTTP and WS go through the same port (the Unix socket handles both)
        Thread {
            try {
                // Small delay to ensure the page is registered with CDP
                Thread.sleep(500)

                val conn = java.net.URL("http://127.0.0.1:$cdpPort/json").openConnection()
                    as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                Log.d(TAG, "CDP /json response: $json")

                // Parse page entries
                val idMatch = Regex(""""id"\s*:\s*"([^"]+)"""").findAll(json)
                val urlMatch = Regex(""""url"\s*:\s*"([^"]+)"""").findAll(json)

                val ids = idMatch.map { it.groupValues[1] }.toList()
                val urls = urlMatch.map { it.groupValues[1] }.toList()

                // Pick the page that isn't our devtools page
                var pageId = ids.firstOrNull() ?: ""
                for (i in ids.indices) {
                    if (i < urls.size && !urls[i].contains("devtools_app") && !urls[i].contains("$devtoolsPort")) {
                        pageId = ids[i]
                        break
                    }
                }

                Log.d(TAG, "Discovered page ID: $pageId")

                val devtoolsUrl = "http://localhost:$devtoolsPort/devtools_app.html" +
                    "?ws=127.0.0.1:$cdpPort/devtools/page/$pageId"

                runOnUiThread {
                    Log.d(TAG, "Loading DevTools: $devtoolsUrl")
                    devtoolsWebView.loadUrl(devtoolsUrl)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to discover page ID: ${e.message}", e)
                runOnUiThread {
                    val devtoolsUrl = "http://localhost:$devtoolsPort/devtools_app.html" +
                        "?ws=127.0.0.1:$cdpPort"
                    Log.d(TAG, "Loading DevTools (fallback): $devtoolsUrl")
                    devtoolsWebView.loadUrl(devtoolsUrl)
                }
            }
        }.start()

        btnDevTools.setColorFilter(Color.parseColor("#4fc3f7"))
        isDevToolsVisible = true
    }

    private fun hideDevTools() {
        val containerParams = browserContainer.layoutParams as LinearLayout.LayoutParams
        containerParams.apply { weight = 1f; height = 0; width = ViewGroup.LayoutParams.MATCH_PARENT }
        browserContainer.layoutParams = containerParams

        contentLayout.orientation = LinearLayout.VERTICAL
        divider.visibility = View.GONE
        devtoolsWebView.visibility = View.GONE
        devtoolsWebView.loadUrl("about:blank")

        btnDevTools.setColorFilter(Color.parseColor("#aaaaaa"))
        isDevToolsVisible = false
    }

    /**
     * Hides DevTools panels that don't work with WebView's CDP implementation.
     * Runs after the DevTools frontend has loaded. Uses MutationObserver to
     * catch panels registered after initial load.
     */
    private fun sendInspectCommand() {
        if (!isDevToolsVisible) showDevTools()
        // Activate element picker via CDP — send Overlay.setInspectMode
        devtoolsWebView.evaluateJavascript("""
            (function() {
                // Trigger the inspect element mode in DevTools
                if (window.InspectorFrontendHost) {
                    window.InspectorFrontendHost.enterInspectElementMode();
                }
            })();
        """.trimIndent(), null)
    }

    // ─── Desktop Mode ────────────────────────────────────

    private fun toggleDesktopMode() {
        tabManager.updateDesktopMode(!tabManager.isDesktopMode)
        updateDesktopToggleIcon()
    }

    private fun updateDesktopToggleIcon() {
        if (tabManager.isDesktopMode) {
            btnDesktopToggle.setImageResource(R.drawable.ic_desktop)
            btnDesktopToggle.setColorFilter(Color.parseColor("#4fc3f7"))
        } else {
            btnDesktopToggle.setImageResource(R.drawable.ic_phone)
            btnDesktopToggle.setColorFilter(Color.parseColor("#aaaaaa"))
        }
    }

    // ─── Tabs ────────────────────────────────────────────

    private fun toggleTabStrip() {
        isTabStripVisible = !isTabStripVisible
        tabStrip.visibility = if (isTabStripVisible) View.VISIBLE else View.GONE
        btnTabs.setColorFilter(
            Color.parseColor(if (isTabStripVisible) "#4fc3f7" else "#aaaaaa")
        )
        if (isTabStripVisible) updateTabStrip()
    }

    private fun showNewTabProfileDialog(url: String = "about:blank") {
        val profiles = profileManager.getProfiles()
        val items = profiles.map { "• ${it.name}" }.toTypedArray()

        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Select Profile for New Tab")
        builder.setItems(items) { _, which ->
            val selectedProfile = profiles[which]
            tabManager.createTab(url, selectedProfile)
            if (!isTabStripVisible) toggleTabStrip()
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun updateTabStrip() {
        tabContainer.removeAllViews()
        val tabs = tabManager.allTabs
        val activeTab = tabManager.activeTab

        for ((index, tab) in tabs.withIndex()) {
            val tabView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10, 4, 4, 4)
                val isActive = tab == activeTab
                setBackgroundColor(
                    if (isActive) Color.parseColor("#252545") else Color.TRANSPARENT
                )

                setOnClickListener { tabManager.switchToTab(index) }
            }

            val colorDot = View(this).apply {
                val dotSize = (8 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    marginEnd = (6 * resources.displayMetrics.density).toInt()
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(tab.profile.color)
                }
            }

            val titleView = TextView(this).apply {
                text = if (tab.title.length > 18) tab.title.take(18) + "..." else tab.title
                textSize = 12f
                setTextColor(Color.parseColor("#cccccc"))
                maxLines = 1
                typeface = Typeface.DEFAULT
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val closeBtn = ImageButton(this).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(Color.parseColor("#cccccc"))
                setBackgroundColor(Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(
                    (28 * resources.displayMetrics.density).toInt(),
                    (28 * resources.displayMetrics.density).toInt()
                )
                setPadding(
                    (4 * resources.displayMetrics.density).toInt(),
                    (4 * resources.displayMetrics.density).toInt(),
                    (4 * resources.displayMetrics.density).toInt(),
                    (4 * resources.displayMetrics.density).toInt()
                )
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setOnClickListener { tabManager.closeTab(index) }
            }

            tabView.addView(colorDot)
            tabView.addView(titleView)
            tabView.addView(closeBtn)

            val params = LinearLayout.LayoutParams(
                (160 * resources.displayMetrics.density).toInt(),
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply { marginEnd = (2 * resources.displayMetrics.density).toInt() }

            tabContainer.addView(tabView, params)
        }

        // Add "+" button
        val addBtn = TextView(this).apply {
            text = "+"
            textSize = 18f
            setTextColor(Color.parseColor("#4fc3f7"))
            gravity = Gravity.CENTER
            setPadding(16, 0, 16, 0)
            setOnClickListener {
                showNewTabProfileDialog("about:blank")
            }
        }
        tabContainer.addView(
            addBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    // ─── Find in Page ────────────────────────────────────

    private fun showFindBar() {
        findBar.visibility = View.VISIBLE
        findInput.requestFocus()
        findInput.text.clear()
    }

    private fun hideFindBar() {
        findBar.visibility = View.GONE
        tabManager.activeTab?.webView?.clearMatches()
        findInput.text.clear()
        findCount.text = ""
    }

    // ─── Navigation ──────────────────────────────────────

    private fun navigateToUrl(input: String) {
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.startsWith("localhost") || input.startsWith("127.0.0.1") ||
                input.startsWith("10.0.2.2") -> "http://$input"
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.google.com/search?q=${URLEncoder.encode(input, "UTF-8")}"
        }
        tabManager.activeTab?.webView?.loadUrl(url)
    }

    private fun viewPageSource() {
        val webView = tabManager.activeTab?.webView ?: return
        webView.evaluateJavascript(
            "(function() { return document.documentElement.outerHTML; })();"
        ) { html ->
            // html comes back as a JSON-encoded string
            val decoded = html
                ?.removeSurrounding("\"")
                ?.replace("\\n", "\n")
                ?.replace("\\t", "\t")
                ?.replace("\\\"", "\"")
                ?.replace("\\/", "/")
                ?.replace("\\\\", "\\")

            if (decoded != null) {
                val sourceTab = tabManager.createTab("about:blank")
                sourceTab.webView.loadDataWithBaseURL(
                    null,
                    """
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        <style>
                            body {
                                background: #1e1e2e;
                                color: #cdd6f4;
                                font-family: monospace;
                                font-size: 13px;
                                padding: 8px;
                                white-space: pre-wrap;
                                word-wrap: break-word;
                            }
                        </style>
                    </head>
                    <body></body>
                    <script>
                        document.body.textContent = ${android.util.Base64.encodeToString(
                            decoded.toByteArray(), android.util.Base64.NO_WRAP
                        ).let { "atob('$it')" }};
                    </script>
                    </html>
                    """.trimIndent(),
                    "text/html",
                    "UTF-8",
                    null
                )
                sourceTab.title = "Source: ${webView.url}"
            }
        }
    }

    // ─── Menu ────────────────────────────────────────────

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_new_tab -> {
                    showNewTabProfileDialog("about:blank")
                    true
                }
                R.id.menu_devtools -> {
                    toggleDevTools()
                    true
                }
                R.id.menu_view_source -> {
                    viewPageSource()
                    true
                }
                R.id.menu_find_in_page -> {
                    showFindBar()
                    true
                }
                R.id.menu_dock_bottom -> {
                    dockMode = DockMode.BOTTOM
                    if (isDevToolsVisible) { hideDevTools(); showDevTools() }
                    true
                }
                R.id.menu_dock_right -> {
                    dockMode = DockMode.RIGHT
                    if (isDevToolsVisible) { hideDevTools(); showDevTools() }
                    true
                }
                R.id.menu_inspect -> {
                    sendInspectCommand()
                    true
                }
                R.id.menu_close_tab -> {
                    tabManager.closeCurrentTab()
                    true
                }
                R.id.menu_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    // ─── Lifecycle ───────────────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            findBar.visibility == View.VISIBLE -> hideFindBar()
            isDevToolsVisible -> hideDevTools()
            tabManager.activeTab?.webView?.canGoBack() == true -> {
                tabManager.activeTab?.webView?.goBack()
            }
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cdpBridge?.stop()
        devToolsServer?.stop()
        devtoolsWebView.destroy()
        tabManager.destroyAll()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isDevToolsVisible) {
            dockMode = if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                DockMode.RIGHT
            } else {
                DockMode.BOTTOM
            }
            hideDevTools()
            showDevTools()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::tabManager.isInitialized) {
            tabManager.saveState()
        }
    }

    override fun onResume() {
        super.onResume()
        // Apply settings that may have changed
        applySettings()
    }

    private fun applySettings() {
        val jsEnabled = prefs.getBoolean("javascript_enabled", true)
        val blockImages = prefs.getBoolean("block_images", false)
        val customUa = prefs.getString("custom_user_agent", "") ?: ""

        tabManager.allTabs.forEach { tab ->
            tab.webView.settings.javaScriptEnabled = jsEnabled
            tab.webView.settings.blockNetworkImage = blockImages
            if (customUa.isNotBlank()) {
                tab.webView.settings.userAgentString = customUa
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.dataString?.let { url ->
            tabManager.createTab(url)
        }
    }
}
