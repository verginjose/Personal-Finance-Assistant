import { api, Auth, toast } from '../utils/api.js?v=1783271597';
import { icon } from '../utils/icons.js?v=1783302413';
import {
  esc, pageHeader, emptyState, formatCurrency, formatCategory, formatDate,
  typeBadge, openModal, confirmModal, modalActions, EXPENSE_CATS, INCOME_CATS, categoryOptions, setupCategorySearch
} from '../utils/ui.js?v=1783271597';


let currentPage = 0, totalPages = 0;

export async function renderTransactions(container) {
  const userId = Auth.getUserId();
  container.innerHTML = `
    ${pageHeader('Transactions', 'Manage your income and expenses', '<button class="btn btn-primary btn-sm" id="t-add">+ Add Transaction</button>')}
    <div class="toolbar fade-up">
      <div class="search-box">${icon('search', 'sm')}<input class="form-input" id="t-search" placeholder="Search by name or description…" aria-label="Search transactions"></div>
      <button class="btn btn-secondary btn-sm" id="t-filter-btn">${icon('filter', 'sm')} Filters</button>
      <button class="btn btn-secondary btn-sm" id="t-export">${icon('download', 'sm')} Export CSV</button>
      <button class="btn btn-secondary btn-sm" id="t-batch-import">${icon('upload', 'sm')} Batch Import</button>
      <input type="file" id="t-batch-file" accept=".pdf,.csv" style="display:none;">
    </div>
    <div class="table-wrap fade-up">
      <table>
        <thead><tr><th>Name</th><th>Type</th><th>Category</th><th>Amount</th><th>Date</th></tr></thead>
        <tbody id="t-body"><tr><td colspan="5" style="text-align:center;padding:48px;color:var(--text-dim)">Loading…</td></tr></tbody>
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
    flatpickr('#t-start', { dateFormat: 'Y-m-d', altInput: true, altFormat: 'F j, Y', disableMobile: true });
    flatpickr('#t-end', { dateFormat: 'Y-m-d', altInput: true, altFormat: 'F j, Y', disableMobile: true });
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
            currency: pendingScan.currency || 'INR',
            description: pendingScan.description,
            category: pendingScan.category,
            createdAt: isoDate,
            recurring: pendingScan.recurring,
            receiptUrl: pendingScan.receiptUrl
          }, load);
        }, 100);
      }
    } catch (e) { localStorage.removeItem('pfa_pending_scan'); }
  }

  document.getElementById('t-batch-import').onclick = () => {
    document.getElementById('t-batch-file').click();
  };

  document.getElementById('t-batch-file').onchange = async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    
    const formData = new FormData();
    formData.append('file', file);
    
    document.getElementById('t-batch-import').innerHTML = `${icon('loader', 'sm')} Uploading...`;
    document.getElementById('t-batch-import').disabled = true;
    
    try {
      const res = await api.post(`/bill/batch-process/${userId}`, formData, { raw: true });
      if (!res.ok) throw new Error('Upload failed');
      toast('Statement uploaded. Extracting transactions...', 'info');
      document.getElementById('t-batch-import').innerHTML = `${icon('loader', 'sm')} Processing...`;
    } catch (err) {
      toast('Failed to upload file', 'error');
      document.getElementById('t-batch-import').innerHTML = `${icon('upload', 'sm')} Batch Import`;
      document.getElementById('t-batch-import').disabled = false;
    }
    e.target.value = '';
  };

  const handleBatchOcr = (e) => {
    const detail = e.detail;
    if (detail.type === 'batch-ocr-completed' && detail.userId === userId) {
      document.getElementById('t-batch-import').innerHTML = `${icon('upload', 'sm')} Batch Import`;
      document.getElementById('t-batch-import').disabled = false;
      
      if (detail.status === 'SUCCESS' && Array.isArray(detail.data) && detail.data.length > 0) {
        showBatchModal(userId, detail.data, load);
      } else {
        toast('No transactions found or extraction failed.', 'warning');
      }
    }
  };
  
  window.addEventListener('app-notification', handleBatchOcr);
  
  // Cleanup listener when navigating away
  const observer = new MutationObserver(() => {
    if (!document.contains(container)) {
      window.removeEventListener('app-notification', handleBatchOcr);
      observer.disconnect();
    }
  });
  observer.observe(document.body, { childList: true, subtree: true });

  load();
}

function showBatchModal(userId, transactions, onSuccess) {
  const tableRows = transactions.map((t, idx) => `
    <tr>
      <td><input type="text" class="form-input" id="b-name-${idx}" value="${esc(t.name || '')}"></td>
      <td>
        <select class="form-select" id="b-type-${idx}">
          <option value="EXPENSE" ${t.type === 'EXPENSE' ? 'selected' : ''}>Expense</option>
          <option value="INCOME" ${t.type === 'INCOME' ? 'selected' : ''}>Income</option>
        </select>
      </td>
      <td><input type="number" class="form-input" id="b-amt-${idx}" value="${t.amount || 0}" step="0.01"></td>
      <td><input type="date" class="form-input" id="b-date-${idx}" value="${t.date || ''}"></td>
    </tr>
  `).join('');

  const bodyHtml = `
    <p class="modal-section-label">Review extracted transactions before importing</p>
    <div style="max-height:380px; overflow-y:auto; border:1px solid var(--border); border-radius:var(--radius-sm);">
      <table class="batch-table">
        <thead><tr><th>Name</th><th>Type</th><th>Amount</th><th>Date</th></tr></thead>
        <tbody>${tableRows}</tbody>
      </table>
    </div>
  `;

  openModal(`Import ${transactions.length} Transactions`, bodyHtml, {
    submitLabel: 'Save All',
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
          currency: t.currency || 'INR',
          category: t.type === 'EXPENSE' ? (t.expenseCategory || 'OTHERS') : (t.incomeCategory || 'OTHERS'),
          description: 'Imported from bank statement',
          createdAt: isoDate
        };
      });
      await api.post('/create/bulk', payload);
      toast(`Successfully imported ${payload.length} transactions`, 'success');
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
      tbody.innerHTML = `<tr><td colspan="5">${emptyState('receipt', 'No transactions found', 'Add a transaction or adjust your filters.')}</td></tr>`;
    } else {
      tbody.innerHTML = items.map(t => {
        const isIncome = t.type === 'INCOME';
        const txnIcon  = isIncome ? 'trending-up' : 'trending-down';
        const iconBg   = isIncome ? 'rgba(34,201,147,0.12)' : 'rgba(249,115,22,0.12)';
        const iconClr  = isIncome ? 'var(--accent-g)' : 'var(--accent)';
        return `<tr data-txn='${esc(JSON.stringify(t))}' style="cursor:pointer;">
        <td>
          <div style="display:flex;align-items:center;gap:12px;">
            <div style="width:36px;height:36px;border-radius:50%;background:${iconBg};display:flex;align-items:center;justify-content:center;color:${iconClr};flex-shrink:0">
              ${icon(txnIcon, 'sm')}
            </div>
            <div>
              <strong style="font-size:0.93rem;">${esc(t.name)}</strong>
              ${t.recurring ? `<span class="badge badge-recurring" style="margin-left:6px;">${icon('repeat', 'xs')} ${esc(t.recurringPeriod)}</span>` : ''}
              ${t.receiptUrl ? `<span class="badge badge-info" style="margin-left:6px; font-size:.68rem; padding: 2px 4px; cursor:pointer;" onclick="event.stopPropagation(); window.openReceiptLightbox('${esc(t.receiptUrl)}')" title="View receipt">${icon('document', 'xs')} Receipt</span>` : ''}
              ${t.description ? `<br><small style="color:var(--text-dim)" class="desktop-only">${esc(t.description)}</small>` : ''}
            </div>
          </div>
        </td>
        <td>${typeBadge(t.type)}</td>
        <td><span style="font-size:0.85rem;color:var(--text-dim)">${formatCategory(t.category)}</span></td>
        <td style="text-align:right;font-weight:700;font-size:1.05rem;color:${isIncome ? 'var(--accent-g)' : 'var(--text)'}">
          ${isIncome ? '+' : ''}${formatCurrency(t.amount, t.currency)}
        </td>
        <td style="font-size:.82rem;color:var(--text-dim)">${formatDate(t.createdAt)}</td>
      </tr>`;
      }).join('');
      
      tbody.querySelectorAll('tr').forEach(tr => {
        tr.onclick = () => {
          const t = JSON.parse(tr.dataset.txn);
          openTransactionDetailsModal(t, userId);
        };
      });
    }
    renderPagination();
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="5" style="text-align:center;padding:40px;color:var(--accent)">${esc(err.message)}</td></tr>`;
  }
}

