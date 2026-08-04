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

private const val captureScript = """
(() => {
  if (window.__smartDealsCaptureInstalled) return;
  window.__smartDealsCaptureInstalled = true;

  const bridgePost = value => {
    try {
      if (window.SmartDealsBridge && window.SmartDealsBridge.postMessage) {
        window.SmartDealsBridge.postMessage(JSON.stringify(value));
      }
    } catch (_) {}
  };

  const sendEvent = (event, data) => bridgePost(Object.assign({ event }, data || {}));
  const sent = new Set();
  const quickHash = value => {
    let hash = 2166136261;
    const step = Math.max(1, Math.floor(value.length / 900));
    for (let index = 0; index < value.length; index += step) {
      hash ^= value.charCodeAt(index);
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(16);
  };

  const send = (url, body) => {
    try {
      if (!body || body.length > 6000000) return;
      const value = body.trim();
      if (!(value.startsWith('{') || value.startsWith('['))) return;
      const fingerprint = (url || '') + '|' + value.length + '|' + quickHash(value);
      if (sent.has(fingerprint)) return;
      sent.add(fingerprint);
      if (sent.size > 1600) sent.delete(sent.values().next().value);
      bridgePost({ url: url || '', body: value });
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
          if (text.length > 2 && text.length <= 6000000) {
            send(location.href + '#json-script-' + index, text);
          }
        });
    } catch (_) {}
  };

  const moneyPattern = /(?:\u0024|ARS\s*)\s*\d[\d.]*(?:,\d+)?/i;
  const promoPattern = /(?:-?\s*\d{1,3}(?:[.,]\d+)?\s*%\s*(?:off|dto|de descuento)?|\d{1,2}\s*[x×]\s*\d{1,2}|lleva(?:ndo)?\s*\d{1,2}.{0,30}?paga(?:ndo)?\s*\d{1,2}|(?:2\s*(?:da|do|°|º)?|segunda|segundo)\s*(?:unidad)?\s*(?:al|con|a|:)?\s*\d{1,3}(?:[.,]\d+)?\s*%?|promo(?:cion)?|oferta|descuento|ahorr(?:a|o|á))/i;
  const ignoredLinePattern = /^(agregar|sumar|ver más|envío|delivery|cerrar|buscar|inicio|categorías?|productos?)$/i;

  const textOf = element => (element && (element.innerText || element.textContent) || '')
    .replace(/\s+/g, ' ')
    .trim();

  const moneyMatches = text => text.match(new RegExp(moneyPattern.source, 'gi')) || [];

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
      'article, li, [role="listitem"], [data-testid*="product"], [class*="product"], [class*="Product"], [class*="item-card"], [class*="ItemCard"]'
    );
    if (preferred) {
      const text = textOf(preferred);
      if (text.length >= 8 && text.length <= 1800) return preferred;
    }

    let node = priceElement;
    for (let depth = 0; depth < 8 && node; depth += 1, node = node.parentElement) {
      const text = textOf(node);
      const prices = moneyMatches(text);
      if (text.length >= 8 && text.length <= 1100 && prices.length >= 1 && prices.length <= 8) {
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
    if (preferredText.length >= 3 && preferredText.length <= 200 && !moneyPattern.test(preferredText)) {
      return preferredText;
    }

    const lines = (container.innerText || container.textContent || '')
      .split(/\n+/)
      .map(line => line.replace(/\s+/g, ' ').trim())
      .filter(Boolean);
    return lines.find(line =>
      line.length >= 3 &&
      line.length <= 200 &&
      !moneyPattern.test(line) &&
      !promoPattern.test(line) &&
      !ignoredLinePattern.test(line)
    ) || '';
  };

  const scanVisibleCatalog = () => {
    try {
      const candidateNodes = new Set();
      document.querySelectorAll(
        'article, li, [role="listitem"], [data-testid*="product"], [class*="product"], [class*="Product"], [class*="item-card"], [class*="ItemCard"]'
      ).forEach(node => {
        const text = textOf(node);
        if (text.length >= 8 && text.length <= 1800 && moneyPattern.test(text)) {
          candidateNodes.add(node);
        }
      });

      Array.from(document.querySelectorAll('body *'))
        .filter(element => element.children.length === 0 && moneyPattern.test(textOf(element)))
        .slice(0, 3500)
        .forEach(priceLeaf => {
          const container = preferredContainer(priceLeaf);
          if (container) candidateNodes.add(container);
        });

      const products = new Map();
      Array.from(candidateNodes).slice(0, 3500).forEach(container => {
        const name = findName(container);
        if (name.length < 3) return;

        const priceLeaves = Array.from(container.querySelectorAll('*'))
          .filter(element => element.children.length === 0 && moneyPattern.test(textOf(element)));
        if (moneyPattern.test(textOf(container)) && priceLeaves.length === 0) priceLeaves.push(container);

        const nonCrossed = priceLeaves.filter(element => !isCrossed(element));
        const currentElement = nonCrossed[nonCrossed.length - 1] || priceLeaves[priceLeaves.length - 1];
        const originalElement = priceLeaves.find(element => isCrossed(element));
        const currentMatches = currentElement ? moneyMatches(textOf(currentElement)) : [];
        const originalMatches = originalElement ? moneyMatches(textOf(originalElement)) : [];
        const price = currentMatches[currentMatches.length - 1];
        const originalPrice = originalMatches[0] || null;
        if (!price) return;

        const fullText = textOf(container);
        const promoMatch = fullText.match(promoPattern);
        const link = container.closest('a') || container.querySelector('a');
        const id = container.getAttribute('data-product-id') ||
          container.getAttribute('data-id') ||
          container.getAttribute('data-testid') ||
          (link && link.getAttribute('href')) ||
          name;
        const key = String(id).slice(0, 280) + '|' + name + '|' + price;

        products.set(key, {
          id: String(id).slice(0, 280),
          name,
          price,
          originalPrice,
          promotionLabel: promoMatch ? promoMatch[0] : null,
          promotionText: promoMatch ? promoMatch[0] : null,
          source: 'visible-dom',
        });
      });

      if (products.size > 0) {
        sendObject(location.href + '#visible-catalog-' + Math.round(window.scrollY), {
          products: Array.from(products.values()),
        });
      }
      return products.size;
    } catch (_) {
      return 0;
    }
  };

  let scanTimer = null;
  const rescan = () => {
    clearTimeout(scanTimer);
    scanTimer = setTimeout(() => {
      emitEmbeddedJson();
      scanVisibleCatalog();
    }, 450);
  };
  window.__smartDealsRescan = rescan;

  const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
  window.__smartDealsStartExplore = async () => {
    if (window.__smartDealsExploring) return;
    window.__smartDealsExploring = true;
    const originalY = window.scrollY;
    let step = 0;
    let stableBottomPasses = 0;
    let previousHeight = 0;

    sendEvent('explore_started');
    await sleep(1600);

    for (let index = 0; index < 60; index += 1) {
      emitEmbeddedJson();
      scanVisibleCatalog();
      step += 1;
      sendEvent('explore_progress', { step });

      const documentHeight = Math.max(
        document.body ? document.body.scrollHeight : 0,
        document.documentElement ? document.documentElement.scrollHeight : 0
      );
      const maxY = Math.max(0, documentHeight - window.innerHeight);
      const currentY = window.scrollY;

      if (currentY >= maxY - 8) {
        stableBottomPasses = documentHeight === previousHeight ? stableBottomPasses + 1 : 0;
        previousHeight = documentHeight;
        if (stableBottomPasses >= 4) break;
        await sleep(1100);
      } else {
        const increment = Math.max(420, Math.round(window.innerHeight * 0.65));
        window.scrollTo(0, Math.min(maxY, currentY + increment));
        await sleep(700);
      }
    }

    const horizontalScrollers = Array.from(document.querySelectorAll('body *'))
      .filter(element =>
        element.scrollWidth > element.clientWidth + 120 &&
        element.clientWidth > 160 &&
        element.clientHeight < window.innerHeight * 0.9
      )
      .slice(0, 30);

    for (const scroller of horizontalScrollers) {
      const maxX = Math.max(0, scroller.scrollWidth - scroller.clientWidth);
      for (const ratio of [0, 0.33, 0.66, 1]) {
        scroller.scrollLeft = Math.round(maxX * ratio);
        await sleep(380);
        scanVisibleCatalog();
      }
    }

    window.scrollTo(0, originalY);
    await sleep(900);
    emitEmbeddedJson();
    const visibleCount = scanVisibleCatalog();
    sendEvent('explore_complete', { steps: step, visibleCount, url: location.href });
    window.__smartDealsExploring = false;
  };

  new MutationObserver(rescan).observe(document.documentElement, {
    subtree: true,
    childList: true,
    characterData: true,
  });
  window.addEventListener('load', rescan);
  setTimeout(rescan, 900);
  setTimeout(rescan, 2500);
  setTimeout(rescan, 6000);
  setTimeout(rescan, 12000);
})();
"""

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
                            view.evaluateJavascript(
                                "window.__smartDealsRescan && window.__smartDealsRescan();",
                                null,
                            )
                            view.postDelayed({
                                runCatching {
                                    view.evaluateJavascript(
                                        "window.__smartDealsRescan && window.__smartDealsRescan();",
                                        null,
                                    )
                                }
                            }, 2_500)
                            if (autoExplore) {
                                view.postDelayed({
                                    runCatching {
                                        view.evaluateJavascript(
                                            "window.__smartDealsStartExplore && window.__smartDealsStartExplore();",
                                            null,
                                        )
                                    }
                                }, 4_000)
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
                                    "explore_started" -> Unit
                                    else -> currentOnPayload.value(raw)
                                }
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
