/* === CONFIG === */
// Route through nginx /dev-health proxy — avoids CORS & works in Docker + server.py
const CH = { host:'/dev-health/clickhouse', user:'default', pass:'clickhouse' };
let liveTimer=null, logsPage=0;
let charts={};

async function chQuery(sql) {
  // Use POST to avoid URL length limits and encoding issues
  const p = new URLSearchParams({user:CH.user, password:CH.pass, default_format:'JSONCompact'});
  const r = await fetch(`${CH.host}/?${p}`, { method: 'POST', body: sql });
  if(!r.ok) throw new Error(await r.text());
  return JSON.parse(await r.text());
}

function esc(s){ const d=document.createElement('div');d.textContent=String(s||'');return d.innerHTML; }
function fmtTs(s){ return s ? s.replace('T',' ').substring(0,19) : ''; }
function detectLevel(log){
  const l=log.toLowerCase();
  // Exclude common false positives
  if(l.includes("error=null") || l.includes("error='null'") || l.includes("error\":null")) return 'DEBUG';
  if(l.includes('fatal')||l.includes('exception')) return 'ERROR';
  // Check for explicit ERROR tags (like in Spring/Logback) or common patterns
  if(l.includes(' error ') || l.includes('[error]') || (l.includes('error') && !l.includes('debug'))) return 'ERROR';
  if(l.includes('warn'))  return 'WARN';
  if(l.includes('debug')) return 'DEBUG';
  return 'INFO';
}
function devToast(msg,type='info'){
  const c=document.getElementById('devToastContainer');
  const t=document.createElement('div');
  t.className=`dev-toast ${type}`; t.textContent=msg; c.appendChild(t);
  setTimeout(()=>t.remove(),3000);
}
function destroyChart(k){ if(charts[k]){charts[k].destroy();charts[k]=null;} }
function mkChart(id,cfg){ destroyChart(id); charts[id]=new Chart(document.getElementById(id).getContext('2d'),cfg); }

/* === PANEL NAV === */
document.addEventListener('DOMContentLoaded',()=>{
  document.querySelectorAll('.dev-nav-item').forEach(btn=>{
    btn.addEventListener('click',()=>{
      document.querySelectorAll('.dev-nav-item').forEach(b=>b.classList.remove('active'));
      document.querySelectorAll('.dev-panel').forEach(p=>p.classList.remove('active'));
      btn.classList.add('active');
      document.getElementById('panel-'+btn.dataset.panel).classList.add('active');
      onSwitch(btn.dataset.panel);
    });
  });
  checkCH(); loadHealth();
  initChPresets(); initPgPresets();
  document.getElementById('liveRefreshRate').addEventListener('change',()=>{ stopLive(); startLive(); });
  document.getElementById('healthRefreshRate').addEventListener('change', setupHealthTimer);
  document.addEventListener('keydown',e=>{
    if((e.ctrlKey||e.metaKey)&&e.key==='Enter'){ e.preventDefault(); runChQuery(); }
  });
});

function onSwitch(panel){
  stopLive();
  if(panel==='health')     loadHealth();
  if(panel==='logs')       { fillServiceSelect('logsServiceFilter'); loadServiceLogs(); }
  if(panel==='requests')   loadRequestCounts();
  if(panel==='errors')     { fillServiceSelect('errServiceFilter'); loadErrors(); }
  if(panel==='livelogs')   { fillServiceSelect('liveServiceFilter'); startLive(); }
  if(panel==='tables')     loadChTables();
  if(panel==='postgres')   loadPgTables();
  if(panel==='grafana')    {} // iframe loads automatically
}

/* === CH CONNECTION === */
async function checkCH(){
  const dot=document.getElementById('chDot'), lbl=document.getElementById('chStatusLabel');
  dot.className='status-dot checking'; lbl.textContent='Checking…';
  try{ await chQuery('SELECT 1'); dot.className='status-dot up'; lbl.textContent='ClickHouse OK'; }
  catch{ dot.className='status-dot down'; lbl.textContent='CH Unreachable'; }
}

