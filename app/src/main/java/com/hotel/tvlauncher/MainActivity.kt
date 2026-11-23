package com.hotel.tvlauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var zeroPressCount = 0
    private var lastZeroPressTime = 0L
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val baseUrl = "https://cuongnguyen787-source.github.io/HotelTV/tv-home.html"
    private val prefsName = "hotel_tv_prefs"
    private val keyRoom = "room_number"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(webView)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.useWideViewPort = false
        settings.loadWithOverviewMode = false
        settings.textZoom = 100

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                val decor = window.decorView as? ViewGroup
                decor?.addView(
                    customView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }

            override fun onHideCustomView() {
                val decor = window.decorView as? ViewGroup
                customView?.let { decor?.removeView(it) }
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webView.requestFocus()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    webView.postDelayed({ loadRoomUrl() }, 5000)
                }
            }
        }

        val savedRoom = getRoomNumber()
        if (savedRoom.isNullOrBlank()) {
            showRoomDialog(allowCancel = false)
        } else {
            loadRoomUrl()
        }
    }

    private fun getRoomNumber(): String? {
        return getSharedPreferences(prefsName, MODE_PRIVATE).getString(keyRoom, null)
    }

    private fun saveRoomNumber(room: String) {
        getSharedPreferences(prefsName, MODE_PRIVATE).edit().putString(keyRoom, room).apply()
    }

    private fun loadRoomUrl() {
        val room = getRoomNumber()?.takeIf { it.isNotBlank() } ?: "101"
        val target = "$baseUrl?room=${Uri.encode(room)}"
        webView.loadUrl(target)
    }

    private fun showRoomDialog(allowCancel: Boolean) {
        val input = EditText(this).apply {
            hint = "Nhập số phòng (vd: 101)"
            setText(getRoomNumber() ?: "101")
        }

        AlertDialog.Builder(this)
            .setTitle("Cấu hình phòng TV")
            .setView(input)
            .setCancelable(allowCancel)
            .setPositiveButton("Lưu") { _, _ ->
                val room = input.text.toString().trim().ifEmpty { "101" }
                saveRoomNumber(room)
                loadRoomUrl()
            }
            .setNeutralButton("Cài đặt Box") { _, _ ->
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
            .apply {
                if (allowCancel) {
                    setNegativeButton("Đóng", null)
                }
            }
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_0 || event.keyCode == KeyEvent.KEYCODE_NUMPAD_0) {
                val now = System.currentTimeMillis()
                if (now - lastZeroPressTime > 2000) {
                    zeroPressCount = 0
                }
                lastZeroPressTime = now
                zeroPressCount++
                if (zeroPressCount >= 5) {
                    zeroPressCount = 0
                    showRoomDialog(allowCancel = true)
                    return true
                }
            }

            if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            ) {
                webView.evaluateJavascript(
                    """
                    (function() {
                        var el = document.activeElement;
                        if (el && el !== document.body && el !== document.documentElement && typeof el.click === 'function') {
                            el.click();
                            return 'true';
                        }
                        return 'false';
                    })();
                    """.trimIndent(),
                    null
                )
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        if (customView != null) {
            onHideCustomView()
            return
        }
        webView.evaluateJavascript(
            "(typeof window.closePromoIfOpen === 'function') ? window.closePromoIfOpen() : false"
        ) { result ->
            if (result != "true") {
                // Giữ vai trò Launcher trên màn hình chính, không thoát trắng
            }
        }
    }

    private fun onHideCustomView() {
        (webView.webChromeClient as? WebChromeClient)?.onHideCustomView()
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun launchApp(packageName: String) {
            runOnUiThread {
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Chưa cài ứng dụng: $packageName",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
