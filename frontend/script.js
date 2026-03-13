/* ═══════════ STATE ═══════════ */
let authToken = null, userId = null, currentEmail = null;
let categoryChart = null, timelineChart = null;

/* pagination state */
let txPage = 0, txSize = 20, txTotal = 0, txType = '', txQuery = '';
let txSearchTimer = null;

/* Groups state */
let currentGroupId = null;
let currentGroupMembers = [];

const API = '/api';

/* ── Chart Colors (frontend owns these – green industrial palette) ── */
const PIE_PALETTE = ['#059669','#10b981','#34d399','#6ee7b7','#a7f3d0','#d1fae5',
                     '#065f46','#064e3b','#022c22','#059669','#059669','#059669'];
const COLOR_INCOME  = '#059669';
const COLOR_EXPENSE = '#dc2626';

/* ═══════════ INIT ═══════════ */
document.addEventListener('DOMContentLoaded', () => {
  const t = localStorage.getItem('authToken');
  const u = localStorage.getItem('userId');
  const e = localStorage.getItem('userEmail');
  if (t && u && e) { authToken = t; userId = u; currentEmail = e; showDashboard(); }
  bindEvents();
  setDefaultDates();
});

/* ═══════════ EVENT BINDINGS ═══════════ */
function bindEvents() {
  // Auth
  document.getElementById('goToRegister').onclick = ev => { ev.preventDefault(); showPanel('registerPanel'); };
  document.getElementById('goToLogin').onclick     = ev => { ev.preventDefault(); showPanel('loginPanel'); };
  document.getElementById('loginForm').addEventListener('submit', handleLogin);
  document.getElementById('registerForm').addEventListener('submit', handleRegister);
  document.getElementById('logoutBtn').onclick = handleLogout;

  // Nav tabs
  document.querySelectorAll('.nav-item').forEach(b =>
    b.addEventListener('click', () => switchTab(b.dataset.tab)));

  // Entry form
  document.getElementById('entryForm').addEventListener('submit', handleEntrySubmit);
  document.getElementById('entryType').addEventListener('change', populateCategories);

  // Bill upload
  document.getElementById('billUploadForm').addEventListener('submit', handleBillUpload);
  document.getElementById('confirmOcrEntry').addEventListener('click', handleOcrConfirm);

  // Analytics
  document.getElementById('applyFilters').addEventListener('click', loadAnalytics);

  // Quick date buttons — overview
  document.querySelectorAll('#overviewQuickDates .quick-date-btn').forEach(b =>
    b.addEventListener('click', () => {
      document.querySelectorAll('#overviewQuickDates .quick-date-btn').forEach(x => x.classList.remove('active'));
      b.classList.add('active');
      loadOverview(b.dataset.period);
    }));

  // Quick date buttons — analytics
  document.querySelectorAll('#analyticsQuickDates .quick-date-btn').forEach(b =>
    b.addEventListener('click', () => {
      document.querySelectorAll('#analyticsQuickDates .quick-date-btn').forEach(x => x.classList.remove('active'));
      b.classList.add('active');
      applyQuickDateToAnalytics(b.dataset.period);
      loadAnalytics();
    }));

  // CSV export
  document.getElementById('exportCsvBtn').addEventListener('click', exportCsv);

  // Transaction search (debounced)
  document.getElementById('txSearch').addEventListener('input', e => {
    clearTimeout(txSearchTimer);
    txSearchTimer = setTimeout(() => {
      txQuery = e.target.value.trim();
      txPage = 0;
      loadTransactions();
    }, 350);
  });

  // Type filter pills
  document.querySelectorAll('.filter-pills .pill').forEach(p =>
    p.addEventListener('click', () => {
      document.querySelectorAll('.filter-pills .pill').forEach(x => x.classList.remove('active'));
      p.classList.add('active');
      txType = p.dataset.type;
      txPage = 0;
      loadTransactions();
    }));

  // Pagination
  document.getElementById('txPrevBtn').addEventListener('click', () => { if (txPage > 0) { txPage--; loadTransactions(); } });
  document.getElementById('txNextBtn').addEventListener('click', () => {
    if ((txPage + 1) * txSize < txTotal) { txPage++; loadTransactions(); }
  });

  // Splitwise / Groups
  document.getElementById('createGroupForm').addEventListener('submit', handleCreateGroup);
  document.getElementById('addMemberForm').addEventListener('submit', handleAddMember);
  document.getElementById('sharedExpenseForm').addEventListener('submit', handleAddSharedExpense);
  document.getElementById('splitTypeSelect').addEventListener('change', renderSplitDetails);

  // Modal helpers
  window.hideModal = (id) => document.getElementById(id).style.display = 'none';
  window.showCreateGroupModal = () => document.getElementById('createGroupModal').style.display = 'flex';
  window.showAddMemberModal = () => document.getElementById('addMemberModal').style.display = 'flex';
  window.showAddSharedExpenseModal = () => {
    populatePayerSelect();
    renderSplitDetails();
    document.getElementById('addSharedExpenseModal').style.display = 'flex';
  };
}

