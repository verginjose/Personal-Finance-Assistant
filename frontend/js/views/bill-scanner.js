import { api, Auth, toast } from '../utils/api.js';

export async function renderBillScanner(container) {
  const userId = Auth.getUserId();
  container.innerHTML = `
    <div class="page-header fade-up"><h1>Bill Scanner</h1><p>Upload a receipt and let AI extract the details</p></div>
    <div class="card fade-up" style="animation-delay:.05s;max-width:640px">
      <div class="drop-zone" id="bs-drop">
        <div class="drop-icon">📄</div>
        <p>Drag & drop a receipt image or PDF here</p>
        <p style="margin-top:6px;font-size:.78rem;color:var(--text-muted)">JPEG, PNG, PDF — max 10 MB</p>
        <div class="file-name" id="bs-fname"></div>
      </div>
      <input type="file" id="bs-file" accept="image/*,application/pdf" hidden>
      <button class="btn btn-primary" id="bs-upload" style="width:100%;justify-content:center;margin-top:20px" disabled>
        🔍 Process Receipt
      </button>
    </div>
    <div class="card fade-up" id="bs-result" style="animation-delay:.1s;max-width:640px;margin-top:20px;display:none">
      <h3 style="margin-bottom:16px;font-weight:600">Extracted Data</h3>
      <div id="bs-data"></div>
    </div>`;

  const dropZone = document.getElementById('bs-drop');
  const fileInput = document.getElementById('bs-file');
  const uploadBtn = document.getElementById('bs-upload');
  let selectedFile = null;

  dropZone.onclick = () => fileInput.click();
  dropZone.ondragover = (e) => { e.preventDefault(); dropZone.classList.add('drag-over'); };
  dropZone.ondragleave = () => dropZone.classList.remove('drag-over');
  dropZone.ondrop = (e) => { e.preventDefault(); dropZone.classList.remove('drag-over'); pickFile(e.dataTransfer.files[0]); };
  fileInput.onchange = () => pickFile(fileInput.files[0]);

  function pickFile(f) {
    if (!f) return;
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
      document.getElementById('bs-result').style.display = 'block';
      document.getElementById('bs-data').innerHTML = `
        <div style="display:grid;gap:12px">
          ${field('Name', result.name)}
          ${field('Amount', result.amount)}
          ${field('Type', result.type)}
          ${field('Category', result.expenseCategory || result.incomeCategory)}
          ${field('Currency', result.currency)}
          ${field('Description', result.description)}
        </div>
        <button class="btn btn-success" style="width:100%;justify-content:center;margin-top:20px" id="bs-save">✓ Save as Transaction</button>`;

      document.getElementById('bs-save').onclick = async () => {
        const saveBtn = document.getElementById('bs-save');
        saveBtn.disabled = true;
        saveBtn.innerHTML = '<span class="spinner"></span> Saving…';
        try {
          const type = (result.type || 'EXPENSE').toUpperCase();
          const cleanAmountStr = (result.amount || '0').toString().replace(/[^0-9.]/g, '');
          const amount = parseFloat(cleanAmountStr) || 0;

          const payload = {
            userId,
            name: result.name || 'OCR Receipt',
            amount: amount,
            type: type,
            currency: result.currency || 'INR',
            description: result.description || 'Processed from bill scanner'
          };
          if (type === 'INCOME') {
            payload.incomeCategory = result.incomeCategory || 'OTHERS';
          } else {
            payload.expenseCategory = result.expenseCategory || 'OTHERS';
          }
          await api.post('/upsert/create', payload);
          toast('Transaction saved successfully!', 'success');
          document.getElementById('bs-result').style.display = 'none';
          document.getElementById('bs-fname').textContent = '';
          selectedFile = null;
          uploadBtn.disabled = true;
        } catch (err) {
          toast('Save failed: ' + err.message, 'error');
        } finally {
          saveBtn.disabled = false;
          saveBtn.innerHTML = '✓ Save as Transaction';
        }
      };
    } catch (err) { toast('OCR failed: ' + err.message, 'error'); }
    uploadBtn.disabled = false;
    uploadBtn.innerHTML = '🔍 Process Receipt';
  };
}

function field(label, value) {
  return `<div style="display:flex;justify-content:space-between;padding:10px 14px;background:var(--bg-input);border-radius:var(--radius-sm)">
    <span style="color:var(--text-dim);font-size:.85rem">${label}</span>
    <span style="font-weight:600">${value ?? '—'}</span>
  </div>`;
}
