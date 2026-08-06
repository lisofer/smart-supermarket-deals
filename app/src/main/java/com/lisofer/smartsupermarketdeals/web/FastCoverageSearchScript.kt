package com.lisofer.smartsupermarketdeals.web

internal val fastCoverageSearchTerms: List<String> = buildList {
    add("")
    addAll(
        listOf(
            // Bebidas e infusiones
            "agua", "agua mineral", "agua saborizada", "soda", "gaseosa", "cola",
            "jugo", "nectar", "bebida", "energizante", "isotonica", "cerveza", "vino",
            "espumante", "fernet", "aperitivo", "cafe", "te", "mate", "yerba", "cacao",

            // Lácteos, desayuno, panificados y golosinas
            "leche", "yogur", "queso", "manteca", "crema", "postre", "dulce de leche",
            "huevo", "pan", "tostada", "galletita", "bizcocho", "budin", "alfajor",
            "chocolate", "chocolate blanco", "golosina", "caramelo", "chicle", "cereal",
            "granola", "avena", "mermelada", "miel", "snack", "papas fritas",

            // Almacén
            "arroz", "fideos", "pasta", "harina", "polenta", "semola", "rebozador",
            "lenteja", "poroto", "garbanzo", "legumbre", "aceite", "vinagre", "azucar",
            "edulcorante", "sal", "especia", "condimento", "caldo", "sopa", "pure",
            "salsa", "tomate", "conserva", "atun", "sardina", "choclo", "arveja",
            "mayonesa", "ketchup", "mostaza", "aderezo",

            // Frescos, carnes y congelados
            "carne", "vacuno", "pollo", "cerdo", "pescado", "hamburguesa", "milanesa",
            "fiambre", "jamon", "salame", "salchicha", "fruta", "verdura", "papa",
            "cebolla", "banana", "manzana", "naranja", "mandarina", "limon", "pera",
            "tomate", "zanahoria", "zapallo", "lechuga", "palta", "helado", "congelado",
            "pizza", "empanada", "papas congeladas",

            // Limpieza del hogar
            "detergente", "lavandina", "limpiador", "desinfectante", "jabon", "suavizante",
            "quitamanchas", "desengrasante", "limpiavidrios", "papel higienico", "rollo",
            "servilleta", "pañuelo", "bolsa", "esponja", "guante", "insecticida",
            "repelente", "aromatizante",

            // Cuidado personal, bebé y mascotas
            "shampoo", "acondicionador", "desodorante", "dental", "pasta dental", "cepillo",
            "crema corporal", "protector solar", "toalla femenina", "tampon", "pañal",
            "bebe", "formula infantil", "algodon", "afeitadora", "maquina de afeitar",
            "mascota", "perro", "gato", "alimento balanceado", "arena sanitaria",

            // Señales comerciales y mecánicas promocionales
            "oferta", "ofertas", "promo", "promocion", "descuento", "ahorro", "2x1",
            "3x2", "segunda unidad", "2da unidad",

            // Marcas frecuentes. Incluye Milka explícitamente por el caso real reportado.
            "milka", "cadbury", "cofler", "bon o bon", "arcor", "bagley", "terrabusi",
            "aguila", "coca cola", "pepsi", "sprite", "fanta", "quilmes", "brahma",
            "manaos", "la serenisima", "sancor", "milkaut", "danone", "casanto",
            "molinos", "lucchetti", "matarazzo", "natura", "knorr", "hellmanns",
            "cocinero", "marolio", "dia", "carrefour", "skip", "ala", "cif",
            "magistral", "ayudin", "dove", "sedal", "pantene", "rexona", "colgate",
            "oral b", "elite", "higienol", "pampers", "huggies", "pedigree", "whiskas",
        )
    )

    // Todas las combinaciones de dos letras. Una búsqueda no se corta por coincidencias globales.
    val alphabet = "abcdefghijklmnopqrstuvwxyz"
    addAll(
        alphabet.flatMap { first ->
            alphabet.map { second -> "$first$second" }
        }
    )
    addAll(alphabet.map(Char::toString))
    add("ñ")
    addAll(('0'..'9').map(Char::toString))
}.distinct()

private val fastCoverageTermsLiteral = fastCoverageSearchTerms
    .joinToString(prefix = "[", postfix = "]") { term -> "\"$term\"" }