function renderPagination() {
  const el = document.getElementById('t-pagination');
  if (!el || totalPages <= 1) { if (el) el.innerHTML = ''; return; }
  const userId = Auth.getUserId();
  
  let html = `<button class="btn btn-secondary btn-icon" id="t-prev" ${currentPage === 0 ? 'disabled' : ''} aria-label="Previous Page">${icon('chevron-left', 'sm')}</button>`;
  
  // Show max 5 page numbers
  let startPage = Math.max(0, currentPage - 2);
  let endPage = Math.min(totalPages - 1, startPage + 4);
  if (endPage - startPage < 4) startPage = Math.max(0, endPage - 4);
  
  for (let i = startPage; i <= endPage; i++) {
    html += `<button class="${i === currentPage ? 'active' : ''}" data-p="${i}" aria-label="Page ${i + 1}">${i + 1}</button>`;
  }
  
  html += `<button class="btn btn-secondary btn-icon" id="t-next" ${currentPage === totalPages - 1 ? 'disabled' : ''} aria-label="Next Page">${icon('chevron-right', 'sm')}</button>`;
  
  el.innerHTML = html;
  
  if (currentPage > 0) {
    document.getElementById('t-prev').onclick = () => { currentPage--; loadTransactions(userId); };
  }
  if (currentPage < totalPages - 1) {
    document.getElementById('t-next').onclick = () => { currentPage++; loadTransactions(userId); };
  }
  
  el.querySelectorAll('button[data-p]').forEach(b => {
    b.onclick = () => { currentPage = +b.dataset.p; loadTransactions(userId); };
  });
}

