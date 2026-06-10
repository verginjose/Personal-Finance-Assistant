import { api, Auth, toast } from '../utils/api.js';
import { icon } from '../utils/icons.js';
import {
  esc, pageHeader, emptyState, formatCurrency, formatCategory, formatDate,
  typeBadge, openModal, modalActions, EXPENSE_CATS, INCOME_CATS, categoryOptions
} from '../utils/ui.js';

let currentPage = 0, totalPages = 0;

export async function renderTransactions(container) {
  const userId = Auth.getUserId();
  container.innerHTML = `
    ${pageHeader('Transactions', 'Manage your income and expenses', '<button class="btn btn-primary btn-sm" id="t-add">+ Add Transaction</button>')}
    <div class="toolbar fade-up">
      <div class="search-box">${icon('search', 'sm')}<input class="form-input" id="t-search" placeholder="Search by name or description…" aria-label="Search transactions"></div>
      <select class="form-select" id="t-type-filter" style="width:150px" aria-label="Filter by type">
        <option value="">All Types</option><option value="INCOME">Income</option><option value="EXPENSE">Expense</option>
      </select>
      <input class="form-input" id="t-start" type="date" style="width:160px" aria-label="Start date">
      <input class="form-input" id="t-end" type="date" style="width:160px" aria-label="End date">
      <button class="btn btn-secondary btn-sm" id="t-export">${icon('download', 'sm')} Export CSV</button>
    </div>
    <div class="table-wrap fade-up">
      <table>
        <thead><tr><th>Name</th><th>Type</th><th>Category</th><th>Amount</th><th>Date</th><th>Actions</th></tr></thead>
        <tbody id="t-body"><tr><td colspan="6" style="text-align:center;padding:48px;color:var(--text-dim)">Loading…</td></tr></tbody>
      </table>
    </div>
    <div class="pagination" id="t-pagination"></div>`;

  const load = () => loadTransactions(userId);
  document.getElementById('t-add').onclick = () => showModal(userId, null, load);
  document.getElementById('t-export').onclick = () => exportCsv(userId);
  document.getElementById('t-type-filter').onchange = () => { currentPage = 0; load(); };
  document.getElementById('t-start').onchange = () => { currentPage = 0; load(); };
  document.getElementById('t-end').onchange = () => { currentPage = 0; load(); };
  let debounce;
  document.getElementById('t-search').oninput = () => {
    clearTimeout(debounce);
    debounce = setTimeout(() => { currentPage = 0; load(); }, 400);
  };
  load();
}

async function loadTransactions(userId) {
  const search = document.getElementById('t-search')?.value;
  const type = document.getElementById('t-type-filter')?.value;
  const start = document.getElementById('t-start')?.value;
  const end = document.getElementById('t-end')?.value;
  const tbody = document.getElementById('t-body');
  if (!tbody) return;

  try {
    const result = search
      ? await api.get('/upsert/search', { userId, q: search, page: currentPage, size: 15 })
      : await api.get('/upsert/entries', { userId, type: type || undefined, startDate: start || undefined, endDate: end || undefined, page: currentPage, size: 15 });

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
      tbody.querySelectorAll('.t-del').forEach(b => b.onclick = () => deleteTransaction(userId, b.dataset.id));
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
  let goals = [];
  try {
    goals = await api.get('/upsert/goals', { userId });
    goals = goals.filter(g => !g.completed); // only show active incomplete goals
  } catch(e) { console.error('Failed to load goals for allocation', e); }

  openModal(isEdit ? 'Edit Transaction' : 'Add Transaction', `
    <form id="txn-form">
      <div class="form-row">
        <div class="form-group"><label for="m-name">Name</label><input class="form-input" id="m-name" required value="${esc(existing?.name || '')}"></div>
        <div class="form-group"><label for="m-amount">Amount</label><input class="form-input" id="m-amount" type="number" step="0.01" min="0.01" required value="${existing?.amount || ''}"></div>
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
      <div class="form-group"><label for="m-cat">Category</label><select class="form-select" id="m-cat"></select></div>
      <div class="form-group"><label for="m-desc">Description</label><textarea class="form-textarea" id="m-desc">${esc(existing?.description || '')}</textarea></div>
      
      ${!isEdit && goals.length > 0 ? `
      <div class="form-group" style="background:var(--bg-card);padding:12px;border-radius:6px;border:1px solid var(--border)">
        <label>Allocate to Goals (Optional)</label>
        <div style="font-size:0.85rem;color:var(--text-dim);margin-bottom:8px;">You can split a portion of this amount towards your savings goals.</div>
        <div id="m-allocations"></div>
        <button type="button" class="btn btn-secondary btn-sm" id="btn-add-alloc">+ Add Allocation</button>
      </div>` : ''}

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
      const payload = {
        userId,
        name: document.getElementById('m-name').value,
        amount,
        type,
        currency: document.getElementById('m-currency').value.toUpperCase(),
        description: document.getElementById('m-desc').value,
        category: document.getElementById('m-cat').value,
        recurring,
        recurringPeriod: recurring ? document.getElementById('m-recurring-period').value : null
      };

      if (!isEdit) {
        const allocElements = document.querySelectorAll('.alloc-row');
        if (allocElements.length > 0) {
          payload.allocations = [];
          let totalAllocated = 0;
          allocElements.forEach(el => {
            const goalId = parseInt(el.querySelector('.alloc-goal').value);
            const a = parseFloat(el.querySelector('.alloc-amount').value);
            if (goalId && !isNaN(a) && a > 0) {
              payload.allocations.push({ goalId, amount: a });
              totalAllocated += a;
            }
          });
          if (totalAllocated > amount) {
            toast('Allocations cannot exceed the total transaction amount!', 'error');
            return false;
          }
        }
      }

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

  if (!isEdit && goals.length > 0) {
    const allocContainer = document.getElementById('m-allocations');
    const btnAddAlloc = document.getElementById('btn-add-alloc');
    btnAddAlloc.onclick = () => {
      const row = document.createElement('div');
      row.className = 'alloc-row form-row';
      row.style.marginBottom = '8px';
      row.innerHTML = `
        <div class="form-group" style="flex:2;margin-bottom:0">
          <select class="form-select alloc-goal">
            <option value="">Select Goal...</option>
            ${goals.map(g => `<option value="${g.id}">${esc(g.name)} (Remaining: ${formatCurrency(g.targetAmount - g.savedAmount, g.currency)})</option>`).join('')}
          </select>
        </div>
        <div class="form-group" style="flex:1;margin-bottom:0">
          <input type="number" class="form-input alloc-amount" placeholder="Amount" step="0.01" min="0.01">
        </div>
        <button type="button" class="btn btn-danger btn-icon btn-sm" onclick="this.parentElement.remove()" style="margin-top:auto">${icon('trash', 'sm')}</button>
      `;
      allocContainer.appendChild(row);
    };
  }

  const recCheckbox = document.getElementById('m-recurring');
  const recGroup = document.getElementById('m-rec-period-group');
  recCheckbox.onchange = () => { recGroup.style.display = recCheckbox.checked ? 'block' : 'none'; };

  const updateCats = () => {
    const type = document.getElementById('m-type').value;
    const cats = type === 'INCOME' ? INCOME_CATS : EXPENSE_CATS;
    const sel = existing?.type === type ? existing.category : null;  // single field
    document.getElementById('m-cat').innerHTML = categoryOptions(cats, sel);
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
