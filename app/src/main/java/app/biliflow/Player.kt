@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.biliflow

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException

@SuppressLint("SetJavaScriptEnabled")
@Composable fun OfficialPage(url: String, onBack: () -> Unit, login: Boolean = false, model: MainViewModel? = null) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var webNavigation by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) { Lifecycle.Event.ON_RESUME -> webView?.onResume(); Lifecycle.Event.ON_PAUSE -> { webView?.onPause(); CookieManager.getInstance().flush() }; else -> Unit }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    PredictiveBackSurface(enabled = canGoBack, onBack = { webView?.goBack() }, resetKey = webNavigation, retainedDestination = true) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(if (login) "官方网页登录" else "B 站官方网页", style = MaterialTheme.typography.titleMedium) }, navigationIcon = {
            IconButton(onClick = { if (canGoBack) webView?.goBack() else onBack() }) { InterfaceIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
        }, actions = {
            IconButton(onClick = { webView?.reload() }) { InterfaceIcon(Icons.Rounded.Refresh, "刷新网页") }
            IconButton(onClick = { openExternal(context, webView?.url ?: url) }) { InterfaceIcon(Icons.AutoMirrored.Rounded.OpenInNew, "浏览器打开") }
            IconButton(onClick = onBack) { InterfaceIcon(Icons.Rounded.Close, "关闭网页") }
        })
        if (login) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (model?.account != null) "请在官方页面确认应用授权" else "完成短信或密码验证后同步账号", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    if (model?.account != null && !model.appAuthorized) model.startQrLogin() else model?.refreshAccount(reportMissing = true)
                }, enabled = model?.accountLoading != true) { Text(if (model?.account != null && !model.appAuthorized) "刷新授权" else "完成登录") }
            }
            model?.accountError?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failure) ErrorCard("网页暂时无法加载", { webView?.reload() }) { openExternal(context, url) }
        AndroidView(factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mediaPlaybackRequiresUserGesture = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(true)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                        val popup = WebView(view.context)
                        popup.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(child: WebView, request: WebResourceRequest): Boolean {
                                val target = request.url
                                if (target.scheme == "https" && (target.host == "bilibili.com" || target.host.orEmpty().endsWith(".bilibili.com"))) view.loadUrl(target.toString())
                                else if (isUserGesture) openExternal(context, target.toString())
                                child.post { child.destroy() }
                                return true
                            }
                        }
                        (resultMsg.obj as WebView.WebViewTransport).webView = popup
                        resultMsg.sendToTarget()
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val uri = request.url
                        val host = uri.host.orEmpty()
                        if (!request.isForMainFrame && uri.scheme == "https") return false
                        if (uri.scheme == "https" && (host == "bilibili.com" || host.endsWith(".bilibili.com") || host == "b23.tv")) return false
                        if (request.hasGesture()) openExternal(context, uri.toString())
                        return true
                    }
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { loading = true; failure = false; webNavigation++ }
                    override fun onPageFinished(view: WebView, url: String?) {
                        loading = false; canGoBack = view.canGoBack()
                        webNavigation++
                        if (login) model?.refreshAccount()
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                        if (request.isForMainFrame) { loading = false; failure = true }
                    }
                }
                webView = this
                loadUrl(url)
            }
        }, modifier = Modifier.fillMaxSize(), onRelease = { view -> webView = null; view.stopLoading(); view.onPause(); view.destroy() })
    }
    }
}