/* ═══════════ PANEL HELPERS ═══════════ */
function showPanel(id) {
  ['loginPanel','registerPanel'].forEach(p => {
    const el = document.getElementById(p);
    el.classList.toggle('visible', p === id);
  });
}

/* ═══════════ AUTH ═══════════ */
async function handleLogin(e) {
  e.preventDefault();
  const form = e.target;
  setLoading(true);
  try {
    const res = await api('/auth/login', 'POST', {
      email: form.email.value.trim(), password: form.password.value, role: form.role.value
    });
    authToken = res.token; userId = res.userId; currentEmail = form.email.value.trim();
    localStorage.setItem('authToken', authToken);
    localStorage.setItem('userId', userId);
    localStorage.setItem('userEmail', currentEmail);
    showDashboard();
  } catch (err) { showInlineAlert('loginError', err.message, 'error'); }
  finally { setLoading(false); }
}

async function handleRegister(e) {
  e.preventDefault();
  const form = e.target;
  setLoading(true);
  try {
    await api('/auth/register', 'POST', {
      email: form.email.value.trim(), password: form.password.value, role: form.role.value
    });
    showInlineAlert('registerSuccess', 'Account created! You can now sign in.', 'success');
    form.reset();
    setTimeout(() => showPanel('loginPanel'), 1800);
  } catch (err) { showInlineAlert('registerError', err.message, 'error'); }
  finally { setLoading(false); }
}

function handleLogout() {
  authToken = userId = currentEmail = null;
  ['authToken','userId','userEmail'].forEach(k => localStorage.removeItem(k));
  document.getElementById('authScreen').classList.add('active');
  document.getElementById('dashboardScreen').classList.remove('active');
  showPanel('loginPanel');
  document.getElementById('loginForm').reset();
}

/* ═══════════ SCREENS ═══════════ */
function showDashboard() {
  document.getElementById('authScreen').classList.remove('active');
  document.getElementById('dashboardScreen').classList.add('active');
  const emailEl = document.getElementById('userEmail');
  const avatarEl = document.getElementById('userAvatar');
  emailEl.textContent = currentEmail;
  avatarEl.textContent = currentEmail ? currentEmail[0].toUpperCase() : '?';
  loadOverview('month');
}

/* ═══════════ TAB SWITCHING ═══════════ */
function switchTab(tab) {
  document.querySelectorAll('.nav-item').forEach(b => b.classList.toggle('active', b.dataset.tab === tab));
  document.querySelectorAll('.tab-panel').forEach(p => p.classList.toggle('active', p.id === tab));
  if (tab === 'overview')     loadOverview('month');
  if (tab === 'transactions') { txPage = 0; loadTransactions(); }
  if (tab === 'analytics')    loadAnalytics();
  if (tab === 'groups')       loadGroups();
}

/* ═══════════ API HELPER ═══════════ */
async function api(path, method = 'GET', body = null, isForm = false) {
  const opts = { method, headers: { 'Authorization': `Bearer ${authToken}` } };
  if (body && !isForm) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
  else if (body && isForm) { opts.body = body; }
  const res = await fetch(`${API}${path}`, opts);
  if (!res.ok) {
    let msg = `Request failed (${res.status})`;
    try { const j = await res.json(); msg = j.message || j.error || msg; } catch {}
    throw new Error(msg);
  }
  const text = await res.text();
  return text ? JSON.parse(text) : {};
}

