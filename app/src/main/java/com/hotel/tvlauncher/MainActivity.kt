package com.hotel.tvlauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.useWideViewPort = false
        settings.loadWithOverviewMode = false
        settings.textZoom = 100

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val fixJs = """
                    (function() {
                        var meta = document.querySelector('meta[name="viewport"]');
                        if (!meta) {
                            meta = document.createElement('meta');
                            meta.name = 'viewport';
                            document.head.appendChild(meta);
                        }
                        meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
                        document.documentElement.style.zoom = '1';
                        document.body.style.zoom = '1';
                    })();
                """.trimIndent()
                view?.evaluateJavascript(fixJs, null)
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
        webView.loadUrl("$baseUrl?room=$room")
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

    inner class TvBridge {
        @JavascriptInterface
        fun getTvAppsJson(): String {
            val candidatePackages = listOf(
                "com.vtvprime.tv",
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
        fun launchApp(packageName: String, action: String, room: String) {
            runOnUiThread {
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } else {
                    Toast.makeText(this@MainActivity, "Chưa cài ứng dụng: $packageName", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
