package com.hotel.tvlauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.EditText
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private val baseUrl = "https://cuongnguyen787-source.github.io/HotelTV/tv-home.html"
    private var secretTapCount = 0
    private var lastSecretTapTime = 0L

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(webView)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.useWideViewPort = false
        settings.loadWithOverviewMode = false
        settings.textZoom = 100

        webView.clearCache(true)

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
                webView.evaluateJavascript("window.syncFromCloud && window.syncFromCloud()", null)
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

        webView.addJavascriptInterface(TvBridge(), "AndroidBridge")

        val savedRoom = getRoomCode()
        if (savedRoom.isNullOrBlank()) {
            promptRoomSetup(isInitial = true)
        } else {
            loadRoomUrl()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.requestFocus()
            webView.evaluateJavascript("window.syncFromCloud && window.syncFromCloud()", null)
        }
    }

    private fun getRoomCode(): String? {
        return getSharedPreferences("hotel_tv_prefs", Context.MODE_PRIVATE)
            .getString("room_code", null)
    }

    private fun saveRoomCode(room: String) {
        getSharedPreferences("hotel_tv_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("room_code", room.trim())
            .apply()
    }

    private fun loadRoomUrl() {
        val room = getRoomCode() ?: "101"
        webView.clearCache(true)
        webView.loadUrl("$baseUrl?room=$room&_t=${System.currentTimeMillis()}")
    }

    private fun promptRoomSetup(isInitial: Boolean) {
        val input = EditText(this)
        input.hint = "Ví dụ: 101"
        input.setText(getRoomCode() ?: "")

        val builder = AlertDialog.Builder(this)
            .setTitle("Cài đặt số phòng cho Box TV")
            .setMessage("Nhập số phòng (101 - 510):")
            .setView(input)
            .setCancelable(!isInitial)
            .setPositiveButton("Lưu") { _, _ ->
                val valRoom = input.text.toString().trim()
                if (valRoom.isNotEmpty()) {
                    saveRoomCode(valRoom)
                    loadRoomUrl()
                } else {
                    promptRoomSetup(isInitial)
                }
            }

        if (!isInitial) {
            builder.setNegativeButton("Đóng", null)
            builder.setNeutralButton("Cài đặt Android") { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            }
        }
        builder.show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (customView != null) {
                    (webView.webChromeClient as? WebChromeClient)?.onHideCustomView()
                    return true
                }
                webView.evaluateJavascript("window.handleAndroidBack && window.handleAndroidBack()", null)
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_0) {
                val now = System.currentTimeMillis()
                secretTapCount = if (now - lastSecretTapTime < 1500) secretTapCount + 1 else 1
                lastSecretTapTime = now
                if (secretTapCount >= 5) {
                    secretTapCount = 0
                    promptRoomSetup(isInitial = false)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun openPackageNow(packageName: String, label: String = "") {
        runOnUiThread {
            try {
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } else {
                    val nameText = if (label.isNotBlank()) "$label ($packageName)" else packageName
                    Toast.makeText(this@MainActivity, "Chưa cài app: $nameText", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Không mở được app: $packageName", Toast.LENGTH_SHORT).show()
            }
        }
    }

    inner class TvBridge {
        @JavascriptInterface
        fun getTvAppsJson(): String {
            val candidatePackages = listOf(
                "com.nghenhin24h.vietplaytv",
                "vn.vtvcab.vtvprime.atv",
                "com.apptv.tvplus",
                "com.fptplay.activity",
                "ar.tvplayer.tv",
                "studio.scillarium.ottnavigator",
                "org.xbmc.kodi"
            )
            val arr = JSONArray()
            for (pkg in candidatePackages) {
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    val appInfo = packageManager.getApplicationInfo(pkg, 0)
                    val label = packageManager.getApplicationLabel(appInfo).toString()
                    val obj = JSONObject()
                    obj.put("name", label)
                    obj.put("pkg", pkg)
                    arr.put(obj)
                }
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun launchTvApp(packageName: String, appName: String, room: String) {
            openPackageNow(packageName, appName)
        }

        @JavascriptInterface
        fun launchApp(packageName: String, action: String, room: String) {
            openPackageNow(packageName, action)
        }
    }
}