/* ═══════════ DATE HELPERS ═══════════ */
function periodToDates(period) {
  const now    = new Date();
  const year   = now.getFullYear();
  const month  = now.getMonth();
  switch (period) {
    case 'month':     return [new Date(year, month, 1), new Date(year, month +1, 0)];
    case 'lastmonth': return [new Date(year, month -1, 1), new Date(year, month, 0)];
    case 'quarter': {
      const q = Math.floor(month / 3);
      return [new Date(year, q*3, 1), new Date(year, q*3+3, 0)];
    }
    case 'year':      return [new Date(year, 0, 1), new Date(year, 11, 31)];
    default: return [null, null];
  }
}

function toISO(d) { return d ? d.toISOString().split('T')[0] : null; }

function applyQuickDateToAnalytics(period) {
  const [s, e] = periodToDates(period);
  if (s) document.getElementById('startDate').value = toISO(s);
  if (e) document.getElementById('endDate').value   = toISO(e);
}

function setDefaultDates() {
  const [s, e] = periodToDates('month');
  if (document.getElementById('startDate')) document.getElementById('startDate').value = toISO(s);
  if (document.getElementById('endDate'))   document.getElementById('endDate').value   = toISO(e);
}

/* ═══════════ OVERVIEW ═══════════ */
async function loadOverview(period = 'month') {
  try {
    const [s, e] = periodToDates(period);
    let qs = `userId=${userId}`;
    if (s) qs += `&startDate=${toISO(s)}T00:00:00&endDate=${toISO(e)}T23:59:59`;
    const data = await api(`/analytics/comprehensive?${qs}`);
    renderStatCards(data);
    renderRecentTx(data.recentTransactions || [], 'recentTransactions', false);
    renderCategoryProgressBars(data.expenseByCategory);
  } catch (err) { console.error('Overview error', err); }
}

function renderStatCards(data) {
  const income  = Number(data.totalIncome  ?? 0);
  const expense = Number(data.totalExpense ?? data.totalExpenses ?? 0);
  const net     = income - expense;
  const count   = data.transactionCount ?? 0;
  const savings = income > 0 ? Math.round((net / income) * 100) : 0;

  document.getElementById('totalIncome').textContent   = fmt(income);
  document.getElementById('totalExpenses').textContent = fmt(expense);
  document.getElementById('netBalance').textContent    = fmt(net);
  document.getElementById('netBalance').className      = `stat-value ${net >= 0 ? 'income' : 'expense'}`;
  document.getElementById('txCount').textContent       = count;
  document.getElementById('incomeBadge').textContent   = fmt(income);
  document.getElementById('expenseBadge').textContent  = fmt(expense);
  const nb = document.getElementById('netBadge');
  nb.textContent = savings + '% saved';
  nb.className   = `stat-badge ${savings >= 0 ? 'up' : 'down'}`;
}

function renderCategoryProgressBars(chartData) {
  const container = document.getElementById('categoryBars');
  const labels = chartData?.labels ?? [];
  const values = chartData?.datasets?.[0]?.data ?? [];
  if (!labels.length) {
    container.innerHTML = '<div class="empty-state"><p>No expense data yet.</p></div>';
    return;
  }
  const max = Math.max(...values, 1);
  container.innerHTML = labels.slice(0, 6).map((lbl, i) => {
    const pct = Math.round((values[i] / max) * 100);
    return `<div class="cat-bar-item">
      <div class="cat-bar-header">
        <span class="cat-bar-label">${esc(lbl.replace(/_/g,' '))}</span>
        <span class="cat-bar-pct">${fmt(values[i])}</span>
      </div>
      <div class="cat-bar-track"><div class="cat-bar-fill" style="width:${pct}%"></div></div>
    </div>`;
  }).join('');
}

