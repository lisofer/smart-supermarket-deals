package com.lisofer.smartsupermarketdeals.web

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
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

  const sent = new Set();
  const send = (url, body) => {
    try {
      if (!body || body.length > 5000000) return;
      const value = body.trim();
      if (!(value.startsWith('{') || value.startsWith('['))) return;
      const fingerprint = (url || '') + '|' + value.length + '|' + value.slice(0, 140);
      if (sent.has(fingerprint)) return;
      sent.add(fingerprint);
      if (sent.size > 500) sent.delete(sent.values().next().value);
      if (window.SmartDealsBridge && window.SmartDealsBridge.postMessage) {
        window.SmartDealsBridge.postMessage(JSON.stringify({ url: url || '', body: value }));
      }
    } catch (_) {}
  };

  const sendObject = (url, value) => {
    try { send(url, JSON.stringify(value)); } catch (_) {}
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

  const emitEmbeddedJson = () => {
    try {
      const globals = [
        ['__NEXT_DATA__', window.__NEXT_DATA__],
        ['__PRELOADED_STATE__', window.__PRELOADED_STATE__],
        ['__APOLLO_STATE__', window.__APOLLO_STATE__],
      ];
      globals.forEach(([name, value]) => {
        if (value) sendObject(location.href + '#' + name, value);
      });

      document.querySelectorAll('script[type="application/json"], script#__NEXT_DATA__')
        .forEach((script, index) => {
          const text = script.textContent || '';
          if (text.length > 2 && text.length <= 5000000) {
            send(location.href + '#json-script-' + index, text);
          }
        });
    } catch (_) {}
  };

  const moneySource = '\\' + String.fromCharCode(36) + '\\s*[\\d][\\d.,]*';
  const moneyPattern = new RegExp(moneySource);
  const promoPattern = /(\d{1,2}\s*%|promo|oferta|descuento|ahorr|2\s*x\s*1|3\s*x\s*2|segunda\s+unidad)/i;
  const ignoredLinePattern = /^(agregar|sumar|ver más|envío|delivery|cerrar|buscar|inicio|categorías?)$/i;

  const textOf = element => (element && (element.innerText || element.textContent) || '')
    .replace(/\s+/g, ' ')
    .trim();

  const isCrossed = element => {
    if (!element) return false;
    const tag = (element.tagName || '').toLowerCase();
    if (tag === 's' || tag === 'del' || tag === 'strike') return true;
    try {
      return (getComputedStyle(element).textDecorationLine || '').includes('line-through');
    } catch (_) {
      return false;
    }
  };

  const preferredContainer = priceElement => {
    const preferred = priceElement.closest(
      'article, li, [role="listitem"], [data-testid*="product"], [class*="product"], [class*="Product"], [class*="item-card"]'
    );
    if (preferred) {
      const text = textOf(preferred);
      if (text.length >= 8 && text.length <= 1200) return preferred;
    }

    let node = priceElement;
    for (let depth = 0; depth < 7 && node; depth += 1, node = node.parentElement) {
      const text = textOf(node);
      const prices = text.match(new RegExp(moneySource, 'g')) || [];
      if (text.length >= 8 && text.length <= 650 && prices.length >= 1 && prices.length <= 5) {
        return node;
      }
    }
    return priceElement.parentElement;
  };

  const findName = container => {
    if (!container) return '';
    const preferred = container.querySelector(
      '[data-testid*="name"], [class*="name"], [class*="Name"], h1, h2, h3, h4, strong'
    );
    const preferredText = textOf(preferred);
    if (preferredText.length >= 3 && preferredText.length <= 180 && !moneyPattern.test(preferredText)) {
      return preferredText;
    }

    const lines = (container.innerText || container.textContent || '')
      .split(/\n+/)
      .map(line => line.replace(/\s+/g, ' ').trim())
      .filter(Boolean);
    return lines.find(line =>
      line.length >= 3 &&
      line.length <= 180 &&
      !moneyPattern.test(line) &&
      !promoPattern.test(line) &&
      !ignoredLinePattern.test(line)
    ) || '';
  };

  const scanVisibleCatalog = () => {
    try {
      const leaves = Array.from(document.querySelectorAll('body *'))
        .filter(element => element.children.length === 0 && moneyPattern.test(textOf(element)))
        .slice(0, 1800);
      const products = new Map();

      leaves.forEach(priceLeaf => {
        const container = preferredContainer(priceLeaf);
        if (!container) return;
        const name = findName(container);
        if (name.length < 3) return;

        const priceLeaves = Array.from(container.querySelectorAll('*'))
          .filter(element => element.children.length === 0 && moneyPattern.test(textOf(element)));
        if (moneyPattern.test(textOf(container)) && priceLeaves.length === 0) priceLeaves.push(container);

        const currentElement = priceLeaves.find(element => !isCrossed(element)) || priceLeaf;
        const originalElement = priceLeaves.find(element => isCrossed(element));
        const price = (textOf(currentElement).match(moneyPattern) || [])[0];
        const originalPrice = originalElement
          ? (textOf(originalElement).match(moneyPattern) || [])[0]
          : null;
        if (!price) return;

        const fullText = textOf(container);
        const promoMatch = fullText.match(promoPattern);
        const link = container.closest('a') || container.querySelector('a');
        const id = container.getAttribute('data-product-id') ||
          container.getAttribute('data-id') ||
          (link && link.getAttribute('href')) ||
          name;
        const key = String(id).slice(0, 220) + '|' + name;

        products.set(key, {
          id: String(id).slice(0, 220),
          name,
          price,
          originalPrice,
          promotionLabel: promoMatch ? promoMatch[0] : null,
          source: 'visible-dom',
        });
      });

      if (products.size > 0) {
        sendObject(location.href + '#visible-catalog', { products: Array.from(products.values()) });
      }
    } catch (_) {}
  };

  let scanTimer = null;
  const rescan = () => {
    clearTimeout(scanTimer);
    scanTimer = setTimeout(() => {
      emitEmbeddedJson();
      scanVisibleCatalog();
    }, 700);
  };
  window.__smartDealsRescan = rescan;

  new MutationObserver(rescan).observe(document.documentElement, {
    subtree: true,
    childList: true,
    characterData: true,
  });
  window.addEventListener('load', rescan);
  setTimeout(rescan, 1200);
  setTimeout(rescan, 3500);
  setTimeout(rescan, 8000);
})();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PedidosYaWebView(
    url: String,
    modifier: Modifier = Modifier,
    freshLoad: Boolean = false,
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
                settings.cacheMode = if (freshLoad) {
                    WebSettings.LOAD_NO_CACHE
                } else {
                    WebSettings.LOAD_DEFAULT
                }
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
                        view.evaluateJavascript("window.__smartDealsRescan && window.__smartDealsRescan();", null)
                        view.postDelayed({
                            runCatching {
                                view.evaluateJavascript(
                                    "window.__smartDealsRescan && window.__smartDealsRescan();",
                                    null,
                                )
                            }
                        }, 2_500)
                        view.postDelayed({
                            runCatching {
                                view.evaluateJavascript(
                                    "window.__smartDealsRescan && window.__smartDealsRescan();",
                                    null,
                                )
                            }
                        }, 6_500)
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
                if (freshLoad) clearCache(false)
                loadUrl(url)
            }
        },
        update = { webView ->
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
