package com.lisofer.smartsupermarketdeals.web

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

private val allowedOrigins = setOf(
    "https://pedidosya.com.ar",
    "https://*.pedidosya.com.ar",
)

private const val captureScript = """
(() => {
  if (window.__smartDealsCaptureInstalled) return;
  window.__smartDealsCaptureInstalled = true;

  const send = (url, body) => {
    try {
      if (!body || body.length > 2500000) return;
      const value = body.trim();
      if (!(value.startsWith('{') || value.startsWith('['))) return;
      if (window.SmartDealsBridge && window.SmartDealsBridge.postMessage) {
        window.SmartDealsBridge.postMessage(JSON.stringify({ url: url || '', body: value }));
      }
    } catch (_) {}
  };

  const originalFetch = window.fetch;
  if (originalFetch) {
    window.fetch = async function(...args) {
      const response = await originalFetch.apply(this, args);
      try {
        const clone = response.clone();
        const type = clone.headers.get('content-type') || '';
        if (type.includes('json')) clone.text().then(text => send(response.url, text));
      } catch (_) {}
      return response;
    };
  }

  const originalOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, url, ...rest) {
    this.__smartDealsUrl = url;
    return originalOpen.call(this, method, url, ...rest);
  };

  const originalSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function(...args) {
    this.addEventListener('load', function() {
      try {
        const type = this.getResponseHeader('content-type') || '';
        if (type.includes('json') && (!this.responseType || this.responseType === 'text')) {
          send(this.responseURL || this.__smartDealsUrl || '', this.responseText);
        }
      } catch (_) {}
    });
    return originalSend.apply(this, args);
  };
})();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PedidosYaWebView(
    url: String,
    modifier: Modifier = Modifier,
    onUrlChanged: (String) -> Unit = {},
    onTitleChanged: (String) -> Unit = {},
    onJsonPayload: (String) -> Unit = {},
    onPageFinished: () -> Unit = {},
    onUnsupportedWebView: () -> Unit = {},
) {
    val currentOnUrlChanged = rememberUpdatedState(onUrlChanged)
    val currentOnTitleChanged = rememberUpdatedState(onTitleChanged)
    val currentOnPayload = rememberUpdatedState(onJsonPayload)
    val currentOnPageFinished = rememberUpdatedState(onPageFinished)
    val currentOnUnsupported = rememberUpdatedState(onUnsupportedWebView)
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                webViewHolder[0] = this
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.setSupportMultipleWindows(false)
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

                    override fun onPageFinished(view: WebView, finishedUrl: String) {
                        currentOnUrlChanged.value(finishedUrl)
                        currentOnPageFinished.value()
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
                            message.data?.let(currentOnPayload.value)
                        }
                    }
                    WebViewCompat.addDocumentStartJavaScript(
                        this,
                        captureScript,
                        allowedOrigins,
                    )
                } else {
                    currentOnUnsupported.value()
                }
                tag = url
                loadUrl(url)
            }
        },
        update = { webView ->
            // Internal navigation must not be reset on every Compose recomposition.
            if (webView.tag != url) {
                webView.tag = url
                webView.loadUrl(url)
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            webViewHolder[0]?.apply {
                stopLoading()
                destroy()
            }
            webViewHolder[0] = null
        }
    }
}