/* ═══════════ TRANSACTION LIST ═══════════ */
function renderRecentTx(transactions, containerId, showDelete) {
  const c = document.getElementById(containerId);
  if (!transactions.length) { c.innerHTML = '<div class="empty-state"><p>No transactions yet.</p></div>'; return; }
  c.innerHTML = transactions.map(tx => {
    const isExp = tx.type === 'EXPENSE';
    const cat   = tx.expenseCategory || tx.incomeCategory || '';
    return `<div class="tx-item" id="tx-row-${tx.id}">
      <div class="tx-icon ${isExp ? 'expense' : 'income'}">${isExp ? '↓' : '↑'}</div>
      <div class="tx-info">
        <div class="tx-name">${esc(tx.name)}</div>
        <div class="tx-meta">${cat ? esc(cat.replace(/_/g,' ')) + ' · ' : ''}${fmtDate(tx.createdAt)}</div>
      </div>
      <div class="tx-right">
        <span class="tx-amount ${isExp ? 'expense' : 'income'}">${isExp ? '−' : '+'}${fmt(tx.amount)}</span>
        ${showDelete ? `<button class="delete-btn" onclick="deleteTransaction(${tx.id})">Delete</button>` : ''}
      </div>
    </div>`;
  }).join('');
}

/* ═══════════ LOAD ALL TRANSACTIONS (paginated) ═══════════ */
async function loadTransactions() {
  try {
    let data;
    if (txQuery) {
      data = await api(`/upsert/search?userId=${userId}&q=${encodeURIComponent(txQuery)}&page=${txPage}&size=${txSize}`);
    } else {
      const typeParam = txType ? `&type=${txType}` : '';
      data = await api(`/upsert/entries?userId=${userId}${typeParam}&page=${txPage}&size=${txSize}`);
    }

    txTotal = data.totalElements ?? 0;
    renderRecentTx(data.content ?? [], 'allTransactions', true);

    // Pagination controls
    const pgEl = document.getElementById('txPagination');
    if (txTotal > txSize) {
      pgEl.style.display = 'flex';
      document.getElementById('txPageInfo').textContent =
        `Showing ${txPage * txSize + 1}–${Math.min((txPage+1)*txSize, txTotal)} of ${txTotal}`;
      document.getElementById('txPrevBtn').disabled = txPage === 0;
      document.getElementById('txNextBtn').disabled = (txPage + 1) * txSize >= txTotal;
    } else {
      pgEl.style.display = 'none';
    }
  } catch (err) { toast('Failed to load transactions', 'error'); }
}

async function deleteTransaction(id) {
  if (!confirm('Delete this transaction?')) return;
  try {
    await api(`/upsert/delete/${id}?userId=${userId}`, 'DELETE');
    document.getElementById(`tx-row-${id}`)?.remove();
    toast('Transaction deleted', 'success');
    loadOverview('month');
  } catch (err) { toast('Delete failed: ' + err.message, 'error'); }
}

/* ═══════════ CSV EXPORT ═══════════ */
async function exportCsv() {
  try {
    const data = await api(`/upsert/entries?userId=${userId}&page=0&size=10000`);
    const rows = data.content ?? [];
    if (!rows.length) { toast('No data to export', 'info'); return; }
    const header = 'ID,Name,Amount,Type,ExpenseCategory,IncomeCategory,Currency,Description,Date';
    const lines  = rows.map(r =>
      [r.id, `"${r.name}"`, r.amount, r.type,
       r.expenseCategory||'', r.incomeCategory||'',
       r.currency, `"${r.description||''}"`, fmtDate(r.createdAt)].join(','));
    const csv  = [header, ...lines].join('\n');
    const blob = new Blob([csv], { type: 'text/csv' });
    const url  = URL.createObjectURL(blob);
    const a    = document.createElement('a');
    a.href     = url;
    a.download = `transactions-${toISO(new Date())}.csv`;
    a.click();
    URL.revokeObjectURL(url);
    toast('CSV exported', 'success');
  } catch (err) { toast('Export failed', 'error'); }
}

/* ═══════════ ADD ENTRY ═══════════ */
const INCOME_CATS  = ['SALARY','BUSINESS','INVESTMENTS','GIFTS','FREELANCE','RENTAL_INCOME','INTEREST','OTHERS'];
const EXPENSE_CATS = ['FOOD_AND_DINING','TRANSPORTATION','SHOPPING','ENTERTAINMENT','BILLS_AND_UTILITIES','HEALTHCARE','TRAVEL','EDUCATION','OTHERS'];