async function showModal(userId, existing, onDone) {
  const isEdit = !!(existing && existing.id);
  const hasReceipt = !!(existing?.receiptUrl);

  openModal(isEdit ? 'Edit Transaction' : 'Add Transaction', `
    <form id="txn-form">
      <div class="form-row">
        <div class="form-group" style="flex:2"><label for="m-name">Name</label><input class="form-input" id="m-name" required value="${esc(existing?.name || '')}" placeholder="e.g. Grocery Store"></div>
        <div class="form-group" style="flex:1"><label for="m-amount">Amount</label><input class="form-input" id="m-amount" type="text" inputmode="decimal" required value="${existing?.amount || ''}" placeholder="0.00"></div>
      </div>
      <div class="form-row">
        <div class="form-group"><label for="m-type">Type</label>
          <select class="form-select" id="m-type" required>
            <option value="EXPENSE" ${existing?.type !== 'INCOME' ? 'selected' : ''}>Expense</option>
            <option value="INCOME" ${existing?.type === 'INCOME' ? 'selected' : ''}>Income</option>
          </select>
        </div>
        <div class="form-group"><label for="m-currency">Currency</label><input class="form-input" id="m-currency" maxlength="3" value="${esc(existing?.currency || 'INR')}" required></div>
        <div class="form-group"><label for="m-date">Date</label><input class="form-input" id="m-date" type="date" value="${existing?.createdAt ? existing.createdAt.split('T')[0] : new Date().toISOString().split('T')[0]}"></div>
      </div>
      <div class="form-group" id="m-cat-group">
        <label for="m-cat">Category</label>
        <select class="form-select" id="m-cat" required></select>
      </div>
      <div class="form-group"><label for="m-desc">Note <span style="font-weight:400;text-transform:none;font-size:0.75rem;color:var(--text-muted)">(optional)</span></label><textarea class="form-textarea" id="m-desc" placeholder="Add a note…" style="min-height:64px">${esc(existing?.description || '')}</textarea></div>
      <div class="form-row" style="align-items:center; gap: var(--space-4)">
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
        <label>Receipt / Bill</label>
        <div id="m-receipt-preview" class="receipt-preview-wrap" style="display:${hasReceipt ? 'block' : 'none'}">
          ${hasReceipt ? `<img src="${esc(existing.receiptUrl)}"><button type="button" class="receipt-remove" id="m-receipt-remove" title="Remove">×</button>` : ''}
        </div>
        <label id="m-receipt-upload" class="receipt-upload-zone" style="display:${hasReceipt ? 'none' : 'flex'}" for="m-receipt-file">
          ${icon('upload', 'sm')}
          <span class="upload-label">Click to upload receipt or bill</span>
          <span class="upload-hint">Image or PDF, max 10 MB</span>
          <input type="file" id="m-receipt-file" accept="image/*,application/pdf">
        </label>
        <input type="hidden" id="m-receipt-url" value="${existing?.receiptUrl || ''}">
      </div>
      ${modalActions('Cancel', isEdit ? 'Update' : 'Create')}
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
        submitBtn.innerHTML = '<span class="spinner"></span> Uploading...';
        try {
          const fd = new FormData();
          fd.append('file', fileInput.files[0]);
          const upRes = await api.upload(`/receipt/upload/${userId}`, fd);
          if (upRes && upRes.url) {
             document.getElementById('m-receipt-url').value = upRes.url;
          }
        } catch (e) {
          toast('Failed to upload receipt: ' + e.message, 'error');
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
        recurring,
        recurringPeriod: recurring ? document.getElementById('m-recurring-period').value : null,
        createdAt: isoDate,
        receiptUrl: document.getElementById('m-receipt-url').value || null
      };

      if (isEdit) {
        payload.id = existing.id;
        await api.put('/upsert/update', payload);
        toast('Transaction updated', 'success');
      } else {
        await api.post('/upsert/create', payload);
        toast('Transaction created', 'success');
        // Brief pause so the read replica can catch up before we reload the list
        await new Promise(r => setTimeout(r, 500));
      }
      onDone();
    }
  });

  const rmReceiptBtn = document.getElementById('m-receipt-remove');
  if (rmReceiptBtn) {
    rmReceiptBtn.onclick = () => clearReceiptPreview();
  }

  const receiptFile = document.getElementById('m-receipt-file');
  if (receiptFile) {
    receiptFile.addEventListener('change', () => {
      const file = receiptFile.files[0];
      if (!file) return;
      const preview = document.getElementById('m-receipt-preview');
      const uploadZone = document.getElementById('m-receipt-upload');
      const objectUrl = URL.createObjectURL(file);
      preview.innerHTML = `<img src="${objectUrl}"><button type="button" class="receipt-remove" id="m-receipt-remove" title="Remove">×</button>`;
      preview.style.display = 'block';
      uploadZone.style.display = 'none';
      document.getElementById('m-receipt-url').value = '';
      document.getElementById('m-receipt-remove').onclick = () => {
        receiptFile.value = '';
        URL.revokeObjectURL(objectUrl);
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

  const updateCats = () => {
    const type = document.getElementById('m-type').value;
    const cats = type === 'INCOME' ? INCOME_CATS : EXPENSE_CATS;
    const sel = existing?.type === type ? existing.category : null;
    const catSelect = document.getElementById('m-cat');
    if (catSelect.tomselect) catSelect.tomselect.destroy();
    catSelect.innerHTML = categoryOptions(cats, sel);
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

async function openTransactionDetailsModal(t, userId) {
  try {
    t = await api.get(`/upsert/entries/${t.id}`, { userId });
  } catch (err) {
    toast(err.message, 'error');
    return;
  }

  const amtColor = t.type === 'INCOME' ? 'var(--accent-g)' : 'var(--text)';
  openModal('Transaction Details', `
    <div class="modal-amount-hero">
      <div class="amount-value" style="color:${amtColor}">${t.type === 'INCOME' ? '+' : ''}${formatCurrency(t.amount, t.currency)}</div>
      <div class="amount-name">${esc(t.name)}</div>
      <div class="amount-date">${formatDate(t.createdAt)}</div>
    </div>

    <div class="modal-detail-panel">
      <div class="modal-detail-row"><span class="detail-label">Type</span><span class="detail-value">${typeBadge(t.type)}</span></div>
      <div class="modal-detail-row"><span class="detail-label">Category</span><span class="detail-value">${esc(formatCategory(t.category))}</span></div>
      ${t.recurring ? `<div class="modal-detail-row"><span class="detail-label">Recurring</span><span class="detail-value">${esc(t.recurringPeriod)}</span></div>` : ''}
      ${t.description ? `<div class="modal-detail-row detail-block"><span class="detail-label">Note</span><span class="detail-value">${esc(t.description)}</span></div>` : ''}
      ${t.receiptUrl ? `
        <div class="modal-detail-row detail-block">
          <span class="detail-label">Receipt</span>
          <div style="cursor:pointer;margin-top:4px" onclick="event.stopPropagation();window.openReceiptLightbox('${esc(t.receiptUrl)}')">
            <img src="${esc(t.receiptUrl)}" style="max-width:100%;max-height:180px;border-radius:var(--radius-sm);border:1px solid var(--border);display:block">
          </div>
        </div>` : ''}
    </div>

    <div style="display:flex;gap:10px">
      <button class="btn btn-secondary" id="m-edit-btn" style="flex:1">${icon('edit', 'sm')} Edit</button>
      <button class="btn btn-danger" id="m-del-btn" style="flex:1">${icon('trash', 'sm')} Delete</button>
    </div>
  `);

  document.getElementById('m-edit-btn').onclick = () => {
    document.querySelector('.modal-overlay').click();
    setTimeout(() => editTransaction(userId, t.id), 50);
  };
  document.getElementById('m-del-btn').onclick = async () => {
    if (!(await confirmModal('Delete Transaction', 'Are you sure you want to delete this transaction?', 'Delete'))) return;
    try {
      await api.delete(`/upsert/delete/${t.id}`, { userId });
      toast('Transaction deleted', 'success');
      document.querySelector('.modal-overlay').click();
      loadTransactions(userId);
    } catch (err) { toast(err.message, 'error'); }
  };
}
