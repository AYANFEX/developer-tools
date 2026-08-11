package com.unixshells.devbrowser

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.*
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider

class FloatingBrowserService : Service() {
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var webView: WebView? = null
    private var selectionMenuView: View? = null
    private val selectionHandler = Handler(Looper.getMainLooper())
    private var pendingHideRunnable: Runnable? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val CHANNEL_ID = "floating_browser_channel"
        private const val NOTIFICATION_ID = 1337
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(applicationContext)
        AppLogger.log("FloatingBrowserService.onCreate started")

        try {
            createNotificationChannel()
            val notificationIntent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("DevBrowser Floating")
                .setContentText("Floating browser window is running")
                .setSmallIcon(R.drawable.ic_devtools)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()

            startForeground(NOTIFICATION_ID, notification)
            AppLogger.log("FloatingBrowserService startForeground successful")

            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getMetrics(metrics)
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels

            val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_DevBrowser)
            val view = LayoutInflater.from(themedContext).inflate(R.layout.floating_browser, null)
            floatingView = view
            AppLogger.log("Floating view inflated successfully")

            val defaultWidth = (screenWidth * 0.85).toInt()
            val defaultHeight = (screenHeight * 0.6).toInt()

            val params = WindowManager.LayoutParams(
                defaultWidth,
                defaultHeight,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (screenWidth - defaultWidth) / 2
                y = (screenHeight - defaultHeight) / 4
            }

            webView = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                isLongClickable = true
                isFocusable = true
                isFocusableInTouchMode = true

