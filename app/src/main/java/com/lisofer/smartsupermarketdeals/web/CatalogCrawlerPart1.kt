package com.lisofer.smartsupermarketdeals.web

internal const val catalogCrawlerPart1 = """
(() => {
  if (window.__smartDealsCrawlerV10) return;
  window.__smartDealsCrawlerV10 = true;
  const KEY = '__smartDealsCrawlerV10State';
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const idle = () => new Promise(r => window.requestIdleCallback ? requestIdleCallback(r, {timeout: 100}) : setTimeout(r, 16));
  const clean = v => String(v || '').replace(/\s+/g, ' ').trim();
  const fold = v => clean(v).toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '');
  const abs = raw => { try { const u = new URL(raw, location.href); u.hash=''; return u.toString(); } catch (_) { return ''; } };
  const post = value => { try { window.SmartDealsBridge?.postMessage(JSON.stringify(value)); } catch (_) {} };
  const event = (name, data={}) => post(Object.assign({event:name}, data));
  const selector = 'article,[role="listitem"],[data-testid*="product"],[data-testid*="item-card"],[class*="product-card"],[class*="ProductCard"],[class*="item-card"],[class*="ItemCard"]';
  const money = /(?:\u0024|ARS\s*)\s*\d[\d.]*(?:,\d+)?/i;
  const moneyAll = /(?:\u0024|ARS\s*)\s*\d[\d.]*(?:,\d+)?/gi;
  const second = /(?:\b2\s*(?:\.?\s*(?:da|do)|[°ºª])\.?\s*(?:unidad|producto|item)?\b|\b2\s*(?:unidad|producto|item)\b|\bsegunda\s*(?:unidad|compra)?\b|\bsegundo\s*(?:producto|item)?\b|\bsecond[_\s-]*(?:unit|item|product)\b)/i;
  const percent = /(\d{1,3}(?:[.,]\d+)?)\s*(?:%|por\s*ciento)(?:\s*(?:off|dto|de\s*descuento))?/i;
  const multi = /\b(\d{1,2})\s*[x×]\s*(\d{1,2})\b/i;
  const takePay = /lleva(?:ndo|te|á|a)?\s*(\d{1,2}).{0,50}?paga(?:ndo|á|a)?\s*(\d{1,2})/i;
  const promoWord = /(?:promo|oferta|descuento|beneficio|ahorr|off|dto)/i;
  const routeWord = /(?:ver\s+tod|todos?\s+los\s+productos|categor|secci|department|departamento|aisle|pasillo|catalog|productos|ofertas|promociones)/i;
  const blocked = /(?:checkout|carrito|cart|login|registro|perfil|account|pedido|order|help|ayuda)/i;
  const productLink = /(?:\/product(?:o)?s?\/|\/item(?:s)?\/|[?&](?:product|item|sku)=)/i;

  let root = '';
  let sent = new Set();
  const fresh = value => ({root:abs(value), pending:[], visited:[], controls:[], started:Date.now(), step:0, complete:false});
  const load = () => { try { const x=JSON.parse(sessionStorage.getItem(KEY)||'null'); if(x?.root) return x; } catch(_){} return fresh(location.href); };
  let state = load();
  const save = () => { try { sessionStorage.setItem(KEY, JSON.stringify(state)); } catch(_){} };
  window.__smartDealsSetRoot = value => { root=abs(value); if(root && abs(state.root)!==root){ state=fresh(root); sent=new Set(); save(); } };
  const rootUrl = () => root || abs(state.root) || abs(location.href);
  const key = value => abs(value).replace(/\/${'$'}/, '');

  const parts = card => {
    const out=[]; const add=v=>{v=clean(v).replace(/^['"]|['"]${'$'}/g,''); if(v&&v!=='none'&&v.length<450&&!out.includes(v))out.push(v);};
    add(card.innerText||card.textContent);
    ['aria-label','title','alt','data-promotion','data-discount','data-description'].forEach(a=>add(card.getAttribute?.(a)));
    Array.from(card.querySelectorAll('[aria-label],[title],[alt],[data-testid],[data-promotion],[data-discount],[class*="promo"],[class*="Promo"],[class*="discount"],[class*="Discount"],[class*="badge"],[class*="Badge"]')).slice(0,55).forEach(node=>{
      add(node.innerText||node.textContent);
      ['aria-label','title','alt','data-testid','data-promotion','data-discount'].forEach(a=>add(node.getAttribute?.(a)));
      const marker=fold((node.getAttribute?.('data-testid')||'')+' '+(typeof node.className==='string'?node.className:''));
      if(/promo|discount|benefit|badge|offer|descuento/.test(marker)){try{add(getComputedStyle(node,'::before').content);add(getComputedStyle(node,'::after').content);}catch(_){}}
    });
    return out.join(' · ').slice(0,5000);
  };
  const crossed = el => { if(!el)return false; if(/^(s|del|strike)${'$'}/i.test(el.tagName||''))return true; try{return (getComputedStyle(el).textDecorationLine||'').includes('line-through');}catch(_){return false;} };
  const nameOf = card => {
    const el=card.querySelector('[data-testid*="name"],[data-testid*="title"],[class*="name"],[class*="Name"],[class*="title"],[class*="Title"],h1,h2,h3,h4,strong,img[alt]');
    const first=clean(el?.tagName==='IMG'?el.getAttribute('alt'):(el?.innerText||el?.textContent));
    if(first.length>=3&&first.length<=240&&!money.test(first)&&!promoWord.test(first))return first;
    return String(card.innerText||card.textContent||'').split(/\n+/).map(clean).find(v=>v.length>=3&&v.length<=240&&!money.test(v)&&!second.test(v)&&!percent.test(v)&&!multi.test(v))||'';
  };
  const priceOf = card => {
    const leaves=Array.from(card.querySelectorAll('*')).slice(0,100).filter(e=>e.children.length===0&&money.test(clean(e.innerText||e.textContent)));
    if(!leaves.length&&money.test(clean(card.innerText||card.textContent)))leaves.push(card);
    const now=leaves.filter(e=>!crossed(e)).pop()||leaves.at(-1); const before=leaves.find(crossed);
    return {now:(clean(now?.innerText||now?.textContent).match(moneyAll)||[]).at(-1)||null,before:(clean(before?.innerText||before?.textContent).match(moneyAll)||[])[0]||null};
  };
  const promoOf = text => {
    const p=percent.exec(text);
    if(second.test(text)&&p){const v=p[1].replace(',','.');return {label:'2DA UNIDAD '+v+'% OFF',commercial:{mechanic:{type:'SECOND_UNIT'},benefit:{type:'PERCENTAGE',value:v},rawText:text.slice(0,1200)}};}
    if(second.test(text)&&/(?:gratis|sin\s*cargo)/i.test(text))return {label:'2x1',commercial:{mechanic:{type:'SECOND_UNIT_FREE'},rawText:text.slice(0,1200)}};
    const m=multi.exec(text); if(m)return {label:m[1]+'x'+m[2],commercial:{rawText:text.slice(0,1200)}};
    const t=takePay.exec(text); if(t)return {label:'LLEVÁ '+t[1]+' PAGÁ '+t[2],commercial:{rawText:text.slice(0,1200)}};
    if(p&&promoWord.test(text))return {label:p[1].replace(',','.')+'% OFF',commercial:{type:'PERCENTAGE',value:p[1],rawText:text.slice(0,1200)}};
    return null;
  };
  const scan = async phase => {
    const found=[]; const cards=Array.from(document.querySelectorAll(selector)).slice(0,1200);
    for(let i=0;i<cards.length;i++){
      const card=cards[i], visible=clean(card.innerText||card.textContent); if(visible.length<5||visible.length>4000||!money.test(visible))continue;
      const name=nameOf(card), price=priceOf(card); if(name.length<3||!price.now)continue;
      const raw=parts(card), promo=promoOf(raw), link=card.closest('a[href]')||card.querySelector('a[href]');
      const id=card.getAttribute('data-product-id')||card.getAttribute('data-item-id')||card.getAttribute('data-id')||card.getAttribute('data-testid')||link?.getAttribute('href')||name;
      const sig=clean(id)+'|'+name+'|'+price.now+'|'+(promo?.label||'')+'|'+location.pathname; if(sent.has(sig))continue; sent.add(sig);
      found.push({id:clean(id).slice(0,340),name,price:price.now,originalPrice:price.before,promotionLabel:promo?.label||null,promotionText:promo?.label||null,commercial:promo?.commercial||null,rawCommercialText:raw.slice(0,1600),source:'catalog-route-dom'});
      if(i&&i%24===0)await idle();
    }
    for(let i=0;i<found.length;i+=70)post({url:location.href+'#route-'+Date.now()+'-'+i,body:JSON.stringify({products:found.slice(i,i+70)})});
    state.step++; save(); event('explore_progress',{step:state.step,phase,pageProducts:found.length,pending:state.pending.length,visited:state.visited.length});
    return found.length;
  };
  window.__smartDealsLightScan = phase => scan(phase||'Relectura');
  window.__smartDealsExhaustiveRescan = () => scan('Relectura rápida');
"""
