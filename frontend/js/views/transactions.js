import { api, Auth, toast } from '../utils/api.js';
import { icon } from '../utils/icons.js';
import {
  esc, escAttr, safeImageUrl, pageHeader, emptyState, formatMoney, formatCategory, formatDate,
  openModal, confirmModal, modalActions, EXPENSE_CATS, INCOME_CATS, categoryOptions,
  setupCategorySearch, currencyOptionHtml, formatDayLabel, skeletonRows,
  displayAmountFor, displayName, dateTooltip, humanizeNamesIn, humanizeSystemText,
  categoryDot, SPENDING_CATS, MOVEMENT_CATS, CATEGORY_HELP, transactionCategories, transactionKindLabel,
} from '../utils/ui.js';

let currentPage = 0, totalPages = 0;

function isoDay(iso) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return 'unknown';
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function txCategory(t) {
  return t.expenseCategory || t.incomeCategory || t.category || '';
}

function isSettlementRow(t) {
  return String(txCategory(t)).toUpperCase() === 'SETTLEMENT';
}

/** A settlement reads as a sentence about people, not as a system log. */
function settlementTitle(t, userId) {
  const raw = String(t.name || t.description || '').trim();
  // Settlement entries are written per person: "Paid <name>" on the payer's
  // ledger and "Received from <name>" on the receiver's.
  let m = /^paid\s+(.+)$/i.exec(raw);
  if (m) return `You paid ${displayName(m[1])}`;
  m = /^received from\s+(.+)$/i.exec(raw);
  if (m) return `${displayName(m[1])} paid you`;
  m = /payment from\s+(.+?)\s+to\s+(.+)$/i.exec(raw);
  if (m) {
    const from = displayName(m[1]);
    const to = displayName(m[2]);
    const myName = displayName(Auth.getName() || Auth.getEmail() || '');
    return from === myName ? `You paid ${to}` : `${from} paid ${to}`;
  }
  if (belongsToUser(t, userId)) return 'You recorded a settlement';
  return 'Settlement recorded';
}

function belongsToUser(t, userId) {
  return userId && String(t.userId || '') === String(userId);
}

/** Group name from a settlement note ("Settlement · Office lunch crew"). */
function settlementGroup(t) {
  const m = /^settlement\s*[·:-]\s*(.+)$/i.exec(String(t.description || ''));
  return m ? m[1].trim() : '';
}

/** Sync the page into the URL so refresh and Back both work. */
function setPageInUrl(page) {
  try {
    const url = new URL(location.href);
    if (page > 0) url.searchParams.set('page', String(page + 1));
    else url.searchParams.delete('page');
    history.replaceState(history.state || {}, '', `${url.pathname}${url.search}`);
  } catch { /* jsdom or file URLs */ }
}

function titleCase(value) {
  return String(value || '').toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
}

/** Read-only category label for the ledger rows. */
function categoryDisplayHtml(t, { dot = true } = {}) {
  const cat = txCategory(t) || 'OTHERS';
  return `<span class="cat-label">${dot ? categoryDot(cat) : ''}<span>${esc(formatCategory(cat))}${t.subcategory ? ` · ${esc(t.subcategory)}` : ''}</span></span>`;
}

/** The category chip in the detail sheet is also the control: change it there. */
function categoryButtonHtml(t, { dot = true } = {}) {
  const cat = txCategory(t) || 'OTHERS';
  const label = esc(formatCategory(cat));
  if (['REFUND', 'REIMBURSEMENT'].includes(t.transactionKind)) return `<span>${label}</span>`;
  return `<button type="button" class="cat-edit-btn" data-cat-id="${esc(String(t.id))}" title="Change category" aria-label="Change category: ${label}">
      ${dot ? categoryDot(cat) : ''}<span>${label}</span>${icon('chevron-down', 'xs')}
    </button>`;
}

/** Inline category correction for auto-generated rows. */
function startCategoryEdit(btn, t, userId, onDone) {
  const isIncome = t.type === 'INCOME';
  const cats = transactionCategories(t.type, t.transactionKind, txCategory(t));
  const current = txCategory(t) || 'OTHERS';

  const select = document.createElement('select');
  select.className = 'form-select';
  select.innerHTML = categoryOptions(cats, current);
  btn.replaceWith(select);

  let settled = false;
  let picker = null;

  const onKey = (e) => {
    if (e.key !== 'Escape') return;
    e.preventDefault();
    // Cancel the correction, not the whole transaction sheet behind it.
    e.stopPropagation();
    cancel();
  };

  const cancel = () => {
    if (settled) return;
    settled = true;
    document.removeEventListener('keydown', onKey, true);
    if (picker) { picker.destroy(); picker = null; }
    if (select.isConnected) select.replaceWith(btn);
  };

  const commit = async (value) => {
    if (settled) return;
    settled = true;
    document.removeEventListener('keydown', onKey, true);
    select.disabled = true;
    if (picker) picker.disable();
    try {
      await api.put('/upsert/update', { ...t, category: value, userId });
      t.category = value;
      if (isIncome) t.incomeCategory = value;
      else t.expenseCategory = value;
      toast('Category updated', 'success', {
        undo: async () => {
          await api.put('/upsert/update', { ...t, category: current, userId });
          toast('Change undone', 'info');
          loadTransactions(userId);
        },
      });
      if (picker) { picker.destroy(); picker = null; }
      if (onDone) onDone(value);
      loadTransactions(userId);
    } catch (err) {
      toast(err.message || 'Could not update category', 'error');
      select.disabled = false;
      if (picker) picker.enable();
      settled = false;
      document.addEventListener('keydown', onKey, true);
    }
  };

  document.addEventListener('keydown', onKey, true);

  // A searchable picker beats a hundred-item native dropdown: type to filter.
  if (window.TomSelect) {
    picker = new TomSelect(select, {
      create: false,
      sortField: { field: 'text', direction: 'asc' },
      placeholder: 'Search category…',
      maxOptions: 100,
      onChange: commit,
    });
    picker.focus();
  } else {
    select.focus();
    select.onchange = () => commit(select.value);
    select.onblur = () => setTimeout(cancel, 150);
  }
}

