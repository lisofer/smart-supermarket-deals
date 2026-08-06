package com.lisofer.smartsupermarketdeals.web

/**
 * Captures every product returned while category and aisle pages load.
 *
 * PedidosYa can attach a promotion to a section instead of repeating it inside every SKU. This
 * observer keeps that section context and forwards every child product, so sibling variants of
 * the same promotion are not lost before the Android parser sees them.
 */
internal const val catalogResponseCaptureScript = """
(() => {
  if (window.__smartDealsCatalogResponseV14) return;
  window.__smartDealsCatalogResponseV14 = true;

  const MAX_RESPONSE_CHARS = 12000000;
  const MAX_VISITED_NODES = 320000;
  const MAX_PRODUCTS_PER_RESPONSE = 12000;
  const BATCH_SIZE = 40;
  const sentQuality = new Map();
  const seenResponses = new Set();

  const clean = value => String(value == null ? '' : value).replace(/\s+/g, ' ').trim();
  const fold = value => clean(value).toLowerCase().normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '');
  const normalized = value => fold(value).replace(/[\s-]/g, '_');
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
    const step = Math.max(1, Math.floor(source.length / 5000));
    for (let index = 0; index < source.length; index += step) {
      hash ^= source.charCodeAt(index);
      hash = Math.imul(hash, 16777619);
    }
    return source.length + '|' + (hash >>> 0).toString(16);
  };

  const NAME_KEYS = new Set([
    'name', 'title', 'productname', 'product_name', 'displayname', 'display_name',
    'itemname', 'item_name'
  ]);
  const PRICE_KEYS = new Set([
    'price', 'currentprice', 'current_price', 'saleprice', 'sale_price', 'finalprice',
    'final_price', 'discountedprice', 'discounted_price', 'promotionalprice',
    'promotional_price', 'unitprice', 'unit_price', 'pricewithdiscount', 'price_with_discount'
  ]);
  const ID_KEYS = new Set([
    'productid', 'product_id', 'itemid', 'item_id', 'sku', 'skuid', 'sku_id',
    'barcode', 'gtin', 'ean', 'id'
  ]);
  const BRAND_KEYS = new Set(['brand', 'brandname', 'brand_name', 'manufacturer']);
  const SIZE_KEYS = new Set(['presentation', 'size', 'pack', 'unit', 'variant']);
  const promoKey = key => /(?:promo|discount|descuento|benefit|badge|offer|campaign|saving|deal|commercial|mechanic|condition|rule|tag)/i.test(key);
  const productCollectionKey = key => {
    const value = normalized(key);
    return value === 'products' || value === 'items' || value === 'productlist' ||
      value === 'product_list' || value === 'catalogitems' || value === 'catalog_items' ||
      value === 'results' || value === 'entries' || value === 'elements' ||
      value === 'skus' || value === 'variants' || value === 'children' ||
      value.includes('products') || value.includes('product_list') ||
      value.includes('catalogitem');
  };
  const promotionSignal = /(?:\b(?:2\s*\.?\s*(?:da|do|°|º)|segunda|segundo)\b.{0,100}(?:unidad|producto|item)?.{0,100}\d{1,3}(?:[.,]\d+)?\s*(?:%|off|dto|descuento)|\b1\s*(?:ud|unidad)\.?\s*(?:al|con)\s*\d{1,3}(?:[.,]\d+)?\s*(?:%|off|dto)|\b\d{1,2}\s*[x×]\s*\d{1,2}\b|lleva(?:ndo|te|á|a)?\s*\d{1,2}.{0,70}?paga(?:ndo|á|a)?\s*\d{1,2}|\b\d{1,3}(?:[.,]\d+)?\s*%\s*(?:off|dto|de descuento|descuento)\b|\b(?:descuento|ahorra|promo|oferta|off|dto)\b.{0,55}\d{1,3}(?:[.,]\d+)?\s*%)/i;
  const oldPriceKey = /(?:original|regular|previous|list|before|old|strike|crossed)[_-]?price/i;

  const keyMatches = (set, key) => {
    const value = normalized(key);
    return set.has(value) || set.has(value.replace(/_/g, ''));
  };
  const directValue = (object, keys) => {
    if (!object || typeof object !== 'object' || Array.isArray(object)) return undefined;
    for (const key of Object.keys(object)) {
      if (keyMatches(keys, key)) return object[key];
    }
    return undefined;
  };
  const nestedPrice = object => {
    let value = directValue(object, PRICE_KEYS);
    if (value != null) return value;
    for (const key of ['pricing', 'priceInfo', 'price_info', 'prices', 'commercial', 'product', 'item', 'content']) {
      const child = object && object[key];
      if (child && typeof child === 'object' && !Array.isArray(child)) {
        value = directValue(child, PRICE_KEYS);
        if (value != null) return value;
      }
    }
    return undefined;
  };
  const looksLikeProduct = object => {
    if (!object || typeof object !== 'object' || Array.isArray(object)) return false;
    const name = directValue(object, NAME_KEYS);
    return typeof name === 'string' && clean(name).length >= 2 && nestedPrice(object) != null;
  };

  const keepKey = (key, insidePromo) => {
    if (/^(?:id|source)$/i.test(key)) return true;
    if (/(?:sku|ean|gtin|barcode|product|item|name|title|brand|image|picture|thumbnail|url|href|description|presentation|size|pack|unit|price|pricing|metadata|attribute)/i.test(key)) return true;
    if (promoKey(key)) return true;
    if (insidePromo && /(?:label|text|display|subtitle|caption|message|content|type|kind|scope|value|amount|percentage|percent|quantity|take|buy|pay|required|discounted)/i.test(key)) return true;
    return false;
  };
  const compact = (value, depth, insidePromo) => {
    if (value == null || depth > 11) return null;
    if (typeof value === 'string') return value.slice(0, 1400);
    if (typeof value === 'number' || typeof value === 'boolean') return value;
    if (Array.isArray(value)) {
      return value.slice(0, 220).map(child => compact(child, depth + 1, insidePromo));
    }
    if (typeof value !== 'object') return null;
    const output = {};
    for (const key of Object.keys(value)) {
      const childPromo = insidePromo || promoKey(key);
      if (keepKey(key, childPromo)) output[key] = compact(value[key], depth + 1, childPromo);
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
        snapshot[key] = value.slice(0, 1000);
      }
    }
    const text = JSON.stringify(snapshot);
    if (!text || !promotionSignal.test(fold(text))) return null;
    snapshot.__smartDealsInherited = true;
    return snapshot;
  };
  const hasOwnPromotion = product => {
    const text = JSON.stringify(product).slice(0, 30000);
    return promotionSignal.test(fold(text)) || oldPriceKey.test(text);
  };

  const signature = product => {
    const id = clean(directValue(product, ID_KEYS));
    const name = fold(directValue(product, NAME_KEYS));
    const brand = fold(directValue(product, BRAND_KEYS));
    const size = fold(directValue(product, SIZE_KEYS));
    const price = clean(nestedPrice(product));
    return (id ? 'id|' + id : 'name|' + name + '|' + brand + '|' + size) + '|' + price;
  };
  const quality = product => {
    const text = JSON.stringify(product).slice(0, 30000);
    let score = Object.keys(product || {}).length;
    if (promotionSignal.test(fold(text))) score += 800;
    if (oldPriceKey.test(text)) score += 500;
    if (/(?:tags?|badges?).{0,900}(?:label|text)/i.test(text)) score += 350;
    if (product.__smartDealsSectionPromotion) score += 450;
    return score;
  };

  const collect = root => {
    const products = [];
    const stack = [{ value: root, inherited: null, depth: 0 }];
    let visited = 0;
    while (stack.length > 0 && visited < MAX_VISITED_NODES && products.length < MAX_PRODUCTS_PER_RESPONSE) {
      const entry = stack.pop();
      visited += 1;
      const value = entry && entry.value;
      if (!value || typeof value !== 'object' || entry.depth > 34) continue;
      if (Array.isArray(value)) {
        for (let index = value.length - 1; index >= 0; index -= 1) {
          stack.push({ value: value[index], inherited: entry.inherited, depth: entry.depth + 1 });
        }
        continue;
      }

      if (looksLikeProduct(value)) {
        const product = compact(value, 0, false) || {};
        if (entry.inherited && !hasOwnPromotion(product)) {
          product.__smartDealsSectionPromotion = entry.inherited;
        }
        product.source = 'catalog-response-v14';
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
    return products;
  };

  const emit = (url, products) => {
    const fresh = [];
    for (const product of products || []) {
      const key = signature(product);
      if (!key || key === 'name||||') continue;
      const score = quality(product);
      const previous = sentQuality.get(key);
      if (previous != null && previous >= score) continue;
      sentQuality.set(key, score);
      fresh.push(product);
    }
    for (let index = 0; index < fresh.length; index += BATCH_SIZE) {
      post({
        url: String(url || location.href) + '#catalog-response-v14-' + index,
        body: JSON.stringify({ products: fresh.slice(index, index + BATCH_SIZE) }),
      });
    }
    if (fresh.length > 0) {
      post({ event: 'catalog_response', products: fresh.length, sourceUrl: String(url || '') });
    }
  };

  const capture = (url, text) => {
    try {
      if (!text || text.length > MAX_RESPONSE_CHARS) return;
      const trimmed = text.trimStart();
      if (!(trimmed.startsWith('{') || trimmed.startsWith('['))) return;
      const sample = text.length <= 360000
        ? text
        : text.slice(0, 180000) + text.slice(-180000);
      if (!/(?:product|item|sku|catalog|price|pricing)/i.test(sample)) return;
      const fingerprint = String(url || '') + '|' + hashText(text);
      if (seenResponses.has(fingerprint)) return;
      seenResponses.add(fingerprint);
      if (seenResponses.size > 3000) seenResponses.delete(seenResponses.values().next().value);
      setTimeout(() => {
        try { emit(url, collect(JSON.parse(text))); } catch (_) {}
      }, 0);
    } catch (_) {}
  };

  const previousFetch = window.fetch;
  if (previousFetch && !previousFetch.__smartDealsCatalogResponseV14Wrapped) {
    const wrappedFetch = async function(...args) {
      const response = await previousFetch.apply(this, args);
      try {
        response.clone().text().then(text => capture(response.url, text)).catch(() => {});
      } catch (_) {}
      return response;
    };
    wrappedFetch.__smartDealsCatalogResponseV14Wrapped = true;
    window.fetch = wrappedFetch;
  }

  const previousOpen = XMLHttpRequest.prototype.open;
  if (previousOpen && !previousOpen.__smartDealsCatalogResponseV14Wrapped) {
    const wrappedOpen = function(method, url, ...rest) {
      this.__smartDealsCatalogResponseUrl = url;
      return previousOpen.call(this, method, url, ...rest);
    };
    wrappedOpen.__smartDealsCatalogResponseV14Wrapped = true;
    XMLHttpRequest.prototype.open = wrappedOpen;
  }

  const previousSend = XMLHttpRequest.prototype.send;
  if (previousSend && !previousSend.__smartDealsCatalogResponseV14Wrapped) {
    const wrappedSend = function(...args) {
      this.addEventListener('load', function() {
        try {
          if (!this.responseType || this.responseType === 'text') {
            capture(this.responseURL || this.__smartDealsCatalogResponseUrl || '', this.responseText || '');
          }
        } catch (_) {}
      }, { once: true });
      return previousSend.apply(this, args);
    };
    wrappedSend.__smartDealsCatalogResponseV14Wrapped = true;
    XMLHttpRequest.prototype.send = wrappedSend;
  }
})();

"""
