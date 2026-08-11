package com.unixshells.devbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.util.Log
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

data class Tab(
    val id: Int,
    var webView: WebView?,
    var title: String = "New Tab",
    var url: String = "about:home",
    var favicon: Bitmap? = null,
    var profile: Profile = ProfileManager.DEFAULT_PROFILES[0],
    var progress: Int = 0,
    var savedBundleFileName: String? = null
)

class TabManager(
    private val context: Context,
    private val onTabChanged: (Tab) -> Unit,
    private val onTabListChanged: () -> Unit,
    private val onPageStarted: (String) -> Unit,
    private val onPageFinished: (String) -> Unit,
    private val onTitleChanged: (String) -> Unit,
    private val onProgressChanged: (Tab, Int) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "TabManager"
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        const val HOME_PAGE_URL = "about:home"
        const val HOME_PAGE_HTML = """
<!DOCTYPE html>
<html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>DevBrowser Home</title>
    <style>
        body {
            background: #1a1a2e;
            color: #e0e0e0;
            font-family: sans-serif;
            margin: 0;
            padding: 24px;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            min-height: 100vh;
        }
        h1 {
            color: #4fc3f7;
            margin-bottom: 8px;
            font-size: 28px;
        }
        p {
            color: #8888aa;
            margin-bottom: 24px;
            font-size: 14px;
        }
        .search-box {
            width: 100%;
            max-width: 500px;
            display: flex;
            background: #12122a;
            border: 1px solid #333355;
            border-radius: 24px;
            padding: 8px 16px;
            box-shadow: 0 4px 12px rgba(0,0,0,0.3);
        }
        .search-input {
            flex: 1;
            background: transparent;
            border: none;
            color: #fff;
            font-size: 16px;
            outline: none;
        }
        .shortcuts {
            display: grid;
            grid-template-columns: repeat(4, 1fr);
            gap: 16px;
            margin-top: 32px;
            max-width: 500px;
            width: 100%;
        }
        .shortcut {
            background: #252545;
            border-radius: 12px;
            padding: 16px;
            text-align: center;
            text-decoration: none;
            color: #ccc;
            font-size: 13px;
            transition: background 0.2s, transform 0.2s;
            display: flex;
            flex-direction: column;
            align-items: center;
            gap: 8px;
        }
        .shortcut:hover {
            background: #333355;
            color: #4fc3f7;
            transform: translateY(-2px);
        }
        .shortcut span {
            font-size: 24px;
        }
    </style>
</head>
<body>
    <h1>🛠️ DevBrowser</h1>
    <p>Developer Tools Browser with CDP Support</p>
    <div class="search-box">
        <input type="text" id="searchInput" class="search-input" placeholder="Search or type web URL..." autofocus />
    </div>
    <div class="shortcuts">
        <a href="https://www.google.com" class="shortcut"><span>🔍</span>Google</a>
        <a href="https://github.com" class="shortcut"><span>🐙</span>GitHub</a>
        <a href="https://stackoverflow.com" class="shortcut"><span>💬</span>StackOverflow</a>
        <a href="https://developer.mozilla.org" class="shortcut"><span>📖</span>MDN Docs</a>
    </div>
    <script>
        const input = document.getElementById('searchInput');
        input.addEventListener('keydown', function(e) {
            if (e.key === 'Enter') {
                let val = input.value.trim();
                if (val) {
                    if (val.startsWith('http://') || val.startsWith('https://') || val.includes('.')) {
                        window.location.href = val.startsWith('http') ? val : 'https://' + val;
                    } else {
                        window.location.href = 'https://www.google.com/search?q=' + encodeURIComponent(val);
                    }
                }
            }
        });
    </script>
</body>
</html>
"""
    }

    private val tabs = mutableListOf<Tab>()
    private var activeTabIndex = -1
    private var nextId = 0
    var isDesktopMode = true
        private set

    val activeTab: Tab? get() = tabs.getOrNull(activeTabIndex)
    val tabCount: Int get() = tabs.size
    val allTabs: List<Tab> get() = tabs.toList()

    @SuppressLint("SetJavaScriptEnabled")
    fun createTab(url: String = HOME_PAGE_URL, profile: Profile? = null): Tab {
        val selectedProfile = profile ?: ProfileManager.DEFAULT_PROFILES[0]
        val webView = WebView(context)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                WebViewCompat.setProfile(webView, selectedProfile.id)
            } catch (e: Exception) {
                Log.e(TAG, "Error setting profile: ${e.message}")
            }
        }

        configureWebView(webView)

        val tab = Tab(id = nextId++, webView = webView, url = url, profile = selectedProfile)
        tabs.add(tab)
        switchToTab(tabs.size - 1)
        loadTabContent(tab, url)
        onTabListChanged()
        saveState()
        return tab
    }

    private fun loadTabContent(tab: Tab, url: String) {
        val wv = tab.webView ?: return
        if (url == HOME_PAGE_URL || url.isEmpty() || url == "about:blank") {
            tab.url = HOME_PAGE_URL
            wv.loadDataWithBaseURL(HOME_PAGE_URL, HOME_PAGE_HTML, "text/html", "UTF-8", null)
        } else {
            tab.url = url
            wv.loadUrl(url)
        }
    }

    fun closeTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        val tab = tabs.removeAt(index)
        try {
            java.io.File(context.filesDir, "tab_state_${tab.id}.dat").delete()
        } catch (_: Exception) {}
        tab.webView?.destroy()

        if (tabs.isEmpty()) {
            createTab()
        } else {
            val newIndex = when {
                index <= activeTabIndex && activeTabIndex > 0 -> activeTabIndex - 1
                index < activeTabIndex -> activeTabIndex
                else -> minOf(index, tabs.size - 1)
            }
            switchToTab(newIndex)
        }
        onTabListChanged()
        saveState()
    }

    fun closeCurrentTab() {
        if (activeTabIndex >= 0) closeTab(activeTabIndex)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun switchToTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        activeTabIndex = index
        val tab = tabs[index]

        // Lazy initialize WebView if not yet created
        if (tab.webView == null) {
            val webView = WebView(context)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                try {
                    WebViewCompat.setProfile(webView, tab.profile.id)
                } catch (_: Exception) {}
            }
            configureWebView(webView)
            tab.webView = webView

            val bundle = readBundleFromFile("tab_state_${tab.id}.dat")
            var restored = false
            if (bundle != null) {
                restored = webView.restoreState(bundle) != null
            }
            if (!restored) {
                loadTabContent(tab, tab.url)
            }
        }

        onTabChanged(tab)
        saveState()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT

            userAgentString = if (isDesktopMode) DESKTOP_USER_AGENT else null
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                findTabByWebView(view)?.let { tab ->
                    tab.url = url
                    if (tab == activeTab) onPageStarted(url)
                    saveState()
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                findTabByWebView(view)?.let { tab ->
                    tab.url = url
                    if (tab == activeTab) onPageFinished(url)
                    saveState()
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (url == HOME_PAGE_URL) {
                    view.loadDataWithBaseURL(HOME_PAGE_URL, HOME_PAGE_HTML, "text/html", "UTF-8", null)
                    return true
                }
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                findTabByWebView(view)?.let { tab ->
                    tab.progress = newProgress
                    onProgressChanged(tab, newProgress)
                    if (newProgress == 100) {
                        saveState()
                    }
                }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                findTabByWebView(view)?.let { tab ->
                    tab.title = title ?: tab.url
                    if (tab == activeTab) onTitleChanged(tab.title)
                    onTabListChanged()
                    saveState()
                }
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                findTabByWebView(view)?.let { tab ->
                    tab.favicon = icon
                    onTabListChanged()
                }
            }
        }
    }

    private fun findTabByWebView(webView: WebView): Tab? {
        return tabs.find { it.webView === webView }
    }

    fun saveState() {
        try {
            val array = org.json.JSONArray()
            for (tab in tabs) {
                val obj = org.json.JSONObject().apply {
                    put("id", tab.id)
                    put("url", tab.webView?.url ?: tab.url)
                    put("title", tab.title)
                    put("profileId", tab.profile.id)
                }
                array.put(obj)

                tab.webView?.let { wv ->
                    val bundle = android.os.Bundle()
                    if (wv.saveState(bundle) != null) {
                        saveBundleToFile("tab_state_${tab.id}.dat", bundle)
                    }
                }
            }

            val stateObj = org.json.JSONObject().apply {
                put("activeTabIndex", activeTabIndex)
                put("tabs", array)
            }

            val prefs = context.getSharedPreferences("devbrowser_settings", Context.MODE_PRIVATE)
            prefs.edit().putString("saved_tab_state", stateObj.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save tab state: ${e.message}")
        }
    }

    private fun saveBundleToFile(fileName: String, bundle: android.os.Bundle) {
        try {
            val parcel = android.os.Parcel.obtain()
            bundle.writeToParcel(parcel, 0)
            val bytes = parcel.marshall()
            parcel.recycle()
            java.io.File(context.filesDir, fileName).writeBytes(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write bundle $fileName: ${e.message}")
        }
    }

    private fun readBundleFromFile(fileName: String): android.os.Bundle? {
        return try {
            val file = java.io.File(context.filesDir, fileName)
            if (!file.exists()) return null
            val bytes = file.readBytes()
            val parcel = android.os.Parcel.obtain()
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            val bundle = android.os.Bundle(context.classLoader)
            bundle.readFromParcel(parcel)
            parcel.recycle()
            bundle
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read bundle $fileName: ${e.message}")
            null
        }
    }

    fun restoreState(profileManager: ProfileManager): Boolean {
        val prefs = context.getSharedPreferences("devbrowser_settings", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("saved_tab_state", null) ?: return false
        return try {
            val stateObj = org.json.JSONObject(jsonStr)
            val array = stateObj.getJSONArray("tabs")
            val savedActiveIndex = stateObj.optInt("activeTabIndex", 0)

            if (array.length() == 0) return false

            val profiles = profileManager.getProfiles()
            val profileMap = profiles.associateBy { it.id }

            destroyAll()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optInt("id", nextId)
                if (id >= nextId) nextId = id + 1
                val url = obj.optString("url", HOME_PAGE_URL)
                val title = obj.optString("title", "New Tab")
                val profileId = obj.optString("profileId", "default")
                val profile = profileMap[profileId] ?: profiles.firstOrNull() ?: ProfileManager.DEFAULT_PROFILES[0]

                // Lazy tab loading: do not create WebView for background tabs yet!
                val tab = Tab(id = id, webView = null, title = title, url = url, profile = profile)
                tabs.add(tab)
            }

            if (tabs.isNotEmpty()) {
                val targetIndex = savedActiveIndex.coerceIn(0, tabs.size - 1)
                switchToTab(targetIndex)
                onTabListChanged()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore state: ${e.message}")
            false
        }
    }

    fun updateDesktopMode(enabled: Boolean) {
        isDesktopMode = enabled
        tabs.forEach { tab ->
            tab.webView?.settings?.userAgentString =
                if (enabled) DESKTOP_USER_AGENT else null
        }
        activeTab?.webView?.reload()
    }

    fun destroyAll() {
        tabs.forEach { it.webView?.destroy() }
        tabs.clear()
        activeTabIndex = -1
    }
}
