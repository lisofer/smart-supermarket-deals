package com.lisofer.smartsupermarketdeals.web

/**
 * The catalog crawler owns DOM parsing. Keeping this compatibility hook avoids running a second
 * MutationObserver and a duplicate full-card scan on every layout change.
 */
internal const val promotionDomCaptureScript = """
(() => {
  if (window.__smartDealsPromotionDomInstalled) return;
  window.__smartDealsPromotionDomInstalled = true;
  window.__smartDealsPromotionDomScan = () => {
    try {
      return window.__smartDealsLightScan
        ? window.__smartDealsLightScan('Revisando promociones visibles')
        : 0;
    } catch (_) {
      return 0;
    }
  };
})();
"""