                addJavascriptInterface(SelectionBridge(this), "AndroidSelectionHandler")

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(selectionJs, null)
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(
                        view: WebView?,
                        callback: ValueCallback<Array<Uri>>?,
                        params: FileChooserParams?
                    ): Boolean {
                        filePathCallback?.onReceiveValue(null)
                        filePathCallback = callback
                        showCustomFilePicker()
                        return true
                    }
                }

                loadUrl("https://www.google.com")
            }

            val container = view.findViewById<FrameLayout>(R.id.floatWebViewContainer)
            container?.addView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            val urlInput = view.findViewById<EditText>(R.id.urlInput)
            val goButton = view.findViewById<Button>(R.id.goButton)

            fun loadFromInput() {
                var url = urlInput.text.toString().trim()
                if (url.isEmpty()) return
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://$url"
                }
                webView?.loadUrl(url)
            }

            goButton.setOnClickListener { loadFromInput() }
            urlInput.setOnEditorActionListener { _, _, _ ->
                loadFromInput()
                true
            }

            // Setup Header Dragging
            val header = view.findViewById<View>(R.id.header)
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            header.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        runCatching { windowManager?.updateViewLayout(view, params) }
                        true
                    }
                    else -> false
                }
            }

            // Setup Resize Handle
            val resizeHandle = view.findViewById<View>(R.id.resizeHandle)
            val minWidth = (screenWidth * 0.35).toInt()
            val minHeight = (screenHeight * 0.25).toInt()
            var initialWidth = 0
            var initialHeight = 0

            resizeHandle.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialWidth = params.width
                        initialHeight = params.height
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val newWidth = initialWidth + (event.rawX - initialTouchX).toInt()
                        val newHeight = initialHeight + (event.rawY - initialTouchY).toInt()
                        params.width = newWidth.coerceIn(minWidth, screenWidth)
                        params.height = newHeight.coerceIn(minHeight, screenHeight)
                        runCatching { windowManager?.updateViewLayout(view, params) }
                        true
                    }
                    else -> false
                }
            }

            // Setup Traffic Lights
            view.findViewById<View>(R.id.btnClose).setOnClickListener {
                stopSelf()
            }

            view.findViewById<View>(R.id.btnMinimize).setOnClickListener {
                val addressBar = view.findViewById<View>(R.id.addressBar)
                val webViewContainer = view.findViewById<View>(R.id.floatWebViewContainer)
                val collapsing = addressBar.visibility == View.VISIBLE
                if (collapsing) {
                    addressBar.visibility = View.GONE
                    webViewContainer.visibility = View.GONE
                    params.height = WindowManager.LayoutParams.WRAP_CONTENT
                } else {
                    addressBar.visibility = View.VISIBLE
                    webViewContainer.visibility = View.VISIBLE
                    params.height = (screenHeight * 0.6).toInt()
                }
                runCatching { windowManager?.updateViewLayout(view, params) }
            }

            var previousParams: IntArray? = null
            view.findViewById<View>(R.id.btnMaximize).setOnClickListener {
                if (previousParams == null) {
                    previousParams = intArrayOf(params.width, params.height, params.x, params.y)
                    params.width = screenWidth
                    params.height = screenHeight
                    params.x = 0
                    params.y = 0
                } else {
                    val p = previousParams!!
                    params.width = p[0]
                    params.height = p[1]
                    params.x = p[2]
                    params.y = p[3]
                    previousParams = null
                }
                runCatching { windowManager?.updateViewLayout(view, params) }
            }

            // Handle Back key inside floating window without affecting main app
            view.isFocusableInTouchMode = true
            view.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    if (webView?.canGoBack() == true) {
                        webView?.goBack()
                    } else {
                        stopSelf()
                    }
                    true
                } else {
                    false
                }
            }

            windowManager?.addView(view, params)
            AppLogger.log("Floating view added to WindowManager successfully")
        } catch (e: Exception) {
            AppLogger.log("CRASH in FloatingBrowserService.onCreate: ${e.stackTraceToString()}")
            e.printStackTrace()
            stopSelf()
        }
    }

    private val selectionJs = """
        (function() {
            function getSelectionCoords() {
                var sel = window.getSelection();
                if (sel && sel.rangeCount > 0 && !sel.isCollapsed) {
                    var range = sel.getRangeAt(0);
                    var rect = range.getBoundingClientRect();
                    if (rect.width > 0 || rect.height > 0) {
                        return {
                            left: rect.left,
                            top: rect.top,
                            width: rect.width,
                            height: rect.height,
                            text: sel.toString()
                        };
                    }
                }
                return null;
            }

            var selectionDebounce = null;
            function checkSelection() {
                var coords = getSelectionCoords();
                if (coords && coords.text.trim().length > 0) {
                    AndroidSelectionHandler.showMenu(coords.left, coords.top, coords.width, coords.height);
                } else {
                    AndroidSelectionHandler.hideMenu();
                }
            }

            document.addEventListener('selectionchange', function() {
                if (selectionDebounce) clearTimeout(selectionDebounce);
                selectionDebounce = setTimeout(checkSelection, 80);
            });
            document.addEventListener('touchend', function() {
                setTimeout(checkSelection, 150);
            });
            document.addEventListener('mouseup', function() {
                setTimeout(checkSelection, 150);
            });
        })();
    """.trimIndent()

    private inner class SelectionBridge(private val wv: WebView) {
        @JavascriptInterface
        fun showMenu(left: Float, top: Float, width: Float, height: Float) {
            selectionHandler.post { showSelectionMenu(wv, left, top, width, height) }
        }
        @JavascriptInterface
        fun hideMenu() {
            selectionHandler.post { hideSelectionMenu() }
        }
    }

    private fun showSelectionMenu(webView: WebView, left: Float, top: Float, width: Float, height: Float) {
        pendingHideRunnable?.let { selectionHandler.removeCallbacks(it) }
        pendingHideRunnable = null

        val density = resources.displayMetrics.density
        val scale = webView.scale
        val location = IntArray(2)
        webView.getLocationOnScreen(location)

        val selX = location[0] + (left * scale).toInt()
        val selY = location[1] + (top * scale).toInt()
        val selWidth = (width * scale).toInt()
        val selHeight = (height * scale).toInt()

        val isNew = (selectionMenuView == null)
        val view: View = selectionMenuView ?: run {
            val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_DevBrowser)
            val inflater = LayoutInflater.from(themedContext)
            val inflated = inflater.inflate(R.layout.floating_selection_menu, null)

            inflated.findViewById<View>(R.id.btnCopy)?.setOnClickListener {
                webView.evaluateJavascript("document.execCommand('copy')", null)
                hideSelectionMenuImmediately()
            }
            inflated.findViewById<View>(R.id.btnCut)?.setOnClickListener {
                webView.evaluateJavascript("document.execCommand('cut')", null)
                hideSelectionMenuImmediately()
            }
            inflated.findViewById<View>(R.id.btnPaste)?.setOnClickListener {
                webView.evaluateJavascript("document.execCommand('paste')", null)
                hideSelectionMenuImmediately()
            }
            inflated.findViewById<View>(R.id.btnSelectAll)?.setOnClickListener {
                webView.evaluateJavascript("document.execCommand('selectall')", null)
            }
            inflated
        }

        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val menuWidth = view.measuredWidth
        val menuHeight = view.measuredHeight

        var posX = selX + (selWidth / 2) - (menuWidth / 2)
        var posY = selY - menuHeight - (8 * density).toInt()
        if (posY < location[1]) {
            posY = selY + selHeight + (8 * density).toInt()
        }

        val screenWidth = resources.displayMetrics.widthPixels
        posX = posX.coerceIn(10, screenWidth - menuWidth - 10)

        if (isNew) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = posX
                y = posY
            }
            runCatching { windowManager?.addView(view, params) }
            selectionMenuView = view
        } else {
            val params = view.layoutParams as? WindowManager.LayoutParams
            if (params != null) {
                params.x = posX
                params.y = posY
                runCatching { windowManager?.updateViewLayout(view, params) }
            }
        }
    }

    private fun hideSelectionMenu() {
        pendingHideRunnable?.let { selectionHandler.removeCallbacks(it) }
        val runnable = Runnable {
            selectionMenuView?.let { runCatching { windowManager?.removeView(it) } }
            selectionMenuView = null
            pendingHideRunnable = null
        }
        pendingHideRunnable = runnable
        selectionHandler.postDelayed(runnable, 350)
    }

    private fun hideSelectionMenuImmediately() {
        pendingHideRunnable?.let { selectionHandler.removeCallbacks(it) }
        pendingHideRunnable = null
        selectionMenuView?.let { runCatching { windowManager?.removeView(it) } }
        selectionMenuView = null
    }

    private fun showCustomFilePicker() {
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val files = downloadDir.listFiles()?.filter { it.isFile } ?: emptyList()
            val fileNames = files.map { it.name }.toTypedArray()

            val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_DevBrowser)
            if (fileNames.isEmpty()) {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = null
                return
            }

            val dialog = AlertDialog.Builder(themedContext)
                .setTitle("Select File to Upload")
                .setItems(fileNames) { _, which ->
                    val selectedFile = files[which]
                    val uri = FileProvider.getUriForFile(
                        this, "${packageName}.fileprovider", selectedFile
                    )
                    filePathCallback?.onReceiveValue(arrayOf(uri))
                    filePathCallback = null
                }
                .setOnCancelListener {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                }
                .create()

            dialog.window?.setType(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
            )
            dialog.show()
        } catch (e: Exception) {
            AppLogger.log("Error in showCustomFilePicker: ${e.message}")
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Floating Browser Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hideSelectionMenuImmediately()
        webView?.destroy()
        if (floatingView != null) {
            try {
                windowManager?.removeView(floatingView)
            } catch (e: Exception) {
                AppLogger.log("Error removing floating view: ${e.message}")
            }
        }
    }
}