/* === HEALTH === */
const APP_SVCS=[
  {name:'Auth Service',      url:'/dev-health/auth/actuator/health',      label:'auth-service:8082'},
  {name:'Upsert Service',    url:'/dev-health/upsert/actuator/health',    label:'upsert-service:8081'},
  {name:'Analytics Service', url:'/dev-health/analytics/actuator/health', label:'analytics-service:8084'},
  {name:'OCR Service',       url:'/dev-health/ocr/actuator/health',       label:'ocr-parser-service:8083'},
  {name:'API Gateway',       url:'/dev-health/gateway/actuator/health',   label:'api-gateway:8080'},
];
const INFRA_SVCS=[
  {name:'ClickHouse',  url:`/dev-health/clickhouse/?query=SELECT+1&user=${CH.user}&password=${CH.pass}`, label:'clickhouse:8123'},
  {name:'Postgres',    url:'/api/auth/health',                      label:'postgres-db:5432'},
  {name:'Prometheus',  url:'/dev-health/prometheus/-/ready',        label:'prometheus:9090'},
  {name:'Grafana',     url:'/dev-health/grafana/api/health',        label:'grafana:3000'},
  {name:'Fluent Bit',  url:'/dev-health/fluent-bit/api/v1/health',  label:'fluent-bit:2020'},
  {name:'Frontend',    url:'/',                                      label:'nginx:8000'},
];
let healthTimer=null;
function setupHealthTimer(){
  clearInterval(healthTimer);
  const v=parseInt(document.getElementById('healthRefreshRate').value);
  if(v>0) healthTimer=setInterval(loadHealth,v*1000);
}
async function loadHealth(){
  await renderGrid('healthGrid', APP_SVCS);
  await renderGrid('infraGrid', INFRA_SVCS);
}
async function renderGrid(gridId, svcs){
  const g=document.getElementById(gridId); g.innerHTML='';
  const results=await Promise.all(svcs.map(checkSvc));
  results.forEach(r=>{ g.innerHTML+=healthCard(r); });
}
async function checkSvc(s){
  const t=Date.now();
  try{
    const r=await fetch(s.url,{signal:AbortSignal.timeout(4000)});
    return {name:s.name,url:s.url,label:s.label,status:r.ok?'up':'down',ping:Date.now()-t};
  }catch{ return {name:s.name,url:s.url,label:s.label,status:'down',ping:null}; }
}
function healthCard(r){
  const short = r.label || r.url.replace('http://localhost','').replace('/actuator/health','').replace('/dev-health/','').split('/')[0];
  return `<div class="health-card ${r.status}">
    <div class="health-card-top"><span class="health-service-name">${r.name}</span><span class="health-badge ${r.status}">${r.status.toUpperCase()}</span></div>
    <div class="health-url">${short}</div>
    <div class="health-ping">${r.ping?r.ping+'ms':'timeout'}</div>
  </div>`;
}

/* === LOGS PER SERVICE === */
async function fillServiceSelect(selId){
  try{
    const d=await chQuery('SELECT DISTINCT source FROM observability_logs.container_logs ORDER BY source');
    const sel=document.getElementById(selId); if(!sel)return;
    sel.innerHTML='<option value="">All Services</option>';
    (d.data||[]).forEach(r=>{ sel.innerHTML+=`<option value="${r[0]}">${r[0]}</option>`; });
  }catch{}
}
async function loadServiceLogs(){
  const svc=document.getElementById('logsServiceFilter').value;
  const hrs=document.getElementById('logsTimeFilter').value;
  const srch=document.getElementById('logsSearch').value.trim();
  const lvl=document.getElementById('logsLevelFilter').value;
  let where=`timestamp>=now()-INTERVAL ${hrs} HOUR`;
  if(svc)  where+=` AND source='${svc}'`;
  if(srch) where+=` AND positionCaseInsensitive(log,'${srch.replace(/'/g,"\\'")}')>0`;
  if(lvl==='ERROR') where+=` AND (positionCaseInsensitive(log,'error')>0 OR positionCaseInsensitive(log,'exception')>0)`;
  if(lvl==='WARN')  where+=` AND positionCaseInsensitive(log,'warn')>0`;
  if(lvl==='INFO')  where+=` AND positionCaseInsensitive(log,'info')>0`;
  try{
    const [rows,vol]=await Promise.all([
      chQuery(`SELECT timestamp,source,log FROM observability_logs.container_logs WHERE ${where} ORDER BY timestamp DESC LIMIT 100 OFFSET ${logsPage*100}`),
      chQuery(`SELECT source,count() FROM observability_logs.container_logs WHERE ${where} GROUP BY source ORDER BY count() DESC`)
    ]);
    const data=rows.data||[];
    document.getElementById('logTableCount').textContent=data.length+' rows';
    document.getElementById('logsPageInfo').textContent='Page '+(logsPage+1);
    document.getElementById('logsPrevBtn').disabled=logsPage===0;
    document.getElementById('logsNextBtn').disabled=data.length<100;
    document.getElementById('logsTableBody').innerHTML=data.length?data.map(r=>{
      const lv=detectLevel(r[2]);
      return `<tr><td>${fmtTs(r[0])}</td><td><span class="service-tag">${esc(r[1])}</span></td><td><span class="log-level ${lv.toLowerCase()}">${lv}</span></td><td style="max-width:560px;word-break:break-word">${esc(r[2]).substring(0,280)}</td></tr>`;
    }).join(''):`<tr><td colspan="4" class="empty-cell">No logs found</td></tr>`;
    const vd=vol.data||[];
    document.getElementById('logVolumeTotal').textContent=vd.reduce((a,r)=>a+parseInt(r[1]),0)+' total';
    mkChart('logVolumeChart',{type:'bar',data:{labels:vd.map(r=>r[0]),datasets:[{data:vd.map(r=>parseInt(r[1])),backgroundColor:'rgba(88,166,255,.5)',borderColor:'#58a6ff',borderWidth:1}]},options:{responsive:true,maintainAspectRatio:false,plugins:{legend:{display:false}},scales:{x:{ticks:{color:'#8b949e',font:{size:10}}},y:{ticks:{color:'#8b949e',font:{size:10}}}}}});
  }catch(e){devToast('Error: '+e.message,'error');}
}