function populateCategories() {
  const type = document.getElementById('entryType').value;
  const sel  = document.getElementById('entryCategory');
  const lbl  = document.getElementById('categoryLabel');
  sel.innerHTML = '<option value="">Select category</option>';
  const cats = type === 'INCOME' ? INCOME_CATS : type === 'EXPENSE' ? EXPENSE_CATS : [];
  lbl.textContent = type === 'INCOME' ? 'Income Category' : 'Expense Category';
  cats.forEach(c => { const o = document.createElement('option'); o.value = c; o.textContent = c.replace(/_/g,' '); sel.appendChild(o); });
}

async function handleEntrySubmit(e) {
  e.preventDefault();
  const form = e.target;
  const type = form.type.value;
  const body = { userId, name: form.name.value.trim(), amount: parseFloat(form.amount.value),
    type, currency: form.currency.value, description: form.description.value.trim() || null };
  if (type === 'INCOME') body.incomeCategory  = form.category.value;
  else                   body.expenseCategory = form.category.value;
  setLoading(true);
  try {
    await api('/upsert/create', 'POST', body);
    toast('Entry saved successfully', 'success');
    form.reset();
    document.getElementById('entryCategory').innerHTML = '<option value="">Select type first</option>';
    loadOverview('month');
  } catch (err) { showInlineAlert('entryError', err.message, 'error'); }
  finally { setLoading(false); }
}

/* ═══════════ BILL UPLOAD ═══════════ */
async function handleBillUpload(e) {
  e.preventDefault();
  const file = document.getElementById('billFile').files[0];
  if (!file) { showInlineAlert('uploadError', 'Please select a file.', 'error'); return; }
  const fd = new FormData(); fd.append('file', file);
  setLoading(true);
  try {
    const data = await api(`/bill/process/${userId}`, 'POST', fd, true);
    document.getElementById('ocrName').value        = data.name || '';
    document.getElementById('ocrAmount').value      = data.amount || '';
    document.getElementById('ocrType').value        = (data.type || 'EXPENSE').toUpperCase();
    document.getElementById('ocrCategory').value    = data.expenseCategory || data.incomeCategory || '';
    document.getElementById('ocrCurrency').value    = data.currency || 'INR';
    document.getElementById('ocrDescription').value = data.description || '';
    document.getElementById('ocrResults').style.display = 'block';
    toast('Bill processed successfully', 'success');
  } catch (err) { showInlineAlert('uploadError', err.message, 'error'); }
  finally { setLoading(false); }
}

async function handleOcrConfirm() {
  const type = document.getElementById('ocrType').value;
  const cat  = document.getElementById('ocrCategory').value;
  const body = { userId,
    name:        document.getElementById('ocrName').value,
    amount:      parseFloat(document.getElementById('ocrAmount').value),
    type, currency: document.getElementById('ocrCurrency').value,
    description: document.getElementById('ocrDescription').value };
  if (type === 'INCOME') body.incomeCategory  = cat;
  else                   body.expenseCategory = cat;
  setLoading(true);
  try {
    await api('/upsert/create', 'POST', body);
    toast('Entry saved from bill', 'success');
    document.getElementById('ocrResults').style.display = 'none';
    document.getElementById('billUploadForm').reset();
    loadOverview('month');
  } catch (err) { showInlineAlert('uploadError', err.message, 'error'); }
  finally { setLoading(false); }
}

/* ═══════════ ANALYTICS ═══════════ */
async function loadAnalytics() {
  setLoading(true);
  try {
    const type     = document.getElementById('analyticsType').value;
    const timeline = document.getElementById('timelineType').value;
    const start    = document.getElementById('startDate').value;
    const end      = document.getElementById('endDate').value;
    let qs = `userId=${userId}&timelineType=${timeline}`;
    if (type)  qs += `&transactionFilter=${type}`;
    if (start) qs += `&startDate=${start}T00:00:00`;
    if (end)   qs += `&endDate=${end}T23:59:59`;
    const data = await api(`/analytics/comprehensive?${qs}`);
    renderAnalyticsSummary(data);
    renderCategoryChart(data.expenseByCategory);
    renderTimelineChart(data.timelineTrends);
  } catch (err) { toast('Analytics load failed', 'error'); }
  finally { setLoading(false); }
}