/**
 * Recorre el catálogo general y después agota cada búsqueda estratégica por separado.
 * Todos los productos viajan al analizador de Android; la decisión de qué producto es una
 * promoción se toma después, con toda la evidencia disponible.
 */
internal val fastCoverageSearchScript = """
(() => {
  if (window.__smartDealsFastCoverageV19) return;
  window.__smartDealsFastCoverageV19 = true;

  const SEARCH_TERMS = $fastCoverageTermsLiteral;
  const TEMPLATE_PREFIXES = [
    '__smartDealsEndpointTemplateV12:',
    '__smartDealsEndpointTemplateV11:'
  ];
  const CONCURRENCY = 18;
  const MAX_REQUESTS = 4500;
  const MAX_PRIMARY_PAGES = 180;
  const MAX_PAGES_PER_QUERY = 60;
  const TEMPLATE_WAIT_MS = 30000;
  const FALLBACK_WAIT_MS = 240000;
  const WATCHDOG_MS = 840000;

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
  const hashText = raw => {
    const source = String(raw || '');
    let hash = 2166136261;
    for (let index = 0; index < source.length; index += 1) {
      hash ^= source.charCodeAt(index);
      hash = Math.imul(hash, 16777619);
    }
    return source.length + '|' + (hash >>> 0).toString(16);
  };

  let storeRoot = '';
  let active = false;
  let finished = false;
  let watchdog = null;
  let requestCount = 0;
  let successfulResponses = 0;
  let progressStep = 0;
  const catalogSeen = new Set();
  const emittedProductVariants = new Set();
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
    exhaustiveCoverage: true,
  }, extra || {}));

  const rootHash = () => {
    const source = absolute(storeRoot || location.href);
    let hash = 2166136261;
    for (let index = 0; index < source.length; index += 1) {
      hash ^= source.charCodeAt(index);
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(16);
  };
  const resumeKey = () => '__smartDealsExhaustiveV19:' + rootHash();
  const loadResumeState = () => {
    try {
      const state = JSON.parse(localStorage.getItem(resumeKey()) || 'null');
      if (state && Array.isArray(state.completedTerms)) return state;
    } catch (_) {}
    return { primaryDone: false, completedTerms: [] };
  };
  const saveResumeState = state => {
    try { localStorage.setItem(resumeKey(), JSON.stringify(state)); } catch (_) {}
  };
  const clearResumeState = () => {
    try { localStorage.removeItem(resumeKey()); } catch (_) {}
  };

  const finish = extra => {
    if (finished) return;
    finished = true;
    active = false;
    if (watchdog) clearTimeout(watchdog);
    window.__smartDealsSearchFinished = true;
    clearResumeState();
    post(Object.assign({
      event: 'coverage_complete',
      requests: requestCount,
      catalogProducts: catalogSeen.size,
      strategicQueries: SEARCH_TERMS.length,
      exhaustiveCoverage: true,
    }, extra || {}));
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
      await sleep(300);
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
      ? Math.min(150, Math.max(100, configured))
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
  const BRAND_KEYS = ['brand', 'brandname', 'brand_name', 'manufacturer'];
  const SIZE_KEYS = ['presentation', 'size', 'pack', 'unit', 'variant'];
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
  const catalogSignature = product => {
    const id = clean(directValue(product, ID_KEYS));
    if (id) return 'id|' + id;
    const name = fold(directValue(product, NAME_KEYS));
    const brand = fold(directValue(product, BRAND_KEYS));
    const size = fold(directValue(product, SIZE_KEYS));
    return 'name|' + name + '|' + brand + '|' + size;
  };

  const compact = (value, depth, insidePromo) => {
    if (value == null || depth > 10) return null;
    if (typeof value === 'string') return value.slice(0, 1200);
    if (typeof value === 'number' || typeof value === 'boolean') return value;
    if (Array.isArray(value)) {
      return value.slice(0, 180).map(child => compact(child, depth + 1, insidePromo));
    }
    if (typeof value !== 'object') return null;
    const output = {};
    for (const key of Object.keys(value)) {
      const childPromo = insidePromo || promoKey(key);
      const keep = /(?:^id|sku|ean|gtin|barcode|product|item|name|title|brand|image|picture|thumbnail|url|href|description|presentation|size|pack|unit|price|pricing)/i.test(key) ||
        promoKey(key) ||
        (childPromo && /(?:label|text|subtitle|caption|message|content|type|kind|scope|value|amount|percentage|percent|quantity|take|buy|pay|required|discounted)/i.test(key));
      if (keep) output[key] = compact(value[key], depth + 1, childPromo);
    }
    return output;
  };
  const hasPublishedOldPrice = (node, depth) => {
    if (!node || typeof node !== 'object' || depth > 8) return false;
    if (Array.isArray(node)) {
      return node.slice(0, 120).some(child => hasPublishedOldPrice(child, depth + 1));
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
  const sectionPromotion = object => {
    if (!object || typeof object !== 'object' || Array.isArray(object)) return null;
    if (!Object.keys(object).some(productCollectionKey)) return null;
    const snapshot = {};
    for (const key of Object.keys(object)) {
      if (productCollectionKey(key)) continue;
      const value = object[key];
      if (promoKey(key)) snapshot[key] = compact(value, 0, true);
      if (typeof value === 'string' && promotionSignal.test(fold(value))) {
        snapshot[key] = value.slice(0, 800);
      }
    }
    const text = JSON.stringify(snapshot);
    if (!text || !promotionSignal.test(fold(text))) return null;
    snapshot.__smartDealsInherited = true;
    return snapshot;
  };

  const collect = root => {
    const products = [];
    const signatures = new Set();
    const stack = [{ value: root, inherited: null, depth: 0 }];
    let visited = 0;
    while (stack.length > 0 && visited < 250000 && signatures.size < 50000) {
      const entry = stack.pop();
      visited += 1;
      const value = entry && entry.value;
      if (!value || typeof value !== 'object' || entry.depth > 32) continue;
      if (Array.isArray(value)) {
        for (let index = value.length - 1; index >= 0; index -= 1) {
          stack.push({ value: value[index], inherited: entry.inherited, depth: entry.depth + 1 });
        }
        continue;
      }

      if (looksLikeProduct(value)) {
        const product = compact(value, 0, false) || {};
        const signature = catalogSignature(product);
        if (signature && signature !== 'name|||') signatures.add(signature);
        const text = JSON.stringify(product).slice(0, 26000);
        const ownPromotion = promotionSignal.test(fold(text)) || hasPublishedOldPrice(product, 0);
        if (entry.inherited && !ownPromotion) {
          product.__smartDealsSectionPromotion = entry.inherited;
        }
        product.source = 'exhaustive-products-v19';
        products.push(product);
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
    return { products, signatures: Array.from(signatures) };
  };

  const metadata = root => {
    const queue = [root];
    let visited = 0;
    let total = null;
    let hasNext = null;
    let nextCursor = null;
    while (queue.length > 0 && visited < 80000) {
      const value = queue.shift();
      visited += 1;
      if (!value || typeof value !== 'object') continue;
      if (Array.isArray(value)) {
        for (let index = 0; index < Math.min(value.length, 800); index += 1) queue.push(value[index]);
        continue;
      }
      for (const key of Object.keys(value)) {
        const child = value[key];
        if (keyEquals(key, TOTAL_KEYS)) {
          const number = Number(child);
          if (Number.isFinite(number) && number >= 0 && number <= 500000) {
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

  const emitAllProducts = (url, products) => {
    const fresh = [];
    for (const product of products || []) {
      const signature = catalogSignature(product);
      if (!signature || signature === 'name|||') continue;
      catalogSeen.add(signature);
      const text = JSON.stringify(product);
      const variantKey = signature + '|' + hashText(text);
      if (emittedProductVariants.has(variantKey)) continue;
      emittedProductVariants.add(variantKey);
      fresh.push(product);
    }
    for (let index = 0; index < fresh.length; index += 30) {
      post({
        url: url + '#exhaustive-products-' + index,
        body: JSON.stringify({ products: fresh.slice(index, index + 30) }),
      });
    }
    return fresh.length;
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
      const root = JSON.parse(text);
      const collected = collect(root);
      const pageMetadata = metadata(root);
      emitAllProducts(built.url, collected.products);
      successfulResponses += 1;
      return {
        query,
        pageIndex,
        fingerprint: hashText(text),
        catalogProducts: collected.signatures.length,
        total: pageMetadata.total,
        hasNext: pageMetadata.hasNext,
        nextCursor: pageMetadata.nextCursor,
        limit: built.limit,
      };
    } catch (_) {
      return null;
    }
  };

  const hasPagination = template => Boolean(
    template.pagination &&
      (template.pagination.page || template.pagination.offset || template.pagination.cursor)
  );

  const exhaustQuery = async (template, query, maximumPages) => {
    const seenPages = new Set();
    let pageIndex = 0;
    let cursor = null;
    let previousCursor = null;
    let cumulative = 0;

    while (pageIndex < maximumPages && requestCount < MAX_REQUESTS) {
      const result = await fetchQuery(template, query, pageIndex, cursor);
      if (!result) return false;
      if (seenPages.has(result.fingerprint)) return true;
      seenPages.add(result.fingerprint);
      cumulative += result.catalogProducts;

      progress('Agotando búsqueda: ' + (query || 'catálogo general'), {
        query,
        page: pageIndex + 1,
        requests: requestCount,
        catalogProducts: catalogSeen.size,
      });

      if (result.catalogProducts <= 0) return true;
      if (!hasPagination(template)) return true;
      if (result.hasNext === false) return true;
      if (result.total != null && cumulative >= result.total) return true;
      if (
        result.nextCursor != null &&
        previousCursor != null &&
        result.nextCursor === previousCursor
      ) return true;

      previousCursor = cursor;
      cursor = result.nextCursor;
      pageIndex += 1;
    }
    return pageIndex >= maximumPages;
  };

  const runCoverage = async template => {
    requestCount = 0;
    successfulResponses = 0;
    catalogSeen.clear();
    emittedProductVariants.clear();

    const state = loadResumeState();
    const completedTerms = new Set(state.completedTerms || []);

    if (!state.primaryDone) {
      progress('Buscando todos los productos del catálogo general…');
      const primaryComplete = await exhaustQuery(template, '', MAX_PRIMARY_PAGES);
      if (!primaryComplete || successfulResponses === 0) return false;
      state.primaryDone = true;
      saveResumeState(state);
    }

    const terms = Array.from(new Set(SEARCH_TERMS))
      .filter(term => term !== '' && !completedTerms.has(term));

    for (let index = 0; index < terms.length && requestCount < MAX_REQUESTS; index += CONCURRENCY) {
      const batch = terms.slice(index, index + CONCURRENCY);
      const outcomes = await Promise.all(
        batch.map(async term => ({ term, complete: await exhaustQuery(template, term, MAX_PAGES_PER_QUERY) }))
      );
      for (const outcome of outcomes) {
        if (outcome.complete) completedTerms.add(outcome.term);
      }
      state.completedTerms = Array.from(completedTerms);
      saveResumeState(state);
      progress('Lote completo: buscando todos los productos sin saltear variantes…', {
        requests: requestCount,
        completed: completedTerms.size,
        totalQueries: SEARCH_TERMS.length - 1,
        catalogProducts: catalogSeen.size,
      });
      await sleep(20);
    }

    const allComplete = SEARCH_TERMS
      .filter(term => term !== '')
      .every(term => completedTerms.has(term));
    return allComplete;
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
    post({ event: 'coverage_started', exhaustiveCoverage: true });
    watchdog = setTimeout(() => {
      post({
        event: 'explore_progress',
        phase: 'La búsqueda sigue activa; Android puede reiniciar el motor y retomar el lote guardado…',
        exhaustiveCoverage: true,
      });
    }, WATCHDOG_MS);

    try {
      let template = loadTemplate();
      if (template) {
        progress('Usando la consulta interna guardada…');
        const complete = await runCoverage(template);
        if (complete) {
          finish({ endpointCoverage: true, exhaustive: true, allQueriesCompleted: true });
          return;
        }
      }

      removeTemplates();
      progress('Aprendiendo el buscador interno de esta tienda…');
      if (typeof originalStartExplore === 'function') {
        try { originalStartExplore(); } catch (_) {}
      }
      template = await waitForTemplate(TEMPLATE_WAIT_MS);
      if (template) {
        const complete = await runCoverage(template);
        if (complete) {
          finish({ endpointCoverage: true, learnedNow: true, exhaustive: true, allQueriesCompleted: true });
          return;
        }
      }

      progress('Usando el recorrido visual de respaldo…');
      const deadline = Date.now() + FALLBACK_WAIT_MS;
      while (Date.now() < deadline && !fallbackFinished()) await sleep(500);
      finish({ endpointFallback: true });
    } catch (_) {
      progress('El motor web se reiniciará y retomará el último lote guardado…');
    }
  };
})();
"""
