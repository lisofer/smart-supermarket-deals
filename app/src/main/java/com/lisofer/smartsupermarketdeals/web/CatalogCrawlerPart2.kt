package com.lisofer.smartsupermarketdeals.web

internal const val catalogCrawlerPart2 = """
  const allowedRoute = raw => {
    const u=abs(raw); if(!u)return false;
    try{
      const a=new URL(u),r=new URL(rootUrl());
      if(a.host!==r.host)return false;
      const all=a.pathname+a.search;
      if(blocked.test(all)||productLink.test(all))return false;
      const rootPath=r.pathname.replace(/\/${'$'}/,'');
      const descendant=a.pathname.startsWith(rootPath+'/');
      const samePath=a.pathname===r.pathname;
      const rs=r.pathname.split('/').filter(Boolean),as=a.pathname.split('/').filter(Boolean),n=Math.min(2,rs.length);
      const sameStore=n>0&&rs.slice(0,n).every((v,i)=>as[i]===v);
      return samePath||descendant||(sameStore&&routeWord.test(all));
    }catch(_){return false;}
  };
  const likelyCategoryLink = (el, raw, label) => {
    try{
      const u=new URL(abs(raw)),r=new URL(rootUrl());
      const rootPath=r.pathname.replace(/\/${'$'}/,'');
      const descendant=u.pathname.startsWith(rootPath+'/');
      const nav=Boolean(el.closest('nav,[role="navigation"],[role="tablist"],[data-testid*="category"],[data-testid*="section"],[data-testid*="department"],[data-testid*="aisle"],[class*="category"],[class*="Category"],[class*="department"],[class*="Department"],[class*="aisle"],[class*="Aisle"]'));
      const query=/[?&](?:category|section|department|aisle|collection|filter|tag|promotion|offer)=/i.test(u.search);
      return routeWord.test(label+' '+raw)||nav||descendant||query;
    }catch(_){return false;}
  };
  const discover = () => {
    const visited=new Set(state.visited.map(key)), pending=new Set(state.pending.map(x=>key(x.url||x))); let added=0;
    Array.from(document.querySelectorAll('a[href],[data-href],[data-url]')).slice(0,5000).forEach(el=>{
      if(el.closest(selector))return;
      const label=clean(el.innerText||el.textContent||el.getAttribute('aria-label')||el.getAttribute('title')||el.getAttribute('data-testid'));
      const raw=el.getAttribute('href')||el.getAttribute('data-href')||el.getAttribute('data-url');
      if(!raw||!likelyCategoryLink(el,raw,label))return;
      const u=abs(raw),k=key(u);
      if(!allowedRoute(u)||k===key(location.href)||k===key(rootUrl())||visited.has(k)||pending.has(k)||state.pending.length>=MAX_PENDING_ROUTES)return;
      state.pending.push({url:u,label:(label||new URL(u).pathname).slice(0,140)}); pending.add(k); added++;
    });
    save(); event('catalog_routes',{added,pending:state.pending.length,visited:state.visited.length,current:location.href});
  };
  const visible = el => {try{const s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>1&&r.height>1;}catch(_){return false;}};
  const loadMore = async () => {
    const el=Array.from(document.querySelectorAll('button,[role="button"]')).slice(0,800).find(x=>visible(x)&&/^(?:cargar|mostrar|traer)\s+(?:más|mas)(?:\s+productos?)?${'$'}|^ver\s+(?:más|mas)\s+productos?${'$'}|^más\s+productos?${'$'}/i.test(clean(x.innerText||x.textContent||x.getAttribute('aria-label'))));
    if(!el)return false; try{el.scrollIntoView({block:'center'});await sleep(120);el.click();await sleep(900);return true;}catch(_){return false;}
  };
  const sweep = async deadline => {
    window.scrollTo(0,0); await sleep(300); let stable=0,lastH=-1,lastN=-1;
    for(let i=0;i<180&&Date.now()<deadline;i++){
      await scan('Recorriendo categoría completa'); discover();
      const h=Math.max(document.body?.scrollHeight||0,document.documentElement?.scrollHeight||0),bottom=Math.max(0,h-innerHeight),at=scrollY>=bottom-20;
      if(at){
        const loaded=await loadMore(),same=h===lastH&&sent.size===lastN&&!loaded;
        stable=same?stable+1:0;
        if(stable>=4)break;
        await sleep(700);
      } else {
        stable=0;
        scrollTo(0,Math.min(bottom,scrollY+Math.max(480,innerHeight*.78)));
        await sleep(450);
      }
      lastH=h;lastN=sent.size;await idle();
    }
    await scan('Final de la categoría'); discover();
  };
  const clickTabs = async deadline => {
    const done=new Set(state.controls||[]),tabs=Array.from(document.querySelectorAll('[role="tab"],button[data-testid*="category"],button[data-testid*="section"],button[data-testid*="department"],[data-testid*="aisle"][role="button"],nav button,[role="navigation"] button')).filter(visible).slice(0,120);
    for(const tab of tabs){
      if(Date.now()>=deadline)break;
      const fp=fold((tab.getAttribute('data-testid')||'')+'|'+(tab.getAttribute('aria-label')||'')+'|'+clean(tab.innerText||tab.textContent)).slice(0,240);
      if(!fp||done.has(fp)||blocked.test(fp))continue;
      done.add(fp);state.controls=Array.from(done);save();const before=abs(location.href);
      try{tab.scrollIntoView({block:'center'});await sleep(100);tab.click();await sleep(1000);}catch(_){continue;}
      if(abs(location.href)!==before)return true;
      sent=new Set();
      await sweep(Math.min(deadline,Date.now()+22000));
    }
    return false;
  };
  const next = () => {
    const current=key(location.href),visited=new Set(state.visited.map(key));
    if(!visited.has(current))state.visited.push(current);
    state.pending=state.pending.filter(x=>{const k=key(x.url||x);return k&&k!==current&&!visited.has(k);});save();
    while(state.pending.length){
      const item=state.pending.shift(),u=abs(item.url||item);
      if(!u||new Set(state.visited.map(key)).has(key(u)))continue;
      save();event('route_change',{label:item.label||'',remaining:state.pending.length,visited:state.visited.length,url:u});location.assign(u);return;
    }
    state.complete=true;save();event('explore_complete',{steps:state.step,routesVisited:state.visited.length,pending:0,exhaustive:true,categoryCrawler:true,url:location.href});
  };
  window.__smartDealsStartExplore = async () => {
    if(window.__smartDealsExploring)return;window.__smartDealsExploring=true;
    if(root&&key(state.root)!==key(root)){state=fresh(root);sent=new Set();save();}
    if(state.complete){event('explore_complete',{steps:state.step,routesVisited:state.visited.length,pending:0,exhaustive:true,categoryCrawler:true,url:location.href});window.__smartDealsExploring=false;return;}
    if(Date.now()-state.started>=CRAWLER_TOTAL_MS){state.pending=[];next();window.__smartDealsExploring=false;return;}
    event('explore_started',{route:location.href,exhaustive:true,categoryCrawler:true,visited:state.visited.length,pending:state.pending.length});
    await sleep(700);discover();
    const deadline=Math.min(state.started+CRAWLER_TOTAL_MS,Date.now()+ROUTE_SLICE_MS);
    await sweep(deadline);
    const moved=await clickTabs(deadline);
    if(!moved)next();
    window.__smartDealsExploring=false;
  };
  setTimeout(()=>scan('Lectura inicial de categoría'),1000);setTimeout(discover,1700);
})();
"""