function renderAnalyticsSummary(data) {
  const income  = Number(data.totalIncome  ?? 0);
  const expense = Number(data.totalExpense ?? 0);
  const net     = income - expense;
  const count   = data.transactionCount ?? 0;
  const savings = income > 0 ? (net / income * 100).toFixed(1) : '0.0';
  document.getElementById('comprehensiveStats').innerHTML = `
    <div class="analytics-stat-item"><div class="stat-label">Income</div><div class="stat-value income">${fmt(income)}</div></div>
    <div class="analytics-stat-item"><div class="stat-label">Expenses</div><div class="stat-value expense">${fmt(expense)}</div></div>
    <div class="analytics-stat-item"><div class="stat-label">Net Balance</div><div class="stat-value ${net>=0?'income':'expense'}">${fmt(net)}</div></div>
    <div class="analytics-stat-item"><div class="stat-label">Savings Rate</div><div class="stat-value brand">${savings}%</div></div>
    <div class="analytics-stat-item"><div class="stat-label">Transactions</div><div class="stat-value">${count}</div></div>
  `;
}

function renderCategoryChart(chartData) {
  const ctx = document.getElementById('categoryChart').getContext('2d');
  if (categoryChart) categoryChart.destroy();
  const labels = chartData?.labels ?? [];
  const values = chartData?.datasets?.[0]?.data ?? [];
  if (!labels.length) return;
  categoryChart = new Chart(ctx, {
    type: 'doughnut',
    data: { labels, datasets: [{ data: values, backgroundColor: PIE_PALETTE.slice(0, labels.length), borderWidth: 0, hoverOffset: 6 }] },
    options: {
      responsive: true, maintainAspectRatio: false, cutout: '65%',
      plugins: {
        legend: { position: 'bottom', labels: { usePointStyle: true, padding: 14, font: { size: 12, family: 'Inter' } } },
        tooltip: { callbacks: { label: ctx => ` ${ctx.label}: ${fmt(ctx.parsed)}` } }
      }
    }
  });
}

function renderTimelineChart(chartData) {
  const ctx = document.getElementById('timelineChart').getContext('2d');
  if (timelineChart) timelineChart.destroy();
  const labels   = chartData?.labels ?? [];
  const datasets = chartData?.datasets ?? [];
  if (!labels.length) return;
  timelineChart = new Chart(ctx, {
    type: 'line',
    data: {
      labels,
      datasets: datasets.map(ds => ({
        label:           ds.label,
        data:            ds.data,
        borderColor:     ds.label === 'Income' ? COLOR_INCOME : COLOR_EXPENSE,
        backgroundColor: ds.label === 'Income' ? 'rgba(22,163,74,.08)' : 'rgba(220,38,38,.08)',
        tension: 0.3, fill: true, pointRadius: 3, pointHoverRadius: 5, borderWidth: 2
      }))
    },
    options: {
      responsive: true, maintainAspectRatio: false,
      plugins: { legend: { position: 'top', labels: { usePointStyle: true, font: { size: 12, family: 'Inter' } } },
        tooltip: { callbacks: { label: ctx => ` ${ctx.dataset.label}: ${fmt(ctx.parsed.y)}` } }
      },
      scales: {
        y: { beginAtZero: true, grid: { color: 'rgba(0,0,0,.04)' }, ticks: { font: { size: 11 }, callback: v => fmt(v) } },
        x: { grid: { display: false }, ticks: { font: { size: 11 } } }
      }
    }
  });
}

/* ═══════════ UTILITIES ═══════════ */
/* ═══════════ GROUPS & SPLITWISE ═══════════ */

async function loadGroups() {
  try {
    const groups = await api(`/upsert/groups?userId=${userId}`);
    renderGroups(groups);
  } catch (err) { toast('Failed to load groups', 'error'); }
}