/* === REQUEST COUNTS === */
async function loadRequestCounts(){
  const hrs=document.getElementById('reqTimeFilter').value;
  try{
    const [stats,tl,ps]=await Promise.all([
      chQuery(`SELECT count(),countIf((positionCaseInsensitive(log,'error')>0 OR positionCaseInsensitive(log,'exception')>0) AND positionCaseInsensitive(log,'error=null')=0 AND positionCaseInsensitive(log,'error=\\'null\\'')=0 AND positionCaseInsensitive(log,'debug')=0) FROM observability_logs.container_logs WHERE timestamp>=now()-INTERVAL ${hrs} HOUR`),
      chQuery(`SELECT toStartOfHour(timestamp) as h,count() FROM observability_logs.container_logs WHERE timestamp>=now()-INTERVAL ${hrs} HOUR GROUP BY h ORDER BY h`),
      chQuery(`SELECT source,count(),countIf((positionCaseInsensitive(log,'error')>0 OR positionCaseInsensitive(log,'exception')>0) AND positionCaseInsensitive(log,'error=null')=0 AND positionCaseInsensitive(log,'error=\\'null\\'')=0 AND positionCaseInsensitive(log,'debug')=0) FROM observability_logs.container_logs WHERE timestamp>=now()-INTERVAL ${hrs} HOUR GROUP BY source ORDER BY count() DESC LIMIT 12`)
    ]);
    const total=parseInt((stats.data||[[0]])[0][0]||0);
    const errors=parseInt((stats.data||[[0,0]])[0][1]||0);
    const rate=total>0?((errors/total)*100).toFixed(1):'0.0';
    document.getElementById('reqStatRow').innerHTML=`
      <div class="stat-item"><div class="stat-item-label">Total Logs</div><div class="stat-item-value blue">${total.toLocaleString()}</div></div>
      <div class="stat-item"><div class="stat-item-label">Error Logs</div><div class="stat-item-value red">${errors.toLocaleString()}</div></div>
      <div class="stat-item"><div class="stat-item-label">Error Rate</div><div class="stat-item-value ${parseFloat(rate)>5?'red':'green'}">${rate}%</div></div>
      <div class="stat-item"><div class="stat-item-label">Containers</div><div class="stat-item-value">${(ps.data||[]).length}</div></div>`;
    const td=tl.data||[];
    mkChart('reqTimelineChart',{type:'line',data:{labels:td.map(r=>r[0].substring(11,16)),datasets:[{label:'Logs/hr',data:td.map(r=>parseInt(r[1])),borderColor:'#58a6ff',backgroundColor:'rgba(88,166,255,.1)',tension:0.3,fill:true,pointRadius:2}]},options:{responsive:true,maintainAspectRatio:false,plugins:{legend:{display:false}},scales:{x:{ticks:{color:'#8b949e',font:{size:10},maxTicksLimit:10}},y:{ticks:{color:'#8b949e',font:{size:10}}}}}});
    const pd=ps.data||[];
    mkChart('reqPerServiceChart',{type:'bar',data:{labels:pd.map(r=>r[0]),datasets:[{label:'Total',data:pd.map(r=>parseInt(r[1])),backgroundColor:'rgba(88,166,255,.5)'},{label:'Errors',data:pd.map(r=>parseInt(r[2])),backgroundColor:'rgba(248,81,73,.5)'}]},options:{responsive:true,maintainAspectRatio:false,indexAxis:'y',plugins:{legend:{labels:{color:'#8b949e',font:{size:10}}}},scales:{x:{ticks:{color:'#8b949e',font:{size:10}}},y:{ticks:{color:'#8b949e',font:{size:10}}}}}});
    document.getElementById('reqRollupBody').innerHTML=pd.length?pd.map(r=>{
      const rate=parseInt(r[1])>0?((parseInt(r[2])/parseInt(r[1]))*100).toFixed(1):'0.0';
      return `<tr><td>—</td><td><span class="service-tag">${esc(r[0])}</span></td><td>${parseInt(r[1]).toLocaleString()}</td><td>${parseInt(r[2]).toLocaleString()}</td><td>${rate}%</td><td><div style="width:${Math.min(parseFloat(rate)*4,100)}%;height:4px;background:${parseFloat(rate)>5?'#f85149':'#3fb950'};border-radius:2px"></div></td></tr>`;
    }).join(''):`<tr><td colspan="6" class="empty-cell">No data</td></tr>`;
  }catch(e){devToast('Error: '+e.message,'error');}
}

