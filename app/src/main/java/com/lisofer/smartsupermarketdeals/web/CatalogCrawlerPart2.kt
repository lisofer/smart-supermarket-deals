package com.lisofer.smartsupermarketdeals.web

internal const val catalogCrawlerPart2 = """
  const allowedRoute = raw => {
    const u=abs(raw); if(!u)return false;
    try{const a=new URL(u),r=new URL(rootUrl()); if(a.host!==r.host)return false; const all=a.pathname+a.search; if(blocked.test(all)||productLink.test(all))return false;
      const rs=r.pathname.split('/').filter(Boolean),as=a.pathname.split('/').filter(Boolean),n=Math.min(2,rs.length),same=n>0&&rs.slice(0,n).every((v,i)=>as[i]===v);
      return a.pathname===r.pathname||a.pathname.startsWith(r.pathname.replace(/\/${'$'}/,'')+'/')||(same&&routeWord.test(all));
    }catch(_){return false;}
  };
  const discover = () => {
    const visited=new Set(state.visited.map(key)), pending=new Set(state.pending.map(x=>key(x.url||x))); let added=0;
    Array.from(document.querySelectorAll('a[href],[data-href],[data-url]')).slice(0,2200).forEach(el=>{
      if(el.closest(selector))return; const label=clean(el.innerText||el.textContent||el.getAttribute('aria-label')||el.getAttribute('title'));
      const raw=el.getAttribute('href')||el.getAttribute('data-href')||el.getAttribute('data-url'); if(!raw||(!routeWord.test(label)&&!routeWord.test(raw)))return;
      const u=abs(raw),k=key(u); if(!allowedRoute(u)||k===key(location.href)||k===key(rootUrl())||visited.has(k)||pending.has(k)||state.pending.length>=160)return;
      state.pending.push({url:u,label:label.slice(0,100)}); pending.add(k); added++;
    });
    save(); event('catalog_routes',{added,pending:state.pending.length,visited:state.visited.length});
  };
  const visible = el => {try{const s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>1&&r.height>1;}catch(_){return false;}};
  const loadMore = async () => {
    const el=Array.from(document.querySelectorAll('button,[role="button"]')).slice(0,400).find(x=>visible(x)&&/^(?:cargar|mostrar|traer)\s+(?:más|mas)(?:\s+productos?)?${'$'}|^ver\s+(?:más|mas)\s+productos?${'$'}/i.test(clean(x.innerText||x.textContent||x.getAttribute('aria-label'))));
    if(!el)return false; try{el.scrollIntoView({block:'center'});await sleep(100);el.click();await sleep(750);return true;}catch(_){return false;}
  };
  const sweep = async deadline => {
    window.scrollTo(0,0); await sleep(250); let stable=0,lastH=-1,lastN=-1;
    for(let i=0;i<100&&Date.now()<deadline;i++){
      await scan('Recorriendo listado completo'); discover();
      const h=Math.max(document.body?.scrollHeight||0,document.documentElement?.scrollHeight||0),bottom=Math.max(0,h-innerHeight),at=scrollY>=bottom-16;
      if(at){const loaded=await loadMore(),same=h===lastH&&sent.size===lastN&&!loaded;stable=same?stable+1:0;if(stable>=3)break;await sleep(600);}
      else{stable=0;scrollTo(0,Math.min(bottom,scrollY+Math.max(420,innerHeight*.72)));await sleep(400);} lastH=h;lastN=sent.size;await idle();
    }
    await scan('Final del listado'); discover();
  };
  const clickTabs = async deadline => {
    const done=new Set(state.controls||[]),tabs=Array.from(document.querySelectorAll('[role="tab"],button[data-testid*="category"],button[data-testid*="section"],[data-testid*="aisle"][role="button"]')).filter(visible).slice(0,36);
    for(const tab of tabs){if(Date.now()>=deadline)break;const fp=fold((tab.getAttribute('data-testid')||'')+'|'+(tab.getAttribute('aria-label')||'')+'|'+clean(tab.innerText||tab.textContent)).slice(0,220);if(!fp||done.has(fp))continue;
      done.add(fp);state.controls=Array.from(done);save();const before=abs(location.href);try{tab.click();await sleep(850);}catch(_){continue;}if(abs(location.href)!==before)return true;sent=new Set();await sweep(Math.min(deadline,Date.now()+18000));
    } return false;
  };
  const next = () => {
    const current=key(location.href),visited=new Set(state.visited.map(key));if(!visited.has(current))state.visited.push(current);
    state.pending=state.pending.filter(x=>{const k=key(x.url||x);return k&&k!==current&&!visited.has(k);});save();
    while(state.pending.length){const item=state.pending.shift(),u=abs(item.url||item);if(!u||new Set(state.visited.map(key)).has(key(u)))continue;save();event('route_change',{label:item.label||'',remaining:state.pending.length});location.assign(u);return;}
    state.complete=true;save();event('explore_complete',{steps:state.step,routesVisited:state.visited.length,exhaustive:true,url:location.href});
  };
  window.__smartDealsStartExplore = async () => {
    if(window.__smartDealsExploring)return;window.__smartDealsExploring=true;
    if(root&&key(state.root)!==key(root)){state=fresh(root);sent=new Set();save();}
    if(state.complete){event('explore_complete',{steps:state.step,routesVisited:state.visited.length,exhaustive:true,url:location.href});window.__smartDealsExploring=false;return;}
    if(Date.now()-state.started>=285000){state.pending=[];next();window.__smartDealsExploring=false;return;}
    event('explore_started',{route:location.href,exhaustive:true});await sleep(650);discover();const deadline=Math.min(state.started+285000,Date.now()+42000);await sweep(deadline);const moved=await clickTabs(deadline);if(!moved)next();window.__smartDealsExploring=false;
  };
  setTimeout(()=>scan('Lectura inicial'),900);setTimeout(discover,1500);
})();
"""
