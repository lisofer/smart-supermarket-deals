package com.lisofer.smartsupermarketdeals.web

/**
 * Starts the proven v1.2.2 adaptive endpoint engine immediately.
 *
 * The foreground service still owns the WebView, notification and lifecycle. This tiny first
 * phase only hands control to SearchEndpointHarvesterScript, while disabling the later exhaustive
 * query engine and the category-response parser that changed the behaviour after v1.2.2.
 */
internal const val exhaustiveCatalogScript = """
(() => {
  if (window.__smartDealsV122BackgroundBootstrap) return;
  window.__smartDealsV122BackgroundBootstrap = true;

  // PromotionScanService still injects these scripts for compatibility. Mark the two newer
  // coverage engines as installed so they do not replace the v1.2.2 adaptive endpoint engine.
  window.__smartDealsFastCoverageV19 = true;
  window.__smartDealsCatalogResponseV14 = true;
  window.__smartDealsSearchFinished = false;

  let storeRoot = '';
  let bootstrapReported = false;
  let completionReported = false;

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

  window.__smartDealsSetRoot = value => {
    storeRoot = absolute(value);
  };
  window.__smartDealsResetCatalogCrawler = value => {
    storeRoot = absolute(value || storeRoot || location.href);
    bootstrapReported = false;
    completionReported = false;
    window.__smartDealsSearchFinished = false;
  };
  window.__smartDealsCatalogCrawlerState = () => ({
    pending: 0,
    visited: 0,
    complete: true,
    step: bootstrapReported ? 1 : 0,
  });

  // PromotionScanService interprets this first explore_complete as the hand-off to the endpoint
  // phase. No category crawl or strategic word list runs before it.
  window.__smartDealsStartExplore = () => {
    if (bootstrapReported) return;
    bootstrapReported = true;
    post({ event: 'explore_started', v122BackgroundEngine: true, root: storeRoot });
    post({
      event: 'explore_progress',
      step: 1,
      phase: 'Iniciando la búsqueda rápida de la v1.2.2…',
      v122BackgroundEngine: true,
    });
    post({
      event: 'explore_complete',
      v122BootstrapComplete: true,
      v122BackgroundEngine: true,
    });
  };

  // SearchEndpointHarvesterScript sets this flag after finishing its adaptive empty-query/prefix
  // traversal. Translate that completion into the event expected by the background service.
  setInterval(() => {
    if (completionReported || !window.__smartDealsSearchFinished) return;
    completionReported = true;
    post({
      event: 'coverage_complete',
      endpointCoverage: true,
      v122BackgroundEngine: true,
    });
  }, 250);
})();
"""
