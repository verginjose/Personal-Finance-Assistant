import { api, Auth, toast } from '../utils/api.js';
import { icon } from '../utils/icons.js';
import {
  esc, pageHeader, emptyState, formatCurrency, formatCategory, formatDate,
  typeBadge, openModal, confirmModal, modalActions, EXPENSE_CATS, INCOME_CATS, categoryOptions, setupCategorySearch
} from '../utils/ui.js';

let currentPage = 0, totalPages = 0;

export async function renderTransactions(container) {
  const userId = Auth.getUserId();
  container.innerHTML = `
    ${pageHeader('Transactions', 'Manage your income and expenses', '<button class="btn btn-primary btn-sm" id="t-add">+ Add Transaction</button>')}
    <div class="toolbar fade-up">
      <div class="search-box">${icon('search', 'sm')}<input class="form-input" id="t-search" placeholder="Search by name or description…" aria-label="Search transactions"></div>
      <button class="btn btn-secondary btn-sm" id="t-filter-btn">${icon('filter', 'sm')} Filters</button>
      <button class="btn btn-secondary btn-sm" id="t-export">${icon('download', 'sm')} Export CSV</button>
    </div>
    <div class="table-wrap fade-up">
      <table>
        <thead><tr><th>Name</th><th>Type</th><th>Category</th><th>Amount</th><th>Date</th><th>Actions</th></tr></thead>
        <tbody id="t-body"><tr><td colspan="6" style="text-align:center;padding:48px;color:var(--text-dim)">Loading…</td></tr></tbody>
      </table>
    </div>
    <div class="pagination" id="t-pagination"></div>

    <div class="side-panel-overlay" id="t-filter-overlay"></div>
    <div class="side-panel" id="t-filter-panel">
      <div class="side-panel-header">
        <h2>Advanced Filters</h2>
        <button class="btn btn-icon btn-ghost" id="t-filter-close" aria-label="Close filters">${icon('close', 'sm')}</button>
      </div>
      <div class="side-panel-body">
        <div class="form-group">
          <label for="t-type-filter">Transaction Type</label>
          <select class="form-select" id="t-type-filter" aria-label="Filter by type">
            <option value="">All Types</option><option value="INCOME">Income</option><option value="EXPENSE">Expense</option>
          </select>
        </div>
        <div class="form-group">
          <label for="t-category">Category</label>
          <select class="form-select" id="t-category" aria-label="Filter by category">
            <option value="">All Categories</option>
            <optgroup label="Expenses">${categoryOptions(EXPENSE_CATS)}</optgroup>
            <optgroup label="Income">${categoryOptions(INCOME_CATS)}</optgroup>
          </select>
        </div>
        <div class="form-group">
          <label for="t-start">Start Date</label>
          <input class="form-input" id="t-start" type="date" aria-label="Start date">
        </div>
        <div class="form-group">
          <label for="t-end">End Date</label>
          <input class="form-input" id="t-end" type="date" aria-label="End date">
        </div>
      </div>
      <div class="side-panel-footer">
        <button class="btn btn-secondary" id="t-filter-reset">Reset</button>
        <button class="btn btn-primary" id="t-filter-apply">Apply Filters</button>
      </div>
    </div>`;

  const load = () => loadTransactions(userId);
  document.getElementById('t-add').onclick = () => showModal(userId, null, load);
  document.getElementById('t-export').onclick = () => exportCsv(userId);

  const panel = document.getElementById('t-filter-panel');
  const overlay = document.getElementById('t-filter-overlay');
  const openPanel = () => { panel.classList.add('open'); overlay.classList.add('open'); };
  const closePanel = () => { panel.classList.remove('open'); overlay.classList.remove('open'); };

  if (window.flatpickr) {
    flatpickr('#t-start', { dateFormat: 'Y-m-d' });
    flatpickr('#t-end', { dateFormat: 'Y-m-d' });
  }

  document.getElementById('t-filter-btn').onclick = openPanel;
  document.getElementById('t-filter-close').onclick = closePanel;
  overlay.onclick = closePanel;

  document.getElementById('t-filter-apply').onclick = () => { currentPage = 0; load(); closePanel(); };
  document.getElementById('t-filter-reset').onclick = () => {
    document.getElementById('t-type-filter').value = '';
    document.getElementById('t-category').value = '';
    document.getElementById('t-start').value = '';
    document.getElementById('t-end').value = '';
    currentPage = 0;
    load();
    closePanel();
  };

  let debounce;
  document.getElementById('t-search').oninput = () => {
    clearTimeout(debounce);
    debounce = setTimeout(() => { currentPage = 0; load(); }, 400);
  };

  const pendingCategory = sessionStorage.getItem('pendingCategoryFilter');
  if (pendingCategory) {
    document.getElementById('t-category').value = pendingCategory;
    sessionStorage.removeItem('pendingCategoryFilter');
  }

  load();
}