export async function renderTransactions(container) {
  const userId = Auth.getUserId();
  // Keep the active page (and any category deep-link) in the URL.
  const urlParams = new URLSearchParams(location.search);
  const pageParam = Number(urlParams.get('page'));
  currentPage = Number.isInteger(pageParam) && pageParam > 0 ? pageParam - 1 : 0;
  const urlCategory = urlParams.get('category');
  container.innerHTML = `
    ${pageHeader('Transactions', '', '<button class="btn btn-primary" id="t-add" title="Add transaction (N)">Add transaction</button>')}
    <div class="toolbar">
      <div class="search-box page-search">${icon('search', 'sm')}<input class="form-input" id="t-search" placeholder="Search by name or note…" aria-label="Search transactions" autocomplete="off"></div>
      <button class="btn btn-ghost btn-sm" id="t-filter-btn">${icon('filter', 'sm')} Filters</button>
      <button class="btn btn-ghost btn-sm" id="t-export">${icon('download', 'sm')} Export CSV</button>
      <button class="btn btn-ghost btn-sm" id="t-batch-import">${icon('upload', 'sm')} Batch import</button>
      <input type="file" id="t-batch-file" accept=".pdf,.csv" hidden>
    </div>
    <div class="filter-chips" id="t-filter-chips" hidden></div>
    <div class="table-wrap txn-desktop">
      <table>
        <thead><tr><th>Name</th><th>Category</th><th style="text-align:right">Amount</th></tr></thead>
        <tbody id="t-body"><tr><td colspan="3">${skeletonRows(6)}</td></tr></tbody>
      </table>
    </div>
    <div class="txn-mobile" id="t-mobile" aria-live="polite"></div>
    <div class="pagination" id="t-pagination"></div>

    <div class="side-panel-overlay" id="t-filter-overlay"></div>
    <div class="side-panel" id="t-filter-panel">
      <div class="side-panel-header">
        <h2>Filters</h2>
        <button class="btn btn-icon btn-ghost" id="t-filter-close" aria-label="Close filters">${icon('close', 'sm')}</button>
      </div>
      <div class="side-panel-body">
        <div class="form-group">
          <label for="t-type-filter">Type</label>
          <select class="form-select" id="t-type-filter" aria-label="Filter by type">
            <option value="">All types</option><option value="INCOME">Income</option><option value="EXPENSE">Expense</option>
          </select>
        </div>
        <div class="form-group">
          <label for="t-category">Category</label>
          <select class="form-select" id="t-category" aria-label="Filter by category">
            <option value="">All categories</option>
            <optgroup label="Expenses">${categoryOptions(EXPENSE_CATS)}</optgroup>
            <optgroup label="Income">${categoryOptions(INCOME_CATS)}</optgroup>
          </select>
        </div>
        <div class="form-group">
          <label for="t-start">From</label>
          <input class="form-input" id="t-start" type="date" aria-label="Start date">
        </div>
        <div class="form-group">
          <label for="t-end">To</label>
          <input class="form-input" id="t-end" type="date" aria-label="End date">
        </div>
      </div>
      <div class="side-panel-footer">
        <button class="btn btn-secondary" id="t-filter-reset">Reset</button>
        <button class="btn btn-primary" id="t-filter-apply">Apply filters</button>
      </div>
    </div>`;

  const load = () => loadTransactions(userId);
  document.getElementById('t-add').onclick = () => showModal(userId, null, load);
  document.getElementById('t-export').onclick = () => exportCsv(userId);

  const panel = document.getElementById('t-filter-panel');
  const overlay = document.getElementById('t-filter-overlay');
  const openPanel = () => { panel.classList.add('open'); overlay.classList.add('open'); };
  const closePanel = () => { panel.classList.remove('open'); overlay.classList.remove('open'); };

  if (window.TomSelect) {
    ['#t-type-filter', '#t-category'].forEach(sel => {
      const el = document.querySelector(sel);
      if (el && !el.tomselect) new TomSelect(sel, { create: false, controlInput: null, maxOptions: 200 });
    });
  }
  if (window.flatpickr) {
    flatpickr('#t-start', { dateFormat: 'Y-m-d', altInput: true, altFormat: 'j M Y', disableMobile: true });
    flatpickr('#t-end', { dateFormat: 'Y-m-d', altInput: true, altFormat: 'j M Y', disableMobile: true });
  }

  document.getElementById('t-filter-btn').onclick = openPanel;
  document.getElementById('t-filter-close').onclick = closePanel;
  overlay.onclick = closePanel;

  document.getElementById('t-filter-apply').onclick = () => {
    currentPage = 0;
    renderFilterChips();
    load();
    closePanel();
  };
  document.getElementById('t-filter-reset').onclick = () => {
    const set = (id, value) => {
      const el = document.getElementById(id);
      if (!el) return;
      if (el.tomselect) el.tomselect.setValue(value, true);
      else el.value = value;
      if (el._flatpickr) el._flatpickr.clear();
    };
    set('t-type-filter', '');
    set('t-category', '');
    set('t-start', '');
    set('t-end', '');
    currentPage = 0;
    renderFilterChips();
    load();
    closePanel();
  };

  let debounce;
  document.getElementById('t-search').oninput = () => {
    clearTimeout(debounce);
    debounce = setTimeout(() => { currentPage = 0; load(); }, 350);
  };

  const pendingCategory = urlCategory || sessionStorage.getItem('pendingCategoryFilter');
  if (pendingCategory) {
    sessionStorage.removeItem('pendingCategoryFilter');
    const catEl = document.getElementById('t-category');
    if (catEl.tomselect) catEl.tomselect.setValue(pendingCategory, true);
    else catEl.value = pendingCategory;
    renderFilterChips();
  }

  const pendingScanRaw = localStorage.getItem('pfa_pending_scan');
  if (pendingScanRaw) {
    try {
      const pendingScan = JSON.parse(pendingScanRaw);
      if (pendingScan.groupId === 'PERSONAL') {
        localStorage.removeItem('pfa_pending_scan');
        setTimeout(() => {
          let isoDate = null;
          if (pendingScan.date) {
            const d = new Date(pendingScan.date);
            if (!isNaN(d.getTime())) isoDate = d.toISOString();
          }
          showModal(userId, {
            name: pendingScan.name,
            amount: pendingScan.amount,
            type: pendingScan.type || 'EXPENSE',
            currency: pendingScan.currency || Auth.getDefaultCurrency(),
            description: pendingScan.description,
            category: pendingScan.category,
            createdAt: isoDate,
            recurring: pendingScan.recurring,
            recurringPeriod: pendingScan.recurringPeriod || null,
            transactionKind: pendingScan.transactionKind || 'STANDARD',
            originalTransactionId: pendingScan.originalTransactionId || null,
            subcategory: pendingScan.subcategory || null,
            receiptUrl: pendingScan.receiptUrl
          }, load);
        }, 100);
      }
    } catch { localStorage.removeItem('pfa_pending_scan'); }
  }

  document.getElementById('t-batch-import').onclick = () => document.getElementById('t-batch-file').click();

  document.getElementById('t-batch-file').onchange = async (e) => {
    const file = e.target.files[0];
    if (!file) return;

    const formData = new FormData();
    formData.append('file', file);

    const btn = document.getElementById('t-batch-import');
    btn.innerHTML = `${icon('loader', 'sm')} Uploading…`;
    btn.disabled = true;

    try {
      const target = encodeURIComponent(Auth.getDefaultCurrency());
      const res = await api.post(`/bill/batch-process/${userId}?targetCurrency=${target}`, formData, { raw: true });
      if (!res.ok) throw new Error('Upload failed');
      toast('Statement uploaded. Extracting transactions…', 'info');
      btn.innerHTML = `${icon('loader', 'sm')} Processing…`;
    } catch {
      toast('Could not upload that file. Check it is a PDF or CSV under 10 MB.', 'error');
      btn.innerHTML = `${icon('upload', 'sm')} Batch import`;
      btn.disabled = false;
    }
    e.target.value = '';
  };

  const handleBatchOcr = (e) => {
    const detail = e.detail;
    if (detail.type === 'batch-ocr-completed' && detail.userId === userId) {
      const btn = document.getElementById('t-batch-import');
      if (btn) {
        btn.innerHTML = `${icon('upload', 'sm')} Batch import`;
        btn.disabled = false;
      }
      if (detail.status === 'SUCCESS' && Array.isArray(detail.data) && detail.data.length > 0) {
        showBatchModal(userId, detail.data, load);
      } else {
        toast('No transactions were found in that statement.', 'warning');
      }
    }
  };

  window.addEventListener('app-notification', handleBatchOcr);
  const observer = new MutationObserver(() => {
    if (!document.contains(container)) {
      window.removeEventListener('app-notification', handleBatchOcr);
      observer.disconnect();
    }
  });
  observer.observe(document.body, { childList: true, subtree: true });

  function currentFilters() {
    return {
      type: document.getElementById('t-type-filter')?.value || '',
      category: document.getElementById('t-category')?.value || '',
      start: document.getElementById('t-start')?.value || '',
      end: document.getElementById('t-end')?.value || '',
    };
  }

  function renderFilterChips() {
    const chips = document.getElementById('t-filter-chips');
    if (!chips) return;
    const f = currentFilters();
    const labelFor = {
      type: v => ({ INCOME: 'Income', EXPENSE: 'Expense' }[v] || v),
      category: v => formatCategory(v),
      start: v => `From ${formatDate(`${v}T00:00:00`)}`,
      end: v => `To ${formatDate(`${v}T00:00:00`)}`,
    };
    const entries = Object.entries(f).filter(([, v]) => v);
    chips.hidden = entries.length === 0;
    chips.innerHTML = entries.map(([key, value]) => `
      <span class="filter-chip">${esc(labelFor[key](value))}
        <button type="button" data-clear="${key}" aria-label="Remove ${esc(labelFor[key](value))} filter">${icon('close', 'xs')}</button>
      </span>`).join('');
    chips.querySelectorAll('[data-clear]').forEach(btn => {
      btn.onclick = () => {
        const key = btn.dataset.clear;
        const id = { type: 't-type-filter', category: 't-category', start: 't-start', end: 't-end' }[key];
        const el = document.getElementById(id);
        if (el?.tomselect) el.tomselect.setValue('', true);
        else if (el) { el.value = ''; if (el._flatpickr) el._flatpickr.clear(); }
        currentPage = 0;
        renderFilterChips();
        load();
      };
    });
  }

  renderFilterChips();
  load();
}

