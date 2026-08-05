package com.lisofer.smartsupermarketdeals.web

/**
 * Preserves the commercial badge attached to each PedidosYa product card.
 *
 * The endpoint harvester intentionally compacts large responses. PedidosYa often stores the
 * visible badge inside `tags[].label` or `tags[].text`, while the nested commercial condition may
 * only say something ambiguous such as "1 ud. al 70% dto". This lightweight response observer
 * emits a second, richer version of promotional product cards so the parser can distinguish
 * "2DA AL 70% OFF" from a normal "70% OFF" and can retain ordinary direct discounts.
 */
internal const val promotionCardCaptureScript = """
(() => {
  if (window.__smartDealsPromotionCardCaptureV13) return;
  window.__smartDealsPromotionCardCaptureV13 = true;

  const MAX_RESPONSE_CHARS = 1500000;
  const MAX_VISITED = 140000;
  const MAX_PRODUCTS_PER_RESPONSE = 4000;
  const BATCH_SIZE = 50;
  const sentQuality = new Map();

  const clean = value => String(value == null ? '' : value).replace(/\s+/g, ' ').trim();
  const fold = value => clean(value).toLowerCase().normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '');
  const post = value => {
    try {
      if (window.SmartDealsBridge && window.SmartDealsBridge.postMessage) {
        window.SmartDealsBridge.postMessage(JSON.stringify(value));
      }
    } catch (_) {}
  };

  const promotionSignal = /(?:\b(?:2\s*\.?\s*(?:da|do|°|º)|segunda|segundo)\b.{0,80}(?:unidad|producto|item)?.{0,80}\d{1,3}(?:[.,]\d+)?\s*(?:%|off|dto|descuento)|\b\d{1,2}\s*[x×]\s*\d{1,2}\b|\b\d{1,3}(?:[.,]\d+)?\s*%\s*(?:off|dto|de descuento|descuento)\b|\b(?:descuento|ahorra|promo|oferta|off|dto)\b.{0,45}\d{1,3}(?:[.,]\d+)?\s*%)/i;
  const priceSignal = /(?:beforePrice|before_price|originalPrice|original_price|regularPrice|regular_price|previousPrice|previous_price|oldPrice|old_price|strikePrice|strike_price|crossedPrice|crossed_price|pricing|price)/i;
  const promoContainerKey = /(?:tags?|badges?|promo|promotion|discount|descuento|benefit|offer|campaign|saving|deal|commercial|mechanic|condition|rule)/i;
  const productCollectionKey = /^(?:products?|items?|results?|entries|elements|children|skus|variants|catalogItems?|catalog_items)$/i;
  const nameKeys = ['productName', 'product_name', 'displayName', 'display_name', 'itemName', 'item_name', 'name', 'title'];
  const priceKeys = ['price', 'currentPrice', 'current_price', 'salePrice', 'sale_price', 'finalPrice', 'final_price', 'discountedPrice', 'discounted_price', 'promotionalPrice', 'promotional_price', 'unitPrice', 'unit_price'];
  const idKeys = ['productId', 'product_id', 'itemId', 'item_id', 'sku', 'skuId', 'sku_id', 'barcode', 'gtin', 'ean', 'id'];

  const directValue = (object, keys) => {
    if (!object || typeof object !== 'object' || Array.isArray(object)) return undefined;
    for (const key of keys) {
      if (Object.prototype.hasOwnProperty.call(object, key)) return object[key];
    }
    const normalized = new Map(Object.keys(object).map(key => [key.toLowerCase().replace(/[_-]/g, ''), key]));
    for (const key of keys) {
      const actual = normalized.get(key.toLowerCase().replace(/[_-]/g, ''));
      if (actual) return object[actual];
    }
    return undefined;
  };

  const nestedPrice = object => {
    let value = directValue(object, priceKeys);
    if (value != null) return value;
    for (const key of ['pricing', 'priceInfo', 'price_info', 'prices', 'commercial', 'product', 'item', 'content']) {
      const child = object && object[key];
      if (child && typeof child === 'object' && !Array.isArray(child)) {
        value = directValue(child, priceKeys);
        if (value != null) return value;
      }
    }
    return undefined;
  };

  const looksLikeProduct = object => {
    if (!object || typeof object !== 'object' || Array.isArray(object)) return false;
    const name = directValue(object, nameKeys);
    return typeof name === 'string' && clean(name).length >= 2 && nestedPrice(object) != null;
  };

  const keepKey = (key, insidePromo) => {
    if (/^(?:id|source)$/i.test(key)) return true;
    if (/(?:sku|ean|gtin|barcode|product|item|name|title|brand|image|picture|thumbnail|url|href|description|presentation|size|pack|unit|price|pricing)/i.test(key)) return true;
    if (promoContainerKey.test(key)) return true;
    if (insidePromo && /(?:label|text|displayText|display_text|tagText|tag_text|badgeText|badge_text|subtitle|caption|message|content|type|kind|scope|value|amount|percentage|percent|quantity|take|buy|pay|required|discounted)/i.test(key)) return true;
    return false;
  };

  const compact = (value, depth, insidePromo) => {
    if (value == null || depth > 10) return null;
    if (typeof value === 'string') return value.slice(0, 1000);
    if (typeof value === 'number' || typeof value === 'boolean') return value;
    if (Array.isArray(value)) {
      return value.slice(0, 100).map(child => compact(child, depth + 1, insidePromo));
    }
    if (typeof value !== 'object') return null;

    const output = {};
    for (const key of Object.keys(value)) {
      const childPromo = insidePromo || promoContainerKey.test(key);
      if (!keepKey(key, childPromo)) continue;
      output[key] = compact(value[key], depth + 1, childPromo);
    }
    return output;
  };

  const signature = product => {
    const id = clean(directValue(product, idKeys));
    const name = fold(directValue(product, nameKeys));
    const price = clean(nestedPrice(product));
    return (id || name) + '|' + price;
  };

  const quality = product => {
    const text = JSON.stringify(product).slice(0, 20000);
    let score = Object.keys(product || {}).length;
    if (promotionSignal.test(fold(text))) score += 400;
    if (/(?:before|original|regular|previous|old|strike|crossed)[_-]?price/i.test(text)) score += 300;
    if (/(?:tags?|badges?).{0,500}(?:label|text)/i.test(text)) score += 250;
    if (/(?:second[_\s-]*(?:unit|item|product)|segunda\s+unidad|2\s*\.?\s*(?:da|do|°|º))/i.test(fold(text))) score += 500;
    return score;
  };

  const collect = root => {
    const found = [];
    const stack = [root];
    let visited = 0;
    while (stack.length > 0 && visited < MAX_VISITED && found.length < MAX_PRODUCTS_PER_RESPONSE) {
      const value = stack.pop();
      visited += 1;
      if (!value || typeof value !== 'object') continue;
      if (Array.isArray(value)) {
        for (let index = value.length - 1; index >= 0; index -= 1) stack.push(value[index]);
        continue;
      }

      if (looksLikeProduct(value)) {
        const text = JSON.stringify(value).slice(0, 24000);
        const relevant = promotionSignal.test(fold(text)) ||
          /(?:before|original|regular|previous|old|strike|crossed)[_-]?price/i.test(text) ||
          /(?:tags?|badges?|promotion|discount|commercial)/i.test(text);
        if (relevant) {
          const product = compact(value, 0, false) || {};
          product.source = 'promotion-card-v13';
          found.push(product);
        }
      }

      for (const key of Object.keys(value)) {
        const child = value[key];
        if (child && typeof child === 'object') stack.push(child);
      }
    }
    return found;
  };

  const emit = (url, products) => {
    const fresh = [];
    for (const product of products || []) {
      const key = signature(product);
      if (!key || key === '|') continue;
      const score = quality(product);
      const previous = sentQuality.get(key);
      if (previous != null && previous >= score) continue;
      sentQuality.set(key, score);
      fresh.push(product);
    }
    for (let index = 0; index < fresh.length; index += BATCH_SIZE) {
      post({
        url: String(url || location.href) + '#promotion-card-v13-' + index,
        body: JSON.stringify({ products: fresh.slice(index, index + BATCH_SIZE) }),
      });
    }
  };

  const capture = (url, text) => {
    try {
      if (!text || text.length > MAX_RESPONSE_CHARS) return;
      const sample = text.length <= 260000
        ? text
        : text.slice(0, 130000) + text.slice(-130000);
      if (!priceSignal.test(sample) || !/(?:product|item|pricing|tags?|badges?)/i.test(sample)) return;
      const root = JSON.parse(text);
      emit(url, collect(root));
    } catch (_) {}
  };

  const previousFetch = window.fetch;
  if (previousFetch && !previousFetch.__smartDealsPromotionCaptureV13Wrapped) {
    const wrappedFetch = async function(...args) {
      const response = await previousFetch.apply(this, args);
      try {
        response.clone().text().then(text => capture(response.url, text)).catch(() => {});
      } catch (_) {}
      return response;
    };
    wrappedFetch.__smartDealsPromotionCaptureV13Wrapped = true;
    window.fetch = wrappedFetch;
  }

  const previousOpen = XMLHttpRequest.prototype.open;
  if (previousOpen && !previousOpen.__smartDealsPromotionCaptureV13Wrapped) {
    const wrappedOpen = function(method, url, ...rest) {
      this.__smartDealsPromotionCaptureUrl = url;
      return previousOpen.call(this, method, url, ...rest);
    };
    wrappedOpen.__smartDealsPromotionCaptureV13Wrapped = true;
    XMLHttpRequest.prototype.open = wrappedOpen;
  }

  const previousSend = XMLHttpRequest.prototype.send;
  if (previousSend && !previousSend.__smartDealsPromotionCaptureV13Wrapped) {
    const wrappedSend = function(...args) {
      this.addEventListener('load', function() {
        try {
          if (!this.responseType || this.responseType === 'text') {
            capture(this.responseURL || this.__smartDealsPromotionCaptureUrl || '', this.responseText || '');
          }
        } catch (_) {}
      }, { once: true });
      return previousSend.apply(this, args);
    };
    wrappedSend.__smartDealsPromotionCaptureV13Wrapped = true;
    XMLHttpRequest.prototype.send = wrappedSend;
  }
})();

"""