function renderGroups(groups) {
  const c = document.getElementById('groupList');
  if (!groups.length) {
    c.innerHTML = '<div class="empty-state"><p>You are not in any groups yet.</p></div>';
    return;
  }
  c.innerHTML = groups.map(g => `
    <div class="tx-item" onclick="selectGroup(${g.id}, '${esc(g.name)}')">
      <div class="tx-icon" style="background:var(--brand-50); color:var(--brand-600)">G</div>
      <div class="tx-info">
        <div class="tx-name">${esc(g.name)}</div>
        <div class="tx-meta">${esc(g.description || 'No description')}</div>
      </div>
      <div class="tx-right"><span class="chip">${g.currency}</span></div>
    </div>
  `).join('');
}

async function selectGroup(id, name) {
  currentGroupId = id;
  document.getElementById('currentGroupName').textContent = name;
  document.getElementById('groupDetailArea').style.display = 'block';
  loadGroupMembers();
  loadGroupBalances();
  loadGroupExpenses();
}

async function handleCreateGroup(e) {
  e.preventDefault();
  const form = e.target;
  const body = {
    name: form.name.value.trim(),
    description: form.description.value.trim(),
    createdBy: userId,
    currency: form.currency.value
  };
  try {
    await api('/upsert/groups', 'POST', body);
    toast('Group created!', 'success');
    hideModal('createGroupModal');
    form.reset();
    loadGroups();
  } catch (err) { toast(err.message, 'error'); }
}

async function loadGroupMembers() {
  try {
    currentGroupMembers = await api(`/upsert/groups/${currentGroupId}/members`);
  } catch (err) { console.error('Failed to load members', err); }
}

async function handleAddMember(e) {
  e.preventDefault();
  const form = e.target;
  const body = {
    userId: form.userId.value.trim() || uuidv4(), // Fallback to random ID if guest
    name: form.name.value.trim()
  };
  try {
    await api(`/upsert/groups/${currentGroupId}/members`, 'POST', body);
    toast('Member added!', 'success');
    hideModal('addMemberModal');
    form.reset();
    loadGroupMembers();
    loadGroupBalances();
  } catch (err) { toast(err.message, 'error'); }
}

function populatePayerSelect() {
  const sel = document.getElementById('expensePayerSelect');
  sel.innerHTML = currentGroupMembers.map(m => 
    `<option value="${m.userId}">${esc(m.name)}</option>`
  ).join('');
}

function renderSplitDetails() {
  const type = document.getElementById('splitTypeSelect').value;
  const area = document.getElementById('splitDetailsArea');
  if (type === 'EQUAL') { area.innerHTML = ''; return; }
  
  area.innerHTML = `
    <label style="font-size:12px; font-weight:600; margin-bottom:8px; display:block;">
      ${type === 'PERCENTAGE' ? 'Split percentages (must total 100%)' : 'Split amounts (must total expense amount)'}
    </label>
    ${currentGroupMembers.map(m => `
      <div class="field-inline" style="margin-bottom:8px; display:flex; justify-content:space-between; align-items:center;">
        <span style="font-size:13px;">${esc(m.name)}</span>
        <input type="number" step="0.01" data-userid="${m.userId}" data-username="${esc(m.name)}" 
               style="width: 100px; padding: 4px;" placeholder="${type === 'PERCENTAGE' ? '%' : 'Amount'}">
      </div>
    `).join('')}
  `;
}

async function handleAddSharedExpense(e) {
  e.preventDefault();
  const form = e.target;
  const splitType = form.splitType.value;
  const body = {
    description: form.description.value.trim(),
    amount: parseFloat(form.amount.value),
    paidBy: form.paidBy.value,
    splitType: splitType,
    splitDetails: []
  };

  if (splitType !== 'EQUAL') {
    const inputs = document.getElementById('splitDetailsArea').querySelectorAll('input');
    inputs.forEach(inp => {
      body.splitDetails.push({
        userId: inp.dataset.userid,
        userName: inp.dataset.username,
        value: parseFloat(inp.value) || 0
      });
    });
  }

  try {
    await api(`/upsert/groups/${currentGroupId}/expenses`, 'POST', body);
    toast('Expense added!', 'success');
    hideModal('addSharedExpenseModal');
    form.reset();
    loadGroupBalances();
    loadGroupExpenses();
  } catch (err) { toast(err.message, 'error'); }
}

