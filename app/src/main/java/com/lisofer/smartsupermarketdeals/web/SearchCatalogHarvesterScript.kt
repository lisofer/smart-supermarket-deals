package com.lisofer.smartsupermarketdeals.web

/**
 * Learns the real PedidosYa product-search request from one user search, then replays it with
 * blank/alphabetic queries and the endpoint's own pagination. This reaches products that are not
 * rendered on the store landing page. All requests execute asynchronously and responses are sent
 * to Android in bounded batches so the WebView rendering thread remains responsive.
 */
internal const val searchCatalogHarvesterScript = """
(() => {
  if (window.__smartDealsSearchHarvesterV11) return;
  window.__smartDealsSearchHarvesterV11 = true;

  const BASE_KEY = '__smartDealsSearchTemplateV11:';
  const DONE_KEY = '__smartDealsSearchDoneV11:';
  const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
  const clean = value => String(value == null ? '' : value).replace(/\s+/g, ' ').trim();
  const fold = value => clean(value).toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '');
  const absolute = raw => {
    try {
      const url = new URL(raw, location.href);
      url.hash = '';
      return url.toString();
    } catch (_) {
      return '';
    }
  };
  const post = value => {
    try { window.SmartDealsBridge?.postMessage(JSON.stringify(value)); } catch (_) {}
  };
  const progress = (phase, extra) => post(Object.assign({
    event: 'explore_progress',
    step: ++window.__smartDealsSearchStep,
    phase,
  }, extra || {}));
  window.__smartDealsSearchStep = window.__smartDealsSearchStep || 0;

  const queryKeys = new Set([
    'q', 'query', 'search', 'searchquery', 'search_query', 'term', 'keyword', 'keywords',
    'text', 'phrase', 'searchterm', 'search_term', 'querytext', 'query_text'
  ]);
  const pageKeys = new Set(['page', 'pagenumber', 'page_number', 'pageindex', 'page_index']);
  const offsetKeys = new Set(['offset', 'skip', 'start', 'from']);
  const limitKeys = new Set(['limit', 'pagesize', 'page_size', 'size', 'take', 'perpage', 'per_page']);
  const cursorKeys = new Set(['cursor', 'after', 'nextcursor', 'next_cursor', 'pagetoken', 'page_token']);
  const nameKeys = new Set(['name', 'title', 'productname', 'product_name', 'displayname', 'display_name', 'itemname', 'item_name']);
  const priceKeys = new Set(['price', 'currentprice', 'current_price', 'saleprice', 'sale_price', 'finalprice', 'final_price', 'discountedprice', 'discounted_price', 'promotionalprice', 'promotional_price', 'unitprice', 'unit_price']);
  const searchPathPattern = /(?:search|buscar|query|catalog|products?)/i;
  const sensitiveHeader = /^(?:authorization|cookie|set-cookie|proxy-authorization)$/i;
  const replayHeader = 'x-smart-deals-replay';

  let rootUrl = '';
  let waitingForSearch = false;
  let harvestRunning = false;
  let fallbackTimer = null;
  let originalStartExplore = window.__smartDealsStartExplore;
  let capturedInMemory = null;

  const rootFingerprint = () => {
    const source = absolute(rootUrl || location.href);
    let hash = 2166136261;
    for (let index = 0; index < source.length; index += 1) {
      hash ^= source.charCodeAt(index);
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(16);
  };
  const templateKey = () => BASE_KEY + rootFingerprint();
  const doneKey = () => DONE_KEY + rootFingerprint();

  const previousSetRoot = window.__smartDealsSetRoot;
  window.__smartDealsSetRoot = value => {
    rootUrl = absolute(value);
    try { previousSetRoot?.(value); } catch (_) {}
  };

  const headersObject = headers => {
    const output = {};
    try {
      new Headers(headers || {}).forEach((value, key) => {
        if (!sensitiveHeader.test(key) && fold(key) !== replayHeader) output[key] = value;
      });
    } catch (_) {}
    return output;
  };

  const cloneJson = value => {
    try { return JSON.parse(JSON.stringify(value)); } catch (_) { return null; }
  };
  const getPath = (object, path) => {
    let current = object;
    for (const part of path || []) {
      if (current == null) return undefined;
      current = current[part];
    }
    return current;
  };
  const setPath = (object, path, value) => {
    if (!object || !path || path.length === 0) return false;
    let current = object;
    for (let index = 0; index < path.length - 1; index += 1) {
      const part = path[index];
      if (current[part] == null || typeof current[part] !== 'object') return false;
      current = current[part];
    }
    current[path[path.length - 1]] = value;
    return true;
  };

  const findPath = (root, acceptedKeys, depthLimit) => {
    const queue = [{ value: root, path: [], depth: 0 }];
    const seen = new Set();
    while (queue.length > 0) {
      const current = queue.shift();
      if (!current || current.value == null || current.depth > depthLimit) continue;
      if (typeof current.value !== 'object') continue;
      if (seen.has(current.value)) continue;
      seen.add(current.value);
      for (const key of Object.keys(current.value)) {
        const normalized = fold(key).replace(/[\s-]/g, '_');
        if (acceptedKeys.has(normalized) || acceptedKeys.has(normalized.replace(/_/g, ''))) {
          return current.path.concat(key);
        }
        const child = current.value[key];
        if (child && typeof child === 'object') {
          queue.push({ value: child, path: current.path.concat(key), depth: current.depth + 1 });
        }
      }
    }
    return null;
  };

  const parseBody = body => {
    if (body == null || body === '') return { type: 'none', value: null };
    if (typeof body !== 'string') return { type: 'text', value: String(body) };
    const trimmed = body.trim();
    if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
      try { return { type: 'json', value: JSON.parse(trimmed) }; } catch (_) {}
    }
    try {
      const params = new URLSearchParams(trimmed);
      if (Array.from(params.keys()).length > 0) {
        const object = {};
        params.forEach((value, key) => { object[key] = value; });
        return { type: 'form', value: object };
      }
    } catch (_) {}
    return { type: 'text', value: body };
  };

  const productStats = text => {
    let root;
    try { root = JSON.parse(text); } catch (_) { return { count: 0, root: null }; }
    let count = 0;
    let visited = 0;
    const stack = [root];
    while (stack.length > 0 && visited < 60000 && count < 10000) {
      const value = stack.pop();
      visited += 1;
      if (!value || typeof value !== 'object') continue;
      if (Array.isArray(value)) {
        for (let index = 0; index < value.length; index += 1) stack.push(value[index]);
        continue;
      }
      const keys = Object.keys(value);
      const normalized = new Set(keys.map(key => fold(key).replace(/[\s-]/g, '_')));
      const hasName = Array.from(nameKeys).some(key => normalized.has(key) || normalized.has(key.replace(/_/g, '')));
      let hasPrice = Array.from(priceKeys).some(key => normalized.has(key) || normalized.has(key.replace(/_/g, '')));
      if (!hasPrice) {
        for (const wrapper of ['pricing', 'priceInfo', 'price_info', 'commercial', 'product', 'item']) {
          const child = value[wrapper];
          if (child && typeof child === 'object' && !Array.isArray(child)) {
            const childKeys = new Set(Object.keys(child).map(key => fold(key).replace(/[\s-]/g, '_')));
            hasPrice = Array.from(priceKeys).some(key => childKeys.has(key) || childKeys.has(key.replace(/_/g, '')));
            if (hasPrice) break;
          }
        }
      }
      if (hasName && hasPrice) count += 1;
      for (const key of keys) {
        const child = value[key];
        if (child && typeof child === 'object') stack.push(child);
      }
    }
    return { count, root };
  };

  const requestLooksSearchLike = request => {
    const url = absolute(request.url);
    if (!url) return false;
    try {
      const parsed = new URL(url);
      for (const key of parsed.searchParams.keys()) {
        const normalized = fold(key).replace(/[\s-]/g, '_');
        if (queryKeys.has(normalized) || queryKeys.has(normalized.replace(/_/g, ''))) return true;
      }
      if (searchPathPattern.test(parsed.pathname)) return true;
    } catch (_) {}
    const body = parseBody(request.body);
    if (body.type === 'json' && findPath(body.value, queryKeys, 8)) return true;
    if (body.type === 'form') {
      return Object.keys(body.value).some(key => {
        const normalized = fold(key).replace(/[\s-]/g, '_');
        return queryKeys.has(normalized) || queryKeys.has(normalized.replace(/_/g, ''));
      });
    }
    return /(?:search|query|keyword|term)/i.test(String(request.body || ''));
  };

  const buildTemplate = request => {
    const url = absolute(request.url);
    if (!url) return null;
    const parsedUrl = new URL(url);
    let query = null;
    for (const key of parsedUrl.searchParams.keys()) {
      const normalized = fold(key).replace(/[\s-]/g, '_');
      if (queryKeys.has(normalized) || queryKeys.has(normalized.replace(/_/g, ''))) {
        query = { location: 'url', key };
        break;
      }
    }

    const body = parseBody(request.body);
    if (!query && body.type === 'json') {
      const path = findPath(body.value, queryKeys, 9);
      if (path) query = { location: 'json', path };
    }
    if (!query && body.type === 'form') {
      const key = Object.keys(body.value).find(candidate => {
        const normalized = fold(candidate).replace(/[\s-]/g, '_');
        return queryKeys.has(normalized) || queryKeys.has(normalized.replace(/_/g, ''));
      });
      if (key) query = { location: 'form', key };
    }
    if (!query) return null;

    const page = body.type === 'json' ? findPath(body.value, pageKeys, 9) : null;
    const offset = body.type === 'json' ? findPath(body.value, offsetKeys, 9) : null;
    const limit = body.type === 'json' ? findPath(body.value, limitKeys, 9) : null;
    const cursor = body.type === 'json' ? findPath(body.value, cursorKeys, 9) : null;
    const urlPagination = {};
    for (const key of parsedUrl.searchParams.keys()) {
      const normalized = fold(key).replace(/[\s-]/g, '_');
      if (!urlPagination.page && (pageKeys.has(normalized) || pageKeys.has(normalized.replace(/_/g, '')))) urlPagination.page = key;
      if (!urlPagination.offset && (offsetKeys.has(normalized) || offsetKeys.has(normalized.replace(/_/g, '')))) urlPagination.offset = key;
      if (!urlPagination.limit && (limitKeys.has(normalized) || limitKeys.has(normalized.replace(/_/g, '')))) urlPagination.limit = key;
      if (!urlPagination.cursor && (cursorKeys.has(normalized) || cursorKeys.has(normalized.replace(/_/g, '')))) urlPagination.cursor = key;
    }

    return {
      version: 11,
      url,
      method: String(request.method || 'GET').toUpperCase(),
      headers: headersObject(request.headers),
      bodyType: body.type,
      bodyValue: body.value,
      query,
      pagination: {
        page: page ? { location: 'json', path: page } : urlPagination.page ? { location: 'url', key: urlPagination.page } : null,
        offset: offset ? { location: 'json', path: offset } : urlPagination.offset ? { location: 'url', key: urlPagination.offset } : null,
        limit: limit ? { location: 'json', path: limit } : urlPagination.limit ? { location: 'url', key: urlPagination.limit } : null,
        cursor: cursor ? { location: 'json', path: cursor } : urlPagination.cursor ? { location: 'url', key: urlPagination.cursor } : null,
      },
      capturedAt: Date.now(),
    };
  };

  const saveTemplate = template => {
    capturedInMemory = template;
    try { localStorage.setItem(templateKey(), JSON.stringify(template)); } catch (_) {}
  };
  const loadTemplate = () => {
    if (capturedInMemory) return capturedInMemory;
    try {
      const parsed = JSON.parse(localStorage.getItem(templateKey()) || 'null');
      if (parsed && parsed.version === 11 && parsed.url && parsed.query) return parsed;
    } catch (_) {}
    return null;
  };

  const showInstruction = () => {
    if (document.getElementById('__smartDealsSearchInstruction')) return;
    const panel = document.createElement('div');
    panel.id = '__smartDealsSearchInstruction';
    panel.textContent = 'Para leer el catálogo completo, buscá cualquier producto una sola vez en PedidosYa. Después la búsqueda continuará automáticamente.';
    panel.style.cssText = [
      'position:fixed', 'left:10px', 'right:10px', 'top:8px', 'z-index:2147483647',
      'background:#fff7d6', 'color:#241f00', 'border:1px solid #a88b00', 'border-radius:10px',
      'padding:10px 12px', 'font:600 14px sans-serif', 'box-shadow:0 2px 10px rgba(0,0,0,.22)',
      'pointer-events:none'
    ].join(';');
    document.documentElement.appendChild(panel);
  };
  const hideInstruction = () => document.getElementById('__smartDealsSearchInstruction')?.remove();

  const findNextCursor = root => {
    if (!root || typeof root !== 'object') return null;
    const queue = [root];
    let visited = 0;
    while (queue.length > 0 && visited < 30000) {
      const value = queue.shift();
      visited += 1;
      if (!value || typeof value !== 'object') continue;
      if (Array.isArray(value)) {
        value.slice(0, 1000).forEach(child => queue.push(child));
        continue;
      }
      for (const key of Object.keys(value)) {
        const normalized = fold(key).replace(/[\s-]/g, '_');
        if (['nextcursor', 'next_cursor', 'endcursor', 'end_cursor', 'nextpagetoken', 'next_page_token'].includes(normalized)) {
          const candidate = value[key];
          if (candidate != null && String(candidate).length > 0) return candidate;
        }
        const child = value[key];
        if (child && typeof child === 'object') queue.push(child);
      }
    }
    return null;
  };

  const compactProductObjects = root => {
    const output = [];
    const seen = new Set();
    let visited = 0;
    const keepKey = key => /(?:^id$|sku|ean|gtin|barcode|name|title|brand|image|picture|thumbnail|url|href|description|presentation|size|pack|unit|product|item|price|commercial|promo|discount|descuento|benefit|badge|offer|campaign|saving|deal|mechanic|condition|rule|metadata|attribute|tag)/i.test(key);
    const compact = (value, depth) => {
      if (value == null || depth > 8) return null;
      if (typeof value === 'string') return value.slice(0, 1200);
      if (typeof value === 'number' || typeof value === 'boolean') return value;
      if (Array.isArray(value)) return value.slice(0, 160).map(child => compact(child, depth + 1));
      if (typeof value === 'object') {
        const object = {};
        Object.keys(value).forEach(key => { if (keepKey(key)) object[key] = compact(value[key], depth + 1); });
        return object;
      }
      return null;
    };
    const stack = [root];
    while (stack.length > 0 && visited < 100000 && output.length < 50000) {
      const value = stack.pop();
      visited += 1;
      if (!value || typeof value !== 'object') continue;
      if (Array.isArray(value)) {
        value.forEach(child => stack.push(child));
        continue;
      }
      const stats = productStats(JSON.stringify(value));
      if (stats.count > 0) {
        const candidate = compact(value, 0);
        const signature = clean(JSON.stringify(candidate).slice(0, 1400));
        if (!seen.has(signature)) {
          seen.add(signature);
          if (candidate && typeof candidate === 'object') candidate.source = 'search-endpoint-fragment';
          output.push(candidate);
        }
      }
      Object.keys(value).forEach(key => {
        const child = value[key];
        if (child && typeof child === 'object') stack.push(child);
      });
    }
    return output;
  };

  const emitResponse = (url, text, parsedRoot) => {
    if (!text) return;
    if (text.length <= 1400000) {
      post({ url: url + '#search-harvest', body: text });
      return;
    }
    const products = compactProductObjects(parsedRoot);
    for (let index = 0; index < products.length; index += 90) {
      post({
        url: url + '#search-harvest-fragment-' + index,
        body: JSON.stringify({ products: products.slice(index, index + 90) }),
      });
    }
  };

  const applyValue = (target, descriptor, value) => {
    if (!descriptor) return;
    if (descriptor.location === 'url') target.url.searchParams.set(descriptor.key, String(value));
    if (descriptor.location === 'json') setPath(target.body, descriptor.path, value);
    if (descriptor.location === 'form') target.body[descriptor.key] = String(value);
  };

  const requestFor = (template, queryValue, pageIndex, cursorValue) => {
    const target = {
      url: new URL(template.url),
      body: cloneJson(template.bodyValue),
    };
    applyValue(target, template.query, queryValue);
    const pagination = template.pagination || {};
    const originalLimit = pagination.limit
      ? Number(pagination.limit.location === 'url'
        ? target.url.searchParams.get(pagination.limit.key)
        : getPath(target.body, pagination.limit.path))
      : 0;
    const limit = Number.isFinite(originalLimit) && originalLimit > 0 ? Math.min(100, Math.max(20, originalLimit)) : 50;
    if (pagination.limit) applyValue(target, pagination.limit, limit);
    if (pagination.page) {
      const initial = Number(pagination.page.location === 'url'
        ? new URL(template.url).searchParams.get(pagination.page.key)
        : getPath(template.bodyValue, pagination.page.path));
      const base = Number.isFinite(initial) ? initial : 0;
      applyValue(target, pagination.page, base + pageIndex);
    }
    if (pagination.offset) {
      const initial = Number(pagination.offset.location === 'url'
        ? new URL(template.url).searchParams.get(pagination.offset.key)
        : getPath(template.bodyValue, pagination.offset.path));
      const base = Number.isFinite(initial) ? initial : 0;
      applyValue(target, pagination.offset, base + pageIndex * limit);
    }
    if (pagination.cursor && cursorValue != null) applyValue(target, pagination.cursor, cursorValue);

    let body = null;
    if (template.bodyType === 'json') body = JSON.stringify(target.body);
    if (template.bodyType === 'form') body = new URLSearchParams(target.body || {}).toString();
    if (template.bodyType === 'text') body = String(template.bodyValue || '');
    return { url: target.url.toString(), body, limit };
  };

  const executeHarvest = async template => {
    if (harvestRunning) return;
    harvestRunning = true;
    waitingForSearch = false;
    hideInstruction();
    clearTimeout(fallbackTimer);
    progress('Consulta del buscador identificada; recorriendo catálogo interno', { learned: true });

    const queries = [''];
    'abcdefghijklmnopqrstuvwxyz0123456789'.split('').forEach(value => queries.push(value));
    let responses = 0;
    let productSignals = 0;
    const globalResponseHashes = new Set();

    for (let queryIndex = 0; queryIndex < queries.length; queryIndex += 1) {
      const query = queries[queryIndex];
      let cursor = null;
      const localHashes = new Set();
      const hasPagination = Boolean(template.pagination?.page || template.pagination?.offset || template.pagination?.cursor);
      const maxPages = hasPagination ? 14 : 1;

      for (let pageIndex = 0; pageIndex < maxPages; pageIndex += 1) {
        const built = requestFor(template, query, pageIndex, cursor);
        const headers = Object.assign({}, template.headers || {});
        headers[replayHeader] = '1';
        try {
          const response = await nativeFetch(built.url, {
            method: template.method,
            headers,
            body: ['GET', 'HEAD'].includes(template.method) ? undefined : built.body,
            credentials: 'include',
            cache: 'no-store',
          });
          const text = await response.text();
          if (!response.ok || !text) break;
          const signature = text.length + '|' + text.slice(0, 180) + '|' + text.slice(-120);
          if (localHashes.has(signature)) break;
          localHashes.add(signature);
          globalResponseHashes.add(signature);

          const stats = productStats(text);
          responses += 1;
          productSignals += stats.count;
          emitResponse(built.url, text, stats.root);
          progress('Consultando catálogo: búsqueda ' + (query || 'vacía') + ', página ' + (pageIndex + 1), {
            responses,
            productSignals,
            queryIndex: queryIndex + 1,
            queryTotal: queries.length,
          });

          if (stats.count === 0) break;
          if (template.pagination?.cursor) {
            const nextCursor = findNextCursor(stats.root);
            if (!nextCursor || String(nextCursor) === String(cursor || '')) break;
            cursor = nextCursor;
          } else if (!hasPagination || stats.count < Math.max(2, Math.floor(built.limit * 0.45))) {
            break;
          }
          await sleep(110);
        } catch (_) {
          break;
        }
      }
      await sleep(80);
    }

    try { localStorage.setItem(doneKey(), String(Date.now())); } catch (_) {}
    progress('Catálogo por buscador terminado', {
      responses,
      distinctResponses: globalResponseHashes.size,
      productSignals,
      searchHarvestComplete: true,
    });
    window.__smartDealsSearchFinished = true;
    harvestRunning = false;
    if (typeof originalStartExplore === 'function') {
      try { originalStartExplore(); } catch (_) {
        post({ event: 'explore_complete', searchHarvest: true, responses, productSignals });
      }
    } else {
      post({ event: 'explore_complete', searchHarvest: true, responses, productSignals });
    }
  };

  const considerCandidate = (request, text) => {
    if (!request || request.replay || !requestLooksSearchLike(request)) return;
    const stats = productStats(text);
    if (stats.count < 2) return;
    const template = buildTemplate(request);
    if (!template) return;
    saveTemplate(template);
    progress('Aprendí la consulta real del buscador de PedidosYa', { endpoint: true });
    if (waitingForSearch && !harvestRunning) executeHarvest(template);
  };

  const snapshotFetch = async (input, init) => {
    const request = input instanceof Request ? input : null;
    const url = request ? request.url : String(input || '');
    const method = String(init?.method || request?.method || 'GET').toUpperCase();
    const headers = Object.assign({}, headersObject(request?.headers), headersObject(init?.headers));
    let body = init?.body;
    if (body == null && request && !['GET', 'HEAD'].includes(method)) {
      try { body = await request.clone().text(); } catch (_) {}
    }
    return { url, method, headers, body: body == null ? null : String(body), replay: headers[replayHeader] === '1' };
  };

  const nativeFetch = window.fetch.bind(window);
  window.fetch = async function(input, init) {
    const snapshotPromise = snapshotFetch(input, init);
    const response = await nativeFetch(input, init);
    try {
      const snapshot = await snapshotPromise;
      if (!snapshot.replay) {
        response.clone().text().then(text => considerCandidate(snapshot, text)).catch(() => {});
      }
    } catch (_) {}
    return response;
  };

  const nativeOpen = XMLHttpRequest.prototype.open;
  const nativeSetHeader = XMLHttpRequest.prototype.setRequestHeader;
  const nativeSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(method, url, ...rest) {
    this.__smartDealsSearchRequest = { url, method: String(method || 'GET').toUpperCase(), headers: {}, body: null, replay: false };
    return nativeOpen.call(this, method, url, ...rest);
  };
  XMLHttpRequest.prototype.setRequestHeader = function(key, value) {
    if (this.__smartDealsSearchRequest && !sensitiveHeader.test(key)) {
      this.__smartDealsSearchRequest.headers[key] = value;
      if (fold(key) === replayHeader && String(value) === '1') this.__smartDealsSearchRequest.replay = true;
    }
    return nativeSetHeader.call(this, key, value);
  };
  XMLHttpRequest.prototype.send = function(body) {
    if (this.__smartDealsSearchRequest) this.__smartDealsSearchRequest.body = body == null ? null : String(body);
    this.addEventListener('load', () => {
      try {
        const request = this.__smartDealsSearchRequest;
        if (!request?.replay && (!this.responseType || this.responseType === 'text')) {
          considerCandidate(request, this.responseText || '');
        }
      } catch (_) {}
    });
    return nativeSend.call(this, body);
  };

  window.__smartDealsStartExplore = () => {
    if (harvestRunning || waitingForSearch) return;
    const template = loadTemplate();
    if (template) {
      executeHarvest(template);
      return;
    }
    waitingForSearch = true;
    showInstruction();
    progress('Necesito aprender el buscador: buscá cualquier producto una sola vez', { awaitingSearch: true });
    fallbackTimer = setTimeout(() => {
      if (!waitingForSearch || harvestRunning) return;
      waitingForSearch = false;
      hideInstruction();
      progress('No se detectó una búsqueda; continúo con el recorrido visual de respaldo', { searchFallback: true });
      if (typeof originalStartExplore === 'function') originalStartExplore();
      else post({ event: 'explore_complete', searchFallback: true });
    }, 105000);
  };
})();
"""
