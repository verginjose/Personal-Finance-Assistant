import {api, Auth, toast} from '../utils/api.js';
import {icon} from '../utils/icons.js';
import {esc, EXPENSE_CATS, INCOME_CATS, pageHeader, setupCategorySearch} from '../utils/ui.js';
import {navigateTo} from '../app.js';

export async function renderBillScanner(container) {
  const userId = Auth.getUserId();
  container.innerHTML = `
    ${pageHeader('Bill Scanner', 'Upload a receipt and let AI extract the details')}
    <div class="card fade-up" style="max-width:680px">
      <div class="drop-zone" id="bs-drop" role="button" tabindex="0" aria-label="Upload receipt">
        <div class="drop-icon">${icon('document', 'lg')}</div>
        <p>Drag & drop a receipt image or PDF here</p>
        <p style="margin-top:6px;font-size:.78rem;color:var(--text-muted)">JPEG, PNG, PDF — max 10 MB</p>
        <div class="file-name" id="bs-fname"></div>
      </div>
      <input type="file" id="bs-file" accept="image/*,application/pdf" hidden>
      <button class="btn btn-primary" id="bs-upload" style="width:100%;margin-top:20px" disabled>
        ${icon('scan', 'sm')} Process Receipt
      </button>
    </div>
    <div class="card fade-up" id="bs-result" style="max-width:680px;margin-top:20px;display:none">
      <div class="card-header"><h3>Extracted Data</h3><span class="badge badge-info">OCR Preview — edit before saving</span></div>
      <form id="bs-preview-form" style="display:grid;gap:10px"></form>
    </div>`;

  let groupsCache = [];
  try {
    groupsCache = await api.get('/upsert/groups', { userId });
  } catch (e) { console.error('Failed to load groups', e); }

  const dropZone = document.getElementById('bs-drop');
  if (!dropZone) return;
  
  const fileInput = document.getElementById('bs-file');
  const uploadBtn = document.getElementById('bs-upload');
  let selectedFile = null;

  dropZone.onclick = () => fileInput.click();
  dropZone.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fileInput.click(); } };
  dropZone.ondragover = (e) => { e.preventDefault(); dropZone.classList.add('drag-over'); };
  dropZone.ondragleave = () => dropZone.classList.remove('drag-over');
  dropZone.ondrop = (e) => { e.preventDefault(); dropZone.classList.remove('drag-over'); pickFile(e.dataTransfer.files[0]); };
  fileInput.onchange = () => pickFile(fileInput.files[0]);

  function pickFile(f) {
    if (!f) return;
    if (f.size > 10 * 1024 * 1024) { toast('File exceeds 10 MB limit', 'error'); return; }
    selectedFile = f;
    document.getElementById('bs-fname').textContent = f.name;
    uploadBtn.disabled = false;
  }

  function normalizeCategory(raw, type) {
    const fallback = type === 'INCOME' ? 'OTHERS' : 'OTHERS';
    let cat = (raw || fallback).toUpperCase().replace(/ /g, '_').replace(/-/g, '_');
    if (cat === 'OTHER') cat = 'OTHERS';
    const allowed = type === 'INCOME' ? INCOME_CATS : EXPENSE_CATS;
    return allowed.includes(cat) ? cat : 'OTHERS';
  }

  function renderPreviewForm(result) {
    const type = (result.type || 'EXPENSE').toUpperCase();
    const category = normalizeCategory(
      type === 'INCOME' ? result.incomeCategory : result.expenseCategory,
      type
    );
    const amount = parseFloat((result.amount || '0').toString().replace(/[^0-9.]/g, '')) || 0;
    let currency = (result.currency || 'INR').trim().toUpperCase();
    if (currency.length !== 3) {
      const map = { '₹': 'INR', RS: 'INR', 'RS.': 'INR', $: 'USD', '€': 'EUR', '£': 'GBP' };
      currency = map[currency] || 'INR';
    }

    const form = document.getElementById('bs-preview-form');
    form.innerHTML = `
      <div class="form-group">
        <label for="bs-name">Name</label>
        <input class="form-input" id="bs-name" value="${escapeAttr(result.name || 'OCR Receipt')}" maxlength="100" required>
      </div>
      <div class="form-row">
        <div class="form-group" style="flex:1;">
          <label for="bs-amount">Amount</label>
          <input class="form-input" id="bs-amount" type="text" inputmode="decimal" pattern="^\\d+(\\.\\d{1,2})?$" title="Enter a valid amount" value="${amount}" required>
        </div>
        <div class="form-group">
          <label for="bs-currency">Currency</label>
          <input class="form-input" id="bs-currency" value="${escapeAttr(currency)}" maxlength="3" required>
        </div>
      </div>
      <div class="form-row">
        <div class="form-group">
          <label for="bs-date">Date</label>
          <input class="form-input" id="bs-date" type="date" value="${escapeAttr(result.date || '')}">
        </div>
        <div class="form-group" style="display:flex;align-items:center;padding-top:28px;gap:8px">
          <input type="checkbox" id="bs-recurring" ${result.recurring ? 'checked' : ''} style="width:18px;height:18px;accent-color:var(--primary)">
          <label for="bs-recurring" style="margin:0;cursor:pointer">Is Recurring?</label>
        </div>
      </div>
      <div class="form-row">
        <div class="form-group">
          <label for="bs-type">Type</label>
          <select class="form-select" id="bs-type">
            <option value="EXPENSE" ${type === 'EXPENSE' ? 'selected' : ''}>Expense</option>
            <option value="INCOME" ${type === 'INCOME' ? 'selected' : ''}>Income</option>
          </select>
        </div>
        <div class="form-group" id="bs-cat-group">
          <label for="bs-category">Category</label>
          <input type="text" id="bs-cat-search" class="form-input category-search" placeholder="Search category..." style="margin-bottom: 8px;">
          <select class="form-select category-select" id="bs-category"></select>
        </div>
      </div>
      <div class="form-group">
        <label for="bs-desc">Description</label>
        <textarea class="form-textarea" id="bs-desc" maxlength="500">${escapeAttr(result.description || 'Processed from bill scanner')}</textarea>
      </div>
      <div class="form-group" style="padding-top:10px;border-top:1px solid var(--border)">
        <label for="bs-dest">Save Destination</label>
        <select class="form-select" id="bs-dest">
          <option value="PERSONAL">Personal Transaction</option>
          ${groupsCache.map(g => `<option value="${g.id}">Group: ${esc(g.name)}</option>`).join('')}
        </select>
        <p style="font-size:0.8rem;color:var(--text-dim);margin-top:4px">Group expenses will default to an EQUAL split.</p>
      </div>
      <button type="submit" class="btn btn-success" style="width:100%;margin-top:4px" id="bs-save">Save Transaction</button>`;

    const typeSelect = document.getElementById('bs-type');
    const catSelect = document.getElementById('bs-category');

    function fillCategories() {
      const t = typeSelect.value;
      const cats = t === 'INCOME' ? INCOME_CATS : EXPENSE_CATS;
      const current = catSelect.value || category;
      catSelect.innerHTML = cats.map(c =>
        `<option value="${c}" ${c === current || (current === category && c === category) ? 'selected' : ''}>${c.replace(/_/g, ' ')}</option>`
      ).join('');
    }

    typeSelect.onchange = fillCategories;
    fillCategories();
    setupCategorySearch('bs-cat-search', 'bs-category');
    if (window.TomSelect) {
      new TomSelect('#bs-type', { create: false, controlInput: null });
      new TomSelect('#bs-dest', { create: false, controlInput: null, sortField: { field: 'text', direction: 'asc' }});
    }
    if (window.flatpickr) {
      flatpickr('#bs-date', { dateFormat: 'Y-m-d', altInput: true, altFormat: 'F j, Y', disableMobile: true });
    }
    form.onsubmit = (e) => { e.preventDefault(); saveTransaction(userId); };
  }

  uploadBtn.onclick = async () => {
    if (!selectedFile) return;
    uploadBtn.disabled = true;
    uploadBtn.innerHTML = '<span class="spinner"></span> Processing…';
    try {
      const fd = new FormData();
      fd.append('file', selectedFile);
      await api.upload(`/bill/process/${userId}`, fd);
      toast('Bill uploaded. AI is processing...', 'info');
    } catch (err) {
      toast('OCR upload failed: ' + err.message, 'error');
      uploadBtn.disabled = false;
      uploadBtn.innerHTML = `${icon('scan', 'sm')} Process Receipt`;
    }
  };

  if (window._bsOcrListener) {
    window.removeEventListener('ocr-completed', window._bsOcrListener);
  }
  window._bsOcrListener = (e) => {
    const detail = e.detail;
    if (detail.status === 'SUCCESS' && detail.data) {
      document.getElementById('bs-result').style.display = 'block';
      renderPreviewForm(detail.data);
    }
    uploadBtn.disabled = !selectedFile;
    uploadBtn.innerHTML = `${icon('scan', 'sm')} Process Receipt`;
  };
  window.addEventListener('ocr-completed', window._bsOcrListener);
}