async function loadGroupBalances() {
  try {
    const data = await api(`/upsert/groups/${currentGroupId}/balances`);
    renderBalances(data);
  } catch (err) { console.error('Failed to load balances', err); }
}

function renderBalances(data) {
  const bc = document.getElementById('groupBalances');
  let html = '<div style="display:flex; flex-direction:column; gap:8px;">';
  
  // Net Balances
  data.memberBalances.forEach(mb => {
    const isOwed = mb.netBalance > 0;
    const isOwes = mb.netBalance < 0;
    html += `
      <div style="display:flex; justify-content:space-between; align-items:center; padding:8px 12px; background:var(--bg); border-radius:8px;">
        <span style="font-weight:500;">${esc(mb.userName)}</span>
        <span class="${isOwed ? 'income' : (isOwes ? 'expense' : '')}" style="font-weight:600;">
          ${isOwed ? 'is owed ' : (isOwes ? 'owes ' : 'is settled')} ${isOwed || isOwes ? fmt(Math.abs(mb.netBalance)) : ''}
        </span>
      </div>
    `;
  });

  // Suggestions
  if (data.simplifiedDebts && data.simplifiedDebts.length > 0) {
    html += '<div style="margin-top:16px;"><h4 style="font-size:12px; margin-bottom:8px;">Settlement Suggestions</h4>';
    data.simplifiedDebts.forEach(s => {
      html += `
        <div style="font-size:12.5px; margin-bottom:4px; color:var(--text-2);">
          ${esc(s.fromUserName)} should pay ${esc(s.toUserName)} <strong>${fmt(s.amount)}</strong>
          <button class="btn btn-xs btn-ghost" style="margin-left:8px;" 
                  onclick="settleDebt('${s.fromUserId}', '${s.toUserId}')">Settled</button>
        </div>
      `;
    });
    html += '</div>';
  }
  
  html += '</div>';
  bc.innerHTML = html;
}

async function loadGroupExpenses() {
  try {
    const expenses = await api(`/upsert/groups/${currentGroupId}/expenses`);
    renderRecentTx(expenses, 'groupExpenseList', false);
  } catch (err) { console.error('Failed to load group expenses', err); }
}

async function settleDebt(from, to) {
  if (!confirm('Mark this debt as settled?')) return;
  try {
    await api(`/upsert/groups/${currentGroupId}/settle?fromUserId=${from}&toUserId=${to}`, 'POST');
    toast('Debt settled!', 'success');
    loadGroupBalances();
  } catch (err) { toast(err.message, 'error'); }
}

function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
    var r = Math.random() * 16 | 0, v = c == 'x' ? r : (r & 0x3 | 0x8);
    return v.toString(16);
  });
}

function fmt(n)    { return '₹' + Number(n).toLocaleString('en-IN', { minimumFractionDigits: 0, maximumFractionDigits: 2 }); }
function fmtDate(s){ return s ? new Date(s).toLocaleDateString('en-IN',{ day:'numeric', month:'short', year:'numeric'}) : ''; }
function esc(s)    { const d = document.createElement('div'); d.textContent = s; return d.innerHTML; }

function setLoading(on) {
  document.getElementById('loadingOverlay').classList.toggle('active', on);
}

/* Inline alert (form-level) */
function showInlineAlert(id, msg, type) {
  const el = document.getElementById(id);
  el.textContent = msg;
  el.className   = `alert alert-${type}`;
  el.style.display = 'block';
  setTimeout(() => { el.style.display = 'none'; }, type === 'error' ? 5000 : 3000);
}

/* Toast (global) */
function toast(msg, type = 'info') {
  const c = document.getElementById('toastContainer');
  const t = document.createElement('div');
  t.className   = `toast ${type}`;
  t.textContent = msg;
  c.appendChild(t);
  setTimeout(() => {
    t.style.animation = 'toastOut .25s ease forwards';
    setTimeout(() => t.remove(), 260);
  }, 3000);
}
