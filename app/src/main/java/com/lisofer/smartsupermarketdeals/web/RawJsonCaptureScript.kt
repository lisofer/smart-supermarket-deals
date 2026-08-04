package com.lisofer.smartsupermarketdeals.web

/** Captures bounded JSON only while browsing manually; endpoint scans use compact batches. */
internal const val rawJsonCaptureScript = """

(() => {
  if (window.__smartDealsRawCaptureInstalled) return;
  window.__smartDealsRawCaptureInstalled = true;

  const MAX_JSON_CHARS = 420000;
  const endpointMode = () => Boolean(
    window.__smartDealsEndpointMode || Number(window.__smartDealsHarvestTransportDepth || 0) > 0
  );
  const post = value => {
    try {
      if (window.SmartDealsBridge && window.SmartDealsBridge.postMessage) {
        window.SmartDealsBridge.postMessage(JSON.stringify(value));
      }
    } catch (_) {}
  };
  const sent = new Set();
  const useful = value => {
    const sample = value.length <= 180000
      ? value
      : value.slice(0, 90000) + value.slice(-90000);
    return /(?:product|item|price|pricing|promo|discount|descuento|benefit|offer|commercial)/i.test(sample);
  };
  const quickHash = value => {
    let hash = 2166136261;
    const step = Math.max(1, Math.floor(value.length / 800));
    for (let index = 0; index < value.length; index += step) {
      hash ^= value.charCodeAt(index);
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(16);
  };
  const send = (url, body) => {
    try {
      if (endpointMode() || !body || body.length > MAX_JSON_CHARS) return;
      const value = body.trim();
      if (!(value.startsWith('{') || value.startsWith('[')) || !useful(value)) return;
      const fingerprint = String(url || '') + '|' + value.length + '|' + quickHash(value);
      if (sent.has(fingerprint)) return;
      sent.add(fingerprint);
      if (sent.size > 900) sent.delete(sent.values().next().value);
      post({ url: url || '', body: value });
    } catch (_) {}
  };
  const contentLengthAllowed = response => {
    try {
      const raw = response.headers.get('content-length');
      return !raw || Number(raw) <= MAX_JSON_CHARS;
    } catch (_) { return true; }
  };

  const previousFetch = window.fetch;
  if (previousFetch && !previousFetch.__smartDealsRawV12Wrapped) {
    const wrappedFetch = async function(...args) {
      const skipCapture = endpointMode();
      const response = await previousFetch.apply(this, args);
      try {
        const type = response.headers.get('content-type') || '';
        if (!skipCapture && type.includes('json') && contentLengthAllowed(response)) {
          response.clone().text().then(text => send(response.url, text)).catch(() => {});
        }
      } catch (_) {}
      return response;
    };
    wrappedFetch.__smartDealsRawV12Wrapped = true;
    window.fetch = wrappedFetch;
  }

  const previousOpen = XMLHttpRequest.prototype.open;
  if (previousOpen && !previousOpen.__smartDealsRawV12Wrapped) {
    const wrappedOpen = function(method, url, ...rest) {
      this.__smartDealsUrl = url;
      this.__smartDealsSkipRawCapture = endpointMode();
      return previousOpen.call(this, method, url, ...rest);
    };
    wrappedOpen.__smartDealsRawV12Wrapped = true;
    XMLHttpRequest.prototype.open = wrappedOpen;
  }

  const previousSend = XMLHttpRequest.prototype.send;
  if (previousSend && !previousSend.__smartDealsRawV12Wrapped) {
    const wrappedSend = function(...args) {
      this.addEventListener('load', function() {
        try {
          if (this.__smartDealsSkipRawCapture || endpointMode()) return;
          const type = this.getResponseHeader('content-type') || '';
          const length = Number(this.getResponseHeader('content-length') || 0);
          if (type.includes('json') && (!length || length <= MAX_JSON_CHARS) &&
              (!this.responseType || this.responseType === 'text')) {
            send(this.responseURL || this.__smartDealsUrl || '', this.responseText || '');
          }
        } catch (_) {}
      }, { once: true });
      return previousSend.apply(this, args);
    };
    wrappedSend.__smartDealsRawV12Wrapped = true;
    XMLHttpRequest.prototype.send = wrappedSend;
  }

  const emitEmbeddedJson = () => {
    try {
      if (endpointMode()) return;
      const globals = [
        ['__NEXT_DATA__', window.__NEXT_DATA__],
        ['__PRELOADED_STATE__', window.__PRELOADED_STATE__],
        ['__APOLLO_STATE__', window.__APOLLO_STATE__],
        ['__INITIAL_STATE__', window.__INITIAL_STATE__],
        ['__NUXT__', window.__NUXT__],
      ];
      globals.forEach(([name, value]) => {
        if (!value) return;
        try {
          const text = JSON.stringify(value);
          send(location.href + '#' + name, text);
        } catch (_) {}
      });
      Array.from(document.querySelectorAll(
        'script[type="application/json"], script[type="application/ld+json"], script#__NEXT_DATA__'
      )).slice(0, 40).forEach((script, index) => {
        const text = script.textContent || '';
        send(location.href + '#json-script-' + index, text);
      });
    } catch (_) {}
  };

  window.__smartDealsEmitEmbeddedJson = emitEmbeddedJson;
})();

"""
