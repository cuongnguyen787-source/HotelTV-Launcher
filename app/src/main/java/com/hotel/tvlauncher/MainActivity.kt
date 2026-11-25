package com.hotel.tvlauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.EditText
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private val baseUrl = "https://cuongnguyen787-source.github.io/HotelTV/tv-home.html"
    private var secretTapCount = 0
    private var lastSecretTapTime = 0L

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val tvPackages = listOf(
        Pair("com.nghenhin24h.vietplaytv", "Vietplaytv"),
        Pair("vn.vtvcab.vtvprime.atv", "VTVprime"),
        Pair("com.sports.tv", "SportsTV")
    )

    private val moviePackages = listOf(
        Pair("com.google.android.youtube.tv", "YouTube"),
        Pair("com.iqiyi.i18n.tv", "iQIYI"),
        Pair("com.sports.tv", "SportsTV")
    )

    private val directComponents = mapOf(
        "vn.vtvcab.vtvprime.atv" to ComponentName(
            "vn.vtvcab.vtvprime.atv",
            "vn.vtvcab.seenow.tv.presentation.splash.SplashActivity"
        )
    )

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

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveLaunchIntent(packageName: String): Intent? {
        directComponents[packageName]?.let { comp ->
            return Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                component = comp
            }
        }

        packageManager.getLaunchIntentForPackage(packageName)?.let { return it }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            packageManager.getLeanbackLaunchIntentForPackage(packageName)?.let { return it }
        }

        val categories = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
        for (cat in categories) {
            val queryIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(cat)
                setPackage(packageName)
            }
            val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                packageManager.queryIntentActivities(queryIntent, PackageManager.MATCH_ALL)
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(queryIntent, 0)
            }
            val first = list.firstOrNull()?.activityInfo
            if (first != null) {
                return Intent(Intent.ACTION_MAIN).apply {
                    addCategory(cat)
                    component = ComponentName(first.packageName, first.name)
                }
            }
        }

        return null
    }

    private fun drawableToBase64(drawable: Drawable): String? {
        return try {
            val bitmap: Bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                Bitmap.createScaledBitmap(drawable.bitmap, 128, 128, true)
            } else {
                val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 128
                val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 128
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                Bitmap.createScaledBitmap(bmp, 128, 128, true)
            }
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 85, stream)
            val bytes = stream.toByteArray()
            "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildAppsJson(items: List<Pair<String, String>>): String {
        val arr = JSONArray()
        for ((pkg, fallbackName) in items) {
            val installed = isPackageInstalled(pkg) || resolveLaunchIntent(pkg) != null
            var appLabel = fallbackName
            var iconBase64 = ""
            if (installed) {
                try {
                    val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        packageManager.getApplicationInfo(pkg, 0)
                    }
                    appLabel = packageManager.getApplicationLabel(appInfo).toString()
                    iconBase64 = drawableToBase64(packageManager.getApplicationIcon(appInfo)) ?: ""
                } catch (_: Exception) {
                }
            }
            val obj = JSONObject()
            obj.put("name", appLabel)
            obj.put("pkg", pkg)
            obj.put("installed", installed)
            obj.put("icon", iconBase64)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun openPackageNow(packageName: String, label: String = "") {
        runOnUiThread {
            try {
                val intent = resolveLaunchIntent(packageName)
                if (intent != null) {
                    intent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )
                    startActivity(intent)
                    return@runOnUiThread
                }

                if (isPackageInstalled(packageName)) {
                    Toast.makeText(
                        this@MainActivity,
                        "[v4] App $label không mở được từ bên ngoài",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val nameText = if (label.isNotBlank()) "$label ($packageName)" else packageName
                    Toast.makeText(this@MainActivity, "[v4] Chưa cài app: $nameText", Toast.LENGTH_SHORT).show()
                }
            } catch (ex: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "[v4] Lỗi mở $packageName: ${ex.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    inner class TvBridge {
        @JavascriptInterface
        fun getTvAppsJson(): String {
            return buildAppsJson(tvPackages)
        }

        @JavascriptInterface
        fun getMovieAppsJson(): String {
            return buildAppsJson(moviePackages)
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
