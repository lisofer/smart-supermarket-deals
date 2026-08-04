package com.lisofer.smartsupermarketdeals.web

/** Automatic, adaptive catalog harvesting through the store search endpoint. */
internal const val searchEndpointHarvesterScript = """
(() => {
  if (window.__smartDealsEndpointHarvesterV12) return;
  window.__smartDealsEndpointHarvesterV12 = true;

  const VERSION = 12;
  const TEMPLATE_PREFIX = '__smartDealsEndpointTemplateV12:';
  const LEGACY_PREFIX = '__smartDealsEndpointTemplateV11:';
  const MAX_REQUESTS = 360;
  const MAX_PAGES_PER_QUERY = 150;
  const MAX_PREFIX_DEPTH = 3;
  const PAGE_CONCURRENCY = 4;
  const ALPHABET = 'abcdefghijklmnopqrstuvwxyz0123456789';
  const AUTO_TERMS = ['a', 'leche', 'arroz'];
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
    try {
      if (window.SmartDealsBridge && window.SmartDealsBridge.postMessage) {
        window.SmartDealsBridge.postMessage(JSON.stringify(value));
      }
    } catch (_) {}
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
    'promotional_price', 'unitprice', 'unit_price', 'pricewithdiscount', 'price_with_discount'
  ]);
  const idNames = new Set([
    'productid', 'product_id', 'itemid', 'item_id', 'sku', 'skuid', 'sku_id',
    'barcode', 'gtin', 'ean', 'id'
  ]);
  const imageNames = new Set(['image', 'imageurl', 'image_url', 'picture', 'thumbnail', 'photo']);
  const totalNames = new Set([
    'totalcount', 'total_count', 'totalelements', 'total_elements', 'totalresults',
    'total_results', 'totalitems', 'total_items', 'resultcount', 'result_count'
  ]);
  const hasNextNames = new Set(['hasnext', 'has_next', 'hasmore', 'has_more', 'moreavailable', 'more_available']);
  const nextCursorNames = new Set([
    'nextcursor', 'next_cursor', 'endcursor', 'end_cursor', 'nextpagetoken',
    'next_page_token', 'nexttoken', 'next_token'
  ]);
  const searchPathPattern = /(?:search|buscar|query|catalog|products?)/i;
  const sensitiveHeaderPattern = /^(?:authorization|cookie|set-cookie|proxy-authorization|content-length|host)${'$'}/i;
  const exactPromotionSignal = /(?:\b(?:2\s*\.?\s*(?:da|do|°|º)|segunda|segundo)\b.{0,60}\b(?:unidad|producto|item)?\b.{0,60}\d{1,3}(?:[.,]\d+)?\s*(?:%|off|dto|descuento)|\b\d{1,2}\s*[x×]\s*\d{1,2}\b|\b(?:descuento|ahorra|promo|oferta|off|dto)\b.{0,30}\d{1,3}(?:[.,]\d+)?\s*%|\b\d{1,3}(?:[.,]\d+)?\s*%\s*(?:off|dto|de descuento|descuento)\b)/i;

  let storeRoot = '';
  let waiting = false;
  let running = false;
  let fallbackTimer = null;
  let inMemoryTemplate = null;
  const originalStartExplore = window.__smartDealsStartExplore;
  const transportFetch = window.fetch.bind(window);
  window.__smartDealsEndpointMode = false;

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
  const legacyTemplateKey = () => LEGACY_PREFIX + rootHash();

  const oldSetRoot = window.__smartDealsSetRoot;
  window.__smartDealsSetRoot = value => {
    storeRoot = absolute(value);
    try {
      if (typeof oldSetRoot === 'function') oldSetRoot(value);
    } catch (_) {}
  };

  const normalizedKey = value => fold(value).replace(/[\s-]/g, '_');
  const keyMatches = (set, value) => {
    const normalized = normalizedKey(value);
    return set.has(normalized) || set.has(normalized.replace(/_/g, ''));
  };
  const isProductCollectionKey = key => {
    const value = normalizedKey(key);
    return value === 'products' || value === 'items' || value === 'productlist' ||
      value === 'product_list' || value === 'catalogitems' || value === 'catalog_items' ||
      value === 'results' || value === 'entries' || value === 'elements' ||
      value === 'skus' || value === 'variants' || value === 'children' ||
      value.includes('products') || value.includes('product_list') ||
      value.includes('catalogitem');
  };
  const isPromoKey = key => /(?:promo|discount|descuento|benefit|badge|offer|campaign|saving|deal|commercial|mechanic|condition|rule)/i.test(key);

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
      for (const wrapper of ['pricing', 'priceInfo', 'price_info', 'prices', 'commercial', 'product', 'item']) {
        const child = object[wrapper];
        if (child && typeof child === 'object' && !Array.isArray(child)) {
          price = directValueByNames(child, priceNames);
          if (price != null) break;
        }
      }
    }
    return price != null;
  };

  const compactValue = (value, depth) => {
    if (value == null || depth > 8) return null;
    if (typeof value === 'string') return value.slice(0, 900);
    if (typeof value === 'number' || typeof value === 'boolean') return value;
    if (Array.isArray(value)) return value.slice(0, 80).map(child => compactValue(child, depth + 1));
    if (typeof value === 'object') {
      const result = {};
      const keepKey = key => /(?:^id${'$'}|sku|ean|gtin|barcode|name|title|brand|image|picture|thumbnail|url|href|description|presentation|size|pack|unit|product|item|price|commercial|promo|discount|descuento|benefit|badge|offer|campaign|saving|deal|mechanic|condition|rule|metadata|attribute|tag|scope|type|kind|value|amount|percentage|percent)/i.test(key);
      for (const key of Object.keys(value)) {
        if (keepKey(key)) result[key] = compactValue(value[key], depth + 1);
      }
      return result;
    }
    return null;
  };

  const sectionPromotion = object => {
    if (!object || typeof object !== 'object' || Array.isArray(object)) return null;
    const hasCollection = Object.keys(object).some(key => isProductCollectionKey(key));
    if (!hasCollection) return null;
    const snapshot = {};
    for (const key of Object.keys(object)) {
      if (isProductCollectionKey(key)) continue;
      const value = object[key];
      if (isPromoKey(key)) snapshot[key] = compactValue(value, 0);
      if (typeof value === 'string' && exactPromotionSignal.test(fold(value))) snapshot[key] = value.slice(0, 500);
    }
    const text = JSON.stringify(snapshot);
    if (!text || !exactPromotionSignal.test(fold(text))) return null;
    snapshot.__smartDealsInherited = true;
    return snapshot;
  };

  const productSignature = product => {
    const id = clean(directValueByNames(product, idNames));
    const name = fold(directValueByNames(product, nameNames));
    let price = directValueByNames(product, priceNames);
    if (price == null && product.pricing && typeof product.pricing === 'object') {
      price = directValueByNames(product.pricing, priceNames);
    }
    const image = clean(directValueByNames(product, imageNames));
    return (id || name) + '|' + clean(price) + '|' + image.slice(0, 140);
  };

  const promotionQuality = product => {
    let score = 0;
    const text = JSON.stringify(product).slice(0, 12000);
    if (/(?:original|regular|previous|list|before|old|strike|crossed)[_-]?price/i.test(text)) score += 120;
    if (exactPromotionSignal.test(fold(text))) score += 100;
    if (/(?:promotion|commercial|benefit|mechanic|discount)/i.test(text)) score += 30;
    return score + Math.min(20, Object.keys(product || {}).length);
  };

  const collectProducts = root => {
    const products = [];
    const stack = [{ value: root, inherited: null, depth: 0 }];
    let visited = 0;
    while (stack.length > 0 && visited < 160000 && products.length < 60000) {
      const entry = stack.pop();
      visited += 1;
      const value = entry && entry.value;
      if (!value || typeof value !== 'object' || entry.depth > 28) continue;
      if (Array.isArray(value)) {
        for (let index = value.length - 1; index >= 0; index -= 1) {
          stack.push({ value: value[index], inherited: entry.inherited, depth: entry.depth + 1 });
        }
        continue;
      }

      if (looksLikeProduct(value)) {
        const product = compactValue(value, 0) || {};
        const productPromotionText = fold(JSON.stringify(product).slice(0, 12000));
        const hasOwnPromotion = exactPromotionSignal.test(productPromotionText) ||
          /second[_\s-]*(?:unit|item|product)|segunda\s+unidad|2\s*\.?\s*(?:da|do)/i.test(productPromotionText);
        if (entry.inherited && !hasOwnPromotion) {
          product.__smartDealsSectionPromotion = entry.inherited;
        }
        product.source = 'search-endpoint-v12';
        products.push(product);
      }

      const localPromotion = sectionPromotion(value);
      for (const key of Object.keys(value)) {
        const child = value[key];
        if (!child || typeof child !== 'object') continue;
        const inherited = localPromotion && isProductCollectionKey(key)
          ? localPromotion
          : entry.inherited;
        stack.push({ value: child, inherited, depth: entry.depth + 1 });
      }
    }
    return products;
  };

  const pagingMetadata = root => {
    const queue = [root];
    let visited = 0;
    let totalCount = null;
    let hasNext = null;
    let nextCursor = null;
    while (queue.length > 0 && visited < 45000) {
      const value = queue.shift();
      visited += 1;
      if (!value || typeof value !== 'object') continue;
      if (Array.isArray(value)) {
        for (let index = 0; index < Math.min(value.length, 500); index += 1) queue.push(value[index]);
        continue;
      }
      for (const key of Object.keys(value)) {
        const child = value[key];
        if (keyMatches(totalNames, key)) {
          const number = Number(child);
          if (Number.isFinite(number) && number >= 0 && number <= 200000) {
            totalCount = totalCount == null ? number : Math.max(totalCount, number);
          }
        }
        if (keyMatches(hasNextNames, key) && typeof child === 'boolean') hasNext = child;
        if (keyMatches(nextCursorNames, key) && child != null && clean(child)) nextCursor = child;
        if (child && typeof child === 'object') queue.push(child);
      }
    }
    return { totalCount, hasNext, nextCursor };
  };

  const analyzeResponse = text => {
    let root;
    try { root = JSON.parse(text); } catch (_) { return { root: null, products: [], totalCount: null, hasNext: null, nextCursor: null }; }
    const products = collectProducts(root);
    const paging = pagingMetadata(root);
    return Object.assign({ root, products }, paging);
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
      const key = Object.keys(parsedBody.value).find(candidateKey => keyMatches(queryNames, candidateKey));
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
      version: VERSION,
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
    try {
      localStorage.removeItem(templateKey());
      localStorage.removeItem(legacyTemplateKey());
    } catch (_) {}
  };
  const loadTemplate = () => {
    if (inMemoryTemplate) return inMemoryTemplate;
    try {
      const current = JSON.parse(localStorage.getItem(templateKey()) || 'null');
      if (current && current.url && current.query) {
        current.version = VERSION;
        inMemoryTemplate = current;
        return current;
      }
      const legacy = JSON.parse(localStorage.getItem(legacyTemplateKey()) || 'null');
      if (legacy && legacy.url && legacy.query) {
        legacy.version = VERSION;
        saveTemplate(legacy);
        return legacy;
      }
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
  const hideInstruction = () => {
    const panel = document.getElementById('__smartDealsEndpointInstruction');
    if (panel) panel.remove();
  };

  const descriptorValue = (template, descriptor) => {
    if (!descriptor) return undefined;
    if (descriptor.place === 'url') return new URL(template.url).searchParams.get(descriptor.key);
    if (descriptor.place === 'json') return getAtPath(template.body, descriptor.path);
    if (descriptor.place === 'form') return template.body && template.body[descriptor.key];
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

    const configuredLimit = Number(descriptorValue(template, template.pagination && template.pagination.limit));
    const limit = Number.isFinite(configuredLimit) && configuredLimit > 0
      ? Math.min(120, Math.max(100, configuredLimit))
      : 100;
    if (template.pagination && template.pagination.limit) {
      applyDescriptor(target, template.pagination.limit, limit);
    }

    if (template.pagination && template.pagination.page) {
      const initial = Number(descriptorValue(template, template.pagination.page));
      applyDescriptor(target, template.pagination.page, (Number.isFinite(initial) ? initial : 0) + pageIndex);
    }
    if (template.pagination && template.pagination.offset) {
      const initial = Number(descriptorValue(template, template.pagination.offset));
      applyDescriptor(target, template.pagination.offset, (Number.isFinite(initial) ? initial : 0) + pageIndex * limit);
    }
    if (template.pagination && template.pagination.cursor && cursor != null) {
      applyDescriptor(target, template.pagination.cursor, cursor);
    }

    let body;
    if (template.bodyKind === 'json') body = JSON.stringify(target.body);
    if (template.bodyKind === 'form') body = new URLSearchParams(target.body || {}).toString();
    if (template.bodyKind === 'text') body = String(template.body || '');
    return { url: target.url.toString(), body, limit };
  };

  const harvestFetch = async (url, options) => {
    window.__smartDealsHarvestTransportDepth = Number(window.__smartDealsHarvestTransportDepth || 0) + 1;
    try {
      return await transportFetch(url, options);
    } finally {
      window.__smartDealsHarvestTransportDepth = Math.max(
        0,
        Number(window.__smartDealsHarvestTransportDepth || 1) - 1,
      );
    }
  };

  const globalBest = new Map();
  let globalProductCount = 0;
  const emitProducts = (url, products) => {
    const fresh = [];
    for (const product of products || []) {
      const signature = productSignature(product);
      if (!signature || signature === '||') continue;
      const quality = promotionQuality(product);
      const previous = globalBest.get(signature);
      if (previous != null && previous >= quality) continue;
      globalBest.set(signature, quality);
      if (previous == null) globalProductCount += 1;
      fresh.push(product);
    }
    for (let index = 0; index < fresh.length; index += 60) {
      post({
        url: url + '#endpoint-v12-' + index,
        body: JSON.stringify({ products: fresh.slice(index, index + 60) }),
      });
    }
    return fresh.length;
  };

  const responseSignature = text => {
    const source = String(text || '');
    return source.length + '|' + source.slice(0, 120) + '|' + source.slice(-80);
  };

  const fetchPage = async (template, query, pageIndex, cursor) => {
    const built = buildRequest(template, query, pageIndex, cursor);
    const response = await harvestFetch(built.url, {
      method: template.method,
      headers: template.headers || {},
      body: ['GET', 'HEAD'].includes(template.method) ? undefined : built.body,
      credentials: 'include',
      cache: 'no-store',
    });
    const text = await response.text();
    if (!response.ok || !text) return { ok: false, built, text: '', analysis: null };
    return { ok: true, built, text, analysis: analyzeResponse(text) };
  };

  const execute = async (template, validateFirst) => {
    if (running) return;
    running = true;
    waiting = false;
    window.__smartDealsEndpointMode = true;
    clearTimeout(fallbackTimer);
    instruction('Consulta interna encontrada. Recorriendo el catálogo automáticamente…');

    let requests = 0;
    if (validateFirst) {
      try {
        const probe = await fetchPage(template, template.query.sample, 0, null);
        requests += 1;
        if (!probe.ok || !probe.analysis || probe.analysis.products.length < 2) {
          removeTemplate();
          running = false;
          waiting = false;
          window.__smartDealsEndpointMode = false;
          hideInstruction();
          progress('La consulta guardada dejó de funcionar; intentando aprenderla de nuevo', { invalidTemplate: true });
          autoLearn();
          return;
        }
        emitProducts(probe.built.url, probe.analysis.products);
      } catch (_) {
        removeTemplate();
        running = false;
        waiting = false;
        window.__smartDealsEndpointMode = false;
        hideInstruction();
        autoLearn();
        return;
      }
    }

    progress('Consulta real validada; recorriendo páginas y subdividiendo solo donde hace falta', {
      endpointValidated: true,
    });

    const queued = new Set();
    const queue = [];
    const enqueue = query => {
      if (queued.has(query)) return;
      queued.add(query);
      queue.push(query);
    };
    enqueue('');

    const harvestQuery = async query => {
      const localResponses = new Set();
      const localProducts = new Set();
      let pages = 0;
      let lastFull = false;
      let reportedTotal = null;
      let remainingCursor = null;
      let cursor = null;
      const hasPageMode = Boolean(template.pagination && (template.pagination.page || template.pagination.offset));
      const hasCursorMode = Boolean(template.pagination && template.pagination.cursor);

      const consume = result => {
        if (!result || !result.ok || !result.analysis) return { accepted: false, stop: true };
        const signature = responseSignature(result.text);
        if (localResponses.has(signature)) return { accepted: false, stop: true };
        localResponses.add(signature);
        pages += 1;
        reportedTotal = result.analysis.totalCount == null
          ? reportedTotal
          : Math.max(reportedTotal || 0, result.analysis.totalCount);
        remainingCursor = result.analysis.nextCursor;
        for (const product of result.analysis.products) localProducts.add(productSignature(product));
        emitProducts(result.built.url, result.analysis.products);
        lastFull = result.analysis.products.length >= Math.max(2, Math.floor(result.built.limit * 0.55));
        const completeByTotal = reportedTotal != null && localProducts.size >= reportedTotal;
        const stop = result.analysis.products.length === 0 ||
          result.analysis.hasNext === false || completeByTotal;
        return { accepted: true, stop };
      };

      let first;
      try {
        first = await fetchPage(template, query, 0, null);
      } catch (_) {
        return { truncated: false, products: 0, pages: 0 };
      }
      requests += 1;
      const firstState = consume(first);
      if (!firstState.accepted) return { truncated: false, products: 0, pages };
      if (firstState.stop || requests >= MAX_REQUESTS) {
        return { truncated: false, products: localProducts.size, pages };
      }

      if (hasCursorMode) {
        cursor = remainingCursor;
        for (let pageIndex = 1; pageIndex < MAX_PAGES_PER_QUERY && requests < MAX_REQUESTS; pageIndex += 1) {
          if (cursor == null || clean(cursor) === '') break;
          let result;
          try { result = await fetchPage(template, query, pageIndex, cursor); } catch (_) { break; }
          requests += 1;
          const state = consume(result);
          if (!state.accepted || state.stop) break;
          const next = remainingCursor;
          if (next == null || String(next) === String(cursor)) break;
          cursor = next;
          await sleep(12);
        }
        const truncated = pages >= MAX_PAGES_PER_QUERY && remainingCursor != null;
        return { truncated, products: localProducts.size, pages };
      }

      if (hasPageMode && reportedTotal != null) {
        const capacity = Math.max(1, first.built.limit) * MAX_PAGES_PER_QUERY;
        if (reportedTotal > capacity && query.length < MAX_PREFIX_DEPTH) {
          return { truncated: true, products: localProducts.size, pages };
        }
        const totalPages = Math.min(
          MAX_PAGES_PER_QUERY,
          Math.max(1, Math.ceil(reportedTotal / Math.max(1, first.built.limit)) + 1),
        );
        for (let start = 1; start < totalPages && requests < MAX_REQUESTS; start += PAGE_CONCURRENCY) {
          const indices = [];
          for (let offset = 0; offset < PAGE_CONCURRENCY; offset += 1) {
            const pageIndex = start + offset;
            if (pageIndex < totalPages && requests + indices.length < MAX_REQUESTS) indices.push(pageIndex);
          }
          const results = await Promise.all(indices.map(async pageIndex => {
            try { return await fetchPage(template, query, pageIndex, null); } catch (_) { return null; }
          }));
          requests += results.length;
          let shouldStop = false;
          for (const result of results) {
            const state = consume(result);
            if (!state.accepted || state.stop) shouldStop = true;
          }
          progress('Buscador interno: ' + (query || 'consulta vacía') + ' · ' + pages + ' páginas', {
            requests,
            products: globalProductCount,
          });
          if (shouldStop) break;
          await sleep(18);
        }
        const truncated = reportedTotal > localProducts.size && pages >= MAX_PAGES_PER_QUERY;
        return { truncated, products: localProducts.size, pages };
      }

      if (hasPageMode) {
        for (let pageIndex = 1; pageIndex < MAX_PAGES_PER_QUERY && requests < MAX_REQUESTS; pageIndex += 1) {
          let result;
          try { result = await fetchPage(template, query, pageIndex, null); } catch (_) { break; }
          requests += 1;
          const state = consume(result);
          if (!state.accepted || state.stop || !lastFull) break;
          if (pageIndex % 4 === 0) {
            progress('Buscador interno: ' + (query || 'consulta vacía') + ' · ' + pages + ' páginas', {
              requests,
              products: globalProductCount,
            });
          }
          await sleep(12);
        }
        const truncated = pages >= MAX_PAGES_PER_QUERY && lastFull;
        return { truncated, products: localProducts.size, pages };
      }

      const truncated = lastFull || (reportedTotal != null && reportedTotal > localProducts.size);
      return { truncated, products: localProducts.size, pages };
    };

    while (queue.length > 0 && requests < MAX_REQUESTS) {
      const query = queue.shift();
      const result = await harvestQuery(query);
      progress('Catálogo: ' + (query || 'consulta vacía') + ' · ' + globalProductCount + ' productos únicos', {
        requests,
        products: globalProductCount,
        query,
        pages: result.pages,
      });

      const needsSplit = result.truncated || (query === '' && result.products === 0);
      if (needsSplit && query.length < MAX_PREFIX_DEPTH) {
        for (const character of ALPHABET) enqueue(query + character);
      }
      await sleep(20);
    }

    hideInstruction();
    window.__smartDealsSearchFinished = true;
    running = false;
    window.__smartDealsEndpointMode = false;

    if (globalProductCount > 0) {
      progress('Catálogo interno terminado', {
        requests,
        products: globalProductCount,
        endpointHarvestComplete: true,
      });
      post({
        event: 'explore_complete',
        endpointHarvest: true,
        requests,
        productSignals: globalProductCount,
      });
      return;
    }

    progress('La consulta interna no entregó productos; usando el recorrido visual de respaldo', {
      endpointFallback: true,
    });
    if (typeof originalStartExplore === 'function') {
      try { originalStartExplore(); } catch (_) { post({ event: 'explore_complete', endpointFallback: true }); }
    } else {
      post({ event: 'explore_complete', endpointFallback: true });
    }
  };

  const candidate = (request, text) => {
    if (!request || running || !searchLikeRequest(request)) return;
    const analysis = analyzeResponse(text);
    if (analysis.products.length < 2) return;
    const template = makeTemplate(request);
    if (!template) return;
    saveTemplate(template);
    progress('Consulta real aprendida automáticamente', { endpointCaptured: true });
    if (waiting && !running) execute(template, false);
  };

  const snapshotFetch = async (input, init) => {
    const request = input instanceof Request ? input : null;
    const url = request ? request.url : String(input || '');
    const method = String((init && init.method) || (request && request.method) || 'GET').toUpperCase();
    const headers = Object.assign({}, headerMap(request && request.headers), headerMap(init && init.headers));
    let body = init && init.body;
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
    }, { once: true });
    return nativeSend.call(this, body);
  };

  const visible = element => {
    if (!element) return false;
    try {
      const style = getComputedStyle(element);
      const rect = element.getBoundingClientRect();
      return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 10 && rect.height > 10;
    } catch (_) {
      return true;
    }
  };

  const findSearchInput = () => {
    const selectors = [
      'input[type="search"]',
      'input[placeholder*="buscar" i]',
      'input[aria-label*="buscar" i]',
      'input[name*="search" i]',
      'input[data-testid*="search" i]',
      'input[placeholder*="producto" i]',
    ];
    for (const selector of selectors) {
      const element = Array.from(document.querySelectorAll(selector)).find(visible);
      if (element) return element;
    }
    return null;
  };

  const openSearchControl = () => {
    const controls = Array.from(document.querySelectorAll('button,a,[role="button"]')).filter(visible);
    const control = controls.find(element => {
      const text = fold(
        (element.innerText || element.textContent || '') + ' ' +
        (element.getAttribute('aria-label') || '') + ' ' +
        (element.getAttribute('title') || '') + ' ' +
        (element.getAttribute('data-testid') || ''),
      );
      return /(?:buscar|search|buscador)/.test(text);
    });
    if (control) {
      try { control.click(); } catch (_) {}
      return true;
    }
    return false;
  };

  const setInputValue = (input, value) => {
    try {
      const descriptor = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
      if (descriptor && descriptor.set) descriptor.set.call(input, value);
      else input.value = value;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
      return true;
    } catch (_) {
      return false;
    }
  };

  const submitSearch = input => {
    try {
      input.focus();
      ['keydown', 'keypress', 'keyup'].forEach(type => {
        input.dispatchEvent(new KeyboardEvent(type, {
          key: 'Enter',
          code: 'Enter',
          keyCode: 13,
          which: 13,
          bubbles: true,
        }));
      });
      const form = input.closest('form');
      if (form) {
        if (typeof form.requestSubmit === 'function') form.requestSubmit();
        else form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      }
      const container = input.parentElement && input.parentElement.parentElement;
      const button = container && Array.from(container.querySelectorAll('button,[role="button"]')).find(visible);
      if (button) button.click();
    } catch (_) {}
  };

  const fallbackToVisual = reason => {
    if (running) return;
    waiting = false;
    window.__smartDealsEndpointMode = false;
    hideInstruction();
    progress(reason || 'No se pudo usar el buscador interno; usando el recorrido visual', {
      endpointFallback: true,
    });
    if (typeof originalStartExplore === 'function') {
      try { originalStartExplore(); } catch (_) { post({ event: 'explore_complete', endpointFallback: true }); }
    } else {
      post({ event: 'explore_complete', endpointFallback: true });
    }
  };

  const autoLearn = async () => {
    if (running) return;
    waiting = true;
    window.__smartDealsEndpointMode = true;
    instruction('Detectando automáticamente el buscador del supermercado…');
    progress('Buscando automáticamente el campo de búsqueda', { automaticSearch: true });

    const started = Date.now();
    let termIndex = 0;
    while (waiting && !running && Date.now() - started < 30000) {
      let input = findSearchInput();
      if (!input) {
        openSearchControl();
        await sleep(700);
        input = findSearchInput();
      }
      if (!input) {
        await sleep(650);
        continue;
      }

      const term = AUTO_TERMS[Math.min(termIndex, AUTO_TERMS.length - 1)];
      instruction('Probando automáticamente el buscador interno…');
      setInputValue(input, term);
      await sleep(650);
      submitSearch(input);
      await sleep(2600);
      termIndex += 1;
      if (termIndex >= AUTO_TERMS.length) termIndex = 0;
    }
    if (waiting && !running) {
      fallbackToVisual('No se pudo aprender el buscador automáticamente; usando el recorrido visual');
    }
  };

  window.__smartDealsStartExplore = () => {
    if (running || waiting) return;
    window.__smartDealsEndpointMode = true;
    const template = loadTemplate();
    if (template) {
      execute(template, true);
      return;
    }

    autoLearn();
    fallbackTimer = setTimeout(() => {
      if (!waiting || running) return;
      fallbackToVisual('No se pudo aprender el buscador automáticamente; usando el recorrido visual');
    }, 36000);
  };
})();

"""