function escapeAttr(s) {
  return String(s ?? '').replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
}

async function saveTransaction(userId) {
  const saveBtn = document.getElementById('bs-save');
  saveBtn.disabled = true;
  saveBtn.innerHTML = '<span class="spinner"></span> Saving…';
  try {
    const type = document.getElementById('bs-type').value;
    const amount = parseFloat(document.getElementById('bs-amount').value) || 0;
    let currency = document.getElementById('bs-currency').value.trim().toUpperCase();
    if (currency.length !== 3) currency = 'INR';
    
    const dateVal = document.getElementById('bs-date').value;
    const isRecurring = document.getElementById('bs-recurring').checked;
    const dest = document.getElementById('bs-dest').value;
    const description = document.getElementById('bs-desc').value.trim() || 'Processed from bill scanner';
    const name = document.getElementById('bs-name').value.trim() || 'OCR Receipt';

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

      if (dest === 'PERSONAL') {
        const payload = {
          userId, name, amount, type, currency, description,
          category: document.getElementById('bs-category').value,
          recurring: isRecurring
        };
        if (isoDate) payload.createdAt = isoDate;
      
      await api.post('/upsert/create', payload);
      toast('Personal transaction saved!', 'success');
      document.getElementById('bs-result').style.display = 'none';
      document.getElementById('bs-fname').textContent = '';
    } else {
      localStorage.setItem('pfa_pending_scan', JSON.stringify({
        groupId: dest,
        amount,
        currency,
        description: name + (description ? ' - ' + description : ''),
        date: dateVal
      }));
      navigateTo('split');
      toast('Forwarded to group split page', 'info');
    }
  } catch (err) {
    toast('Save failed: ' + err.message, 'error');
  } finally {
    saveBtn.disabled = false;
    saveBtn.textContent = 'Save as Transaction';
  }
}
