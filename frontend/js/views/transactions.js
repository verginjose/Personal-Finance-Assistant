import { api, Auth, toast } from '../utils/api.js';

const EXPENSE_CATS = ['FOOD_AND_DINING','TRANSPORTATION','SHOPPING','ENTERTAINMENT','BILLS_AND_UTILITIES','HEALTHCARE','TRAVEL','EDUCATION','OTHERS'];
const INCOME_CATS  = ['SALARY','BUSINESS','INVESTMENTS','GIFTS','FREELANCE','RENTAL_INCOME','INTEREST','OTHERS'];
let currentPage = 0, totalPages = 0;

export async function renderTransactions(container) {
  const userId = Auth.getUserId();
  container.innerHTML = `
    <div class="page-header fade-up"><h1>Transactions</h1><p>Manage your income and expenses</p></div>
    <div class="toolbar fade-up" style="animation-delay:.05s">
      <div class="search-box"><input class="form-input" id="t-search" placeholder="Search transactions…"></div>
      <select class="form-select" id="t-type-filter" style="width:150px"><option value="">All Types</option><option value="INCOME">Income</option><option value="EXPENSE">Expense</option></select>
      <input class="form-input" id="t-start" type="date" style="width:160px" placeholder="Start date">
      <input class="form-input" id="t-end" type="date" style="width:160px" placeholder="End date">
      <button class="btn btn-secondary btn-sm" id="t-export">📥 CSV</button>
      <button class="btn btn-primary btn-sm" id="t-add">+ Add</button>
    </div>
    <div class="table-wrap fade-up" style="animation-delay:.1s"><table>
      <thead><tr><th>Name</th><th>Type</th><th>Category</th><th>Amount</th><th>Currency</th><th>Date</th><th style="width:90px">Actions</th></tr></thead>
      <tbody id="t-body"><tr><td colspan="7" style="text-align:center;padding:40px;color:var(--text-dim)">Loading…</td></tr></tbody>
    </table></div>
    <div class="pagination" id="t-pagination"></div>`;

  const load = () => loadTransactions(userId);
  document.getElementById('t-add').onclick = () => showModal(userId, null, load);
  document.getElementById('t-export').onclick = () => exportCsv(userId);
  document.getElementById('t-type-filter').onchange = () => { currentPage = 0; load(); };
  document.getElementById('t-start').onchange = () => { currentPage = 0; load(); };
  document.getElementById('t-end').onchange   = () => { currentPage = 0; load(); };
  let debounce;
  document.getElementById('t-search').oninput = (e) => { clearTimeout(debounce); debounce = setTimeout(() => { currentPage = 0; load(); }, 400); };
  load();
}

