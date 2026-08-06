package com.lisofer.smartsupermarketdeals.web

internal val fastCoverageSearchTerms: List<String> = buildList {
    add("")
    addAll(
        listOf(
            "agua", "gaseosa", "jugo", "bebida", "cerveza", "vino",
            "leche", "yogur", "queso", "manteca", "crema",
            "galletita", "pan", "budin", "alfajor", "chocolate", "cereal", "snack",
            "arroz", "fideos", "pasta", "harina", "aceite", "azucar", "sal",
            "salsa", "tomate", "atun", "conserva", "mayonesa", "ketchup",
            "carne", "pollo", "hamburguesa", "milanesa", "fiambre", "salchicha",
            "fruta", "verdura", "papa", "cebolla", "banana",
            "helado", "congelado",
            "detergente", "lavandina", "limpiador", "jabon", "papel", "bolsa", "esponja",
            "shampoo", "acondicionador", "desodorante", "dental",
            "panal", "mascota",
        )
    )
    addAll(('a'..'z').map(Char::toString))
    addAll(('0'..'9').map(Char::toString))
}

private val fastCoverageTermsLiteral = fastCoverageSearchTerms
    .distinct()
    .joinToString(prefix = "[", postfix = "]") { term -> "\"$term\"" }

/**
 * Runs an exhaustive set of catalog searches. It starts with the broad empty query, then uses
 * letters and product families to recover items omitted by ranked search results. Every query is
 * paginated until PedidosYa reports exhaustion, returns a short/empty page or repeats a response.
 */