function showBatchModal(userId, transactions, onSuccess) {
  const displayAmount = (t) => {
    return t.amount || 0;
  };
  const tableRows = transactions.map((t, idx) => `
    <tr>
      <td><input type="text" class="form-input" id="b-name-${idx}" value="${escAttr(t.name || '')}"></td>
      <td>
        <select class="form-select" id="b-type-${idx}">
          <option value="EXPENSE" ${t.type === 'EXPENSE' ? 'selected' : ''}>Expense</option>
          <option value="INCOME" ${t.type === 'INCOME' ? 'selected' : ''}>Income</option>
        </select>
      </td>
      <td><select class="form-select" id="b-kind-${idx}">${[['STANDARD','Income / expense'],['TRANSFER','Transfer'],['INVESTMENT','Savings / investment'],['REFUND','Refund'],['REIMBURSEMENT','Reimbursement']].map(([value,label]) => `<option value="${value}" ${t.transactionKind === value ? 'selected' : ''}>${label}</option>`).join('')}</select></td>
      <td><select class="form-select" id="b-cat-${idx}">${categoryOptions([...new Set([...EXPENSE_CATS, ...INCOME_CATS])], t.category || t.expenseCategory || t.incomeCategory || (t.type === 'INCOME' ? 'OTHER_INCOME' : 'OTHERS'))}</select><input class="form-input" id="b-subcat-${idx}" maxlength="80" aria-label="Subcategory" placeholder="Subcategory (optional)" value="${escAttr(t.subcategory || '')}"></td>
      <td><input type="number" class="form-input" id="b-original-${idx}" min="1" step="1" aria-label="Original expense ID" placeholder="For refund / reimbursement" value="${escAttr(String(t.originalTransactionId || ''))}"></td>
      <td><input type="number" class="form-input" id="b-amt-${idx}" value="${displayAmount(t)}" step="0.01"><span>${esc(t.currency || Auth.getDefaultCurrency())}</span></td>
      <td><input type="date" class="form-input" id="b-date-${idx}" value="${t.date || ''}"></td>
    </tr>
  `).join('');

  const bodyHtml = `
    <p class="modal-section-label">Check the ${transactions.length} extracted ${transactions.length === 1 ? 'transaction' : 'transactions'} before importing. Refunds and reimbursements need the ID of an existing expense in the same currency; their category follows that expense.</p>
    <div style="max-height:380px;overflow-y:auto;border:1px solid var(--line);border-radius:var(--r-control)">
      <table class="batch-table">
        <thead><tr><th>Name</th><th>Direction</th><th>Treatment</th><th>Category</th><th>Original expense ID</th><th>Amount / currency</th><th>Date</th></tr></thead>
        <tbody>${tableRows}</tbody>
      </table>
    </div>
  `;

  openModal(`Import ${transactions.length} transactions`, bodyHtml, {
    submitLabel: 'Save all',
    size: 'lg',
    onSubmit: async () => {
      const payload = transactions.map((t, idx) => {
        let isoDate = null;
        const parsedDate = document.getElementById(`b-date-${idx}`).value;
        if (parsedDate) { const d = new Date(parsedDate); if (!isNaN(d.getTime())) isoDate = d.toISOString(); }
        return {
          userId,
          name: document.getElementById(`b-name-${idx}`).value,
          amount: parseFloat(document.getElementById(`b-amt-${idx}`).value) || 0,
          type: document.getElementById(`b-type-${idx}`).value,
          currency: t.currency || Auth.getDefaultCurrency(),
          category: document.getElementById(`b-cat-${idx}`).value,
          transactionKind: document.getElementById(`b-kind-${idx}`).value,
          originalTransactionId: Number(document.getElementById(`b-original-${idx}`).value) || null,
          subcategory: document.getElementById(`b-subcat-${idx}`).value.trim() || null,
          recurring: !!t.recurring,
          recurringPeriod: t.recurring ? (t.recurringPeriod || 'MONTHLY') : null,
          description: 'Imported from bank statement',
          createdAt: isoDate
        };
      });
      await api.post('/upsert/create/bulk', payload);
      toast(`Imported ${payload.length} transactions`, 'success');
      onSuccess();
    }
  });
}

