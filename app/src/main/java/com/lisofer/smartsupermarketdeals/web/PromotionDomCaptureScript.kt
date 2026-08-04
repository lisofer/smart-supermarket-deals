package com.lisofer.smartsupermarketdeals.web

/**
 * Defensive DOM capture for promotions rendered as separate visual fragments inside one card.
 * It evaluates the complete card and always gives second-unit mechanics priority over a direct
 * percentage, preventing `2da unidad · 50% OFF` from becoming plain `50% OFF`.
 */
internal const val promotionDomCaptureScript = """
(() => {
  if (window.__smartDealsPromotionDomInstalled) return;
  window.__smartDealsPromotionDomInstalled = true;

  const post = value => {
    try {
      if (window.SmartDealsBridge && window.SmartDealsBridge.postMessage) {
        window.SmartDealsBridge.postMessage(JSON.stringify(value));
      }
    } catch (_) {}
  };
  const normalize = value => String(value || '').replace(/\s+/g, ' ').trim();
  const sent = new Set();
  const money = /(?:\u0024|ARS\s*)\s*\d[\d.]*(?:,\d+)?/gi;
  const secondMarker = '(?:2\\s*\\.?\\s*(?:da|do|°|º)\\.?\\s*(?:unidad|producto|item)?|segunda\\s*(?:unidad|compra)?|segundo\\s*(?:producto|item)?|second[_\\s-]*(?:unit|item|product))';
  const percentage = '(\\d{1,3}(?:[.,]\\d+)?)\\s*(?:%|off|dto|de descuento)';
  const secondForward = new RegExp(secondMarker + '.{0,90}?' + percentage, 'i');
  const secondReverse = new RegExp(percentage + '.{0,90}?' + secondMarker, 'i');
  const secondFree = new RegExp(secondMarker + '.{0,50}?(?:gratis|sin cargo)', 'i');
  const multibuy = /\b(\d{1,2})\s*[x×]\s*(\d{1,2})\b/i;
  const takePay = /lleva(?:ndo|te|á|a)?\s*(\d{1,2}).{0,45}?paga(?:ndo|á|a)?\s*(\d{1,2})/i;
  const directPercent = /(?:-|ahorra|hasta|descuento)?\s*(\d{1,3}(?:[.,]\d+)?)\s*(?:%|off|dto|de descuento)(?!\w)/i;
  const ignoredName = /^(agregar|sumar|ver más|ver mas|mostrar más|mostrar mas|cargar más|cargar mas|envío|delivery|cerrar|buscar|inicio|categorías?|productos?|ofertas?)$/i;
  const selector = [
    'article', 'li', '[role="listitem"]',
    '[data-testid*="product"]', '[data-testid*="item"]',
    '[class*="product"]', '[class*="Product"]',
    '[class*="item-card"]', '[class*="ItemCard"]',
    '[class*="catalog-item"]', '[class*="CatalogItem"]'
  ].join(',');

  const textOf = element => normalize(element && (element.innerText || element.textContent));
  const crossed = element => {
    if (!element) return false;
    const tag = (element.tagName || '').toLowerCase();
    if (tag === 's' || tag === 'del' || tag === 'strike') return true;
    try { return (getComputedStyle(element).textDecorationLine || '').includes('line-through'); }
    catch (_) { return false; }
  };
  const nameOf = card => {
    const preferred = card.querySelector(
      '[data-testid*="name"], [data-testid*="title"], [class*="name"], [class*="Name"], ' +
      '[class*="title"], [class*="Title"], h1, h2, h3, h4, strong, img[alt]'
    );
    const preferredText = preferred && preferred.tagName === 'IMG'
      ? normalize(preferred.getAttribute('alt'))
      : textOf(preferred);
    if (preferredText.length >= 3 && preferredText.length <= 240 &&
        !money.test(preferredText) && !ignoredName.test(preferredText)) {
      money.lastIndex = 0;
      return preferredText;
    }
    money.lastIndex = 0;
    const lines = String(card.innerText || card.textContent || '')
      .split(/\n+/).map(normalize).filter(Boolean);
    return lines.find(line => {
      money.lastIndex = 0;
      const hasMoney = money.test(line);
      money.lastIndex = 0;
      return line.length >= 3 && line.length <= 240 && !hasMoney && !ignoredName.test(line) &&
        !secondForward.test(line) && !secondReverse.test(line) && !multibuy.test(line) &&
        !directPercent.test(line);
    }) || '';
  };
  const pricesOf = card => {
    const leaves = Array.from(card.querySelectorAll('*')).filter(element => {
      if (element.children.length !== 0) return false;
      money.lastIndex = 0;
      const matches = money.test(textOf(element));
      money.lastIndex = 0;
      return matches;
    });
    if (leaves.length === 0) leaves.push(card);
    const regular = leaves.filter(element => !crossed(element));
    const currentElement = regular[regular.length - 1] || leaves[leaves.length - 1];
    const originalElement = leaves.find(crossed);
    const currentMatches = textOf(currentElement).match(money) || [];
    const originalMatches = originalElement ? textOf(originalElement).match(money) || [] : [];
    money.lastIndex = 0;
    return {
      current: currentMatches[currentMatches.length - 1] || null,
      original: originalMatches[0] || null,
    };
  };
  const promotionOf = text => {
    const forward = secondForward.exec(text);
    if (forward) {
      const percent = forward[1];
      return {
        label: '2DA UNIDAD ' + percent + '% OFF',
        commercial: {
          mechanic: { type: 'SECOND_UNIT' },
          benefit: { type: 'PERCENTAGE', value: percent },
        },
      };
    }
    const reverse = secondReverse.exec(text);
    if (reverse) {
      const percent = reverse[1];
      return {
        label: '2DA UNIDAD ' + percent + '% OFF',
        commercial: {
          mechanic: { type: 'SECOND_UNIT' },
          benefit: { type: 'PERCENTAGE', value: percent },
        },
      };
    }
    if (secondFree.test(text)) {
      return {
        label: '2x1',
        commercial: { mechanic: { type: 'SECOND_UNIT_FREE' } },
      };
    }
    const multi = multibuy.exec(text);
    if (multi) return { label: multi[1] + 'x' + multi[2], commercial: null };
    const take = takePay.exec(text);
    if (take) return { label: 'LLEVÁ ' + take[1] + ' PAGÁ ' + take[2], commercial: null };
    const direct = directPercent.exec(text);
    if (direct) {
      return {
        label: direct[1] + '% OFF',
        commercial: { type: 'PERCENTAGE', value: direct[1] },
      };
    }
    return null;
  };

  const scan = () => {
    const products = [];
    document.querySelectorAll(selector).forEach(card => {
      const fullText = textOf(card);
      if (fullText.length < 6 || fullText.length > 4000) return;
      const promotion = promotionOf(fullText);
      if (!promotion) return;
      money.lastIndex = 0;
      if (!money.test(fullText)) {
        money.lastIndex = 0;
        return;
      }
      money.lastIndex = 0;
      const name = nameOf(card);
      if (name.length < 3) return;
      const prices = pricesOf(card);
      if (!prices.current) return;
      const link = card.closest('a') || card.querySelector('a');
      const id = card.getAttribute('data-product-id') || card.getAttribute('data-item-id') ||
        card.getAttribute('data-id') || card.getAttribute('data-testid') ||
        (link && link.getAttribute('href')) || name;
      const signature = normalize(id) + '|' + name + '|' + prices.current + '|' + promotion.label;
      if (sent.has(signature)) return;
      sent.add(signature);
      products.push({
        id: normalize(id).slice(0, 320),
        name,
        price: prices.current,
        originalPrice: prices.original,
        promotionLabel: promotion.label,
        promotionText: promotion.label,
        commercial: promotion.commercial,
        source: 'promotion-dom',
      });
    });
    if (products.length > 0) {
      for (let start = 0; start < products.length; start += 120) {
        post({
          url: location.href + '#promotion-dom-' + start + '-' + sent.size,
          body: JSON.stringify({ products: products.slice(start, start + 120) }),
        });
      }
    }
    return products.length;
  };

  let timer = null;
  const schedule = () => {
    clearTimeout(timer);
    timer = setTimeout(scan, 280);
  };
  window.__smartDealsPromotionDomScan = scan;
  new MutationObserver(schedule).observe(document.documentElement, {
    subtree: true,
    childList: true,
    characterData: true,
  });
  window.addEventListener('scroll', schedule, true);
  window.addEventListener('load', schedule);
  setTimeout(scan, 1000);
  setTimeout(scan, 3000);
  setTimeout(scan, 7000);
})();
"""