internal val fastCoverageSearchScript = """
(() => {
  if (window.__smartDealsFastCoverageV16) return;
  window.__smartDealsFastCoverageV16 = true;

  const SEARCH_TERMS = $fastCoverageTermsLiteral;
  const TEMPLATE_PREFIXES = [
    '__smartDealsEndpointTemplateV12:',
    '__smartDealsEndpointTemplateV11:'
  ];
  const CONCURRENCY = 7;
  const MAX_REQUESTS = 1500;
  const MAX_PAGES_PER_QUERY = 40;
  const TEMPLATE_WAIT_MS = 36000;
  const FALLBACK_WAIT_MS = 780000;
  const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
  const clean = value => String(value == null ? '' : value).replace(/\s+/g, ' ').trim();
  const fold = value => clean(value).toLowerCase().normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '');
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

  let storeRoot = '';
  let active = false;
  let finished = false;
  let watchdog = null;
  let requestCount = 0;
  let successfulResponses = 0;
  let progressStep = 0;
  const originalStartExplore = window.__smartDealsStartExplore;
  const previousSetRoot = window.__smartDealsSetRoot;

  window.__smartDealsSetRoot = value => {
    storeRoot = absolute(value);
    try {
      if (typeof previousSetRoot === 'function') previousSetRoot(value);
    } catch (_) {}
  };

  const progress = (phase, extra) => post(Object.assign({
    event: 'explore_progress',
    step: ++progressStep,
    phase,
    fastCoverage: true,
  }, extra || {}));

  const finish = extra => {
    if (finished) return;
    finished = true;
    active = false;
    if (watchdog) clearTimeout(watchdog);
    window.__smartDealsSearchFinished = true;
    post(Object.assign({
      event: 'coverage_complete',
      requests: requestCount,
      promotionSignals: globalPromotionCount,
      fastCoverage: true,
    }, extra || {}));
  };

  const rootHash = () => {
    const source = absolute(storeRoot || location.href);
    let hash = 2166136261;
    for (let index = 0; index < source.length; index += 1) {
      hash ^= source.charCodeAt(index);
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(16);
  };

  const templateKeys = () => TEMPLATE_PREFIXES.map(prefix => prefix + rootHash());
  const loadTemplate = () => {
    for (const key of templateKeys()) {
      try {
        const template = JSON.parse(localStorage.getItem(key) || 'null');
        if (template && template.url && template.query) return template;
      } catch (_) {}
    }
    return null;
  };
  const removeTemplates = () => {
    for (const key of templateKeys()) {
      try { localStorage.removeItem(key); } catch (_) {}
    }
  };
  const waitForTemplate = async timeout => {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
      const template = loadTemplate();
      if (template) return template;
      await sleep(350);
    }
    return null;
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

    const configured = Number(descriptorValue(template, template.pagination && template.pagination.limit));
    const limit = Number.isFinite(configured) && configured > 0
      ? Math.min(120, Math.max(100, configured))
      : 120;
    if (template.pagination && template.pagination.limit) {
      applyDescriptor(target, template.pagination.limit, limit);
    }
    if (template.pagination && template.pagination.page) {
      const initial = Number(descriptorValue(template, template.pagination.page));
      applyDescriptor(
        target,
        template.pagination.page,
        (Number.isFinite(initial) ? initial : 0) + pageIndex,
      );
    }
    if (template.pagination && template.pagination.offset) {
      const initial = Number(descriptorValue(template, template.pagination.offset));
      applyDescriptor(
        target,
        template.pagination.offset,
        (Number.isFinite(initial) ? initial : 0) + pageIndex * limit,
      );
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

  const normalizedKey = value => fold(value).replace(/[\s-]/g, '_');
  const keyEquals = (key, values) => {
    const normalized = normalizedKey(key);
    return values.includes(normalized) || values.includes(normalized.replace(/_/g, ''));
  };
  const NAME_KEYS = [
    'name', 'title', 'productname', 'product_name', 'displayname', 'display_name',
    'itemname', 'item_name'
  ];
  const PRICE_KEYS = [
    'price', 'currentprice', 'current_price', 'saleprice', 'sale_price', 'finalprice',
    'final_price', 'discountedprice', 'discounted_price', 'promotionalprice',
    'promotional_price', 'unitprice', 'unit_price', 'pricewithdiscount', 'price_with_discount'
  ];
  const ID_KEYS = [
    'productid', 'product_id', 'itemid', 'item_id', 'sku', 'skuid', 'sku_id',
    'barcode', 'gtin', 'ean', 'id'
  ];
  const IMAGE_KEYS = ['image', 'imageurl', 'image_url', 'picture', 'thumbnail', 'photo'];
  const TOTAL_KEYS = [
    'totalcount', 'total_count', 'totalelements', 'total_elements', 'totalresults',
    'total_results', 'totalitems', 'total_items', 'resultcount', 'result_count'
  ];
  const HAS_NEXT_KEYS = ['hasnext', 'has_next', 'hasmore', 'has_more', 'moreavailable', 'more_available'];
  const NEXT_CURSOR_KEYS = [
    'nextcursor', 'next_cursor', 'endcursor', 'end_cursor', 'nextpagetoken',
    'next_page_token', 'nexttoken', 'next_token'
  ];
  const productCollectionKey = key => {
    const value = normalizedKey(key);
    return value === 'products' || value === 'items' || value === 'productlist' ||
      value === 'product_list' || value === 'catalogitems' || value === 'catalog_items' ||
      value === 'results' || value === 'entries' || value === 'elements' ||
      value === 'skus' || value === 'variants' || value === 'children' ||
      value.includes('products') || value.includes('product_list') ||
      value.includes('catalogitem');
  };
  const promoKey = key => /(?:promo|discount|descuento|benefit|badge|offer|campaign|saving|deal|commercial|mechanic|condition|rule|tag)/i.test(key);
  const oldPriceKey = /(?:original|regular|previous|list|before|old|strike|crossed)[_-]?price/i;
  const promotionSignal = /(?:\b(?:2\s*\.?\s*(?:da|do|°|º)|segunda|segundo)\b.{0,90}(?:unidad|producto|item)?.{0,90}\d{1,3}(?:[.,]\d+)?\s*(?:%|off|dto|descuento)|\b1\s*(?:ud|unidad)\.?\s*(?:al|con)\s*\d{1,3}(?:[.,]\d+)?\s*(?:%|off|dto)|\b\d{1,2}\s*[x×]\s*\d{1,2}\b|\b\d{1,3}(?:[.,]\d+)?\s*%\s*(?:off|dto|de descuento|descuento)\b|\b(?:descuento|ahorra|promo|oferta|off|dto)\b.{0,45}\d{1,3}(?:[.,]\d+)?\s*%)/i;

  const directValue = (object, keys) => {
    if (!object || typeof object !== 'object' || Array.isArray(object)) return undefined;
    for (const key of Object.keys(object)) {
      if (keyEquals(key, keys)) return object[key];
    }
    return undefined;
  };
  const nestedPrice = object => {
    let price = directValue(object, PRICE_KEYS);
    if (price != null) return price;
    for (const wrapper of ['pricing', 'priceInfo', 'price_info', 'prices', 'commercial', 'product', 'item', 'content']) {
      const child = object && object[wrapper];
      if (child && typeof child === 'object' && !Array.isArray(child)) {
        price = directValue(child, PRICE_KEYS);
        if (price != null) return price;
      }
    }
    return undefined;
  };
  const looksLikeProduct = object => {
    if (!object || typeof object !== 'object' || Array.isArray(object)) return false;
    const name = directValue(object, NAME_KEYS);
    return typeof name === 'string' && clean(name).length >= 2 && nestedPrice(object) != null;
  };

  const compact = (value, depth, insidePromo) => {
    if (value == null || depth > 10) return null;
    if (typeof value === 'string') return value.slice(0, 1000);
    if (typeof value === 'number' || typeof value === 'boolean') return value;
    if (Array.isArray(value)) {
      return value.slice(0, 120).map(child => compact(child, depth + 1, insidePromo));
    }
    if (typeof value !== 'object') return null;
    const output = {};
    for (const key of Object.keys(value)) {
      const childPromo = insidePromo || promoKey(key);
      const keep = /(?:^id${'$'}|sku|ean|gtin|barcode|product|item|name|title|brand|image|picture|thumbnail|url|href|description|presentation|size|pack|unit|price|pricing)/i.test(key) ||
        promoKey(key) ||
        (childPromo && /(?:label|text|subtitle|caption|message|content|type|kind|scope|value|amount|percentage|percent|quantity|take|buy|pay|required|discounted)/i.test(key));
      if (keep) output[key] = compact(value[key], depth + 1, childPromo);
    }
    return output;
  };

  const sectionPromotion = object => {
    if (!object || typeof object !== 'object' || Array.isArray(object)) return null;
    if (!Object.keys(object).some(productCollectionKey)) return null;
    const snapshot = {};
    for (const key of Object.keys(object)) {
      if (productCollectionKey(key)) continue;
      const value = object[key];
      if (promoKey(key)) snapshot[key] = compact(value, 0, true);
      if (typeof value === 'string' && promotionSignal.test(fold(value))) {
        snapshot[key] = value.slice(0, 600);
      }
    }
    const text = JSON.stringify(snapshot);
    if (!text || !promotionSignal.test(fold(text))) return null;
    snapshot.__smartDealsInherited = true;
    return snapshot;
  };

  const productSignature = product => {
    const id = clean(directValue(product, ID_KEYS));
    const name = fold(directValue(product, NAME_KEYS));
    const price = clean(nestedPrice(product));
    const image = clean(directValue(product, IMAGE_KEYS));
    return (id || name) + '|' + price + '|' + image.slice(0, 100);
  };
  const hasPublishedOldPrice = (node, depth) => {
    if (!node || typeof node !== 'object' || depth > 8) return false;
    if (Array.isArray(node)) {
      return node.slice(0, 80).some(child => hasPublishedOldPrice(child, depth + 1));
    }
    for (const key of Object.keys(node)) {
      const value = node[key];
      if (oldPriceKey.test(key)) {
        if (typeof value === 'number' && value > 0) return true;
        if (typeof value === 'string' && /\d/.test(value) && Number(value.replace(/[^0-9]/g, '')) > 0) return true;
      }
      if (value && typeof value === 'object' && hasPublishedOldPrice(value, depth + 1)) return true;
    }
    return false;
  };
  const productQuality = product => {
    const text = JSON.stringify(product).slice(0, 18000);
    let score = Object.keys(product || {}).length;
    if (promotionSignal.test(fold(text))) score += 500;
    if (hasPublishedOldPrice(product, 0)) score += 400;
    if (/(?:tags?|badges?).{0,600}(?:label|text)/i.test(text)) score += 250;
    if (product.__smartDealsSectionPromotion) score += 150;
    return score;
  };

  const collect = root => {
    const promotional = [];
    const stack = [{ value: root, inherited: null, depth: 0 }];
    let visited = 0;
    let totalProducts = 0;
    while (stack.length > 0 && visited < 150000 && promotional.length < 12000) {
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
        totalProducts += 1;
        const product = compact(value, 0, false) || {};
        const text = JSON.stringify(product).slice(0, 20000);
        const ownPromotion = promotionSignal.test(fold(text)) ||
          hasPublishedOldPrice(product, 0);
        if (entry.inherited && !ownPromotion) {
          product.__smartDealsSectionPromotion = entry.inherited;
        }
        if (ownPromotion || entry.inherited) {
          product.source = 'fast-coverage-v16';
          promotional.push(product);
        }
      }

      const localPromotion = sectionPromotion(value);
      for (const key of Object.keys(value)) {
        const child = value[key];
        if (!child || typeof child !== 'object') continue;
        const inherited = localPromotion && productCollectionKey(key)
          ? localPromotion
          : entry.inherited;
        stack.push({ value: child, inherited, depth: entry.depth + 1 });
      }
    }
    return { promotional, totalProducts };
  };

  const metadata = root => {
    const queue = [root];
    let visited = 0;
    let total = null;
    let hasNext = null;
    let nextCursor = null;
    while (queue.length > 0 && visited < 50000) {
      const value = queue.shift();
      visited += 1;
      if (!value || typeof value !== 'object') continue;
      if (Array.isArray(value)) {
        for (let index = 0; index < Math.min(value.length, 500); index += 1) queue.push(value[index]);
        continue;
      }
      for (const key of Object.keys(value)) {
        const child = value[key];
        if (keyEquals(key, TOTAL_KEYS)) {
          const number = Number(child);
          if (Number.isFinite(number) && number >= 0 && number <= 200000) {
            total = total == null ? number : Math.max(total, number);
          }
        }
        if (keyEquals(key, HAS_NEXT_KEYS) && typeof child === 'boolean') hasNext = child;
        if (keyEquals(key, NEXT_CURSOR_KEYS) && child != null && clean(child)) nextCursor = child;
        if (child && typeof child === 'object') queue.push(child);
      }
    }
    return { total, hasNext, nextCursor };
  };

  const responseFingerprints = new Set();
  const globalBest = new Map();
  let globalPromotionCount = 0;
  const emitProducts = (url, products) => {
    const fresh = [];
    for (const product of products || []) {
      const signature = productSignature(product);
      if (!signature || signature === '||') continue;
      const quality = productQuality(product);
      const previous = globalBest.get(signature);
      if (previous != null && previous >= quality) continue;
      globalBest.set(signature, quality);
      if (previous == null) globalPromotionCount += 1;
      fresh.push(product);
    }
    for (let index = 0; index < fresh.length; index += 45) {
      post({
        url: url + '#fast-coverage-' + index,
        body: JSON.stringify({ products: fresh.slice(index, index + 45) }),
      });
    }
    return fresh.length;
  };

  const responseFingerprint = text => {
    const source = String(text || '');
    let hash = 2166136261;
    for (let index = 0; index < source.length; index += 1) {
      hash ^= source.charCodeAt(index);
      hash = Math.imul(hash, 16777619);
    }
    return source.length + '|' + (hash >>> 0).toString(16);
  };

  const fetchQuery = async (template, query, pageIndex, cursor) => {
    if (requestCount >= MAX_REQUESTS) return null;
    const built = buildRequest(template, query, pageIndex, cursor);
    requestCount += 1;
    try {
      const response = await window.fetch(built.url, {
        method: String(template.method || 'GET').toUpperCase(),
        headers: template.headers || {},
        body: ['GET', 'HEAD'].includes(String(template.method || 'GET').toUpperCase())
          ? undefined
          : built.body,
        credentials: 'include',
        cache: 'no-store',
      });
      const text = await response.text();
      if (!response.ok || !text) return null;
      const fingerprint = responseFingerprint(text);
      if (responseFingerprints.has(fingerprint)) {
        return {
          query,
          pageIndex,
          totalProducts: 0,
          freshPromotions: 0,
          total: null,
          hasNext: false,
          nextCursor: null,
          limit: built.limit,
          duplicate: true,
        };
      }
      responseFingerprints.add(fingerprint);
      const root = JSON.parse(text);
      const collected = collect(root);
      const pageMetadata = metadata(root);
      const freshPromotions = emitProducts(built.url, collected.promotional);
      successfulResponses += 1;
      return {
        query,
        pageIndex,
        totalProducts: collected.totalProducts,
        freshPromotions,
        total: pageMetadata.total,
        hasNext: pageMetadata.hasNext,
        nextCursor: pageMetadata.nextCursor,
        limit: built.limit,
        duplicate: false,
      };
    } catch (_) {
      return null;
    }
  };

  const runInBatches = async (items, worker, label) => {
    const results = [];
    for (let index = 0; index < items.length && requestCount < MAX_REQUESTS; index += CONCURRENCY) {
      const batch = items.slice(index, index + CONCURRENCY);
      const batchResults = await Promise.all(batch.map(worker));
      results.push(...batchResults.filter(Boolean));
      progress(label, {
        requests: requestCount,
        completed: Math.min(items.length, index + batch.length),
        totalQueries: items.length,
        promotionSignals: globalPromotionCount,
      });
      await sleep(20);
    }
    return results;
  };

  const hasPagination = template => Boolean(
    template.pagination &&
      (template.pagination.page || template.pagination.offset || template.pagination.cursor)
  );

  const exhaustPages = async (template, seeds, observedMaximum, label) => {
    const minimumFull = Math.max(8, Math.floor(observedMaximum * 0.65));
    const cumulativeByQuery = new Map();
    let activePages = [];

    for (const seed of seeds || []) {
      if (!seed || seed.duplicate || seed.totalProducts <= 0) continue;
      cumulativeByQuery.set(seed.query, seed.totalProducts);
      const explicitlyMore = seed.hasNext === true ||
        seed.nextCursor != null ||
        (seed.total != null && seed.totalProducts < seed.total);
      const appearsFull = seed.totalProducts >= minimumFull;
      if (explicitlyMore || appearsFull) activePages.push(seed);
    }

    while (activePages.length > 0 && requestCount < MAX_REQUESTS) {
      const inputs = activePages
        .filter(result => result.pageIndex + 1 < MAX_PAGES_PER_QUERY)
        .map(result => ({
          query: result.query,
          pageIndex: result.pageIndex + 1,
          cursor: result.nextCursor,
          previousCursor: result.nextCursor,
        }));
      if (inputs.length === 0) break;

      const pages = await runInBatches(
        inputs,
        input => fetchQuery(template, input.query, input.pageIndex, input.cursor),
        label,
      );
      activePages = [];

      for (const page of pages) {
        if (!page || page.duplicate || page.totalProducts <= 0) continue;
        const cumulative = (cumulativeByQuery.get(page.query) || 0) + page.totalProducts;
        cumulativeByQuery.set(page.query, cumulative);

        const explicitHasMore = page.hasNext === true ||
          page.nextCursor != null ||
          (page.total != null && cumulative < page.total);
        const appearsFull = page.totalProducts >= minimumFull;
        const cursorAdvanced = page.nextCursor == null ||
          !inputs.some(input => input.query === page.query && input.previousCursor === page.nextCursor);

        if ((explicitHasMore || appearsFull) && cursorAdvanced) activePages.push(page);
      }
    }
  };

  const runCoverage = async template => {
    requestCount = 0;
    successfulResponses = 0;
    responseFingerprints.clear();
    globalBest.clear();
    globalPromotionCount = 0;

    const terms = Array.from(new Set(SEARCH_TERMS));
    const primaryTerm = terms.includes('') ? '' : terms[0];
    const primaryPages = await runInBatches(
      [primaryTerm],
      term => fetchQuery(template, term, 0, null),
      'Revisando el catálogo general…',
    );
    if (successfulResponses === 0) return false;

    if (hasPagination(template) && primaryPages.length > 0) {
      const primaryMaximum = primaryPages.reduce(
        (maximum, result) => Math.max(maximum, result.totalProducts || 0),
        0,
      );
      await exhaustPages(
        template,
        primaryPages,
        primaryMaximum,
        'Agotando todas las páginas del catálogo general…',
      );
    }

    const secondaryTerms = terms.filter(term => term !== primaryTerm);
    const firstPages = await runInBatches(
      secondaryTerms,
      term => fetchQuery(template, term, 0, null),
      'Buscando productos omitidos por letras y rubros…',
    );

    if (hasPagination(template) && firstPages.length > 0 && requestCount < MAX_REQUESTS) {
      const observedMaximum = firstPages.reduce(
        (maximum, result) => Math.max(maximum, result.totalProducts || 0),
        0,
      );
      await exhaustPages(
        template,
        firstPages,
        observedMaximum,
        'Agotando las páginas restantes con promociones…',
      );
    }

    await sleep(1100);
    return true;
  };

  const fallbackFinished = () => {
    if (window.__smartDealsSearchFinished) return true;
    try {
      const crawler = JSON.parse(sessionStorage.getItem('__smartDealsCrawlerV10State') || 'null');
      return Boolean(crawler && crawler.complete);
    } catch (_) {
      return false;
    }
  };

  window.__smartDealsStartExplore = async () => {
    if (active) return;
    active = true;
    finished = false;
    post({ event: 'coverage_started', fastCoverage: true });
    watchdog = setTimeout(() => finish({ watchdog: true }), 840000);

    try {
      let template = loadTemplate();
      if (template) {
        progress('Usando la consulta interna guardada para una búsqueda exhaustiva…');
        const worked = await runCoverage(template);
        if (worked) {
          finish({ endpointCoverage: true });
          return;
        }
        removeTemplates();
      }

      progress('Aprendiendo el buscador interno de esta tienda…');
      if (typeof originalStartExplore === 'function') {
        try { originalStartExplore(); } catch (_) {}
      }
      template = await waitForTemplate(TEMPLATE_WAIT_MS);
      if (template) {
        const worked = await runCoverage(template);
        if (worked) {
          finish({ endpointCoverage: true, learnedNow: true });
          return;
        }
      }

      progress('Usando el recorrido visual de respaldo…');
      const deadline = Date.now() + FALLBACK_WAIT_MS;
      while (Date.now() < deadline && !fallbackFinished()) await sleep(500);
      finish({ endpointFallback: true });
    } catch (_) {
      finish({ failedSafely: true });
    }
  };
})();
"""
