package com.lisofer.smartsupermarketdeals.web

/**
 * Learns a real product-search request from PedidosYa and replays it using the same session.
 * The original user query is validated first; only then are blank/alphabetic queries and the
 * request's own page, offset or cursor fields explored.
 */
internal const val searchEndpointHarvesterScript = """
(() => {
  if (window.__smartDealsEndpointHarvesterV11) return;
  window.__smartDealsEndpointHarvesterV11 = true;

  const TEMPLATE_PREFIX = '__smartDealsEndpointTemplateV11:';
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
  let progressStep = 0;
  const progress = (phase, extra) => post(Object.assign({
    event: 'explore_progress',
    step: ++progressStep,
    phase,
  }, extra || {}));

  const queryNames = new Set([
    'q', 'query', 'search', 'searchquery', 'search_query', 'term', 'keyword', 'keywords',
    'text', 'phrase', 'searchterm', 'search_term', 'querytext', 'query_text'
  ]);
  const pageNames = new Set(['page', 'pagenumber', 'page_number', 'pageindex', 'page_index']);
  const offsetNames = new Set(['offset', 'skip', 'start', 'from']);
  const limitNames = new Set(['limit', 'pagesize', 'page_size', 'size', 'take', 'perpage', 'per_page']);
  const cursorNames = new Set(['cursor', 'after', 'nextcursor', 'next_cursor', 'pagetoken', 'page_token']);
  const nameNames = new Set([
    'name', 'title', 'productname', 'product_name', 'displayname', 'display_name',
    'itemname', 'item_name'
  ]);
  const priceNames = new Set([
    'price', 'currentprice', 'current_price', 'saleprice', 'sale_price', 'finalprice',
    'final_price', 'discountedprice', 'discounted_price', 'promotionalprice',
    'promotional_price', 'unitprice', 'unit_price'
  ]);
  const searchPathPattern = /(?:search|buscar|query|catalog|products?)/i;
  const sensitiveHeaderPattern = /^(?:authorization|cookie|set-cookie|proxy-authorization)$/i;

  let storeRoot = '';
  let waiting = false;
  let running = false;
  let fallbackTimer = null;
  let inMemoryTemplate = null;
  const originalStartExplore = window.__smartDealsStartExplore;
  const transportFetch = window.fetch.bind(window);

  const rootHash = () => {
    const source = absolute(storeRoot || location.href);
    let hash = 2166136261;
    for (let index = 0; index < source.length; index += 1) {
      hash ^= source.charCodeAt(index);
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(16);
  };
  const templateKey = () => TEMPLATE_PREFIX + rootHash();

  const oldSetRoot = window.__smartDealsSetRoot;
  window.__smartDealsSetRoot = value => {
    storeRoot = absolute(value);
    try { oldSetRoot?.(value); } catch (_) {}
  };

  const normalizedKey = value => fold(value).replace(/[\s-]/g, '_');
  const keyMatches = (set, value) => {
    const normalized = normalizedKey(value);
    return set.has(normalized) || set.has(normalized.replace(/_/g, ''));
  };

  const headerMap = source => {
    const output = {};
    try {
      new Headers(source || {}).forEach((value, key) => {
        if (!sensitiveHeaderPattern.test(key)) output[key] = value;
      });
    } catch (_) {}
    return output;
  };

  const cloneJson = value => {
    try { return JSON.parse(JSON.stringify(value)); } catch (_) { return null; }
  };
  const getAtPath = (object, path) => {
    let current = object;
    for (const part of path || []) {
      if (current == null) return undefined;
      current = current[part];
    }
    return current;
  };
  const setAtPath = (object, path, value) => {
    if (!object || !path || path.length === 0) return false;
    let current = object;
    for (let index = 0; index < path.length - 1; index += 1) {
      const part = path[index];
      if (!current[part] || typeof current[part] !== 'object') return false;
      current = current[part];
    }
    current[path[path.length - 1]] = value;
    return true;
  };

  const findNamedPath = (root, accepted, maxDepth) => {
    if (!root || typeof root !== 'object') return null;
    const queue = [{ value: root, path: [], depth: 0 }];
    const seen = new Set();
    while (queue.length > 0) {
      const entry = queue.shift();
      if (!entry || !entry.value || typeof entry.value !== 'object' || entry.depth > maxDepth) continue;
      if (seen.has(entry.value)) continue;
      seen.add(entry.value);
      for (const key of Object.keys(entry.value)) {
        if (keyMatches(accepted, key)) return entry.path.concat(key);
        const child = entry.value[key];
        if (child && typeof child === 'object') {
          queue.push({ value: child, path: entry.path.concat(key), depth: entry.depth + 1 });
        }
      }
    }
    return null;
  };

  const parseBody = raw => {
    if (raw == null || raw === '') return { kind: 'none', value: null };
    const text = String(raw);
    const trimmed = text.trim();
    if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
      try { return { kind: 'json', value: JSON.parse(trimmed) }; } catch (_) {}
    }
    try {
      const params = new URLSearchParams(trimmed);
      if (Array.from(params.keys()).length > 0) {
        const value = {};
        params.forEach((item, key) => { value[key] = item; });
        return { kind: 'form', value };
      }
    } catch (_) {}
    return { kind: 'text', value: text };
  };

  const directValueByNames = (object, names) => {
    if (!object || typeof object !== 'object' || Array.isArray(object)) return undefined;
    for (const key of Object.keys(object)) {
      if (keyMatches(names, key)) return object[key];
    }
    return undefined;
  };

  const looksLikeProduct = object => {
    if (!object || typeof object !== 'object' || Array.isArray(object)) return false;
    const name = directValueByNames(object, nameNames);
    if (typeof name !== 'string' || clean(name).length < 2) return false;
    let price = directValueByNames(object, priceNames);
    if (price == null) {
      for (const wrapper of ['pricing', 'priceInfo', 'price_info', 'commercial', 'product', 'item']) {
        const child = object[wrapper];
        if (child && typeof child === 'object' && !Array.isArray(child)) {
          price = directValueByNames(child, priceNames);
          if (price != null) break;
        }
      }
    }
    return price != null;
  };

  const analyzeResponse = text => {
    let root;
    try { root = JSON.parse(text); } catch (_) { return { root: null, products: 0 }; }
    const stack = [root];
    let visited = 0;
    let products = 0;
    while (stack.length > 0 && visited < 90000 && products < 20000) {
      const value = stack.pop();
      visited += 1;
      if (!value || typeof value !== 'object') continue;
      if (Array.isArray(value)) {
        for (let index = 0; index < value.length; index += 1) stack.push(value[index]);
        continue;
      }
      if (looksLikeProduct(value)) products += 1;
      for (const key of Object.keys(value)) {
        const child = value[key];
        if (child && typeof child === 'object') stack.push(child);
      }
    }
    return { root, products };
  };

  const searchLikeRequest = request => {
    const target = absolute(request.url);
    if (!target) return false;
    try {
      const url = new URL(target);
      for (const key of url.searchParams.keys()) if (keyMatches(queryNames, key)) return true;
      if (searchPathPattern.test(url.pathname)) return true;
    } catch (_) {}
    const parsed = parseBody(request.body);
    if (parsed.kind === 'json' && findNamedPath(parsed.value, queryNames, 9)) return true;
    if (parsed.kind === 'form' && Object.keys(parsed.value).some(key => keyMatches(queryNames, key))) return true;
    return /(?:search|query|keyword|searchterm)/i.test(String(request.body || ''));
  };

  const locateUrlField = (url, names) => {
    for (const key of url.searchParams.keys()) if (keyMatches(names, key)) return key;
    return null;
  };

  const makeTemplate = request => {
    const requestUrl = absolute(request.url);
    if (!requestUrl) return null;
    const url = new URL(requestUrl);
    const parsedBody = parseBody(request.body);

    let query = null;
    const urlQuery = locateUrlField(url, queryNames);
    if (urlQuery) query = { place: 'url', key: urlQuery, sample: url.searchParams.get(urlQuery) || '' };
    if (!query && parsedBody.kind === 'json') {
      const path = findNamedPath(parsedBody.value, queryNames, 10);
      if (path) query = { place: 'json', path, sample: String(getAtPath(parsedBody.value, path) || '') };
    }
    if (!query && parsedBody.kind === 'form') {
      const key = Object.keys(parsedBody.value).find(candidate => keyMatches(queryNames, candidate));
      if (key) query = { place: 'form', key, sample: String(parsedBody.value[key] || '') };
    }
    if (!query || clean(query.sample).length === 0) return null;

    const pagination = { page: null, offset: null, limit: null, cursor: null };
    const urlPage = locateUrlField(url, pageNames);
    const urlOffset = locateUrlField(url, offsetNames);
    const urlLimit = locateUrlField(url, limitNames);
    const urlCursor = locateUrlField(url, cursorNames);
    if (urlPage) pagination.page = { place: 'url', key: urlPage };
    if (urlOffset) pagination.offset = { place: 'url', key: urlOffset };
    if (urlLimit) pagination.limit = { place: 'url', key: urlLimit };
    if (urlCursor) pagination.cursor = { place: 'url', key: urlCursor };

    if (parsedBody.kind === 'json') {
      const pagePath = findNamedPath(parsedBody.value, pageNames, 10);
      const offsetPath = findNamedPath(parsedBody.value, offsetNames, 10);
      const limitPath = findNamedPath(parsedBody.value, limitNames, 10);
      const cursorPath = findNamedPath(parsedBody.value, cursorNames, 10);
      if (!pagination.page && pagePath) pagination.page = { place: 'json', path: pagePath };
      if (!pagination.offset && offsetPath) pagination.offset = { place: 'json', path: offsetPath };
      if (!pagination.limit && limitPath) pagination.limit = { place: 'json', path: limitPath };
      if (!pagination.cursor && cursorPath) pagination.cursor = { place: 'json', path: cursorPath };
    }

    return {
      version: 11,
      url: requestUrl,
      method: String(request.method || 'GET').toUpperCase(),
      headers: headerMap(request.headers),
      bodyKind: parsedBody.kind,
      body: parsedBody.value,
      query,
      pagination,
      capturedAt: Date.now(),
    };
  };

  const saveTemplate = template => {
    inMemoryTemplate = template;
    try { localStorage.setItem(templateKey(), JSON.stringify(template)); } catch (_) {}
  };
  const removeTemplate = () => {
    inMemoryTemplate = null;
    try { localStorage.removeItem(templateKey()); } catch (_) {}
  };
  const loadTemplate = () => {
    if (inMemoryTemplate) return inMemoryTemplate;
    try {
      const parsed = JSON.parse(localStorage.getItem(templateKey()) || 'null');
      if (parsed?.version === 11 && parsed.url && parsed.query) return parsed;
    } catch (_) {}
    return null;
  };

  const instruction = message => {
    let panel = document.getElementById('__smartDealsEndpointInstruction');
    if (!panel) {
      panel = document.createElement('div');
      panel.id = '__smartDealsEndpointInstruction';
      panel.style.cssText = [
        'position:fixed', 'left:10px', 'right:10px', 'top:8px', 'z-index:2147483647',
        'background:#fff7d6', 'color:#241f00', 'border:1px solid #a88b00',
        'border-radius:10px', 'padding:10px 12px', 'font:600 14px sans-serif',
        'box-shadow:0 2px 10px rgba(0,0,0,.22)', 'pointer-events:none'
      ].join(';');
      document.documentElement.appendChild(panel);
    }
    panel.textContent = message;
  };
  const hideInstruction = () => document.getElementById('__smartDealsEndpointInstruction')?.remove();

  const descriptorValue = (template, descriptor) => {
    if (!descriptor) return undefined;
    if (descriptor.place === 'url') return new URL(template.url).searchParams.get(descriptor.key);
    if (descriptor.place === 'json') return getAtPath(template.body, descriptor.path);
    if (descriptor.place === 'form') return template.body?.[descriptor.key];
    return undefined;
  };

  const applyDescriptor = (target, descriptor, value) => {
    if (!descriptor) return;
    if (descriptor.place === 'url') target.url.searchParams.set(descriptor.key, String(value));
    if (descriptor.place === 'json') setAtPath(target.body, descriptor.path, value);
    if (descriptor.place === 'form' && target.body) target.body[descriptor.key] = String(value);
  };

  const buildRequest = (template, query, pageIndex, cursor) => {
    const target = { url: new URL(template.url), body: cloneJson(template.body) };
    applyDescriptor(target, template.query, query);

    const configuredLimit = Number(descriptorValue(template, template.pagination?.limit));
    const limit = Number.isFinite(configuredLimit) && configuredLimit > 0
      ? Math.min(100, Math.max(10, configuredLimit))
      : 40;
    if (template.pagination?.limit) applyDescriptor(target, template.pagination.limit, limit);

    if (template.pagination?.page) {
      const initial = Number(descriptorValue(template, template.pagination.page));
      applyDescriptor(target, template.pagination.page, (Number.isFinite(initial) ? initial : 0) + pageIndex);
    }
    if (template.pagination?.offset) {
      const initial = Number(descriptorValue(template, template.pagination.offset));
      applyDescriptor(target, template.pagination.offset, (Number.isFinite(initial) ? initial : 0) + pageIndex * limit);
    }
    if (template.pagination?.cursor && cursor != null) {
      applyDescriptor(target, template.pagination.cursor, cursor);
    }

    let body;
    if (template.bodyKind === 'json') body = JSON.stringify(target.body);
    if (template.bodyKind === 'form') body = new URLSearchParams(target.body || {}).toString();
    if (template.bodyKind === 'text') body = String(template.body || '');
    return { url: target.url.toString(), body, limit };
  };

  const nextCursorFrom = root => {
    if (!root || typeof root !== 'object') return null;
    const queue = [root];
    let visited = 0;
    while (queue.length > 0 && visited < 50000) {
      const value = queue.shift();
      visited += 1;
      if (!value || typeof value !== 'object') continue;
      if (Array.isArray(value)) {
        value.slice(0, 1200).forEach(child => queue.push(child));
        continue;
      }
      for (const key of Object.keys(value)) {
        const normalized = normalizedKey(key);
        if (['nextcursor', 'next_cursor', 'endcursor', 'end_cursor', 'nextpagetoken', 'next_page_token'].includes(normalized)) {
          const candidate = value[key];
          if (candidate != null && clean(candidate).length > 0) return candidate;
        }
        const child = value[key];
        if (child && typeof child === 'object') queue.push(child);
      }
    }
    return null;
  };

  const compactLargeResponse = root => {
    const products = [];
    const seen = new Set();
    const stack = [root];
    let visited = 0;
    const keepKey = key => /(?:^id$|sku|ean|gtin|barcode|name|title|brand|image|picture|thumbnail|url|href|description|presentation|size|pack|unit|product|item|price|commercial|promo|discount|descuento|benefit|badge|offer|campaign|saving|deal|mechanic|condition|rule|metadata|attribute|tag)/i.test(key);
    const compact = (value, depth) => {
      if (value == null || depth > 8) return null;
      if (typeof value === 'string') return value.slice(0, 1200);
      if (typeof value === 'number' || typeof value === 'boolean') return value;
      if (Array.isArray(value)) return value.slice(0, 180).map(child => compact(child, depth + 1));
      if (typeof value === 'object') {
        const result = {};
        for (const key of Object.keys(value)) if (keepKey(key)) result[key] = compact(value[key], depth + 1);
        return result;
      }
      return null;
    };

    while (stack.length > 0 && visited < 140000 && products.length < 60000) {
      const value = stack.pop();
      visited += 1;
      if (!value || typeof value !== 'object') continue;
      if (Array.isArray(value)) {
        value.forEach(child => stack.push(child));
        continue;
      }
      if (looksLikeProduct(value)) {
        const product = compact(value, 0);
        const signature = JSON.stringify(product).slice(0, 1800);
        if (!seen.has(signature)) {
          seen.add(signature);
          product.source = 'search-endpoint-fragment';
          products.push(product);
        }
      }
      for (const key of Object.keys(value)) {
        const child = value[key];
        if (child && typeof child === 'object') stack.push(child);
      }
    }
    return products;
  };

  const emitResponse = (url, text, root) => {
    if (text.length <= 1500000) {
      post({ url: url + '#endpoint-harvest', body: text });
      return;
    }
    const products = compactLargeResponse(root);
    for (let index = 0; index < products.length; index += 80) {
      post({
        url: url + '#endpoint-harvest-' + index,
        body: JSON.stringify({ products: products.slice(index, index + 80) }),
      });
    }
  };

  const execute = async (template, validateFirst) => {
    if (running) return;
    running = true;
    waiting = false;
    clearTimeout(fallbackTimer);
    instruction('Consulta interna encontrada. Validando y recorriendo el catálogo completo…');

    if (validateFirst) {
      try {
        const probe = buildRequest(template, template.query.sample, 0, null);
        const response = await transportFetch(probe.url, {
          method: template.method,
          headers: template.headers || {},
          body: ['GET', 'HEAD'].includes(template.method) ? undefined : probe.body,
          credentials: 'include',
          cache: 'no-store',
        });
        const text = await response.text();
        const analysis = analyzeResponse(text);
        if (!response.ok || analysis.products < 2) {
          removeTemplate();
          running = false;
          waiting = true;
          instruction('La consulta guardada ya no funciona. Buscá cualquier producto una vez para volver a aprenderla.');
          progress('La consulta guardada no devolvió productos; esperando una búsqueda nueva', { invalidTemplate: true });
          return;
        }
        emitResponse(probe.url, text, analysis.root);
      } catch (_) {
        removeTemplate();
        running = false;
        waiting = true;
        instruction('No pude validar la consulta. Buscá cualquier producto una vez para volver a aprenderla.');
        return;
      }
    }

    progress('Consulta real validada; iniciando cosecha del buscador', { endpointValidated: true });
    const queries = [''];
    'abcdefghijklmnopqrstuvwxyz0123456789'.split('').forEach(value => queries.push(value));
    let requests = 0;
    let productSignals = 0;
    let blankSignals = 0;
    const globalHashes = new Set();
    const maximumRequests = 280;

    for (let queryIndex = 0; queryIndex < queries.length && requests < maximumRequests; queryIndex += 1) {
      if (queryIndex > 0 && blankSignals >= 900) break;
      const query = queries[queryIndex];
      const paginated = Boolean(template.pagination?.page || template.pagination?.offset || template.pagination?.cursor);
      const maximumPages = paginated ? 12 : 1;
      const localHashes = new Set();
      let cursor = null;

      for (let pageIndex = 0; pageIndex < maximumPages && requests < maximumRequests; pageIndex += 1) {
        const built = buildRequest(template, query, pageIndex, cursor);
        try {
          const response = await transportFetch(built.url, {
            method: template.method,
            headers: template.headers || {},
            body: ['GET', 'HEAD'].includes(template.method) ? undefined : built.body,
            credentials: 'include',
            cache: 'no-store',
          });
          const text = await response.text();
          requests += 1;
          if (!response.ok || !text) break;

          const signature = text.length + '|' + text.slice(0, 160) + '|' + text.slice(-100);
          if (localHashes.has(signature)) break;
          localHashes.add(signature);
          globalHashes.add(signature);

          const analysis = analyzeResponse(text);
          if (analysis.products === 0) break;
          productSignals += analysis.products;
          if (query === '') blankSignals += analysis.products;
          emitResponse(built.url, text, analysis.root);
          progress('Buscador interno: consulta ' + (query || 'vacía') + ', página ' + (pageIndex + 1), {
            requests,
            productSignals,
            queryIndex: queryIndex + 1,
            queryTotal: queries.length,
          });

          if (template.pagination?.cursor) {
            const nextCursor = nextCursorFrom(analysis.root);
            if (!nextCursor || String(nextCursor) === String(cursor || '')) break;
            cursor = nextCursor;
          } else if (!paginated || analysis.products < Math.max(2, Math.floor(built.limit * 0.4))) {
            break;
          }
          await sleep(100);
        } catch (_) {
          break;
        }
      }
      await sleep(70);
    }

    hideInstruction();
    progress('Cosecha del buscador terminada; comprobando secciones visuales', {
      requests,
      distinctResponses: globalHashes.size,
      productSignals,
      endpointHarvestComplete: true,
    });
    window.__smartDealsSearchFinished = true;
    running = false;
    if (typeof originalStartExplore === 'function') {
      try { originalStartExplore(); } catch (_) {
        post({ event: 'explore_complete', endpointHarvest: true, requests, productSignals });
      }
    } else {
      post({ event: 'explore_complete', endpointHarvest: true, requests, productSignals });
    }
  };

  const candidate = (request, text) => {
    if (!request || !searchLikeRequest(request)) return;
    const analysis = analyzeResponse(text);
    if (analysis.products < 2) return;
    const template = makeTemplate(request);
    if (!template) return;
    saveTemplate(template);
    progress('Aprendí la consulta real de productos de PedidosYa', { endpointCaptured: true });
    if (waiting && !running) execute(template, false);
  };

  const snapshotFetch = async (input, init) => {
    const request = input instanceof Request ? input : null;
    const url = request ? request.url : String(input || '');
    const method = String(init?.method || request?.method || 'GET').toUpperCase();
    const headers = Object.assign({}, headerMap(request?.headers), headerMap(init?.headers));
    let body = init?.body;
    if (body == null && request && !['GET', 'HEAD'].includes(method)) {
      try { body = await request.clone().text(); } catch (_) {}
    }
    return { url, method, headers, body: body == null ? null : String(body) };
  };

  window.fetch = async function(input, init) {
    const snapshotPromise = snapshotFetch(input, init);
    const response = await transportFetch(input, init);
    try {
      const snapshot = await snapshotPromise;
      response.clone().text().then(text => candidate(snapshot, text)).catch(() => {});
    } catch (_) {}
    return response;
  };

  const nativeOpen = XMLHttpRequest.prototype.open;
  const nativeHeader = XMLHttpRequest.prototype.setRequestHeader;
  const nativeSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(method, url, ...rest) {
    this.__smartDealsEndpointRequest = {
      method: String(method || 'GET').toUpperCase(),
      url,
      headers: {},
      body: null,
    };
    return nativeOpen.call(this, method, url, ...rest);
  };
  XMLHttpRequest.prototype.setRequestHeader = function(key, value) {
    if (this.__smartDealsEndpointRequest && !sensitiveHeaderPattern.test(key)) {
      this.__smartDealsEndpointRequest.headers[key] = value;
    }
    return nativeHeader.call(this, key, value);
  };
  XMLHttpRequest.prototype.send = function(body) {
    if (this.__smartDealsEndpointRequest) this.__smartDealsEndpointRequest.body = body == null ? null : String(body);
    this.addEventListener('load', () => {
      try {
        if (!this.responseType || this.responseType === 'text') {
          candidate(this.__smartDealsEndpointRequest, this.responseText || '');
        }
      } catch (_) {}
    });
    return nativeSend.call(this, body);
  };

  window.__smartDealsStartExplore = () => {
    if (running || waiting) return;
    const template = loadTemplate();
    if (template) {
      execute(template, true);
      return;
    }
    waiting = true;
    instruction('Para aprender el catálogo real, buscá cualquier producto una sola vez en PedidosYa. Después continúa automáticamente.');
    progress('Esperando una búsqueda manual para aprender la consulta interna', { awaitingEndpoint: true });
    fallbackTimer = setTimeout(() => {
      if (!waiting || running) return;
      waiting = false;
      hideInstruction();
      progress('No se detectó una búsqueda; usando el recorrido visual como respaldo', { endpointFallback: true });
      if (typeof originalStartExplore === 'function') originalStartExplore();
      else post({ event: 'explore_complete', endpointFallback: true });
    }, 105000);
  };
})();
"""
