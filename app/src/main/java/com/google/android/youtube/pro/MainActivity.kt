package com.google.android.youtube.pro

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.youtube.pro.receivers.MediaCommandReceiver
import com.google.android.youtube.pro.webview.BinaryStreamManager
import com.google.android.youtube.pro.webview.WebAppInterface
import com.google.android.youtube.pro.webview.YTProWebChromeClient
import com.google.android.youtube.pro.webview.YTProWebView
import com.google.android.youtube.pro.webview.YTProWebViewClient

open class MainActivity : ComponentActivity() {

    @JvmField var portrait: Boolean = false
    @JvmField var isPlaying: Boolean = false
    @JvmField var mediaSession: Boolean = false
    @JvmField var isPip: Boolean = false
    @JvmField var dL: Boolean = false
    var isYouTubeMusic: Boolean = false

    lateinit var web: YTProWebView
    var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var broadcastReceiver: MediaCommandReceiver? = null
    private var backCallback: OnBackInvokedCallback? = null
    @JvmField var streamManager: BinaryStreamManager? = null

    private val isWebViewInitialized = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("YTPRO", MODE_PRIVATE)
        if (!prefs.contains("bgplay")) {
            prefs.edit().putBoolean("bgplay", false).apply()
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen() {
        var isMusicPlatform by remember { mutableStateOf(isYouTubeMusic) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (isMusicPlatform) "YouTube Music" else "YouTube Pro") },
                    actions = {
                        IconButton(onClick = {
                            isMusicPlatform = !isMusicPlatform
                            isYouTubeMusic = isMusicPlatform
                            val targetUrl = if (isYouTubeMusic) "https://music.youtube.com/" else "https://m.youtube.com/"
                            if (::web.isInitialized) {
                                web.loadUrl(targetUrl)
                            }
                        }) {
                            Icon(
                                imageVector = if (isMusicPlatform) Icons.Default.VideoLibrary else Icons.Default.MusicNote,
                                contentDescription = "Toggle Platform"
                            )
                        }
                    }
                )
            }
        ) { paddingValues ->
            YoutubeContent(modifier = Modifier.padding(paddingValues))
        }
    }

    @Composable
    fun YoutubeContent(modifier: Modifier = Modifier) {
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { context ->
                SwipeRefreshLayout(context).apply {
                    id = View.generateViewId()
                    this@MainActivity.swipeRefreshLayout = this

                    val webView = YTProWebView(context).apply {
                        id = View.generateViewId()
                        this@MainActivity.web = this
                    }

                    addView(webView)

                    setOnRefreshListener {
                        web.reload()
                    }

                    setupWebView()
                    load(dL || intent.action == "com.google.android.youtube.pro.DOWNLOAD")
                    setupReceiver()
                    setupBackNavigation()
                    streamManager = BinaryStreamManager(web, this@MainActivity)
                    isWebViewInitialized.value = true
                }
            },
            update = {
                // Update logic if needed
            }
        )
    }

    private fun setupWebView() {
        web.settings.javaScriptEnabled = true
        web.settings.setSupportZoom(true)
        web.settings.builtInZoomControls = true
        web.settings.displayZoomControls = false
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(web, true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            web.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        web.addJavascriptInterface(WebAppInterface(this, web), "Android")
        web.webChromeClient = YTProWebChromeClient(this, web)
        web.webViewClient = object : YTProWebViewClient(this, web) {
            override fun onPageStarted(view: android.webkit.WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url?.contains("music.youtube.com") == true) {
                    web.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                } else {
                    web.settings.userAgentString = null // Reset to default
                }
            }
        }
    }

    fun load(dl: Boolean) {
        this.dL = dl
        if (!::web.isInitialized) return

        val intent = intent
        val action = intent.action
        val data = intent.data
        var url = if (isYouTubeMusic) "https://music.youtube.com/" else "https://m.youtube.com/"
        if (Intent.ACTION_VIEW == action && data != null) {
            url = data.toString()
        } else if (Intent.ACTION_SEND == action) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedText != null && (sharedText.contains("youtube.com") || sharedText.contains("youtu.be"))) {
                url = sharedText
            }
        }
        web.loadUrl(url)
    }

    private fun setupReceiver() {
        broadcastReceiver = MediaCommandReceiver(web)
        if (Build.VERSION.SDK_INT >= 34 && applicationInfo.targetSdkVersion >= 34) {
            registerReceiver(broadcastReceiver, IntentFilter("TRACKS_TRACKS"), RECEIVER_EXPORTED)
        } else {
            registerReceiver(broadcastReceiver, IntentFilter("TRACKS_TRACKS"))
        }
    }

    private fun setupBackNavigation() {
        if (Build.VERSION.SDK_INT >= 33) {
            val dispatcher = onBackInvokedDispatcher
            backCallback = OnBackInvokedCallback { handleBackPress() }
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback!!)
        }
    }

    private fun handleBackPress() {
        if (::web.isInitialized && web.canGoBack()) {
            web.goBack()
        } else {
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        handleBackPress()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (::web.isInitialized) web.loadUrl("https://m.youtube.com")
            } else {
                Toast.makeText(applicationContext, getString(R.string.grant_mic), Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(applicationContext, getString(R.string.grant_storage), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        if (::web.isInitialized) web.evaluateJavascript(if (isInPictureInPictureMode) "PIPlayer();" else "removePIP();", null)
        isPip = isInPictureInPictureMode
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 26 && ::web.isInitialized && web.url != null && web.url!!.contains("watch")) {
            if (isPlaying) {
                try {
                    isPip = true
                    val params = PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(if (portrait) 9 else 16, if (portrait) 16 else 9))
                        .build()
                    enterPictureInPictureMode(params)
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(applicationContext, ForegroundService::class.java))
        if (broadcastReceiver != null) unregisterReceiver(broadcastReceiver)
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback!!)
        }
        streamManager?.cleanup()
    }
}
