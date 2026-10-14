package com.example.parkinson.ui.screens.call

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

/**
 * A voice or video call with the doctor. The call itself is the server's call page (WebRTC) in a WebView, the same one
 * the doctor's panel uses, so the app needs no calling library of its own. [pageUrl] carries the phone token after #.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CallScreen(pageUrl: String, video: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val closeScreen by rememberUpdatedState(onClose)
    val needed = remember(video) {
        listOfNotNull(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA.takeIf { video }).toTypedArray()
    }
    var asked by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { asked = true }
    LaunchedEffect(Unit) {
        val missing = needed.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) asked = true else permissions.launch(missing.toTypedArray())
    }
    if (!asked) {
        Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        return
    }

    val server = remember(pageUrl) { Uri.parse(pageUrl).let { it.scheme + "://" + it.authority } }
    var webView by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            // Leaving the screen ends the call: the page's connection closes and the server tells the doctor.
            webView?.apply { loadUrl("about:blank"); destroy() }
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                keepScreenOn = true
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    // Only the doctor's server: a link elsewhere does not open inside the call screen.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.let { it.scheme + "://" + it.authority } != server
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) {
                        val allowed = setOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE, PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                        val origin = request.origin.let { it.scheme + "://" + it.authority }
                        if (origin == server && request.resources.all { it in allowed }) request.grant(request.resources) else request.deny()
                    }
                }
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun close() {
                        Handler(Looper.getMainLooper()).post { closeScreen() }
                    }
                }, "ParkinsonApp")
                loadUrl(pageUrl)
                webView = this
            }
        },
    )
}
