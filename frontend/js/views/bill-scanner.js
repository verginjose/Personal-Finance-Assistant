import { api, Auth, toast } from '../utils/api.js';
import { icon, categoryIcon } from '../utils/icons.js';
import {
  esc, pageHeader, EXPENSE_CATS, INCOME_CATS, categoryOptions, currencyOptionHtml,
  formatMoney, formatCategory, openReceiptLightbox,
} from '../utils/ui.js';

/** Same breakpoint as the mobile shell in style.css. */
const isMobileViewport = () => typeof window !== 'undefined'
  && typeof window.matchMedia === 'function'
  && window.matchMedia('(max-width: 767px)').matches;

export async function renderBillScanner(container) {
  const userId = Auth.getUserId();
  const defaultCurrency = Auth.getDefaultCurrency();

  container.innerHTML = `
    ${pageHeader('Bill scanner', '')}
    <div id="bs-stage">
      <section class="scanner-single" id="bs-upload-stage" aria-labelledby="bs-scan-title">
        <h2 id="bs-scan-title">Add a bill from a photo</h2>
        <p class="pane-sub">We'll fill in the merchant, amount and date. You can edit everything before saving.</p>
        <div class="drop-zone" id="bs-drop" role="button" tabindex="0" aria-label="Add a bill from a photo">
          <span class="receipt-sample" aria-hidden="true">
            <span class="receipt-sample-line"></span>
            <span class="receipt-sample-line"></span>
            <span class="receipt-sample-line"></span>
            <span class="receipt-sample-line"></span>
          </span>
          <p>Drop a bill here, or choose a file</p>
          <p class="drop-help">A photo or a PDF, up to 10 MB. Amounts are shown in ${esc(defaultCurrency)}.</p>
          <div class="file-name" id="bs-fname"></div>
        </div>
        <input type="file" id="bs-file" accept="image/*,application/pdf" hidden>
        <p class="read-reason" id="bs-reason"></p>

        <ol class="scan-steps">
          <li><strong>Photograph the bill.</strong> A flat, well-lit photo reads best.</li>
          <li><strong>Check the details.</strong> Merchant, amount, date and category stay editable.</li>
          <li><strong>Save once.</strong> Nothing is added to your ledger until you confirm.</li>
        </ol>
      </section>

      <div class="scanner-grid" id="bs-result" hidden>
        <section class="scanner-pane">
          <div class="scanner-preview" id="bs-preview"></div>
        </section>
        <section class="scanner-pane">
          <div class="ocr-result-head">
            <h2>Check the details</h2>
            <p class="pane-sub">Anything the scan got wrong is editable before you save.</p>
          </div>
          <form id="bs-preview-form" class="ocr-form"></form>
        </section>
      </div>
    </div>`;

  let groupsCache = [];
  try {
    groupsCache = await api.get('/upsert/groups', { userId });
  } catch { /* groups are optional */ }

  const dropZone = document.getElementById('bs-drop');
  if (!dropZone) return;

  const fileInput = document.getElementById('bs-file');
  let selectedFile = null;
  let previewUrl = null;

  dropZone.onclick = () => fileInput.click();
  dropZone.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fileInput.click(); } };
  dropZone.ondragover = (e) => { e.preventDefault(); dropZone.classList.add('drag-over'); };
  dropZone.ondragleave = () => dropZone.classList.remove('drag-over');
  dropZone.ondrop = (e) => { e.preventDefault(); dropZone.classList.remove('drag-over'); pickFile(e.dataTransfer.files[0]); };
  fileInput.onchange = () => pickFile(fileInput.files[0]);

  function pickFile(f) {
    if (!f) return;
    if (f.size > 10 * 1024 * 1024) {
      toast('That file is over 10 MB. Choose a smaller photo or a PDF.', 'error');
      return;
    }
    selectedFile = f;
    document.getElementById('bs-fname').textContent = f.name;
    const reason = document.getElementById('bs-reason');
    if (reason) reason.textContent = 'Reading…';
    readReceipt();
  }

  function renderPreview(file) {
    const preview = document.getElementById('bs-preview');
    if (!preview) return;
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    const isPdf = file.type === 'application/pdf';
    if (isPdf) {
      previewUrl = null;
      preview.innerHTML = `<div class="preview-pdf">${icon('document', 'lg')}<span>${esc(file.name)}</span><small>PDF document</small></div>`;
      return;
    }
    previewUrl = URL.createObjectURL(file);
    preview.innerHTML = `<button type="button" style="padding:0;border:0;background:none;width:100%" id="bs-zoom" aria-label="Zoom receipt"><img src="${previewUrl}" alt="Receipt preview"></button>`;
    document.getElementById('bs-zoom').onclick = () => openReceiptLightbox(previewUrl);
  }

  async function readReceipt() {
    if (!selectedFile) return;
    const drop = document.getElementById('bs-drop');
    if (drop) drop.classList.add('drag-over');
    const reason = document.getElementById('bs-reason');
    if (reason) reason.textContent = 'Reading the bill…';

    try {
      const fd = new FormData();
      fd.append('file', selectedFile);
      const target = encodeURIComponent(Auth.getDefaultCurrency());
      const res = await api.upload(`/bill/process/${userId}?targetCurrency=${target}`, fd);
      if (res && res.url) window._bsReceiptUrl = res.url;
      // The gateway returns the id of this upload; the OCR callback echoes it
      // back, so a stale notification cannot overwrite a newer scan.
      window._bsReceiptId = (res && res.receiptId) || null;
      // Move to the review layout immediately so the receipt is visible while
      // the extractor works; the fields fill in when the event arrives.
      const uploadStage = document.getElementById('bs-upload-stage');
      const resultStage = document.getElementById('bs-result');
      if (uploadStage && resultStage) {
        uploadStage.hidden = true;
        resultStage.hidden = false;
        resultStage.className = 'scanner-grid';
      }
      renderPreview(selectedFile);
      const form = document.getElementById('bs-preview-form');
      if (form) {
        form.innerHTML = `
          <div class="ocr-summary"><div class="ocr-summary-main">
            <span class="ocr-summary-label">Reading</span>
            <div class="ocr-amount" style="color:var(--text-2)">…</div>
            <div class="ocr-summary-name">${esc(selectedFile.name)}</div>
          </div></div>
          <div class="advisor-skeleton">
            <div class="skeleton advisor-skeleton-line advisor-skeleton-line--short"></div>
            <div class="skeleton advisor-skeleton-line"></div>
            <div class="skeleton advisor-skeleton-line"></div>
          </div>
          <p class="ocr-save-hint">${icon('sparkles', 'xs')} Reading the merchant, amount, date and category.</p>`;
      }
      if (reason) reason.textContent = 'Finding the merchant, amount and date…';
      toast('Receipt uploaded. Reading it now…', 'info');
    } catch (err) {
      if (drop) drop.classList.remove('drag-over');
      if (reason) reason.textContent = 'That file could not be read. Try a clearer photo or a PDF.';
      toast('Could not read that receipt: ' + err.message, 'error');
    }
  }

  function confidenceFor(field, result) {
    const c = result?.confidence;
    if (c && typeof c === 'object' && c[field] != null) return Number(c[field]);
    if (result?.[`${field}Confidence`] != null) return Number(result[`${field}Confidence`]);
    return null;
  }

  function confidenceHtml(field, result) {
    const value = confidenceFor(field, result);
    if (value == null || Number.isNaN(value)) return '';
    const high = value >= 0.8;
    return `<span class="confidence ${high ? 'is-high' : 'is-low'}" title="${high ? 'High confidence' : 'Check this value'}">${high ? icon('check', 'xs') + ' High confidence' : icon('alert-triangle', 'xs') + ' Check this'}</span>`;
  }

  function normalizeCategory(raw, type) {
    let cat = (raw || 'OTHERS').toUpperCase().replace(/ /g, '_').replace(/-/g, '_');
    if (cat === 'OTHER') cat = 'OTHERS';
    const allowed = type === 'INCOME' ? INCOME_CATS : EXPENSE_CATS;
    return allowed.includes(cat) ? cat : 'OTHERS';
  }

  function renderPreviewForm(result) {
    const type = (result.type || 'EXPENSE').toUpperCase();
    const category = normalizeCategory(type === 'INCOME' ? result.incomeCategory : result.expenseCategory, type);
    const amountOriginal = parseFloat((result.amount || '0').toString().replace(/[^0-9.]/g, '')) || 0;
    let originalCurrency = (result.currency || Auth.getDefaultCurrency()).trim().toUpperCase();
    if (originalCurrency.length !== 3) {
      const map = { '₹': 'INR', RS: 'INR', 'RS.': 'INR', $: 'USD', '€': 'EUR', '£': 'GBP' };
      originalCurrency = map[originalCurrency] || Auth.getDefaultCurrency();
    }
    const currency = (result.defaultCurrency || defaultCurrency || originalCurrency).toUpperCase();
    const converted = Number(result.amountDefault);
    const amount = Number.isFinite(converted) && converted > 0 ? converted : amountOriginal;
    const wasConverted = currency !== originalCurrency;

    const form = document.getElementById('bs-preview-form');
    const now = new Date();
    const todayIso = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
    const dateDisplay = result.date
      ? new Date(`${result.date}T00:00:00`).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })
      : 'No date detected';

    form.innerHTML = `
      <div class="ocr-summary">
        <div class="ocr-summary-main">
          <span class="ocr-summary-label">Detected amount</span>
          <div class="ocr-amount"><span class="ocr-amount-currency">${esc(currency)}</span><span id="bs-amount-preview">${formatMoney(amount, currency).replace(/^[^\d−-]*/, '')}</span></div>
          <div class="ocr-summary-name" id="bs-name-preview">${esc(result.name || 'Receipt')}</div>
          ${wasConverted ? `<div class="ocr-conversion">${icon('refresh', 'xs')} Converted to ${esc(currency)} · original ${formatMoney(amountOriginal, originalCurrency)}</div>` : ''}
        </div>
        <div class="ocr-summary-meta">
          <span class="ocr-type-badge ${type === 'INCOME' ? 'amount--pos' : ''}">${type === 'INCOME' ? 'Income' : 'Expense'}</span>
          <span class="ocr-date">${icon('calendar', 'xs')} ${esc(dateDisplay)}</span>
        </div>
      </div>

      <div class="ocr-field-grid">
        <div class="ocr-field ocr-field--full has-fill" style="animation-delay:0ms">
          <label for="bs-name">Merchant</label>
          <input class="form-input" id="bs-name" value="${escapeAttr(result.name || 'Receipt')}" maxlength="100" required>
          ${confidenceHtml('name', result)}
        </div>
        <div class="ocr-field has-fill" style="animation-delay:40ms">
          <label for="bs-amount">Amount</label>
          <div class="ocr-input-affix"><span>${escapeAttr(currency)}</span><input class="form-input" id="bs-amount" type="text" inputmode="decimal" pattern="^\\d+(\\.\\d{1,2})?$" title="Enter a valid amount" value="${amount}" required></div>
          ${confidenceHtml('amount', result)}
        </div>
        <div class="ocr-field has-fill" style="animation-delay:80ms">
          <label for="bs-date">Date</label>
          <input class="form-input" id="bs-date" type="date" value="${escapeAttr(result.date || todayIso)}">
          ${confidenceHtml('date', result)}
        </div>
        <div class="ocr-field has-fill" style="animation-delay:120ms">
          <label for="bs-category">Category</label>
          <select class="form-select" id="bs-category"></select>
          ${confidenceHtml('category', result)}
        </div>
        <div class="ocr-field has-fill" style="animation-delay:160ms">
          <label for="bs-type">Type</label>
          <select class="form-select" id="bs-type">
            <option value="EXPENSE" ${type === 'EXPENSE' ? 'selected' : ''}>Expense</option>
            <option value="INCOME" ${type === 'INCOME' ? 'selected' : ''}>Income</option>
          </select>
        </div>
        <div class="ocr-field ocr-field--full">
          <label for="bs-desc">Note (optional)</label>
          <textarea class="form-textarea" id="bs-desc" maxlength="500" placeholder="Add context for this entry">${escapeAttr(result.description || '')}</textarea>
        </div>
        <div class="ocr-field">
          <span class="ocr-field-label">Recurring</span>
          <label class="ocr-switch"><input type="checkbox" id="bs-recurring" ${result.recurring ? 'checked' : ''}><span>Repeats every month</span></label>
        </div>
        <div class="ocr-field">
          <label for="bs-dest">Save to</label>
          <select class="form-select" id="bs-dest">
            <option value="PERSONAL">Personal transactions</option>
            ${groupsCache.map(g => `<option value="${g.id}">Group: ${esc(g.name)}</option>`).join('')}
          </select>
        </div>
      </div>

      <div class="ocr-save-bar">
        <div class="ocr-save-hint">${icon('shield', 'xs')} Nothing is saved until you confirm.</div>
        <div style="display:flex;gap:8px">
          <button type="button" class="btn btn-secondary" id="bs-scan-another">Scan another</button>
          <button type="submit" class="btn btn-primary" id="bs-save">Save transaction</button>
        </div>
      </div>`;

    const typeSelect = document.getElementById('bs-type');
    const catSelect = document.getElementById('bs-category');
    const amountInput = document.getElementById('bs-amount');
    const nameInput = document.getElementById('bs-name');
    const amountPreview = document.getElementById('bs-amount-preview');
    const namePreview = document.getElementById('bs-name-preview');

    const fillCategories = () => {
      const t = typeSelect.value;
      const cats = t === 'INCOME' ? INCOME_CATS : EXPENSE_CATS;
      const current = cats.includes(category) ? category : 'OTHERS';
      catSelect.innerHTML = categoryOptions(cats, current);
    };
    typeSelect.onchange = fillCategories;
    fillCategories();

    // On phones use the native controls: a native select opens the OS picker
    // instead of the keyboard, and a native date input shows the date and its
    // picker icon. TomSelect and flatpickr are desktop-only here.
    if (window.TomSelect && !isMobileViewport()) {
      ['#bs-category', '#bs-type', '#bs-dest'].forEach(sel => {
        const el = document.querySelector(sel);
        if (el && !el.tomselect) new TomSelect(sel, { create: false, maxOptions: 200 });
      });
    }
    if (window.flatpickr && !isMobileViewport()) {
      flatpickr('#bs-date', { dateFormat: 'Y-m-d', altInput: true, altFormat: 'j M Y' });
    }

    const syncSummary = () => {
      const value = parseFloat((amountInput.value || '0').toString().replace(/[^0-9.]/g, '')) || 0;
      if (amountPreview) amountPreview.textContent = formatMoney(value, currency).replace(/^[^\d−-]*/, '');
      if (namePreview) namePreview.textContent = nameInput.value.trim() || 'Receipt';
    };
    [amountInput, nameInput].forEach(el => el.addEventListener('input', syncSummary));

    document.getElementById('bs-scan-another').onclick = () => {
      resetScanner();
      fileInput.click();
    };

    form.onsubmit = (e) => { e.preventDefault(); saveTransaction(userId); };
  }

  function showError(message) {
    const result = document.getElementById('bs-result');
    const upload = document.getElementById('bs-upload-stage');
    if (!result) return;
    upload.hidden = true;
    result.hidden = false;
    result.className = 'scanner-single';
    result.innerHTML = `
      <div class="empty-state">
        <div class="empty-icon">${icon('alert-triangle', 'lg')}</div>
        <h3>${esc(message)}</h3>
        <p>Check the photo is straight and well lit, or enter the details manually.</p>
        <div class="empty-action" style="display:flex;gap:8px;justify-content:center">
          <button type="button" class="btn btn-secondary" id="bs-try-again">Scan another</button>
          <button type="button" class="btn btn-primary" id="bs-manual">Enter manually</button>
        </div>
      </div>`;
    document.getElementById('bs-try-again').onclick = () => { resetScanner(); fileInput.click(); };
    document.getElementById('bs-manual').onclick = () => showReview({ name: '', amount: '', date: '', type: 'EXPENSE' });
  }

  function showReview(result) {
    const resultEl = document.getElementById('bs-result');
    const upload = document.getElementById('bs-upload-stage');
    if (!resultEl) return;
    upload.hidden = true;
    resultEl.hidden = false;
    resultEl.className = 'scanner-grid';
    if (!document.getElementById('bs-preview').children.length && selectedFile) renderPreview(selectedFile);
    renderPreviewForm(result);
  }

  function resetScanner() {
    selectedFile = null;
    if (previewUrl) { URL.revokeObjectURL(previewUrl); previewUrl = null; }
    const upload = document.getElementById('bs-upload-stage');
    const result = document.getElementById('bs-result');
    if (upload) upload.hidden = false;
    if (result) {
      result.hidden = true;
      result.className = 'scanner-grid';
      result.innerHTML = '<section class="scanner-pane"><div class="scanner-preview" id="bs-preview"></div></section><section class="scanner-pane"><div class="ocr-result-head"><h2>Check the details</h2><p class="pane-sub">Anything the scan got wrong is editable before you save.</p></div><form id="bs-preview-form" class="ocr-form"></form></section>';
    }
    const fname = document.getElementById('bs-fname');
    if (fname) fname.textContent = '';
    const reason = document.getElementById('bs-reason');
    if (reason) reason.textContent = '';
    document.getElementById('bs-drop')?.classList.remove('drag-over');
    fileInput.value = '';
    window._bsReceiptUrl = null;
    window._bsReceiptId = null;
  }

  if (window._bsOcrListener) window.removeEventListener('ocr-completed', window._bsOcrListener);
  window._bsOcrListener = (e) => {
    const detail = e.detail;
    // Ignore a result for a different upload than the one in flight.
    if (detail.receiptId && window._bsReceiptId && detail.receiptId !== window._bsReceiptId) return;
    if (detail.receiptId && detail.receiptId === window._bsReceiptId) window._bsReceiptId = null;
    if (detail.status === 'SUCCESS' && detail.data) {
      showReview(detail.data);
      const reason = document.getElementById('bs-reason');
      if (reason) reason.textContent = '';
    } else if (detail.status === 'ERROR') {
      showError(detail.message || "We couldn't read this receipt.");
    }
  };
  window.addEventListener('ocr-completed', window._bsOcrListener);

  async function saveTransaction(uid) {
    const saveBtn = document.getElementById('bs-save');
    saveBtn.disabled = true;
    saveBtn.innerHTML = '<span class="spinner"></span> Saving…';
    try {
      const type = document.getElementById('bs-type').value;
      const amount = parseFloat(document.getElementById('bs-amount').value) || 0;
      let currency = Auth.getDefaultCurrency();
      const dateVal = document.getElementById('bs-date').value;
      const isRecurring = document.getElementById('bs-recurring').checked;
      const dest = document.getElementById('bs-dest').value;
      const description = document.getElementById('bs-desc').value.trim();
      const name = document.getElementById('bs-name').value.trim() || 'Receipt';

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
          receiptUrl: window._bsReceiptUrl || null,
        }));
        window.navigateTo('transactions');
        toast('Receipt sent to transactions', 'info');
      } else {
        localStorage.setItem('pfa_pending_scan', JSON.stringify({
          groupId: dest,
          amount,
          currency,
          description: name + (description ? ` - ${description}` : ''),
          date: dateVal,
          receiptUrl: window._bsReceiptUrl || null,
        }));
        window.navigateTo('split');
        toast('Receipt sent to the group', 'info');
      }
    } catch (err) {
      toast('Could not save: ' + err.message, 'error');
      saveBtn.disabled = false;
      saveBtn.innerHTML = `${icon('check', 'sm')} Save transaction`;
    }
  }
}

function escapeAttr(s) {
  return String(s ?? '').replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
}
