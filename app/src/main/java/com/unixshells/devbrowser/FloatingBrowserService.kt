package com.unixshells.devbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.*
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat

class FloatingBrowserService : Service() {
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var webView: WebView? = null
    private var windowParams: WindowManager.LayoutParams? = null

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
            windowParams = params

            webView = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
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

            windowManager?.addView(view, params)
            AppLogger.log("Floating view added to WindowManager successfully")
        } catch (e: Exception) {
            AppLogger.log("CRASH in FloatingBrowserService.onCreate: ${e.stackTraceToString()}")
            e.printStackTrace()
            stopSelf()
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
        AppLogger.log("FloatingBrowserService.onDestroy")
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