async function loadTransactions(userId) {
  const search = document.getElementById('t-search')?.value;
  const type = document.getElementById('t-type-filter')?.value;
  const category = document.getElementById('t-category')?.value;
  const start = document.getElementById('t-start')?.value;
  const end = document.getElementById('t-end')?.value;
  const tbody = document.getElementById('t-body');
  if (!tbody) return;
  setPageInUrl(currentPage);

  try {
    const params = { userId, page: currentPage, size: 8 };
    if (type) params.type = type;
    if (category) params.category = category;
    if (start) params.startDate = start;
    if (end) params.endDate = end;

    const result = search
      ? await api.get('/upsert/search', { userId, q: search, page: currentPage, size: 8 })
      : await api.get('/upsert/entries', params);

    const items = result.content || [];
    totalPages = result.totalPages || 1;

    if (!items.length) {
      tbody.innerHTML = `<tr><td colspan="3">${emptyState('receipt', 'No transactions found', 'Add a transaction or adjust your filters.')}</td></tr>`;
      const mobile = document.getElementById('t-mobile');
      if (mobile) mobile.innerHTML = emptyState('receipt', 'No transactions found', 'Add a transaction or adjust your filters.');
    } else {
      const currency = Auth.getDefaultCurrency();
      const groups = new Map();
      for (const t of items) {
        const key = isoDay(t.createdAt);
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(t);
      }

      tbody.innerHTML = [...groups.entries()].map(([key, rows]) => {
        // A day total only adds rows in one currency; never mix currencies.
        let mixed = false;
        const dayTotal = rows.reduce((sum, t) => {
          const shown = displayAmountFor(t, currency);
          if (shown.currency !== currency) { mixed = true; return sum; }
          return sum + (t.type === 'INCOME' ? Number(shown.amount) : -Number(shown.amount));
        }, 0);
        const dayRows = rows.map(t => {
          const isIncome = t.type === 'INCOME';
          const shown = displayAmountFor(t, currency);
          const amount = formatMoney(isIncome ? shown.amount : -shown.amount, shown.currency);
          const settlement = isSettlementRow(t);

          // A split row names the group and what the number means. The API
          // resolves groupName/groupId/totalAmount/myShare from the link table.
          const isSplit = !!(t.groupName || t.groupId);
          const total = t.totalAmount != null ? Number(t.totalAmount) : null;
          const subParts = [];
          if (transactionKindLabel(t)) subParts.push(esc(transactionKindLabel(t)));
          if (settlement) subParts.push(esc(settlementGroup(t) || 'Settlement'));
          else if (isSplit) subParts.push(`Your share${total != null ? ` of ${formatMoney(total, currency)}` : ''}`);
          else if (t.description) subParts.push(esc(humanizeSystemText(t.description)));
          if (isSplit && t.groupName) {
            subParts.push(`<a href="#" class="txn-group-link" data-group="${esc(String(t.groupId || ''))}">${esc(t.groupName)}</a>`);
          }
          if (shown.secondary) subParts.push(`<span class="amount-hint">${esc(shown.secondary)}</span>`);

          const receipt = t.receiptUrl
            ? `<button type="button" class="attach-btn" data-receipt="${escAttr(t.receiptUrl)}" aria-label="Receipt attached" title="Receipt attached">${icon('paperclip', 'sm')}</button>`
            : '';
          const recurring = t.recurring
            ? `<span class="txn-tag" title="Recurring">${icon('repeat', 'xs')} ${esc(titleCase(t.recurringPeriod || 'Recurring'))}</span>`
            : '';
          const title = settlement ? settlementTitle(t, userId) : humanizeNamesIn(t.name || 'Transaction');
          const categoryCell = settlement
            ? `<span class="chip chip--neutral">Settlement</span>`
            : categoryDisplayHtml(t);

          return `<tr data-txn='${escAttr(JSON.stringify(t))}' tabindex="0" class="txn-row ${settlement ? 'is-settlement' : ''}" style="cursor:pointer">
            <td>
              <div class="txn-row-main">
                <div class="txn-row-text">
                  <div class="txn-name">${esc(title)}${receipt}${recurring}</div>
                  ${subParts.length ? `<div class="txn-sub">${subParts.join(' · ')}</div>` : ''}
                </div>
              </div>
            </td>
            <td>${categoryCell}</td>
            <td class="amount-cell"><span class="amount ${isIncome ? 'amount--pos' : ''}" style="display:inline-flex">${amount}</span></td>
          </tr>`;
        }).join('');
        return `
          <tr class="ledger-day-row"><td colspan="3"><span title="${esc(dateTooltip(`${key}T00:00:00`))}">${esc(formatDayLabel(key))}</span><span class="day-total" title="${mixed ? 'Excludes amounts in another currency' : ''}">${formatMoney(dayTotal, currency)}${mixed ? ' <span class="day-total-mark" aria-label="Excludes amounts in another currency">*</span>' : ''}</span></td></tr>
          ${dayRows}`;
      }).join('');

      tbody.querySelectorAll('.txn-group-link').forEach(a => {
        a.onclick = (e) => {
          e.preventDefault();
          e.stopPropagation();
          window.navigateTo('split');
        };
      });

      tbody.querySelectorAll('tr[data-txn]').forEach(tr => {
        tr.onclick = (e) => {
          if (e.target.closest('.attach-btn')) return;
          const t = JSON.parse(tr.dataset.txn);
          openTransactionDetailsModal(t, userId);
        };
        tr.onkeydown = (e) => {
          if (e.key === 'Enter') {
            e.preventDefault();
            openTransactionDetailsModal(JSON.parse(tr.dataset.txn), userId);
          } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
            e.preventDefault();
            const rows = [...tbody.querySelectorAll('tr[data-txn]')];
            const i = rows.indexOf(tr);
            const next = rows[e.key === 'ArrowDown' ? i + 1 : i - 1];
            next?.focus();
          }
        };
      });

      tbody.querySelectorAll('[data-receipt]').forEach(btn => {
        btn.onclick = (e) => { e.stopPropagation(); window.openReceiptLightbox(btn.dataset.receipt); };
      });

      renderMobileTransactions(items, currency, userId);
    }
    renderPagination();
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="3" style="text-align:center;padding:40px;color:var(--neg)">${esc(err.message)}</td></tr>`;
  }
}

/** The phone layout: same rows, no sideways scrolling. */
function renderMobileTransactions(items, currency, userId) {
  const el = document.getElementById('t-mobile');
  if (!el) return;

  const groups = new Map();
  for (const t of items) {
    const key = isoDay(t.createdAt);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(t);
  }

  let anyMixed = false;
  const html = [...groups.entries()].map(([key, rows]) => {
    let mixed = false;
    const dayTotal = rows.reduce((sum, t) => {
      const shown = displayAmountFor(t, currency);
      if (shown.currency !== currency) { mixed = true; return sum; }
      return sum + (t.type === 'INCOME' ? Number(shown.amount) : -Number(shown.amount));
    }, 0);
    if (mixed) anyMixed = true;
    const rowHtml = rows.map(t => {
      const isIncome = t.type === 'INCOME';
      const cat = txCategory(t);
      const settlement = isSettlementRow(t);
      const shown = displayAmountFor(t, currency);
      const amount = formatMoney(isIncome ? shown.amount : -shown.amount, shown.currency);
      const title = settlement ? settlementTitle(t, userId) : humanizeNamesIn(t.name || 'Transaction');
      const sub = settlement
        ? esc(settlementGroup(t) || 'Settlement')
        : `${categoryDisplayHtml(t, { dot: false })}${transactionKindLabel(t) ? ` · ${esc(transactionKindLabel(t))}` : ''}${shown.secondary ? ` · ${esc(shown.secondary)}` : ''}`;
      return `
        <div class="ledger-row ${settlement ? 'is-settlement' : ''}" role="button" tabindex="0" data-txn-id="${esc(String(t.id || ''))}">
          ${settlement
            ? `<span class="row-icon">${icon('arrow-right', 'sm')}</span>`
            : categoryDot(cat)}
          <div class="ledger-name">
            <span class="txn-title">${esc(title)}</span>
            <div class="txn-sub">${sub}</div>
          </div>
          <span class="ledger-amount ${isIncome ? 'is-pos' : ''}">${amount}</span>
        </div>`;
    }).join('');

    return `<div class="ledger-day"><span title="${esc(dateTooltip(`${key}T00:00:00`))}">${esc(formatDayLabel(key))}</span><span class="day-total" title="${mixed ? 'Excludes amounts in another currency' : ''}">${formatMoney(dayTotal, currency)}${mixed ? ' <span class="day-total-mark" aria-label="Excludes amounts in another currency">*</span>' : ''}</span></div>${rowHtml}`;
  }).join('');
  el.innerHTML = html + (anyMixed
    ? '<p class="day-total-footnote">* Excludes amounts in another currency.</p>'
    : '');

  el.querySelectorAll('[data-txn-id]').forEach(row => {
    const open = () => {
      const t = items.find(i => String(i.id) === row.dataset.txnId);
      if (t) openTransactionDetailsModal(t, userId);
    };
    row.onclick = open;
    row.onkeydown = (e) => {
      if (e.key !== 'Enter') return;
      e.preventDefault();
      open();
    };
  });
}

function renderPagination() {
  const el = document.getElementById('t-pagination');
  if (!el || totalPages <= 1) { if (el) el.innerHTML = ''; return; }
  const userId = Auth.getUserId();
  const goPage = (page) => { currentPage = page; loadTransactions(userId); };

  let html = `<span class="pagination-info">Page ${currentPage + 1} of ${totalPages}</span>`;
  html += `<button class="btn-icon" id="t-prev" ${currentPage === 0 ? 'disabled' : ''} aria-label="Previous page">${icon('chevron-left', 'sm')}</button>`;

  let startPage = Math.max(0, currentPage - 2);
  let endPage = Math.min(totalPages - 1, startPage + 4);
  if (endPage - startPage < 4) startPage = Math.max(0, endPage - 4);

  for (let i = startPage; i <= endPage; i++) {
    html += `<button class="${i === currentPage ? 'active' : ''}" data-p="${i}" aria-label="Page ${i + 1}" aria-current="${i === currentPage ? 'page' : 'false'}">${i + 1}</button>`;
  }

  html += `<button class="btn-icon" id="t-next" ${currentPage === totalPages - 1 ? 'disabled' : ''} aria-label="Next page">${icon('chevron-right', 'sm')}</button>`;

  el.innerHTML = html;

  if (currentPage > 0) document.getElementById('t-prev').onclick = () => goPage(currentPage - 1);
  if (currentPage < totalPages - 1) document.getElementById('t-next').onclick = () => goPage(currentPage + 1);
  el.querySelectorAll('button[data-p]').forEach(b => {
    b.onclick = () => goPage(+b.dataset.p);
  });
}

async function showModal(userId, existing, onDone, originalExpense = null) {
  const originals = new Map();
  if (originalExpense) originals.set(String(originalExpense.id), originalExpense);
  let originalPage = 0;
  let originalQuery = '';
  let originalLoaded = false;
  let originalRequest = 0;
  const isEdit = !!(existing && existing.id);
  const hasReceipt = !!(existing?.receiptUrl);

  openModal(isEdit ? 'Edit transaction' : 'Add transaction', `
    <form id="txn-form">
      <div class="form-row">
        <div class="form-group" style="flex:2"><label for="m-name">Name</label><input class="form-input" id="m-name" required value="${escAttr(existing?.name || '')}" placeholder="e.g. Grocery store"></div>
        <div class="form-group" style="flex:1"><label for="m-amount">Amount</label><input class="form-input" id="m-amount" type="text" inputmode="decimal" required value="${existing?.amount || ''}" placeholder="0.00"></div>
      </div>
      <div class="form-row">
        <div class="form-group"><label for="m-type">Type</label>
          <select class="form-select" id="m-type" required>
            <option value="EXPENSE" ${existing?.type !== 'INCOME' ? 'selected' : ''}>Expense</option>
            <option value="INCOME" ${existing?.type === 'INCOME' ? 'selected' : ''}>Income</option>
          </select>
        </div>
        <div class="form-group"><label for="m-currency">Currency</label><select class="form-select" id="m-currency" required>${currencyOptionHtml(existing?.currency || Auth.getDefaultCurrency())}</select></div>
        <div class="form-group"><label for="m-date">Date</label><input class="form-input" id="m-date" type="date" value="${existing?.createdAt ? existing.createdAt.split('T')[0] : new Date().toISOString().split('T')[0]}"></div>
      </div>
      <div class="form-group">
        <label for="m-kind">Treatment</label>
        <select class="form-select" id="m-kind">
          ${[['STANDARD', 'Income / expense'], ['TRANSFER', 'Transfer / borrowing / repayment'], ['INVESTMENT', 'Savings / investment'], ['REFUND', 'Purchase refund'], ['REIMBURSEMENT', 'Expense reimbursement']].map(([value, label]) => `<option value="${value}" ${existing?.transactionKind === value ? 'selected' : ''}>${label}</option>`).join('')}
        </select>
        <p class="meta" id="m-kind-help" aria-live="polite"></p>
      </div>
      <div class="form-group" id="m-original-group" hidden>
        <label for="m-original-search">Find the original expense</label>
        <div class="form-row"><input class="form-input" id="m-original-search" placeholder="Search by name or note"><button type="button" class="btn btn-secondary" id="m-original-find">Search</button></div>
        <label for="m-original">Original expense</label>
        <select class="form-select" id="m-original"><option value="">Choose an expense…</option></select>
        <button type="button" class="btn btn-ghost btn-sm" id="m-original-more">Load more expenses</button>
        <p class="meta" id="m-original-status" aria-live="polite"></p>
      </div>
      <div class="form-group" id="m-cat-group">
        <label for="m-cat">Category</label>
        <select class="form-select" id="m-cat" required></select>
        <p class="meta" id="m-cat-help" aria-live="polite"></p>
      </div>
      <div class="form-group"><label for="m-subcategory">Subcategory <span class="meta">(optional)</span></label><input class="form-input" id="m-subcategory" maxlength="80" value="${escAttr(existing?.subcategory || '')}" placeholder="e.g. Cleaning supplies, consulting, weekend gigs"><p class="meta">Your own label within the selected category.</p></div>
      <div class="form-group"><label for="m-desc">Note <span class="meta">(optional)</span></label><textarea class="form-textarea" id="m-desc" placeholder="Add a note…" style="min-height:64px">${esc(existing?.description || '')}</textarea></div>
      <div class="form-row" style="align-items:center;gap:var(--s4)">
        <div class="form-check">
          <input type="checkbox" id="m-recurring" ${existing?.recurring ? 'checked' : ''}>
          <label for="m-recurring">Recurring</label>
        </div>
        <div class="form-group" id="m-rec-period-group" style="display:${existing?.recurring ? 'block' : 'none'};margin-bottom:0;flex:1">
          <select class="form-select" id="m-recurring-period">
            <option value="DAILY" ${existing?.recurringPeriod === 'DAILY' ? 'selected' : ''}>Daily</option>
            <option value="WEEKLY" ${existing?.recurringPeriod === 'WEEKLY' ? 'selected' : ''}>Weekly</option>
            <option value="MONTHLY" ${existing?.recurringPeriod === 'MONTHLY' || !existing?.recurringPeriod ? 'selected' : ''}>Monthly</option>
            <option value="YEARLY" ${existing?.recurringPeriod === 'YEARLY' ? 'selected' : ''}>Yearly</option>
          </select>
        </div>
      </div>

      <div class="form-group" style="margin-top:4px">
        <label>Receipt or bill</label>
        <div id="m-receipt-preview" class="receipt-preview-wrap" style="display:${hasReceipt ? 'block' : 'none'}">
          ${hasReceipt ? `<img src="${escAttr(safeImageUrl(existing.receiptUrl))}" alt="Receipt"><button type="button" class="receipt-remove" id="m-receipt-remove" aria-label="Remove receipt">×</button>` : ''}
        </div>
        <label id="m-receipt-upload" class="receipt-upload-zone" style="display:${hasReceipt ? 'none' : 'flex'}" for="m-receipt-file">
          ${icon('upload', 'sm')}
          <span class="upload-label">Choose a receipt</span>
          <span class="upload-hint">Image or PDF, up to 10 MB</span>
          <input type="file" id="m-receipt-file" accept="image/*,application/pdf">
        </label>
        <input type="hidden" id="m-receipt-url" value="${escAttr(existing?.receiptUrl || '')}">
      </div>
      ${modalActions('Cancel', isEdit ? 'Save changes' : 'Add transaction')}
    </form>`, {
    onSubmit: async () => {
      const type = document.getElementById('m-type').value;
      const recurring = document.getElementById('m-recurring').checked;
      const amount = parseFloat(document.getElementById('m-amount').value);
      const dateVal = document.getElementById('m-date').value;

      const submitBtn = document.querySelector('#txn-form button[type="submit"]');
      const fileInput = document.getElementById('m-receipt-file');
      if (fileInput && fileInput.files.length > 0) {
        submitBtn.disabled = true;
        const originalText = submitBtn.textContent;
        submitBtn.innerHTML = '<span class="spinner"></span> Uploading…';
        try {
          const fd = new FormData();
          fd.append('file', fileInput.files[0]);
          const upRes = await api.upload(`/receipt/upload/${userId}`, fd);
          if (upRes && upRes.url) document.getElementById('m-receipt-url').value = upRes.url;
        } catch (e) {
          toast('Could not upload the receipt: ' + e.message, 'error');
          submitBtn.disabled = false;
          submitBtn.textContent = originalText;
          return;
        }
        submitBtn.textContent = originalText;
      }

      let timeStr = '00:00:00';
      let isoDate = null;
      if (dateVal) {
        const today = new Date();
        const todayStr = today.getFullYear() + '-' + String(today.getMonth() + 1).padStart(2, '0') + '-' + String(today.getDate()).padStart(2, '0');
        if (dateVal === todayStr) {
          timeStr = String(today.getHours()).padStart(2, '0') + ':' +
                    String(today.getMinutes()).padStart(2, '0') + ':' +
                    String(today.getSeconds()).padStart(2, '0');
        }
        isoDate = new Date(`${dateVal}T${timeStr}`).toISOString();
      }

      const payload = {
        userId,
        name: document.getElementById('m-name').value,
        amount,
        type,
        currency: document.getElementById('m-currency').value.toUpperCase(),
        description: document.getElementById('m-desc').value,
        category: document.getElementById('m-cat').value,
        transactionKind: document.getElementById('m-kind').value,
        originalTransactionId: ['REFUND', 'REIMBURSEMENT'].includes(document.getElementById('m-kind').value)
          ? Number(document.getElementById('m-original').value) || null : null,
        subcategory: document.getElementById('m-subcategory').value.trim() || null,
        recurring,
        recurringPeriod: recurring ? document.getElementById('m-recurring-period').value : null,
        createdAt: isoDate,
        receiptUrl: document.getElementById('m-receipt-url').value || null
      };

      if (isEdit) {
        payload.id = existing.id;
        await api.put('/upsert/update', payload);
        toast('Saved', 'success');
      } else {
        await api.post('/upsert/create', payload);
        toast('Transaction added', 'success');
        // Give the read replica a moment before reloading the list.
        await new Promise(r => setTimeout(r, 500));
      }
      onDone();
    }
  });

  document.getElementById('m-receipt-remove')?.addEventListener('click', () => clearReceiptPreview());

  const receiptFile = document.getElementById('m-receipt-file');
  if (receiptFile) {
    receiptFile.addEventListener('change', () => {
      const file = receiptFile.files[0];
      if (!file) return;
      const preview = document.getElementById('m-receipt-preview');
      const uploadZone = document.getElementById('m-receipt-upload');
      const isPdf = file.type === 'application/pdf';
      const isImage = file.type.startsWith('image/');

      let previewContent = '';
      let objectUrl = null;

      if (isImage) {
        objectUrl = URL.createObjectURL(file);
        previewContent = `<img src="${objectUrl}" alt="Receipt preview">`;
      } else if (isPdf) {
        previewContent = `<div class="receipt-pdf-preview">${icon('document', 'lg')}<span>${esc(file.name)}</span><small>PDF document</small></div>`;
      } else {
        previewContent = `<div class="receipt-file-preview">${icon('file', 'lg')}<span>${esc(file.name)}</span><small>${esc(file.type || 'File')}</small></div>`;
      }

      preview.innerHTML = `${previewContent}<button type="button" class="receipt-remove" id="m-receipt-remove" aria-label="Remove receipt">×</button>`;
      preview.style.display = 'block';
      uploadZone.style.display = 'none';
      document.getElementById('m-receipt-url').value = '';

      document.getElementById('m-receipt-remove').onclick = () => {
        receiptFile.value = '';
        if (objectUrl) URL.revokeObjectURL(objectUrl);
        clearReceiptPreview();
      };
    });
  }

  function clearReceiptPreview() {
    document.getElementById('m-receipt-preview').style.display = 'none';
    document.getElementById('m-receipt-upload').style.display = 'flex';
    document.getElementById('m-receipt-url').value = '';
    const fileInput = document.getElementById('m-receipt-file');
    if (fileInput) fileInput.value = '';
  }

  const recCheckbox = document.getElementById('m-recurring');
  const recGroup = document.getElementById('m-rec-period-group');
  recCheckbox.onchange = () => { recGroup.style.display = recCheckbox.checked ? 'block' : 'none'; };

  const isRecovery = () => ['REFUND', 'REIMBURSEMENT'].includes(document.getElementById('m-kind').value);
  const setCategoryHelp = () => {
    document.getElementById('m-cat-help').textContent = CATEGORY_HELP[document.getElementById('m-cat').value] || '';
  };
  const selectOriginal = () => {
    const original = originals.get(document.getElementById('m-original').value);
    if (!original) return;
    const cat = document.getElementById('m-cat');
    cat.innerHTML = categoryOptions(SPENDING_CATS, txCategory(original));
    document.getElementById('m-currency').value = original.currency;
    document.getElementById('m-subcategory').value = original.subcategory || '';
    setCategoryHelp();
  };
  const renderOriginals = (selected) => {
    const select = document.getElementById('m-original');
    select.innerHTML = '<option value="">Choose an expense…</option>' + [...originals.values()].map(t =>
      `<option value="${escAttr(String(t.id))}">${esc(`${t.name} · ${formatMoney(t.amount, t.currency)} · ${formatDate(t.createdAt)}`)}</option>`).join('');
    if (selected && originals.has(String(selected))) select.value = String(selected);
    selectOriginal();
  };
  const loadOriginals = async (reset = false) => {
    if (!isRecovery()) return;
    const token = ++originalRequest;
    const select = document.getElementById('m-original');
    const selected = select.value || existing?.originalTransactionId;
    if (reset) { originalPage = 0; originals.clear(); }
    const status = document.getElementById('m-original-status');
    status.textContent = 'Loading expenses…';
    document.getElementById('m-original-more').disabled = true;
    try {
      const params = { userId, page: originalPage, size: 50 };
      const result = originalQuery
        ? await api.get('/upsert/search', { ...params, q: originalQuery })
        : await api.get('/upsert/entries', { ...params, type: 'EXPENSE' });
      if (token !== originalRequest || !select.isConnected) return;
      for (const t of result.content || []) {
        if (t.type === 'EXPENSE' && !t.groupId && !MOVEMENT_CATS.includes(txCategory(t))
            && !['TRANSFER', 'INVESTMENT', 'REFUND', 'REIMBURSEMENT'].includes(t.transactionKind)) originals.set(String(t.id), t);
      }
      if (selected && !originals.has(String(selected))) {
        const original = await api.get(`/upsert/entries/${selected}`, { userId });
        if (token !== originalRequest || !select.isConnected) return;
        originals.set(String(original.id), original);
      }
      renderOriginals(selected);
      originalLoaded = true;
      originalPage++;
      document.getElementById('m-original-more').hidden = originalPage >= (result.totalPages || 1);
      status.textContent = originals.size ? 'The category and currency follow the original expense.' : 'No eligible expenses found. Try another search.';
    } catch (err) {
      if (token === originalRequest && status.isConnected) status.textContent = err.message || 'Could not load expenses. Try again.';
    } finally {
      if (token === originalRequest && select.isConnected) document.getElementById('m-original-more').disabled = false;
    }
  };
  const updateCats = () => {
    const kind = document.getElementById('m-kind').value;
    const recovery = isRecovery();
    const typeSelect = document.getElementById('m-type');
    if (recovery) typeSelect.value = 'INCOME';
    typeSelect.disabled = recovery;
    const type = typeSelect.value;
    const sel = existing?.type === type ? txCategory(existing) : null;
    const cats = transactionCategories(type, kind, sel);
    const catSelect = document.getElementById('m-cat');
    if (catSelect.tomselect) catSelect.tomselect.destroy();
    catSelect.disabled = recovery;
    catSelect.innerHTML = categoryOptions(cats, sel);
    if (window.TomSelect && !recovery) {
      new TomSelect(catSelect, { create: false, placeholder: 'Search category…', maxOptions: 100, onChange: setCategoryHelp });
    }
    document.getElementById('m-original-group').hidden = !recovery;
    document.getElementById('m-original').required = recovery;
    document.getElementById('m-currency').disabled = recovery;
    document.getElementById('m-subcategory').disabled = recovery;
    recCheckbox.disabled = recovery;
    if (recovery) { recCheckbox.checked = false; recGroup.style.display = 'none'; }
    document.getElementById('m-kind-help').textContent = recovery
      ? 'An incoming payment that reduces spending in the original category; it does not count as earnings.'
      : ['TRANSFER', 'INVESTMENT'].includes(kind)
        ? 'Excluded from earned income and spending. Choose Income for incoming money or Expense for outgoing money.'
        : 'Counts toward earned income or spending. Record credit card repayments and money moved to savings using the matching treatment.';
    setCategoryHelp();
    if (recovery) {
      renderOriginals(document.getElementById('m-original').value || existing?.originalTransactionId);
      if (!originalLoaded) loadOriginals();
    }
  };
  updateCats();
  document.getElementById('m-type').onchange = updateCats;
  document.getElementById('m-kind').onchange = updateCats;
  document.getElementById('m-cat').onchange = setCategoryHelp;
  document.getElementById('m-original').onchange = selectOriginal;
  document.getElementById('m-original-more').onclick = () => loadOriginals();
  document.getElementById('m-original-find').onclick = () => {
    originalQuery = document.getElementById('m-original-search').value.trim();
    loadOriginals(true);
  };
  document.getElementById('m-original-search').onkeydown = e => {
    if (e.key === 'Enter') { e.preventDefault(); document.getElementById('m-original-find').click(); }
  };
}

async function editTransaction(userId, id) {
  try {
    const t = await api.get(`/upsert/entries/${id}`, { userId });
    showModal(userId, t, () => loadTransactions(userId));
  } catch (err) { toast(err.message, 'error'); }
}

async function deleteTransaction(userId, id, rowElement) {
  if (!(await confirmModal('Delete transaction', 'This transaction will be removed permanently. This cannot be undone.', 'Delete'))) return;
  if (rowElement) { rowElement.style.opacity = '0.3'; }
  try {
    await api.delete(`/upsert/delete/${id}`, { userId });
    toast('Deleted', 'success');
    if (rowElement) rowElement.remove();
    loadTransactions(userId);
  } catch (err) {
    if (rowElement) rowElement.style.opacity = '1';
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

async function openTransactionDetailsModal(t, userId) {
  try {
    t = await api.get(`/upsert/entries/${t.id}`, { userId });
  } catch (err) {
    toast(err.message, 'error');
    return;
  }

  const isIncome = t.type === 'INCOME';
  const shown = displayAmountFor(t, Auth.getDefaultCurrency());
  const currency = shown.currency;
  const cat = t.expenseCategory || t.incomeCategory || t.category || '';

  openModal('Transaction', `
    <div class="modal-amount-hero">
      <div class="amount-value ${isIncome ? 'amount--pos' : ''}">${formatMoney(isIncome ? shown.amount : -shown.amount, currency)}</div>
      ${shown.secondary ? `<div class="amount-secondary">${esc(shown.secondary)}</div>` : ''}
      <div class="amount-name">${esc(humanizeNamesIn(t.name))}</div>
      <div class="amount-date">${esc(formatDate(t.createdAt))}</div>
    </div>

    <div class="modal-detail-panel">
      <div class="modal-detail-row"><span class="detail-label">Type</span><span class="detail-value">${esc(transactionKindLabel(t) || (isIncome ? 'Income' : 'Expense'))}</span></div>
      <div class="modal-detail-row">
        <span class="detail-label">Category</span>
        <span class="detail-value" id="m-detail-cat">${isSettlementRow(t) ? esc(formatCategory(cat)) : categoryButtonHtml(t)}</span>
      </div>
      ${t.subcategory ? `<div class="modal-detail-row"><span class="detail-label">Subcategory</span><span class="detail-value">${esc(t.subcategory)}</span></div>` : ''}
      ${t.originalTransactionId ? `<div class="modal-detail-row"><span class="detail-label">Original expense</span><span class="detail-value">#${esc(String(t.originalTransactionId))}</span></div>` : ''}
      <div class="modal-detail-row"><span class="detail-label">Currency</span><span class="detail-value">${esc(currency)}</span></div>
      ${t.recurring ? `<div class="modal-detail-row"><span class="detail-label">Recurring</span><span class="detail-value">${esc(titleCase(t.recurringPeriod || 'Yes'))}</span></div>` : ''}
      ${t.description ? `<div class="modal-detail-row detail-block"><span class="detail-label">Note</span><span class="detail-value">${esc(humanizeSystemText(t.description))}</span></div>` : ''}
      ${t.receiptUrl ? `
        <div class="modal-detail-row detail-block">
          <span class="detail-label">Receipt</span>
          <div style="margin-top:6px">
            ${t.receiptUrl.toLowerCase().endsWith('.pdf')
              ? `<button type="button" class="btn btn-secondary" data-open-receipt="${escAttr(t.receiptUrl)}">${icon('document', 'sm')} Open PDF receipt</button>`
              : `<button type="button" style="padding:0;border:0;background:none" data-open-receipt="${escAttr(t.receiptUrl)}"><img src="${escAttr(safeImageUrl(t.receiptUrl))}" alt="Receipt" style="max-width:100%;max-height:180px;border-radius:8px;border:1px solid var(--line);display:block"></button>`
            }
          </div>
        </div>` : ''}
    </div>

    <div style="display:flex;flex-wrap:wrap;gap:10px;margin-top:var(--s4)">
      <button class="btn btn-secondary" id="m-edit-btn" style="flex:1">${icon('edit', 'sm')} Edit</button>
      ${!isIncome && !MOVEMENT_CATS.includes(cat) && !['TRANSFER', 'INVESTMENT'].includes(t.transactionKind) && !t.groupId
        ? '<button class="btn btn-secondary" id="m-refund-btn">Record refund</button><button class="btn btn-secondary" id="m-reimburse-btn">Reimbursement</button>' : ''}
      <button class="btn btn-danger" id="m-del-btn" style="flex:1">${icon('trash', 'sm')} Delete</button>
    </div>
  `);

  const recordRecovery = kind => {
    document.querySelector('.modal-overlay').click();
    const draft = { name: `${kind === 'REFUND' ? 'Refund' : 'Reimbursement'}: ${t.name}`.slice(0, 100),
      type: 'INCOME', transactionKind: kind, originalTransactionId: t.id,
      category: cat, currency: t.currency, subcategory: t.subcategory };
    showModal(userId, draft, () => loadTransactions(userId), t);
  };
  document.getElementById('m-refund-btn')?.addEventListener('click', () => recordRecovery('REFUND'));
  document.getElementById('m-reimburse-btn')?.addEventListener('click', () => recordRecovery('REIMBURSEMENT'));
  document.getElementById('m-edit-btn').onclick = () => {
    document.querySelector('.modal-overlay').click();
    setTimeout(() => editTransaction(userId, t.id), 50);
  };
  // Change the category straight from the detail sheet — the edit form is only
  // for name, amount, date and note.
  const catBox = document.getElementById('m-detail-cat');
  if (catBox) {
    catBox.onclick = (e) => {
      const btn = e.target.closest('.cat-edit-btn');
      if (!btn) return;
      e.stopPropagation();
      startCategoryEdit(btn, t, userId, () => { catBox.innerHTML = categoryButtonHtml(t); });
    };
  }
  document.getElementById('m-del-btn').onclick = async () => {
    if (!(await confirmModal('Delete transaction', 'This transaction will be removed permanently.', 'Delete'))) return;
    try {
      await api.delete(`/upsert/delete/${t.id}`, { userId });
      const restore = { ...t };
      delete restore.id;
      toast('Deleted', 'success', {
        undo: async () => {
          await api.post('/upsert/create', restore);
          toast('Transaction restored', 'info');
          loadTransactions(userId);
        },
      });
      document.querySelector('.modal-overlay').click();
      loadTransactions(userId);
    } catch (err) { toast(err.message, 'error'); }
  };
}
