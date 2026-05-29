package com.google.android.youtube.pro.webview

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import com.google.android.youtube.pro.MainActivity

class YTProWebChromeClient(private val activity: MainActivity, private val web: YTProWebView) : WebChromeClient() {

    private var mCustomView: View? = null
    private var mCustomViewCallback: CustomViewCallback? = null
    private var mOriginalOrientation = 0
    private var mOriginalSystemUiVisibility = 0

    override fun getDefaultVideoPoster(): Bitmap? {
        return BitmapFactory.decodeResource(activity.applicationContext.resources, 2130837573)
    }

    override fun onShowCustomView(paramView: View, viewCallback: CustomViewCallback) {
        // 1. Determine orientation for FULL SCREEN
        mOriginalOrientation = if (activity.portrait)
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        if (activity.isPip) mOriginalOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activity.window.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            val params = activity.window.attributes
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            activity.window.attributes = params
        }

        if (mCustomView != null) {
            onHideCustomView()
            return
        }

        mCustomView = paramView
        mOriginalSystemUiVisibility = activity.window.decorView.systemUiVisibility

        // 2. Set the activity to full screen orientation (Landscape usually)
        activity.requestedOrientation = mOriginalOrientation

        // Store portrait so onHideCustomView knows what to go back to
        mOriginalOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        mCustomViewCallback = viewCallback
        (activity.window.decorView as FrameLayout).addView(mCustomView, FrameLayout.LayoutParams(-1, -1))
        activity.window.decorView.systemUiVisibility = 3846
    }

    override fun onHideCustomView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            val params = activity.window.attributes
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            activity.window.attributes = params
        }

        (activity.window.decorView as FrameLayout).removeView(mCustomView)
        mCustomView = null
        activity.window.decorView.systemUiVisibility = mOriginalSystemUiVisibility

        // 3. Set the activity BACK to the orientation saved right after going full screen (Portrait)
        activity.requestedOrientation = mOriginalOrientation

        // Reset state for the next time we enter full screen
        mOriginalOrientation = if (activity.portrait)
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        mCustomViewCallback = null
        web.clearFocus()
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        if (Build.VERSION.SDK_INT > 22 && request.origin.toString().contains("youtube.com")) {
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_DENIED) {
                activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            } else {
                request.grant(request.resources)
            }
        }
    }
}
