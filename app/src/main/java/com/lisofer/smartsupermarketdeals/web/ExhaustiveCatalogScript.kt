package com.lisofer.smartsupermarketdeals.web

/**
 * Supplemental capture layer. The base script intercepts ordinary JSON and performs a quick
 * visible scan. This layer is deliberately slower and exhaustive: it sweeps every scrollable
 * catalog surface, opens safe category controls and load-more buttons, compacts oversized JSON
 * responses, and stops only after repeated full passes discover nothing new.
 */
internal const val exhaustiveCatalogScript = """
(() => {
  if (window.__smartDealsExhaustiveInstalled) return;
  window.__smartDealsExhaustiveInstalled = true;

  const post = value => {
    try {
      if (window.SmartDealsBridge && window.SmartDealsBridge.postMessage) {
        window.SmartDealsBridge.postMessage(JSON.stringify(value));
      }
    } catch (_) {}
  };
  const sendEvent = (event, data) => post(Object.assign({ event }, data || {}));
  const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
  const normalize = value => String(value || '').replace(/\s+/g, ' ').trim();
  const normalizeKey = value => normalize(value).toLowerCase()
    .normalize('NFD').replace(/[\u0300-\u036f]/g, '');

  const state = {
    sentCards: new Set(),
    products: new Set(),
    promotions: new Set(),
    categories: new Set(),
    largePayloads: new Set(),
    steps: 0,
  };

  const moneyPattern = /(?:\u0024|ARS\s*)\s*\d[\d.]*(?:,\d+)?/i;
  const secondPattern = /(?:\b2\s*\.?\s*(?:da|do|°|º)\.?\s*(?:unidad|producto|item)?\b|\bsegunda\s*(?:unidad|compra)?\b|\bsegundo\s*(?:producto|item)?\b|\bsecond[_\s-]*(?:unit|item|product)\b)/i;
  const percentPattern = /\b\d{1,3}(?:[.,]\d+)?\s*(?:%|off|dto|de descuento)\b/i;
  const multibuyPattern = /(?:\b\d{1,2}\s*[x×]\s*\d{1,2}\b|lleva(?:ndo|te|á|a)?\s*\d{1,2}.{0,40}?paga(?:ndo|á|a)?\s*\d{1,2})/i;
  const promoWordPattern = /(?:promo(?:cion)?|oferta|descuento|beneficio|ahorr(?:a|o|á)|gratis|sin cargo)/i;
  const promoPattern = new RegExp(
    secondPattern.source + '|' + percentPattern.source + '|' +
      multibuyPattern.source + '|' + promoWordPattern.source,
    'i'
  );
  const ignoredName = /^(agregar|sumar|ver más|ver mas|mostrar más|mostrar mas|cargar más|cargar mas|envío|delivery|cerrar|buscar|inicio|categorías?|productos?|ofertas?)$/i;
  const loadMorePattern = /^(?:ver|mostrar|cargar|traer)\s+(?:más|mas)(?:\s+productos?)?$|^ver\s+todos?$/i;

  const textOf = element => normalize(element && (element.innerText || element.textContent));
  const moneyMatches = text => text.match(new RegExp(moneyPattern.source, 'gi')) || [];
  const visible = element => {
    if (!element || !element.isConnected) return false;
    try {
      const style = getComputedStyle(element);
      const rect = element.getBoundingClientRect();
      return style.display !== 'none' && style.visibility !== 'hidden' &&
        rect.width > 1 && rect.height > 1;
    } catch (_) {
      return true;
    }
  };
  const isCrossed = element => {
    if (!element) return false;
    const tag = (element.tagName || '').toLowerCase();
    if (tag === 's' || tag === 'del' || tag === 'strike') return true;
    try { return (getComputedStyle(element).textDecorationLine || '').includes('line-through'); }
    catch (_) { return false; }
  };

  const cardSelector = [
    'article', 'li', '[role="listitem"]',
    '[data-testid*="product"]', '[data-testid*="item"]',
    '[class*="product"]', '[class*="Product"]',
    '[class*="item-card"]', '[class*="ItemCard"]',
    '[class*="catalog-item"]', '[class*="CatalogItem"]'
  ].join(',');

  const preferredContainer = leaf => {
    const preferred = leaf.closest(cardSelector);
    if (preferred) {
      const text = textOf(preferred);
      if (text.length >= 6 && text.length <= 3500) return preferred;
    }
    let node = leaf;
    for (let depth = 0; depth < 11 && node; depth += 1, node = node.parentElement) {
      const text = textOf(node);
      const prices = moneyMatches(text);
      if (text.length >= 6 && text.length <= 2600 && prices.length >= 1 && prices.length <= 12) {
        return node;
      }
    }
    return leaf.parentElement;
  };

  const findName = container => {
    const preferred = container.querySelector(
      '[data-testid*="name"], [data-testid*="title"], [class*="name"], [class*="Name"], ' +
      '[class*="title"], [class*="Title"], h1, h2, h3, h4, strong, img[alt]'
    );
    const preferredText = preferred && preferred.tagName === 'IMG'
      ? normalize(preferred.getAttribute('alt'))
      : textOf(preferred);
    if (preferredText.length >= 3 && preferredText.length <= 240 &&
        !moneyPattern.test(preferredText) && !ignoredName.test(preferredText)) {
      return preferredText;
    }
    const lines = String(container.innerText || container.textContent || '')
      .split(/\n+/).map(normalize).filter(Boolean);
    return lines.find(line =>
      line.length >= 3 && line.length <= 240 &&
      !moneyPattern.test(line) && !promoPattern.test(line) && !ignoredName.test(line)
    ) || '';
  };

  const promotionTexts = container => {
    const output = new Set();
    const add = raw => {
      const value = normalize(raw);
      if (value.length >= 2 && value.length <= 300 && promoPattern.test(value)) output.add(value);
    };

    String(container.innerText || container.textContent || '')
      .split(/\n+/).forEach(add);
    add(container.getAttribute('aria-label'));
    add(container.getAttribute('title'));
    add(container.getAttribute('data-promotion'));
    add(container.getAttribute('data-discount'));

    container.querySelectorAll(
      '[data-testid*="promo"], [data-testid*="discount"], [data-testid*="benefit"], ' +
      '[data-testid*="badge"], [class*="promo"], [class*="Promo"], ' +
      '[class*="discount"], [class*="Discount"], [class*="benefit"], ' +
      '[class*="Benefit"], [class*="badge"], [class*="Badge"], [aria-label], [title]'
    ).forEach(element => {
      add(textOf(element));
      add(element.getAttribute('aria-label'));
      add(element.getAttribute('title'));
    });

    // Some layouts render the mechanic and the percentage in adjacent siblings outside the card.
    let ancestor = container.parentElement;
    for (let depth = 0; depth < 2 && ancestor; depth += 1, ancestor = ancestor.parentElement) {
      Array.from(ancestor.children).forEach(sibling => {
        if (sibling === container) return;
        const text = textOf(sibling);
        if (text.length <= 240) add(text);
      });
    }

    const values = Array.from(output);
    const hasSecond = values.some(value => secondPattern.test(value));
    const hasPercent = values.some(value => percentPattern.test(value));
    if (hasSecond && hasPercent) {
      // Joining all matching fragments is intentional: "2DA UNIDAD" and "50% OFF" may
      // be separate DOM nodes, but the Android parser must receive their shared context.
      return values.slice(0, 8).join(' · ');
    }
    return values.slice(0, 6).join(' · ');
  };

  const priceInfo = container => {
    const leaves = Array.from(container.querySelectorAll('*'))
      .filter(element => element.children.length === 0 && moneyPattern.test(textOf(element)));
    if (leaves.length === 0 && moneyPattern.test(textOf(container))) leaves.push(container);
    const nonCrossed = leaves.filter(element => !isCrossed(element));
    const current = nonCrossed[nonCrossed.length - 1] || leaves[leaves.length - 1];
    const original = leaves.find(isCrossed);
    const currentMatches = current ? moneyMatches(textOf(current)) : [];
    const originalMatches = original ? moneyMatches(textOf(original)) : [];
    return {
      price: currentMatches[currentMatches.length - 1] || null,
      originalPrice: originalMatches[0] || null,
    };
  };

  const sendProducts = (products, marker) => {
    for (let start = 0; start < products.length; start += 140) {
      const batch = products.slice(start, start + 140);
      if (batch.length > 0) {
        post({
          url: location.href + '#exhaustive-' + marker + '-' + start,
          body: JSON.stringify({ products: batch }),
        });
      }
    }
  };

  const scanCatalog = marker => {
    try {
      const candidates = new Set();
      document.querySelectorAll(cardSelector).forEach(node => {
        const text = textOf(node);
        if (text.length >= 6 && text.length <= 3500 && moneyPattern.test(text)) candidates.add(node);
      });
      document.querySelectorAll('body *').forEach(element => {
        if (element.children.length !== 0) return;
        const text = textOf(element);
        if (!moneyPattern.test(text)) return;
        const container = preferredContainer(element);
        if (container) candidates.add(container);
      });

      const newlyCaptured = [];
      let newProducts = 0;
      let newPromotions = 0;
      Array.from(candidates).forEach(container => {
        const name = findName(container);
        if (name.length < 3) return;
        const prices = priceInfo(container);
        if (!prices.price) return;
        const promotionText = promotionTexts(container);
        const link = container.closest('a') || container.querySelector('a');
        const id = container.getAttribute('data-product-id') ||
          container.getAttribute('data-item-id') ||
          container.getAttribute('data-id') ||
          container.getAttribute('data-testid') ||
          (link && link.getAttribute('href')) || name;
        const identity = normalize(id).slice(0, 320) + '|' + normalize(name) + '|' + prices.price;
        const promoIdentity = identity + '|' + normalizeKey(promotionText);
        const cardSignature = promoIdentity + '|' + (prices.originalPrice || '');

        if (!state.products.has(identity)) {
          state.products.add(identity);
          newProducts += 1;
        }
        if (promotionText && !state.promotions.has(promoIdentity)) {
          state.promotions.add(promoIdentity);
          newPromotions += 1;
        }
        if (state.sentCards.has(cardSignature)) return;
        state.sentCards.add(cardSignature);
        newlyCaptured.push({
          id: normalize(id).slice(0, 320),
          name,
          price: prices.price,
          originalPrice: prices.originalPrice,
          promotionLabel: promotionText || null,
          promotionText: promotionText || null,
          commercial: promotionText ? { text: promotionText } : null,
          source: 'exhaustive-dom',
        });
      });

      if (newlyCaptured.length > 0) sendProducts(newlyCaptured, marker || state.steps);
      return {
        candidates: candidates.size,
        newProducts,
        newPromotions,
        totalProducts: state.products.size,
        totalPromotions: state.promotions.size,
      };
    } catch (_) {
      return {
        candidates: 0, newProducts: 0, newPromotions: 0,
        totalProducts: state.products.size, totalPromotions: state.promotions.size,
      };
    }
  };

  const progress = (phase, result) => {
    state.steps += 1;
    sendEvent('explore_progress', {
      step: state.steps,
      phase,
      products: state.products.size,
      promotions: state.promotions.size,
      newProducts: result ? result.newProducts : 0,
      newPromotions: result ? result.newPromotions : 0,
    });
  };

  const clickLoadMore = async () => {
    let clicked = 0;
    const controls = Array.from(document.querySelectorAll('button, [role="button"], a'));
    for (const control of controls) {
      if (!visible(control)) continue;
      const text = normalize(control.innerText || control.textContent || control.getAttribute('aria-label'));
      if (!loadMorePattern.test(text)) continue;
      try {
        control.scrollIntoView({ block: 'center', inline: 'nearest' });
        await sleep(150);
        control.click();
        clicked += 1;
        await sleep(950);
        const result = scanCatalog('load-more-' + clicked);
        progress('Cargando más productos', result);
      } catch (_) {}
      if (clicked >= 20) break;
    }
    return clicked;
  };

  const sweepWindow = async deadline => {
    let stable = 0;
    let previousTotal = state.products.size + state.promotions.size;
    let previousHeight = 0;
    for (let iteration = 0; iteration < 260 && Date.now() < deadline; iteration += 1) {
      const height = Math.max(
        document.body ? document.body.scrollHeight : 0,
        document.documentElement ? document.documentElement.scrollHeight : 0
      );
      const maxY = Math.max(0, height - window.innerHeight);
      const currentY = window.scrollY;
      const result = scanCatalog('window-' + iteration + '-' + Math.round(currentY));
      progress('Recorriendo catálogo vertical', result);

      const total = state.products.size + state.promotions.size;
      const atBottom = currentY >= maxY - 12;
      if (atBottom) {
        stable = total === previousTotal && height === previousHeight ? stable + 1 : 0;
        if (stable >= 5) break;
        await clickLoadMore();
        await sleep(900);
      } else {
        stable = 0;
        const increment = Math.max(360, Math.round(window.innerHeight * 0.58));
        window.scrollTo(0, Math.min(maxY, currentY + increment));
        await sleep(520);
      }
      previousTotal = total;
      previousHeight = height;
    }
  };

  const scrollableElements = () => Array.from(document.querySelectorAll('body *'))
    .filter(element => {
      if (!visible(element)) return false;
      try {
        const style = getComputedStyle(element);
        const vertical = element.scrollHeight > element.clientHeight + 100 &&
          /(auto|scroll)/.test(style.overflowY || '');
        const horizontal = element.scrollWidth > element.clientWidth + 100 &&
          /(auto|scroll)/.test(style.overflowX || '');
        return vertical || horizontal;
      } catch (_) { return false; }
    })
    .sort((a, b) =>
      (b.scrollHeight - b.clientHeight + b.scrollWidth - b.clientWidth) -
      (a.scrollHeight - a.clientHeight + a.scrollWidth - a.clientWidth)
    )
    .slice(0, 120);

  const sweepInternalScrollers = async deadline => {
    const scrollers = scrollableElements();
    for (let index = 0; index < scrollers.length && Date.now() < deadline; index += 1) {
      const scroller = scrollers[index];
      if (!scroller.isConnected) continue;
      const originalTop = scroller.scrollTop;
      const originalLeft = scroller.scrollLeft;
      try {
        if (scroller.scrollHeight > scroller.clientHeight + 100) {
          let stable = 0;
          let previousTotal = state.products.size + state.promotions.size;
          for (let step = 0; step < 100 && Date.now() < deadline; step += 1) {
            const maxTop = Math.max(0, scroller.scrollHeight - scroller.clientHeight);
            const current = scroller.scrollTop;
            const result = scanCatalog('vscroll-' + index + '-' + step);
            progress('Revisando listas internas', result);
            const total = state.products.size + state.promotions.size;
            if (current >= maxTop - 8) {
              stable = total === previousTotal ? stable + 1 : 0;
              if (stable >= 3) break;
              await sleep(500);
            } else {
              scroller.scrollTop = Math.min(maxTop, current + Math.max(260, scroller.clientHeight * 0.7));
              stable = 0;
              await sleep(360);
            }
            previousTotal = total;
          }
        }
        if (scroller.scrollWidth > scroller.clientWidth + 100) {
          for (const ratio of [0, 0.2, 0.4, 0.6, 0.8, 1]) {
            const maxLeft = Math.max(0, scroller.scrollWidth - scroller.clientWidth);
            scroller.scrollLeft = Math.round(maxLeft * ratio);
            await sleep(320);
            const result = scanCatalog('hscroll-' + index + '-' + ratio);
            progress('Revisando carruseles', result);
          }
        }
      } catch (_) {}
      try {
        scroller.scrollTop = originalTop;
        scroller.scrollLeft = originalLeft;
      } catch (_) {}
    }
  };

  const categoryCandidates = () => {
    const selector = [
      '[role="tab"]',
      '[data-testid*="category"]', '[data-testid*="aisle"]', '[data-testid*="department"]',
      '[class*="category-tab"]', '[class*="CategoryTab"]',
      '[class*="aisle-tab"]', '[class*="AisleTab"]',
      '[class*="department-tab"]', '[class*="DepartmentTab"]'
    ].join(',');
    return Array.from(document.querySelectorAll(selector)).filter(element => {
      if (!visible(element)) return false;
      const text = normalize(element.innerText || element.textContent || element.getAttribute('aria-label'));
      if (text.length < 2 || text.length > 100 || ignoredName.test(text)) return false;
      const href = element.getAttribute('href') || '';
      if (/product|item|checkout|cart|login/i.test(href)) return false;
      return true;
    });
  };

  const openNextCategory = async () => {
    const controls = categoryCandidates();
    for (const control of controls) {
      const text = normalize(control.innerText || control.textContent || control.getAttribute('aria-label'));
      const fingerprint = normalizeKey(text) + '|' + normalize(control.getAttribute('data-testid')) +
        '|' + normalize(control.getAttribute('href'));
      if (state.categories.has(fingerprint)) continue;
      state.categories.add(fingerprint);
      try {
        control.scrollIntoView({ block: 'center', inline: 'center' });
        await sleep(180);
        control.click();
        await sleep(1200);
        const result = scanCatalog('category-' + state.categories.size);
        progress('Abriendo categoría: ' + text.slice(0, 40), result);
        return true;
      } catch (_) {
        return false;
      }
    }
    return false;
  };

  const NAME_KEYS = new Set([
    'name', 'title', 'productname', 'product_name', 'displayname', 'display_name',
    'itemname', 'item_name'
  ]);
  const PRICE_KEYS = new Set([
    'price', 'currentprice', 'current_price', 'saleprice', 'sale_price', 'finalprice',
    'final_price', 'discountedprice', 'discounted_price', 'promotionalprice',
    'promotional_price', 'unitprice', 'unit_price'
  ]);
  const keepKey = key => {
    const normalized = normalizeKey(key).replace(/[_\s-]/g, '');
    return NAME_KEYS.has(normalizeKey(key)) || PRICE_KEYS.has(normalizeKey(key)) ||
      /^(id|sku|ean|gtin|barcode|brand|image|picture|thumbnail|url|href|description|presentation|size|pack|unit)$/.test(normalizeKey(key)) ||
      /(product|item|price|commercial|promo|discount|descuento|benefit|badge|offer|campaign|saving|deal|mechanic|condition|rule|metadata|attribute|tag)/.test(normalized);
  };
  const localValue = (object, keys) => {
    for (const key of Object.keys(object || {})) {
      if (keys.has(normalizeKey(key))) return object[key];
    }
    return null;
  };
  const looksLikeProductObject = object => {
    if (!object || Array.isArray(object) || typeof object !== 'object') return false;
    const name = localValue(object, NAME_KEYS);
    let price = localValue(object, PRICE_KEYS);
    if (price == null) {
      for (const wrapper of ['pricing', 'priceInfo', 'price_info', 'commercial', 'product', 'item']) {
        const child = object[wrapper];
        if (child && typeof child === 'object' && !Array.isArray(child)) {
          price = localValue(child, PRICE_KEYS);
          if (price != null) break;
        }
      }
    }
    return typeof name === 'string' && normalize(name).length >= 3 && price != null;
  };
  const compactValue = (value, depth) => {
    if (depth > 7 || value == null) return null;
    if (typeof value === 'string') return value.length <= 1000 ? value : value.slice(0, 1000);
    if (typeof value === 'number' || typeof value === 'boolean') return value;
    if (Array.isArray(value)) return value.slice(0, 120).map(item => compactValue(item, depth + 1));
    if (typeof value === 'object') {
      const output = {};
      Object.keys(value).forEach(key => {
        if (keepKey(key)) output[key] = compactValue(value[key], depth + 1);
      });
      return output;
    }
    return null;
  };

  const compactLargeJson = (url, text) => {
    if (!text || text.length <= 5500000) return;
    const fingerprint = normalize(url) + '|' + text.length + '|' + text.slice(0, 120);
    if (state.largePayloads.has(fingerprint)) return;
    state.largePayloads.add(fingerprint);
    try {
      const root = JSON.parse(text);
      const found = [];
      const seen = new Set();
      let visited = 0;
      const walk = (node, depth) => {
        if (node == null || depth > 28 || visited > 250000 || found.length > 40000) return;
        visited += 1;
        if (Array.isArray(node)) {
          node.forEach(child => walk(child, depth + 1));
          return;
        }
        if (typeof node !== 'object') return;
        if (looksLikeProductObject(node)) {
          const compact = compactValue(node, 0);
          compact.source = 'large-json-fragment';
          const identity = normalize(JSON.stringify(compact).slice(0, 1200));
          if (!seen.has(identity)) {
            seen.add(identity);
            found.push(compact);
          }
        }
        Object.keys(node).forEach(key => walk(node[key], depth + 1));
      };
      walk(root, 0);
      sendProducts(found, 'large-json');
      sendEvent('explore_progress', {
        step: ++state.steps,
        phase: 'Procesando respuesta grande',
        products: state.products.size,
        promotions: state.promotions.size,
        fragments: found.length,
      });
    } catch (_) {}
  };

  const previousFetch = window.fetch;
  if (previousFetch && !previousFetch.__smartDealsLargeWrapped) {
    const wrappedFetch = async function(...args) {
      const response = await previousFetch.apply(this, args);
      try {
        const clone = response.clone();
        const type = clone.headers.get('content-type') || '';
        if (type.includes('json')) clone.text().then(text => compactLargeJson(response.url, text));
      } catch (_) {}
      return response;
    };
    wrappedFetch.__smartDealsLargeWrapped = true;
    window.fetch = wrappedFetch;
  }

  const previousXhrSend = XMLHttpRequest.prototype.send;
  if (previousXhrSend && !previousXhrSend.__smartDealsLargeWrapped) {
    const wrappedSend = function(...args) {
      this.addEventListener('load', function() {
        try {
          const type = this.getResponseHeader('content-type') || '';
          if (type.includes('json') && (!this.responseType || this.responseType === 'text')) {
            compactLargeJson(this.responseURL || '', this.responseText || '');
          }
        } catch (_) {}
      });
      return previousXhrSend.apply(this, args);
    };
    wrappedSend.__smartDealsLargeWrapped = true;
    XMLHttpRequest.prototype.send = wrappedSend;
  }

  window.__smartDealsStartExplore = async () => {
    if (window.__smartDealsExploring) return;
    window.__smartDealsExploring = true;
    const originalY = window.scrollY;
    const deadline = Date.now() + 270000;
    let stableFullPasses = 0;
    let previousTotal = -1;

    sendEvent('explore_started', { exhaustive: true });
    await sleep(1800);

    while (Date.now() < deadline && stableFullPasses < 3) {
      window.scrollTo(0, 0);
      await sleep(400);
      const before = state.products.size + state.promotions.size;
      let result = scanCatalog('full-pass-start-' + stableFullPasses);
      progress('Iniciando barrido completo', result);

      await sweepWindow(deadline);
      await sweepInternalScrollers(deadline);
      const loaded = await clickLoadMore();
      const categoryOpened = await openNextCategory();
      if (categoryOpened) {
        await sweepWindow(deadline);
        await sweepInternalScrollers(deadline);
      }

      result = scanCatalog('full-pass-end-' + stableFullPasses);
      progress('Comprobando si quedan ofertas', result);
      const after = state.products.size + state.promotions.size;
      const discovered = after > before || after > previousTotal;
      stableFullPasses = !discovered && loaded === 0 && !categoryOpened
        ? stableFullPasses + 1
        : 0;
      previousTotal = after;
      await sleep(650);
    }

    window.scrollTo(0, originalY);
    await sleep(700);
    const finalResult = scanCatalog('final');
    sendEvent('explore_complete', {
      steps: state.steps,
      visibleCount: finalResult.candidates,
      products: state.products.size,
      promotions: state.promotions.size,
      exhaustive: true,
      timedOut: Date.now() >= deadline,
      url: location.href,
    });
    window.__smartDealsExploring = false;
  };

  window.__smartDealsExhaustiveRescan = () => scanCatalog('manual-rescan');
  setTimeout(() => scanCatalog('initial-1'), 1000);
  setTimeout(() => scanCatalog('initial-2'), 3500);
})();
"""
