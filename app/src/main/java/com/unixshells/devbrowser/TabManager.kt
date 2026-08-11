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
    val webView: WebView,
    var title: String = "New Tab",
    var url: String = "about:blank",
    var favicon: Bitmap? = null,
    var profile: Profile = ProfileManager.DEFAULT_PROFILES[0],
    var progress: Int = 0
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
    fun createTab(url: String = "about:blank", profile: Profile? = null): Tab {
        val selectedProfile = profile ?: ProfileManager.DEFAULT_PROFILES[0]
        val webView = WebView(context)

        // Set WebView Profile for per-tab cookie/session isolation if supported
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                WebViewCompat.setProfile(webView, selectedProfile.id)
                Log.d(TAG, "Set WebView profile '${selectedProfile.id}' for tab")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting WebView profile: ${e.message}")
            }
        }

        configureWebView(webView)

        val tab = Tab(id = nextId++, webView = webView, url = url, profile = selectedProfile)
        tabs.add(tab)
        switchToTab(tabs.size - 1)
        webView.loadUrl(url)
        onTabListChanged()
        saveState()
        return tab
    }

    fun closeTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        val tab = tabs.removeAt(index)
        try {
            java.io.File(context.filesDir, "tab_state_${tab.id}.dat").delete()
        } catch (_: Exception) {}
        tab.webView.destroy()

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

    fun switchToTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        activeTabIndex = index
        onTabChanged(tabs[index])
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
            ): Boolean = false
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
                    put("url", tab.webView.url ?: tab.url)
                    put("title", tab.title)
                    put("profileId", tab.profile.id)
                }
                array.put(obj)

                val bundle = android.os.Bundle()
                val restored = tab.webView.saveState(bundle)
                if (restored != null) {
                    saveBundleToFile("tab_state_${tab.id}.dat", bundle)
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
                val url = obj.optString("url", "about:blank")
                val title = obj.optString("title", "New Tab")
                val profileId = obj.optString("profileId", "default")
                val profile = profileMap[profileId] ?: profiles.firstOrNull() ?: ProfileManager.DEFAULT_PROFILES[0]

                val webView = WebView(context)
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    try {
                        WebViewCompat.setProfile(webView, profile.id)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error setting profile: ${e.message}")
                    }
                }
                configureWebView(webView)

                val tab = Tab(id = id, webView = webView, title = title, url = url, profile = profile)
                tabs.add(tab)

                val bundle = readBundleFromFile("tab_state_${id}.dat")
                var stateRestored = false
                if (bundle != null) {
                    val result = webView.restoreState(bundle)
                    if (result != null) {
                        stateRestored = true
                    }
                }
                if (!stateRestored && url.isNotBlank() && url != "about:blank") {
                    webView.loadUrl(url)
                }
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
            tab.webView.settings.userAgentString =
                if (enabled) DESKTOP_USER_AGENT else null
        }
        activeTab?.webView?.reload()
    }

    fun destroyAll() {
        tabs.forEach { it.webView.destroy() }
        tabs.clear()
        activeTabIndex = -1
    }
}