async function loadTransactions(userId) {
  const search = document.getElementById('t-search')?.value;
  const type   = document.getElementById('t-type-filter')?.value;
  const start  = document.getElementById('t-start')?.value;
  const end    = document.getElementById('t-end')?.value;
  const tbody  = document.getElementById('t-body');
  if (!tbody) return;

  try {
    let result;
    if (search) {
      result = await api.get('/upsert/search', { userId, q: search, page: currentPage, size: 15 });
    } else {
      result = await api.get('/upsert/entries', { userId, type: type || undefined, startDate: start || undefined, endDate: end || undefined, page: currentPage, size: 15 });
    }

    const items = result.content || [];
    totalPages = result.totalPages || 1;

    if (!items.length) {
      tbody.innerHTML = '<tr><td colspan="7"><div class="empty-state"><div class="empty-icon">🧾</div><p>No transactions found</p></div></td></tr>';
    } else {
      tbody.innerHTML = items.map(t => `<tr>
        <td><strong>${esc(t.name)}</strong>${t.description ? `<br><small style="color:var(--text-dim)">${esc(t.description)}</small>` : ''}</td>
        <td><span class="badge ${t.type==='INCOME'?'badge-income':'badge-expense'}">${t.type}</span></td>
        <td>${esc(t.expenseCategory || t.incomeCategory || '—')}</td>
        <td style="font-weight:600;color:${t.type==='INCOME'?'var(--accent-g)':'var(--accent)'}">${t.type==='INCOME'?'+':'−'}${Number(t.amount).toLocaleString('en-IN',{minimumFractionDigits:2})}</td>
        <td>${t.currency||'INR'}</td>
        <td style="font-size:.82rem;color:var(--text-dim)">${new Date(t.createdAt).toLocaleDateString()}</td>
        <td>
          <button class="btn btn-secondary btn-icon btn-sm t-edit" data-id="${t.id}" title="Edit">✏️</button>
          <button class="btn btn-danger btn-icon btn-sm t-del" data-id="${t.id}" title="Delete">🗑</button>
        </td>
      </tr>`).join('');

      tbody.querySelectorAll('.t-edit').forEach(b => b.onclick = () => editTransaction(userId, b.dataset.id));
      tbody.querySelectorAll('.t-del').forEach(b => b.onclick = () => deleteTransaction(userId, b.dataset.id));
    }
    renderPagination();
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="7" style="text-align:center;padding:40px;color:var(--accent)">${err.message}</td></tr>`;
  }
}

function renderPagination() {
  const el = document.getElementById('t-pagination');
  if (!el || totalPages <= 1) { if (el) el.innerHTML = ''; return; }
  const userId = Auth.getUserId();
  let html = '';
  for (let i = 0; i < totalPages; i++) {
    html += `<button class="${i===currentPage?'active':''}" data-p="${i}">${i+1}</button>`;
  }
  el.innerHTML = html;
  el.querySelectorAll('button').forEach(b => b.onclick = () => { currentPage = +b.dataset.p; loadTransactions(userId); });
}

function showModal(userId, existing, onDone) {
  const isEdit = !!existing;
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.innerHTML = `<div class="modal">
    <h2>${isEdit ? 'Edit' : 'Add'} Transaction</h2>
    <form id="txn-form">
      <div class="form-row">
        <div class="form-group"><label>Name</label><input class="form-input" id="m-name" required value="${esc(existing?.name||'')}"></div>
        <div class="form-group"><label>Amount</label><input class="form-input" id="m-amount" type="number" step="0.01" min="0.01" required value="${existing?.amount||''}"></div>
      </div>
      <div class="form-row">
        <div class="form-group"><label>Type</label><select class="form-select" id="m-type" required><option value="EXPENSE" ${existing?.type==='EXPENSE'?'selected':''}>Expense</option><option value="INCOME" ${existing?.type==='INCOME'?'selected':''}>Income</option></select></div>
        <div class="form-group"><label>Currency</label><input class="form-input" id="m-currency" maxlength="3" value="${esc(existing?.currency||'INR')}" required></div>
      </div>
      <div class="form-group" id="m-cat-group"><label>Category</label><select class="form-select" id="m-cat"></select></div>
      <div class="form-group"><label>Description</label><textarea class="form-textarea" id="m-desc">${esc(existing?.description||'')}</textarea></div>
      <div class="modal-actions">
        <button type="button" class="btn btn-secondary" id="m-cancel">Cancel</button>
        <button type="submit" class="btn btn-primary" id="m-save">${isEdit ? 'Update' : 'Create'}</button>
      </div>
    </form>
  </div>`;
  document.body.appendChild(overlay);

  const updateCats = () => {
    const type = document.getElementById('m-type').value;
    const cats = type === 'INCOME' ? INCOME_CATS : EXPENSE_CATS;
    const sel = document.getElementById('m-cat');
    sel.innerHTML = cats.map(c => `<option value="${c}" ${(existing?.expenseCategory===c||existing?.incomeCategory===c)?'selected':''}>${c.replace(/_/g,' ')}</option>`).join('');
  };
  updateCats();
  document.getElementById('m-type').onchange = updateCats;
  overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };
  document.getElementById('m-cancel').onclick = () => overlay.remove();

  document.getElementById('txn-form').onsubmit = async (e) => {
    e.preventDefault();
    const type = document.getElementById('m-type').value;
    const payload = {
      userId,
      name: document.getElementById('m-name').value,
      amount: parseFloat(document.getElementById('m-amount').value),
      type,
      currency: document.getElementById('m-currency').value.toUpperCase(),
      description: document.getElementById('m-desc').value,
      [type === 'INCOME' ? 'incomeCategory' : 'expenseCategory']: document.getElementById('m-cat').value
    };
    try {
      if (isEdit) {
        payload.id = existing.id;
        await api.put('/upsert/update', payload);
        toast('Transaction updated', 'success');
      } else {
        await api.post('/upsert/create', payload);
        toast('Transaction created', 'success');
      }
      overlay.remove();
      onDone();
    } catch (err) { toast(err.message, 'error'); }
  };
}

async function editTransaction(userId, id) {
  try {
    const t = await api.get(`/upsert/entries/${id}`, { userId });
    showModal(userId, t, () => loadTransactions(userId));
  } catch (err) { toast(err.message, 'error'); }
}

async function deleteTransaction(userId, id) {
  if (!confirm('Delete this transaction?')) return;
  try {
    await api.delete(`/upsert/delete/${id}`, { userId });
    toast('Deleted', 'success');
    loadTransactions(userId);
  } catch (err) { toast(err.message, 'error'); }
}

async function exportCsv(userId) {
  try {
    const res = await api.raw('GET', '/upsert/entries/export', { params: { userId } });
    const blob = await res.blob();
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'transactions.csv';
    a.click();
    toast('CSV downloaded', 'success');
  } catch (err) { toast(err.message, 'error'); }
}

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
