package com.lisofer.smartsupermarketdeals.web

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

private val allowedOrigins = setOf(
    "https://pedidosya.com.ar",
    "https://*.pedidosya.com.ar",
)

private object PedidosYaSessionState {
    var addStoreWebState: Bundle? = null
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PedidosYaWebView(
    url: String,
    modifier: Modifier = Modifier,
    freshLoad: Boolean = false,
    autoExplore: Boolean = false,
    onUrlChanged: (String) -> Unit = {},
    onTitleChanged: (String) -> Unit = {},
    onJsonPayload: (String) -> Unit = {},
    onPageFinished: () -> Unit = {},
    onExplorationProgress: (Int) -> Unit = {},
    onExplorationFinished: () -> Unit = {},
    onUnsupportedWebView: () -> Unit = {},
) {
    val currentOnUrlChanged = rememberUpdatedState(onUrlChanged)
    val currentOnTitleChanged = rememberUpdatedState(onTitleChanged)
    val currentOnPayload = rememberUpdatedState(onJsonPayload)
    val currentOnPageFinished = rememberUpdatedState(onPageFinished)
    val currentOnExplorationProgress = rememberUpdatedState(onExplorationProgress)
    val currentOnExplorationFinished = rememberUpdatedState(onExplorationFinished)
    val currentOnUnsupported = rememberUpdatedState(onUnsupportedWebView)
    val currentRootUrl = rememberUpdatedState(url)
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }
    var canGoBack by remember { mutableStateOf(false) }

    BackHandler(enabled = !autoExplore && canGoBack) {
        webViewHolder[0]?.goBack()
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    webViewHolder[0] = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.setSupportMultipleWindows(false)
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            title?.takeIf { it.isNotBlank() }?.let(currentOnTitleChanged.value)
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val target = request.url
                            val host = target.host.orEmpty()
                            return if (host == "pedidosya.com.ar" || host.endsWith(".pedidosya.com.ar")) {
                                false
                            } else {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, target))
                                }
                                true
                            }
                        }

                        override fun doUpdateVisitedHistory(
                            view: WebView,
                            url: String?,
                            isReload: Boolean,
                        ) {
                            canGoBack = view.canGoBack()
                            url?.let(currentOnUrlChanged.value)
                            CookieManager.getInstance().flush()
                        }

                        override fun onPageFinished(view: WebView, finishedUrl: String) {
                            canGoBack = view.canGoBack()
                            currentOnUrlChanged.value(finishedUrl)
                            currentOnPageFinished.value()
                            CookieManager.getInstance().flush()

                            val rootLiteral = JSONObject.quote(currentRootUrl.value)
                            val initialScript =
                                "window.__smartDealsSetRoot && window.__smartDealsSetRoot($rootLiteral); " +
                                    "window.__smartDealsEmitEmbeddedJson && " +
                                    "window.__smartDealsEmitEmbeddedJson(); " +
                                    "window.__smartDealsPromotionDomScan && " +
                                    "window.__smartDealsPromotionDomScan(); " +
                                    "window.__smartDealsExhaustiveRescan && " +
                                    "window.__smartDealsExhaustiveRescan();"
                            view.evaluateJavascript(initialScript, null)
                            view.postDelayed({
                                runCatching { view.evaluateJavascript(initialScript, null) }
                            }, 1_800)

                            if (autoExplore) {
                                view.postDelayed({
                                    runCatching {
                                        view.evaluateJavascript(
                                            "window.__smartDealsSetRoot && " +
                                                "window.__smartDealsSetRoot($rootLiteral); " +
                                                "window.__smartDealsStartExplore && " +
                                                "window.__smartDealsStartExplore();",
                                            null,
                                        )
                                    }
                                }, 2_700)
                            }
                        }
                    }

                    val bridgeSupported = WebViewFeature.isFeatureSupported(
                        WebViewFeature.WEB_MESSAGE_LISTENER
                    )
                    val scriptSupported = WebViewFeature.isFeatureSupported(
                        WebViewFeature.DOCUMENT_START_SCRIPT
                    )
                    if (bridgeSupported && scriptSupported) {
                        WebViewCompat.addWebMessageListener(
                            this,
                            "SmartDealsBridge",
                            allowedOrigins,
                        ) { _, message, sourceOrigin, _, _ ->
                            val host = sourceOrigin.host.orEmpty()
                            if (host == "pedidosya.com.ar" || host.endsWith(".pedidosya.com.ar")) {
                                val raw = message.data ?: return@addWebMessageListener
                                val envelope = runCatching { JSONObject(raw) }.getOrNull()
                                when (envelope?.optString("event")) {
                                    "explore_progress" -> {
                                        currentOnExplorationProgress.value(envelope.optInt("step", 0))
                                    }
                                    "explore_complete" -> currentOnExplorationFinished.value()
                                    "explore_started", "catalog_routes", "route_change" -> Unit
                                    else -> currentOnPayload.value(raw)
                                }
                            }
                        }
                        WebViewCompat.addDocumentStartJavaScript(
                            this,
                            rawJsonCaptureScript,
                            allowedOrigins,
                        )
                        WebViewCompat.addDocumentStartJavaScript(
                            this,
                            promotionDomCaptureScript,
                            allowedOrigins,
                        )
                        WebViewCompat.addDocumentStartJavaScript(
                            this,
                            exhaustiveCatalogScript,
                            allowedOrigins,
                        )
                        WebViewCompat.addDocumentStartJavaScript(
                            this,
                            searchEndpointHarvesterScript,
                            allowedOrigins,
                        )
                    } else {
                        currentOnUnsupported.value()
                    }
                    tag = url

                    val restored = if (!autoExplore) {
                        PedidosYaSessionState.addStoreWebState?.let { savedState ->
                            restoreState(Bundle(savedState))
                        }
                    } else {
                        null
                    }
                    if (restored == null) {
                        if (freshLoad) clearCache(false)
                        loadUrl(url)
                    } else {
                        canGoBack = canGoBack()
                    }
                }
            },
            update = { webView ->
                if (webView.tag != url) {
                    webView.tag = url
                    if (freshLoad) webView.clearCache(false)
                    webView.loadUrl(url)
                }
            },
        )

        if (!autoExplore && canGoBack) {
            TextButton(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp),
                onClick = { webViewHolder[0]?.goBack() },
            ) {
                Text("← Atrás")
            }
        }
    }

    DisposableEffect(autoExplore) {
        onDispose {
            webViewHolder[0]?.apply {
                CookieManager.getInstance().flush()
                if (!autoExplore) {
                    val savedState = Bundle()
                    saveState(savedState)
                    PedidosYaSessionState.addStoreWebState = savedState
                }
                stopLoading()
                destroy()
            }
            webViewHolder[0] = null
        }
    }
}