/* === CLICKHOUSE CONSOLE === */
const CH_PRESETS=[
  {label:'All tables',    sql:'SHOW TABLES FROM observability_logs'},
  {label:'Recent logs',   sql:'SELECT timestamp,source,log FROM observability_logs.container_logs ORDER BY timestamp DESC LIMIT 50'},
  {label:'Log count/svc', sql:'SELECT source,count() as logs FROM observability_logs.container_logs GROUP BY source ORDER BY logs DESC'},
  {label:'Errors',        sql:"SELECT timestamp,source,log FROM observability_logs.container_logs WHERE (positionCaseInsensitive(log,'error')>0 OR positionCaseInsensitive(log,'exception')>0) AND positionCaseInsensitive(log,'error=null')=0 AND positionCaseInsensitive(log,'error=\\'null\\'')=0 AND positionCaseInsensitive(log,'debug')=0 ORDER BY timestamp DESC LIMIT 50"},
  {label:'Hourly volume', sql:'SELECT toStartOfHour(timestamp) as hour,count() as logs FROM observability_logs.container_logs GROUP BY hour ORDER BY hour DESC LIMIT 48'},
  {label:'Today logs',    sql:'SELECT count() FROM observability_logs.container_logs WHERE toDate(timestamp)=today()'},
  {label:'Warn logs',     sql:"SELECT timestamp,source,log FROM observability_logs.container_logs WHERE positionCaseInsensitive(log,'warn')>0 ORDER BY timestamp DESC LIMIT 50"},
  {label:'Table sizes',   sql:"SELECT table,formatReadableSize(sum(bytes)) as size,sum(rows) as rows FROM system.parts WHERE database='observability_logs' AND active GROUP BY table"},
];
function initChPresets(){
  document.getElementById('chPresets').innerHTML=CH_PRESETS.map((p,i)=>
    `<button class="preset-btn ch-preset" onclick="setChPreset(${i})">${p.label}</button>`).join('');
}
function setChPreset(i){ document.getElementById('chQueryEditor').value=CH_PRESETS[i].sql; }
function clearChQuery(){ document.getElementById('chQueryEditor').value=''; document.getElementById('chResultWrap').innerHTML='<div class="empty-cell" style="padding:32px;text-align:center">Run a query to see results</div>'; }
async function runChQuery(){
  const sql=document.getElementById('chQueryEditor').value.trim(); if(!sql)return;
  const btn=document.getElementById('chRunBtn'); btn.disabled=true; btn.textContent='Running…';
  const t=Date.now();
  try{
    const d=await chQuery(sql);
    const elapsed=Date.now()-t;
    document.getElementById('chQueryTime').textContent=elapsed+'ms';
    const cols=d.meta||[];
    const rows=d.data||[];
    document.getElementById('chResultMeta').textContent=`${rows.length} rows · ${elapsed}ms`;
    chLastResult={cols,rows};
    document.getElementById('chResultWrap').innerHTML=rows.length?`<table class="dev-table"><thead><tr>${cols.map(c=>`<th>${esc(c.name)}</th>`).join('')}</tr></thead><tbody>${rows.map(r=>`<tr>${r.map(v=>`<td>${esc(String(v))}</td>`).join('')}</tr>`).join('')}</tbody></table>`:'<div class="empty-cell" style="padding:24px;text-align:center">0 rows returned</div>';
  }catch(e){ document.getElementById('chResultWrap').innerHTML=`<div class="empty-cell" style="padding:24px;text-align:center;color:var(--red)">${esc(e.message)}</div>`; }
  finally{ btn.disabled=false; btn.textContent='▶ Run'; }
}
function exportChResult(){
  const {cols,rows}=chLastResult||{};
  if(!rows||!rows.length){devToast('No results to export','error');return;}
  const csv=[cols.map(c=>c.name).join(','),...rows.map(r=>r.map(v=>'"'+String(v).replace(/"/g,'""')+'"').join(','))].join('\n');
  dl('ch-result.csv',csv,'text/csv');
}

/* === POSTGRES CONSOLE === */
const PG_PRESETS=[
  {label:'All entries',    sql:'SELECT * FROM finance.entries ORDER BY created_at DESC LIMIT 50'},
  {label:'Users',          sql:'SELECT id,email,role,created_at FROM auth.users ORDER BY created_at DESC'},
  {label:'Expense by cat', sql:"SELECT expense_category,COUNT(*),SUM(amount) FROM finance.entries WHERE type='EXPENSE' GROUP BY expense_category ORDER BY SUM(amount) DESC"},
  {label:'Income by cat',  sql:"SELECT income_category,COUNT(*),SUM(amount) FROM finance.entries WHERE type='INCOME' GROUP BY income_category ORDER BY SUM(amount) DESC"},
  {label:'Daily totals',   sql:"SELECT DATE(created_at),type,SUM(amount) FROM finance.entries GROUP BY DATE(created_at),type ORDER BY DATE(created_at) DESC LIMIT 60"},
  {label:'Groups',         sql:'SELECT * FROM finance.expense_groups ORDER BY created_at DESC'},
  {label:'Row counts',     sql:"SELECT schemaname,tablename,n_live_tup FROM pg_stat_user_tables ORDER BY n_live_tup DESC"},
  {label:'Tables list',    sql:"SELECT table_schema,table_name FROM information_schema.tables WHERE table_schema NOT IN ('pg_catalog','information_schema') ORDER BY table_schema,table_name"},
];
function initPgPresets(){
  document.getElementById('pgPresets').innerHTML=PG_PRESETS.map((p,i)=>
    `<button class="preset-btn pg-preset" onclick="setPgPreset(${i})">${p.label}</button>`).join('');
}
function setPgPreset(i){ document.getElementById('pgQueryEditor').value=PG_PRESETS[i].sql; }
async function loadPgTables(){
  const schema=document.getElementById('pgSchemaSelect').value;
  const list=document.getElementById('pgTableList');
  list.innerHTML='<div class="empty-cell">Loading…</div>';
  try{
    const r=await fetch(`http://localhost:8084/actuator/health`);
    list.innerHTML=`<div class="pg-table-item" onclick="setPgPreset(7)">📋 Use preset queries above</div>
    <div class="pg-table-item" onclick="document.getElementById('pgQueryEditor').value='SELECT * FROM ${schema}.entries LIMIT 20'">📋 entries</div>
    <div class="pg-table-item" onclick="document.getElementById('pgQueryEditor').value='SELECT * FROM ${schema}.expense_groups LIMIT 20'">📋 expense_groups</div>
    <div class="pg-table-item" onclick="document.getElementById('pgQueryEditor').value='SELECT * FROM auth.users LIMIT 20'">📋 users</div>`;
  }catch{ list.innerHTML='<div class="empty-cell">Service offline</div>'; }
}
function loadPgSelectQuery(){ document.getElementById('pgQueryEditor').value='SELECT * FROM finance.transaction_entries ORDER BY created_at DESC LIMIT 50'; }
async function runPgQuery(){
  let sql=document.getElementById('pgQueryEditor').value.trim(); if(!sql)return;
  
  // Rewrite the query to use the ClickHouse Postgres proxy schemas
  // auth.* -> pg_auth.*, finance.* -> pg_finance.*
  const mappedSql = sql.replace(/\bauth\./g, 'pg_auth.').replace(/\bfinance\./g, 'pg_finance.');
  
  const t=Date.now();
  document.getElementById('pgResultWrap').innerHTML='<div class="empty-cell" style="padding:24px;text-align:center">Running…</div>';
  try {
    const d=await chQuery(mappedSql);
    const elapsed=Date.now()-t;
    const cols=d.meta||[];
    const rows=d.data||[];
    document.getElementById('pgResultMeta').textContent=`${rows.length} rows · ${elapsed}ms`;
    chLastResult={cols,rows}; // Save for export reuse
    document.getElementById('pgResultWrap').innerHTML=rows.length?`<table class="dev-table"><thead><tr>${cols.map(c=>`<th>${esc(c.name)}</th>`).join('')}</tr></thead><tbody>${rows.map(r=>`<tr>${r.map(v=>`<td>${esc(String(v))}</td>`).join('')}</tr>`).join('')}</tbody></table>`:'<div class="empty-cell" style="padding:24px;text-align:center">0 rows returned</div>';
  }catch(e){ document.getElementById('pgResultWrap').innerHTML=`<div class="empty-cell" style="padding:24px;text-align:center;color:var(--red)">${esc(e.message)}</div>`; }
}
function exportPgResult(){ exportChResult(); }

/* === ERROR TRACKER === */
async function loadErrors(){
  const hrs=document.getElementById('errTimeFilter').value;
  const svc=document.getElementById('errServiceFilter').value;
  let where=`timestamp>=now()-INTERVAL ${hrs} HOUR AND (positionCaseInsensitive(log,'error')>0 OR positionCaseInsensitive(log,'exception')>0 OR positionCaseInsensitive(log,'fatal')>0) AND positionCaseInsensitive(log,'error=null')=0 AND positionCaseInsensitive(log,'error=\\'null\\'')=0 AND positionCaseInsensitive(log,'debug')=0`;
  if(svc) where+=` AND source='${svc}'`;
  try{
    const [rows,bySvc,timeline]=await Promise.all([
      chQuery(`SELECT timestamp,source,log FROM observability_logs.container_logs WHERE ${where} ORDER BY timestamp DESC LIMIT 100`),
      chQuery(`SELECT source,count() FROM observability_logs.container_logs WHERE ${where} GROUP BY source ORDER BY count() DESC`),
      chQuery(`SELECT toStartOfHour(timestamp) as h,count() FROM observability_logs.container_logs WHERE ${where} GROUP BY h ORDER BY h`)
    ]);
    const data=rows.data||[], svd=bySvc.data||[], tld=timeline.data||[];
    document.getElementById('errTableCount').textContent=data.length+' rows';
    document.getElementById('errStatRow').innerHTML=`
      <div class="stat-item"><div class="stat-item-label">Total Errors</div><div class="stat-item-value red">${data.length}</div></div>
      <div class="stat-item"><div class="stat-item-label">Containers</div><div class="stat-item-value yellow">${svd.length}</div></div>
      <div class="stat-item"><div class="stat-item-label">Time Window</div><div class="stat-item-value">${hrs}h</div></div>`;
    mkChart('errTimelineChart',{type:'line',data:{labels:tld.map(r=>r[0].substring(11,16)),datasets:[{label:'Errors/hr',data:tld.map(r=>parseInt(r[1])),borderColor:'#f85149',backgroundColor:'rgba(248,81,73,.1)',tension:0.3,fill:true,pointRadius:2}]},options:{responsive:true,maintainAspectRatio:false,plugins:{legend:{display:false}},scales:{x:{ticks:{color:'#8b949e',font:{size:10}}},y:{ticks:{color:'#8b949e',font:{size:10}}}}}});
    mkChart('errByServiceChart',{type:'bar',data:{labels:svd.map(r=>r[0]),datasets:[{data:svd.map(r=>parseInt(r[1])),backgroundColor:'rgba(248,81,73,.5)',borderColor:'#f85149',borderWidth:1}]},options:{responsive:true,maintainAspectRatio:false,indexAxis:'y',plugins:{legend:{display:false}},scales:{x:{ticks:{color:'#8b949e',font:{size:10}}},y:{ticks:{color:'#8b949e',font:{size:10}}}}}});
    document.getElementById('errTableBody').innerHTML=data.length?data.map(r=>`<tr><td>${fmtTs(r[0])}</td><td><span class="service-tag">${esc(r[1])}</span></td><td><span class="log-level error">ERROR</span></td><td style="max-width:580px;word-break:break-word;color:var(--red)">${esc(r[2]).substring(0,300)}</td></tr>`).join(''):`<tr><td colspan="4" class="empty-cell">No errors in this window 🎉</td></tr>`;
  }catch(e){devToast('Error: '+e.message,'error');}
}

/* === LIVE LOGS === */
function startLive(){
  loadLiveLogs();
  const rate=parseInt(document.getElementById('liveRefreshRate').value)||5;
  if(rate>0) liveTimer=setInterval(loadLiveLogs,rate*1000);
  document.getElementById('liveToggleBtn').textContent='⏸ Pause';
}
function stopLive(){ clearInterval(liveTimer); liveTimer=null; }
function toggleLiveLogs(){ liveTimer?stopLive():startLive(); document.getElementById('liveToggleBtn').textContent=liveTimer?'⏸ Pause':'▶ Resume'; }
function clearLiveLogs(){ document.getElementById('liveLogStream').innerHTML='<div class="log-stream-empty">Cleared</div>'; }
async function loadLiveLogs(){
  const svc=document.getElementById('liveServiceFilter').value;
  const limit=document.getElementById('liveLimit').value;
  let where=svc?`source='${svc}'`:'1=1';
  try{
    const d=await chQuery(`SELECT timestamp,source,log FROM observability_logs.container_logs WHERE ${where} ORDER BY timestamp DESC LIMIT ${limit}`);
    const rows=(d.data||[]).reverse();
    document.getElementById('liveLogCount').textContent=rows.length+' lines';
    document.getElementById('liveLastUpdate').textContent='Updated '+new Date().toLocaleTimeString();
    const stream=document.getElementById('liveLogStream');
    if(!rows.length){stream.innerHTML='<div class="log-stream-empty">No logs yet</div>';return;}
    stream.innerHTML=rows.map(r=>{
      const lv=detectLevel(r[2]);
      const cls=lv==='ERROR'?'error-msg':lv==='WARN'?'warn-msg':'';
      return `<div class="log-line"><span class="log-ts">${fmtTs(r[0])}</span><span class="log-svc"><span class="service-tag">${esc(r[1])}</span></span><span class="log-msg ${cls}">${esc(r[2]).substring(0,200)}</span></div>`;
    }).join('');
    stream.scrollTop=stream.scrollHeight;
  }catch{}
}

/* === TABLE EXPLORER === */
async function loadChTables(){
  try{
    const dbs=await chQuery("SELECT name FROM system.databases ORDER BY name");
    const list=document.getElementById('chDbList');
    list.innerHTML=(dbs.data||[]).map(r=>`<div class="ch-db-item" onclick="loadChDbTables('${r[0]}')">${r[0]}</div>`).join('');
    loadChDbTables('observability_logs');
  }catch(e){devToast('Error: '+e.message,'error');}
}
async function loadChDbTables(db){
  document.getElementById('chSelectedDb').textContent=db;
  document.querySelectorAll('.ch-db-item').forEach(el=>{ el.classList.toggle('active',el.textContent===db); });
  try{
    const d=await chQuery(`SELECT table,engine,total_rows,formatReadableSize(total_bytes) FROM system.tables WHERE database='${db}' ORDER BY table`);
    document.getElementById('chTableListBody').innerHTML=(d.data||[]).length?(d.data||[]).map(r=>`<tr><td style="cursor:pointer;color:var(--blue)" onclick="loadChColumns('${db}','${r[0]}')">${esc(r[0])}</td><td>${esc(r[1])}</td><td>${(r[2]||0).toLocaleString()}</td><td>${esc(r[3])}</td><td><button class="dev-btn dev-btn-ghost dev-btn-sm" onclick="previewChTable('${db}','${r[0]}')">Preview</button></td></tr>`).join(''):`<tr><td colspan="5" class="empty-cell">No tables</td></tr>`;
  }catch(e){devToast('Error: '+e.message,'error');}
}
async function loadChColumns(db,table){
  document.getElementById('chSelectedTable').textContent=db+'.'+table;
  try{
    const d=await chQuery(`SELECT name,type,default_expression,compression_codec FROM system.columns WHERE database='${db}' AND table='${table}' ORDER BY position`);
    document.getElementById('chColumnBody').innerHTML=(d.data||[]).map(r=>`<tr><td>${esc(r[0])}</td><td style="color:var(--purple)">${esc(r[1])}</td><td>${esc(r[2])}</td><td>${esc(r[3])}</td></tr>`).join('');
  }catch{}
}
async function previewChTable(db,table){
  db=db||document.getElementById('chSelectedDb').textContent;
  table=table||document.getElementById('chSelectedTable').textContent.split('.')[1];
  const label=`${db}.${table}`;
  document.getElementById('chPreviewCard').style.display='block';
  document.getElementById('chPreviewLabel').textContent=label;
  try{
    const d=await chQuery(`SELECT * FROM ${label} LIMIT 10`);
    const cols=d.meta||[], rows=d.data||[];
    document.getElementById('chPreviewMeta').textContent=rows.length+' rows';
    document.getElementById('chPreviewWrap').innerHTML=rows.length?`<table class="dev-table"><thead><tr>${cols.map(c=>`<th>${esc(c.name)}</th>`).join('')}</tr></thead><tbody>${rows.map(r=>`<tr>${r.map(v=>`<td>${esc(String(v))}</td>`).join('')}</tr>`).join('')}</tbody></table>`:'<div class="empty-cell" style="padding:24px;text-align:center">Empty table</div>';
  }catch(e){document.getElementById('chPreviewWrap').innerHTML=`<div class="empty-cell" style="padding:24px;color:var(--red)">${esc(e.message)}</div>`;}
}

/* === UTILS === */
function dl(filename,content,type){
  const a=document.createElement('a');
  a.href=URL.createObjectURL(new Blob([content],{type}));
  a.download=filename; a.click();
}
function formatChQuery(){ const el=document.getElementById('chQueryEditor'); el.value=el.value.replace(/\s+/g,' ').replace(/ (SELECT|FROM|WHERE|GROUP BY|ORDER BY|LIMIT|AND|OR|JOIN|ON|LEFT|INNER|UNION ALL) /gi,'\n$1 ').trim(); }

/* === MISSING: Keyboard Shortcuts === */
function setupKeyboardShortcuts() {
  // Ctrl+Enter already bound inline in DOMContentLoaded
  // Number keys for quick panel switching
  const panels = ['health','logs','requests','errors','livelogs','tables','clickhouse','postgres'];
  document.addEventListener('keydown', e => {
    if (e.altKey && e.key >= '1' && e.key <= '8') {
      e.preventDefault();
      const idx = parseInt(e.key) - 1;
      const btn = document.querySelector(`[data-panel="${panels[idx]}"]`);
      if (btn) btn.click();
    }
  });
}

/* === GRAFANA helpers === */
// Use nginx proxy paths so they work from the frontend container and server.py
function openGrafana(path) {
  // Open Grafana via the nginx proxy (same-origin, no CORS)
  window.open('/dev-health/grafana' + (path || '/'), '_blank');
}
function openPrometheus(expr) {
  const base = '/dev-health/prometheus';
  const url = expr ? base + '/graph?g0.expr=' + encodeURIComponent(expr) : base;
  window.open(url, '_blank');
}
function openChPlay(sql) {
  const base = '/dev-health/clickhouse/play';
  const url = sql ? base + '?query=' + encodeURIComponent(sql) : base;
  window.open(url, '_blank');
}


/* === REAL POSTGRES SCHEMA (static — matches project schema) === */
const PG_SCHEMA = {
  finance: [
    { name:'entries',           cols:[{n:'id',t:'UUID'},{n:'user_id',t:'UUID'},{n:'type',t:'VARCHAR'},{n:'amount',t:'NUMERIC'},{n:'currency',t:'VARCHAR'},{n:'description',t:'TEXT'},{n:'expense_category',t:'VARCHAR'},{n:'income_category',t:'VARCHAR'},{n:'date',t:'DATE'},{n:'created_at',t:'TIMESTAMPTZ'},{n:'file_url',t:'TEXT'},{n:'idempotency_key',t:'VARCHAR'}] },
    { name:'expense_groups',    cols:[{n:'id',t:'UUID'},{n:'name',t:'VARCHAR'},{n:'description',t:'TEXT'},{n:'created_by',t:'UUID'},{n:'created_at',t:'TIMESTAMPTZ'}] },
    { name:'group_members',     cols:[{n:'id',t:'UUID'},{n:'group_id',t:'UUID'},{n:'user_id',t:'UUID'},{n:'role',t:'VARCHAR'},{n:'joined_at',t:'TIMESTAMPTZ'}] },
    { name:'group_expenses',    cols:[{n:'id',t:'UUID'},{n:'group_id',t:'UUID'},{n:'paid_by',t:'UUID'},{n:'amount',t:'NUMERIC'},{n:'description',t:'TEXT'},{n:'split_type',t:'VARCHAR'},{n:'created_at',t:'TIMESTAMPTZ'}] },
    { name:'group_settlements', cols:[{n:'id',t:'UUID'},{n:'group_id',t:'UUID'},{n:'from_user',t:'UUID'},{n:'to_user',t:'UUID'},{n:'amount',t:'NUMERIC'},{n:'settled_at',t:'TIMESTAMPTZ'}] },
  ],
  auth: [
    { name:'users', cols:[{n:'id',t:'UUID'},{n:'email',t:'VARCHAR'},{n:'password_hash',t:'VARCHAR'},{n:'full_name',t:'VARCHAR'},{n:'role',t:'VARCHAR'},{n:'created_at',t:'TIMESTAMPTZ'}] },
    { name:'refresh_tokens', cols:[{n:'id',t:'UUID'},{n:'user_id',t:'UUID'},{n:'token',t:'TEXT'},{n:'expires_at',t:'TIMESTAMPTZ'},{n:'revoked',t:'BOOLEAN'}] },
  ],
};

function loadPgTables() {
  const schema = document.getElementById('pgSchemaSelect').value;
  const tables = PG_SCHEMA[schema] || [];
  const list = document.getElementById('pgTableList');
  if (!tables.length) { list.innerHTML='<div class="empty-cell">No tables mapped</div>'; return; }
  list.innerHTML = tables.map(t =>
    `<div class="pg-table-item" onclick="showPgColumns('${schema}','${t.name}')">
      <span class="pg-table-icon">📋</span>${t.name}
    </div>`
  ).join('');
}

function showPgColumns(schema, table) {
  document.querySelectorAll('.pg-table-item').forEach(el => el.classList.toggle('active', el.textContent.trim()===table));
  document.getElementById('pgSelectedTable').textContent = schema + '.' + table;
  const tables = PG_SCHEMA[schema] || [];
  const tbl = tables.find(t => t.name === table);
  if (!tbl) return;
  document.getElementById('pgColumnBody').innerHTML = tbl.cols.map(c =>
    `<tr><td style="color:var(--blue)">${c.n}</td><td style="color:var(--purple)">${c.t}</td><td style="color:var(--muted)">—</td></tr>`
  ).join('');
  document.getElementById('pgQueryEditor').value = `SELECT * FROM ${schema}.${table} LIMIT 20`;
}

function loadPgSelectQuery() {
  const lbl = document.getElementById('pgSelectedTable').textContent;
  if (lbl && lbl !== 'none') {
    document.getElementById('pgQueryEditor').value = `SELECT * FROM ${lbl} LIMIT 20`;
  }
}
