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
import android.view.*
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat

class FloatingBrowserService : Service() {
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var webView: WebView? = null

    companion object {
        private const val CHANNEL_ID = "floating_browser_channel"
        private const val NOTIFICATION_ID = 1337
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // 1. Create foreground service notification to prevent Android 8.0+ ForegroundServiceDidNotStartInTimeException crash
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

        // 2. Initialize WindowManager and Floating View
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_browser, null)

        val params = WindowManager.LayoutParams(
            320,
            480,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl("https://www.google.com")
        }

        val container = floatingView?.findViewById<FrameLayout>(R.id.floatWebViewContainer)
        container?.addView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val closeBtn = floatingView?.findViewById<ImageButton>(R.id.floatClose)
        closeBtn?.setOnClickListener {
            stopSelf()
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        val toolbar = floatingView?.findViewById<LinearLayout>(R.id.floatToolbar)
        toolbar?.setOnTouchListener { _, event ->
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
                    windowManager?.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(floatingView, params)
        } catch (e: Exception) {
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
        webView?.destroy()
        if (floatingView != null) {
            try {
                windowManager?.removeView(floatingView)
            } catch (_: Exception) {}
        }
    }
}