async function loadTransactions(userId) {
  const search = document.getElementById('t-search')?.value;
  const type = document.getElementById('t-type-filter')?.value;
  const category = document.getElementById('t-category')?.value;
  const start = document.getElementById('t-start')?.value;
  const end = document.getElementById('t-end')?.value;
  const tbody = document.getElementById('t-body');
  if (!tbody) return;

  try {
    const params = { userId, page: currentPage, size: 15 };
    if (type) params.type = type;
    if (category) params.category = category;
    if (start) params.startDate = start;
    if (end) params.endDate = end;

    const result = search
      ? await api.get('/upsert/search', { userId, q: search, page: currentPage, size: 15 })
      : await api.get('/upsert/entries', params);

    const items = result.content || [];
    totalPages = result.totalPages || 1;

    if (!items.length) {
      tbody.innerHTML = `<tr><td colspan="6">${emptyState('receipt', 'No transactions found', 'Add a transaction or adjust your filters.')}</td></tr>`;
    } else {
      tbody.innerHTML = items.map(t => `<tr>
        <td>
          <strong>${esc(t.name)}</strong>
          ${t.recurring ? `<span class="badge badge-recurring">${icon('repeat', 'xs')} ${esc(t.recurringPeriod)}</span>` : ''}
          ${t.description ? `<br><small style="color:var(--text-dim)">${esc(t.description)}</small>` : ''}
        </td>
        <td>${typeBadge(t.type)}</td>
        <td>${esc(t.category)}</td>  
        <td style="font-weight:600;color:${t.type === 'INCOME' ? 'var(--accent-g)' : 'var(--accent)'}">
          ${t.type === 'INCOME' ? '+' : '−'}${formatCurrency(t.amount, t.currency)}
        </td>
        <td style="font-size:.82rem;color:var(--text-dim)">${formatDate(t.createdAt)}</td>
        <td class="td-actions">
          <button class="btn btn-secondary btn-icon btn-sm t-edit" data-id="${t.id}" title="Edit" aria-label="Edit">${icon('edit', 'sm')}</button>
          <button class="btn btn-danger btn-icon btn-sm t-del" data-id="${t.id}" title="Delete" aria-label="Delete">${icon('trash', 'sm')}</button>
        </td>
      </tr>`).join('');

      tbody.querySelectorAll('.t-edit').forEach(b => b.onclick = () => editTransaction(userId, b.dataset.id));
      tbody.querySelectorAll('.t-del').forEach(b => b.onclick = () => deleteTransaction(userId, b.dataset.id, b.closest('tr')));
    }
    renderPagination();
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="6" style="text-align:center;padding:40px;color:var(--accent)">${esc(err.message)}</td></tr>`;
  }
}

function renderPagination() {
  const el = document.getElementById('t-pagination');
  if (!el || totalPages <= 1) { if (el) el.innerHTML = ''; return; }
  const userId = Auth.getUserId();
  el.innerHTML = Array.from({ length: totalPages }, (_, i) =>
    `<button class="${i === currentPage ? 'active' : ''}" data-p="${i}" aria-label="Page ${i + 1}">${i + 1}</button>`
  ).join('');
  el.querySelectorAll('button').forEach(b => b.onclick = () => { currentPage = +b.dataset.p; loadTransactions(userId); });
}

async function showModal(userId, existing, onDone) {
  const isEdit = !!existing;

  openModal(isEdit ? 'Edit Transaction' : 'Add Transaction', `
    <form id="txn-form">
      <div class="form-row">
        <div class="form-group" style="flex:2"><label for="m-name">Name</label><input class="form-input" id="m-name" required value="${esc(existing?.name || '')}"></div>
        <div class="form-group" style="flex:1"><label for="m-amount">Amount</label><input class="form-input" id="m-amount" type="number" step="0.01" min="0.01" required value="${existing?.amount || ''}"></div>
        <div class="form-group" style="flex:1.5"><label for="m-date">Date</label><input class="form-input" id="m-date" type="date" value="${existing?.createdAt ? existing.createdAt.split('T')[0] : new Date().toISOString().split('T')[0]}"></div>
      </div>
      <div class="form-row">
        <div class="form-group"><label for="m-type">Type</label>
          <select class="form-select" id="m-type" required>
            <option value="EXPENSE" ${existing?.type === 'EXPENSE' ? 'selected' : ''}>Expense</option>
            <option value="INCOME" ${existing?.type === 'INCOME' ? 'selected' : ''}>Income</option>
          </select>
        </div>
        <div class="form-group"><label for="m-currency">Currency</label><input class="form-input" id="m-currency" maxlength="3" value="${esc(existing?.currency || 'INR')}" required></div>
      </div>
      <div class="form-group" id="m-cat-group">
        <label for="m-cat">Category</label>
        <input type="text" id="m-cat-search" class="form-input category-search" placeholder="Search category..." style="margin-bottom: 8px;">
        <select class="form-select category-select" id="m-cat" required></select>
      </div>
      <div class="form-group"><label for="m-desc">Description</label><textarea class="form-textarea" id="m-desc">${esc(existing?.description || '')}</textarea></div>

      <div class="form-row" style="align-items:center">
        <div class="form-check">
          <input type="checkbox" id="m-recurring" ${existing?.recurring ? 'checked' : ''}>
          <label for="m-recurring">Recurring transaction</label>
        </div>
        <div class="form-group" id="m-rec-period-group" style="display:${existing?.recurring ? 'block' : 'none'};margin-bottom:0">
          <label for="m-recurring-period">Period</label>
          <select class="form-select" id="m-recurring-period">
            <option value="DAILY" ${existing?.recurringPeriod === 'DAILY' ? 'selected' : ''}>Daily</option>
            <option value="WEEKLY" ${existing?.recurringPeriod === 'WEEKLY' ? 'selected' : ''}>Weekly</option>
            <option value="MONTHLY" ${existing?.recurringPeriod === 'MONTHLY' || !existing?.recurringPeriod ? 'selected' : ''}>Monthly</option>
            <option value="YEARLY" ${existing?.recurringPeriod === 'YEARLY' ? 'selected' : ''}>Yearly</option>
          </select>
        </div>
      </div>
      ${modalActions('Cancel', isEdit ? 'Update' : 'Create')}
    </form>`, {
    onSubmit: async () => {
      const type = document.getElementById('m-type').value;
      const recurring = document.getElementById('m-recurring').checked;
      const amount = parseFloat(document.getElementById('m-amount').value);
      const dateVal = document.getElementById('m-date').value;
      const payload = {
        userId,
        name: document.getElementById('m-name').value,
        amount,
        type,
        currency: document.getElementById('m-currency').value.toUpperCase(),
        description: document.getElementById('m-desc').value,
        category: document.getElementById('m-cat').value,
        recurring,
        recurringPeriod: recurring ? document.getElementById('m-recurring-period').value : null,
        createdAt: dateVal ? `${dateVal}T12:00:00` : null
      };

      if (isEdit) {
        payload.id = existing.id;
        await api.put('/upsert/update', payload);
        toast('Transaction updated', 'success');
      } else {
        await api.post('/upsert/create', payload);
        toast('Transaction created', 'success');
      }
      onDone();
    }
  });

  const recCheckbox = document.getElementById('m-recurring');
  const recGroup = document.getElementById('m-rec-period-group');
  recCheckbox.onchange = () => { recGroup.style.display = recCheckbox.checked ? 'block' : 'none'; };

  const updateCats = () => {
    const type = document.getElementById('m-type').value;
    const cats = type === 'INCOME' ? INCOME_CATS : EXPENSE_CATS;
    const sel = existing?.type === type ? existing.category : null;  // single field
    
    const catSelect = document.getElementById('m-cat');
    if (catSelect.tomselect) {
        catSelect.tomselect.destroy();
    }
    
    catSelect.innerHTML = categoryOptions(cats, sel);
    
    // Re-initialize Tom Select with new options
    setupCategorySearch('m-cat-search', 'm-cat');
  };

  updateCats();
  document.getElementById('m-type').onchange = updateCats;
}

async function editTransaction(userId, id) {
  try {
    const t = await api.get(`/upsert/entries/${id}`, { userId });
    showModal(userId, t, () => loadTransactions(userId));
  } catch (err) { toast(err.message, 'error'); }
}

async function deleteTransaction(userId, id, rowElement) {
  if (!(await confirmModal('Delete Transaction', 'Are you sure you want to delete this transaction? This action cannot be undone.', 'Delete'))) return;
  if (rowElement) {
    rowElement.style.transition = 'all 0.3s';
    rowElement.style.opacity = '0.3';
    rowElement.style.transform = 'scale(0.98)';
  }
  try {
    await api.delete(`/upsert/delete/${id}`, { userId });
    toast('Deleted', 'success');
    if (rowElement) rowElement.remove();
    loadTransactions(userId); // reload quietly to get updated pagination
  } catch (err) { 
    if (rowElement) {
      rowElement.style.opacity = '1';
      rowElement.style.transform = 'none';
    }
    toast(err.message, 'error'); 
  }
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
