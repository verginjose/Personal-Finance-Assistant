import {api, Auth, toast} from '../utils/api.js?v=2026110302';
import { icon } from '../utils/icons.js?v=2026110302';
import {esc, EXPENSE_CATS, INCOME_CATS, pageHeader, setupCategorySearch} from '../utils/ui.js?v=2026110302';
import {navigateTo} from '../app.js?v=2026110302';

export async function renderBillScanner(container) {
  const userId = Auth.getUserId();
  container.innerHTML = `
    ${pageHeader('Bill Scanner', 'Upload a receipt and let AI extract the details')}
    <div class="scanner-container fade-up">
      <div class="card scanner-upload-card">
        <span class="ocr-eyebrow">${icon('scan', 'xs')} Step 1 · Upload</span>
        <h3 class="scanner-title">Scan a receipt</h3>
        <p class="scanner-sub">We'll read the merchant, amount, date and category for you.</p>
        <div class="drop-zone" id="bs-drop" role="button" tabindex="0" aria-label="Upload receipt">
          <div class="drop-icon">${icon('document', 'lg')}</div>
          <p>Drag &amp; drop a receipt image or PDF here</p>
          <p style="margin-top:6px;font-size:.78rem;color:var(--text-muted)">JPEG, PNG, PDF — max 10 MB</p>
          <div class="file-name" id="bs-fname"></div>
        </div>
        <input type="file" id="bs-file" accept="image/*,application/pdf" hidden>
        <button class="btn btn-primary" id="bs-upload" style="width:100%;margin-top:20px" disabled>
          ${icon('scan', 'sm')} Process Receipt
        </button>
      </div>
      <div class="card ocr-result-card" id="bs-result" style="display:none; height:fit-content;">
        <div class="ocr-result-head">
          <div>
            <span class="ocr-eyebrow">${icon('sparkles', 'xs')} Step 2 · Review</span>
            <h3>Extracted data</h3>
          </div>
          <span class="badge badge-info">AI preview — edit before saving</span>
        </div>
        <form id="bs-preview-form" class="ocr-form"></form>
      </div>
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
    const dateDisplay = (() => {
      if (!result.date) return 'No date detected';
      const d = new Date(`${result.date}T00:00:00`);
      return Number.isNaN(d.getTime())
        ? result.date
        : d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
    })();

    form.innerHTML = `
      <div class="ocr-summary">
        <div class="ocr-summary-main">
          <span class="ocr-summary-label">Detected amount</span>
          <div class="ocr-amount"><span class="ocr-amount-currency">${escapeAttr(currency)}</span><span id="bs-amount-preview">${amount.toLocaleString('en-IN', { minimumFractionDigits: 0, maximumFractionDigits: 2 })}</span></div>
          <div class="ocr-summary-name" id="bs-name-preview">${esc(result.name || 'OCR Receipt')}</div>
        </div>
        <div class="ocr-summary-meta">
          <span class="ocr-type-badge ${type === 'INCOME' ? 'is-income' : 'is-expense'}">${type === 'INCOME' ? icon('trending-up', 'xs') : icon('trending-down', 'xs')} ${type === 'INCOME' ? 'Income' : 'Expense'}</span>
          <span class="ocr-date">${icon('calendar', 'xs')} ${esc(dateDisplay)}</span>
        </div>
      </div>

      <section class="ocr-section">
        <div class="ocr-section-head">
          <span class="ocr-step">01</span>
          <div><h4>Receipt details</h4><p>Check what was bought, how much, and when.</p></div>
        </div>
        <div class="ocr-field-grid">
          <div class="ocr-field ocr-field--full">
            <label for="bs-name">Merchant / name</label>
            <input class="form-input" id="bs-name" value="${escapeAttr(result.name || 'OCR Receipt')}" maxlength="100" required>
          </div>
          <div class="ocr-field">
            <label for="bs-amount">Amount</label>
            <div class="ocr-input-affix"><span>${escapeAttr(currency)}</span><input class="form-input" id="bs-amount" type="text" inputmode="decimal" pattern="^\\d+(\\.\\d{1,2})?$" title="Enter a valid amount" value="${amount}" required></div>
          </div>
          <div class="ocr-field">
            <label for="bs-currency">Currency</label>
            <input class="form-input" id="bs-currency" value="${escapeAttr(currency)}" maxlength="3" required>
          </div>
          <div class="ocr-field">
            <label for="bs-date">Date</label>
            <input class="form-input" id="bs-date" type="date" value="${escapeAttr(result.date || '')}">
          </div>
          <div class="ocr-field">
            <span class="ocr-field-label">Repeat monthly</span>
            <label class="ocr-switch">
              <input type="checkbox" id="bs-recurring" ${result.recurring ? 'checked' : ''}>
              <span class="ocr-switch-track" aria-hidden="true"></span>
              <span>Is recurring</span>
            </label>
          </div>
        </div>
      </section>

      <section class="ocr-section">
        <div class="ocr-section-head">
          <span class="ocr-step">02</span>
          <div><h4>Classification</h4><p>How this entry should be grouped in your reports.</p></div>
        </div>
        <div class="ocr-field-grid">
          <div class="ocr-field">
            <label for="bs-type">Type</label>
            <select class="form-select" id="bs-type">
              <option value="EXPENSE" ${type === 'EXPENSE' ? 'selected' : ''}>Expense</option>
              <option value="INCOME" ${type === 'INCOME' ? 'selected' : ''}>Income</option>
            </select>
          </div>
          <div class="ocr-field" id="bs-cat-group">
            <label for="bs-category">Category</label>
            <input type="text" id="bs-cat-search" class="form-input category-search" placeholder="Search category..." style="display:none;">
            <select class="form-select category-select" id="bs-category"></select>
          </div>
        </div>
      </section>

      <section class="ocr-section">
        <div class="ocr-section-head">
          <span class="ocr-step">03</span>
          <div><h4>Details &amp; destination</h4><p>Add context and choose where this entry lives.</p></div>
        </div>
        <div class="ocr-field-grid">
          <div class="ocr-field ocr-field--full">
            <label for="bs-desc">Description</label>
            <textarea class="form-textarea" id="bs-desc" maxlength="500" placeholder="Optional note about this transaction">${escapeAttr(result.description || 'Processed from bill scanner')}</textarea>
          </div>
          <div class="ocr-field ocr-field--full">
            <label for="bs-dest">Save destination</label>
            <select class="form-select" id="bs-dest">
              <option value="PERSONAL">Personal transaction</option>
              ${groupsCache.map(g => `<option value="${g.id}">Group: ${esc(g.name)}</option>`).join('')}
            </select>
            <p class="ocr-help">Group expenses will default to an EQUAL split.</p>
          </div>
        </div>
      </section>

      <div class="ocr-save-bar">
        <div class="ocr-save-hint">${icon('shield', 'xs')} Nothing is saved until you confirm.</div>
        <button type="submit" class="btn btn-success" id="bs-save">${icon('check', 'sm')} Save transaction</button>
      </div>`;

    const typeSelect = document.getElementById('bs-type');
    const catSelect = document.getElementById('bs-category');
    const amountInput = document.getElementById('bs-amount');
    const currencyInput = document.getElementById('bs-currency');
    const nameInput = document.getElementById('bs-name');
    const amountPreview = document.getElementById('bs-amount-preview');
    const namePreview = document.getElementById('bs-name-preview');

    // The summary mirrors the editable fields so the review panel stays honest.
    const syncSummary = () => {
      const value = parseFloat((amountInput.value || '0').toString().replace(/[^0-9.]/g, '')) || 0;
      amountPreview.textContent = value.toLocaleString('en-IN', { minimumFractionDigits: 0, maximumFractionDigits: 2 });
      namePreview.textContent = nameInput.value.trim() || 'OCR Receipt';
    };
    [amountInput, currencyInput, nameInput].forEach(el => el.addEventListener('input', syncSummary));

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
      const res = await api.upload(`/bill/process/${userId}`, fd);
      if (res && res.url) {
        window._bsReceiptUrl = res.url;
      }
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
        localStorage.setItem('pfa_pending_scan', JSON.stringify({
          groupId: 'PERSONAL',
          amount,
          currency,
          name,
          type,
          category: document.getElementById('bs-category').value,
          description,
          date: dateVal,
          recurring: isRecurring,
          receiptUrl: window._bsReceiptUrl || null
        }));
        navigateTo('transactions');
        toast('Forwarded to personal transactions page', 'info');
      } else {
      localStorage.setItem('pfa_pending_scan', JSON.stringify({
        groupId: dest,
        amount,
        currency,
        description: name + (description ? ' - ' + description : ''),
        date: dateVal,
        receiptUrl: window._bsReceiptUrl || null
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
