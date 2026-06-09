import { api, Auth, toast } from '../utils/api.js';
import { pageHeader, dataField } from '../utils/ui.js';

export async function renderBillScanner(container) {
  const userId = Auth.getUserId();
  container.innerHTML = `
    ${pageHeader('Bill Scanner', 'Upload a receipt and let AI extract the details')}
    <div class="card fade-up" style="max-width:680px">
      <div class="drop-zone" id="bs-drop" role="button" tabindex="0" aria-label="Upload receipt">
        <div class="drop-icon">📄</div>
        <p>Drag & drop a receipt image or PDF here</p>
        <p style="margin-top:6px;font-size:.78rem;color:var(--text-muted)">JPEG, PNG, PDF — max 10 MB</p>
        <div class="file-name" id="bs-fname"></div>
      </div>
      <input type="file" id="bs-file" accept="image/*,application/pdf" hidden>
      <button class="btn btn-primary" id="bs-upload" style="width:100%;margin-top:20px" disabled>
        🔍 Process Receipt
      </button>
    </div>
    <div class="card fade-up" id="bs-result" style="max-width:680px;margin-top:20px;display:none">
      <div class="card-header"><h3>Extracted Data</h3><span class="badge badge-info">OCR Preview</span></div>
      <div id="bs-data" style="display:grid;gap:10px"></div>
    </div>`;

  const dropZone = document.getElementById('bs-drop');
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

  uploadBtn.onclick = async () => {
    if (!selectedFile) return;
    uploadBtn.disabled = true;
    uploadBtn.innerHTML = '<span class="spinner"></span> Processing…';
    try {
      const fd = new FormData();
      fd.append('file', selectedFile);
      const result = await api.upload(`/bill/process/${userId}`, fd);
      const resultEl = document.getElementById('bs-result');
      resultEl.style.display = 'block';
      document.getElementById('bs-data').innerHTML = `
        ${dataField('Name', result.name)}
        ${dataField('Amount', result.amount)}
        ${dataField('Type', result.type)}
        ${dataField('Category', result.expenseCategory || result.incomeCategory)}
        ${dataField('Currency', result.currency)}
        ${dataField('Description', result.description)}
        <button class="btn btn-success" style="width:100%;margin-top:12px" id="bs-save">✓ Save as Transaction</button>`;

      document.getElementById('bs-save').onclick = () => saveTransaction(userId, result);
    } catch (err) {
      toast('OCR failed: ' + err.message, 'error');
    }
    uploadBtn.disabled = !selectedFile;
    uploadBtn.innerHTML = '🔍 Process Receipt';
  };
}

async function saveTransaction(userId, result) {
  const saveBtn = document.getElementById('bs-save');
  saveBtn.disabled = true;
  saveBtn.innerHTML = '<span class="spinner"></span> Saving…';
  try {
    const type = (result.type || 'EXPENSE').toUpperCase();
    const amount = parseFloat((result.amount || '0').toString().replace(/[^0-9.]/g, '')) || 0;
    let currency = (result.currency || 'INR').trim().toUpperCase();
    if (currency.length !== 3) {
      const map = { '₹': 'INR', RS: 'INR', 'RS.': 'INR', $: 'USD', '€': 'EUR', '£': 'GBP' };
      currency = map[currency] || 'INR';
    }
    const payload = {
      userId,
      name: result.name || 'OCR Receipt',
      amount,
      type,
      currency,
      description: result.description || 'Processed from bill scanner'
    };
    const rawCat = (type === 'INCOME' ? result.incomeCategory : result.expenseCategory) || 'OTHERS';
    let cat = rawCat.toUpperCase().replace(/ /g, '_').replace(/-/g, '_');
    if (cat === 'OTHER') cat = 'OTHERS';
    if (type === 'INCOME') payload.incomeCategory = cat;
    else payload.expenseCategory = cat;

    await api.post('/upsert/create', payload);
    toast('Transaction saved successfully!', 'success');
    document.getElementById('bs-result').style.display = 'none';
    document.getElementById('bs-fname').textContent = '';
  } catch (err) {
    toast('Save failed: ' + err.message, 'error');
  } finally {
    saveBtn.disabled = false;
    saveBtn.innerHTML = '✓ Save as Transaction';
  }
}
