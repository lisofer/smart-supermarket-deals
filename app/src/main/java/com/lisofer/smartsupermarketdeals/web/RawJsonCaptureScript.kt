package com.lisofer.smartsupermarketdeals.web

/** Captures ordinary network and embedded JSON. Oversized responses are handled by the exhaustive layer. */
internal const val rawJsonCaptureScript = """
(() => {
  if (window.__smartDealsRawCaptureInstalled) return;
  window.__smartDealsRawCaptureInstalled = true;

  const post = value => {
    try {
      if (window.SmartDealsBridge && window.SmartDealsBridge.postMessage) {
        window.SmartDealsBridge.postMessage(JSON.stringify(value));
      }
    } catch (_) {}
  };
  const sent = new Set();
  const quickHash = value => {
    let hash = 2166136261;
    const step = Math.max(1, Math.floor(value.length / 1200));
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
      const fingerprint = String(url || '') + '|' + value.length + '|' + quickHash(value);
      if (sent.has(fingerprint)) return;
      sent.add(fingerprint);
      if (sent.size > 4000) sent.delete(sent.values().next().value);
      post({ url: url || '', body: value });
    } catch (_) {}
  };
  const sendObject = (url, value) => {
    try { send(url, JSON.stringify(value)); } catch (_) {}
  };

  const previousFetch = window.fetch;
  if (previousFetch && !previousFetch.__smartDealsRawWrapped) {
    const wrappedFetch = async function(...args) {
      const response = await previousFetch.apply(this, args);
      try {
        const clone = response.clone();
        const type = clone.headers.get('content-type') || '';
        if (type.includes('json')) clone.text().then(text => send(response.url, text));
      } catch (_) {}
      return response;
    };
    wrappedFetch.__smartDealsRawWrapped = true;
    window.fetch = wrappedFetch;
  }

  const previousOpen = XMLHttpRequest.prototype.open;
  if (previousOpen && !previousOpen.__smartDealsRawWrapped) {
    const wrappedOpen = function(method, url, ...rest) {
      this.__smartDealsUrl = url;
      return previousOpen.call(this, method, url, ...rest);
    };
    wrappedOpen.__smartDealsRawWrapped = true;
    XMLHttpRequest.prototype.open = wrappedOpen;
  }

  const previousSend = XMLHttpRequest.prototype.send;
  if (previousSend && !previousSend.__smartDealsRawWrapped) {
    const wrappedSend = function(...args) {
      this.addEventListener('load', function() {
        try {
          const type = this.getResponseHeader('content-type') || '';
          if (type.includes('json') && (!this.responseType || this.responseType === 'text')) {
            send(this.responseURL || this.__smartDealsUrl || '', this.responseText || '');
          }
        } catch (_) {}
      });
      return previousSend.apply(this, args);
    };
    wrappedSend.__smartDealsRawWrapped = true;
    XMLHttpRequest.prototype.send = wrappedSend;
  }

  const emitEmbeddedJson = () => {
    try {
      const globals = [
        ['__NEXT_DATA__', window.__NEXT_DATA__],
        ['__PRELOADED_STATE__', window.__PRELOADED_STATE__],
        ['__APOLLO_STATE__', window.__APOLLO_STATE__],
        ['__INITIAL_STATE__', window.__INITIAL_STATE__],
        ['__NUXT__', window.__NUXT__],
      ];
      globals.forEach(([name, value]) => {
        if (value) sendObject(location.href + '#' + name, value);
      });
      document.querySelectorAll(
        'script[type="application/json"], script[type="application/ld+json"], script#__NEXT_DATA__'
      ).forEach((script, index) => {
        const text = script.textContent || '';
        if (text.length > 2 && text.length <= 6000000) {
          send(location.href + '#json-script-' + index, text);
        }
      });
    } catch (_) {}
  };

  let timer = null;
  const rescan = () => {
    clearTimeout(timer);
    timer = setTimeout(emitEmbeddedJson, 500);
  };
  window.__smartDealsEmitEmbeddedJson = emitEmbeddedJson;
  new MutationObserver(rescan).observe(document.documentElement, {
    subtree: true,
    childList: true,
    characterData: false,
  });
  window.addEventListener('load', emitEmbeddedJson);
  setTimeout(emitEmbeddedJson, 700);
  setTimeout(emitEmbeddedJson, 2500);
  setTimeout(emitEmbeddedJson, 6500);
})();
"""
