package com.unixshells.devbrowser

import android.os.Build
import android.os.Bundle
import android.webkit.WebView

open class ProfileActivity : MainActivity() {
    open val profileSuffix: String = "work"
    override val baseCdpPort: Int = 9225

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                WebView.setDataDirectorySuffix("profile_$profileSuffix")
            } catch (_: Exception) {
                // Suffix already initialized for process
            }
        }
        super.onCreate(savedInstanceState)
    }
}

class WorkProfileActivity : ProfileActivity() {
    override val profileSuffix: String = "work"
    override val baseCdpPort: Int = 9225
}

class TestingProfileActivity : ProfileActivity() {
    override val profileSuffix: String = "testing"
    override val baseCdpPort: Int = 9228
}

class GuestProfileActivity : ProfileActivity() {
    override val profileSuffix: String = "guest"
    override val baseCdpPort: Int = 9231
}
