import { afterEach, describe, it, expect, vi } from 'vitest';
import { renderBillScanner } from '../../js/views/bill-scanner.js';
import { api, Auth } from '../../js/utils/api.js';

async function scannerResult(data) {
  Auth.save({ token: 't', refreshToken: 'r', userId: 'u1', email: 'a@b.com' });
  global.fetch.mockResolvedValue({ status: 200, ok: true, text: async () => '[]' });
  const container = document.createElement('div');
  document.body.appendChild(container);
  window.navigateTo = vi.fn();
  await renderBillScanner(container);
  window.dispatchEvent(new CustomEvent('ocr-completed', { detail: { status: 'SUCCESS', data } }));
  return container;
}

describe('scanner accounting handoff', () => {
  it('marks uncertain fields, leaves unknown dates empty, and saves the reviewed currency', async () => {
    const container = await scannerResult({ name: 'Acme', amount: 7.61, currency: 'INR',
      type: 'EXPENSE', category: 'RESTAURANTS', date: null, needsReview: true,
      reviewFields: ['currency', 'date'] });
    expect(container.querySelector('[role="status"]').textContent).toContain('Check');
    expect(container.querySelector('#bs-date').value).toBe('');
    expect(container.querySelector('#bs-date').required).toBe(true);
    expect(container.querySelector('#bs-currency').value).toBe('');
    expect(container.querySelector('#bs-currency').parentElement.textContent).toContain('Check this value');
    container.querySelector('#bs-currency').value = 'USD';
    container.querySelector('#bs-currency').dispatchEvent(new Event('input'));
    expect(container.querySelector('.ocr-amount-currency').textContent).toBe('USD');
    expect(container.querySelector('#bs-currency-affix').textContent).toBe('USD');
    container.querySelector('#bs-date').value = '2017-09-27';
    container.querySelector('#bs-preview-form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await vi.waitFor(() => expect(window.navigateTo).toHaveBeenCalledWith('transactions'));
    expect(JSON.parse(localStorage.getItem('pfa_pending_scan'))).toMatchObject({ currency: 'USD', date: '2017-09-27' });
  });

  it('requires a reviewed amount and currency before handing off a transaction', async () => {
    const container = await scannerResult({ name: 'Unknown merchant', amount: 158.07, currency: 'USD',
      type: 'EXPENSE', category: 'RESTAURANTS', date: '2019-02-22', needsReview: true,
      reviewFields: ['amount', 'currency'] });
    expect(container.querySelector('#bs-amount').value).toBe('');
    expect(container.querySelector('#bs-currency').value).toBe('');
    container.querySelector('#bs-preview-form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    expect(window.navigateTo).not.toHaveBeenCalled();
    expect(localStorage.getItem('pfa_pending_scan')).toBeNull();
  });

  it('keeps refund treatment, expense link, subcategory, and original currency', async () => {
    const container = await scannerResult({ name: 'Pharmacy refund', amount: 25, amountDefault: 2000,
      defaultCurrency: 'INR', currency: 'USD', type: 'INCOME', category: 'MEDICINES',
      transactionKind: 'REFUND', originalTransactionId: 42, subcategory: 'Prescriptions', date: '2026-11-01' });
    expect(container.querySelector('#bs-kind').value).toBe('REFUND');
    expect(container.querySelector('#bs-category').value).toBe('MEDICINES');
    expect(container.querySelector('#bs-accounting-help').textContent).toContain('original expense');
    expect(container.querySelector('#bs-recurring').disabled).toBe(true);
    container.querySelector('#bs-preview-form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await vi.waitFor(() => expect(window.navigateTo).toHaveBeenCalledWith('transactions'));
    expect(JSON.parse(localStorage.getItem('pfa_pending_scan'))).toMatchObject({ transactionKind: 'REFUND',
      originalTransactionId: 42, subcategory: 'Prescriptions', amount: 25, currency: 'USD', category: 'MEDICINES' });
  });

  it('shows added OCR categories and investment treatment', async () => {
    const container = await scannerResult({ name: 'Cloud backup', amount: 150, currency: 'INR', type: 'EXPENSE',
      category: 'CLOUD_STORAGE', transactionKind: 'STANDARD' });
    expect(container.querySelector('#bs-category').value).toBe('CLOUD_STORAGE');
    const kind = container.querySelector('#bs-kind');
    kind.value = 'INVESTMENT';
    kind.dispatchEvent(new Event('change'));
    expect([...container.querySelector('#bs-category').options].map(o => o.value)).toContain('MUTUAL_FUNDS_SIP');
    expect(container.querySelector('#bs-accounting-help').textContent).toContain('excluded');
    expect(container.querySelector('#bs-dest').disabled).toBe(true);
  });
});

describe('scanner upload and completion ordering', () => {
  afterEach(() => vi.restoreAllMocks());

  async function pendingScan() {
    const container = await scannerResult({ name: 'Previous result', amount: 1, currency: 'USD' });
    container.querySelector('#bs-scan-another').click();
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: vi.fn(() => 'blob:receipt') });
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: vi.fn() });
    let resolveUpload;
    vi.spyOn(api, 'upload').mockImplementation(() => new Promise(resolve => { resolveUpload = resolve; }));
    const input = container.querySelector('#bs-file');
    Object.defineProperty(input, 'files', { configurable: true, value: [new File(['receipt'], 'receipt.jpg', { type: 'image/jpeg' })] });
    input.dispatchEvent(new Event('change'));
    return { container, resolveUpload };
  }

  const completed = { receiptId: 'current-receipt', status: 'SUCCESS', data: {
    name: 'Chilis Hamburgers', amount: 28.31, currency: 'USD', date: '2017-11-24', type: 'EXPENSE', category: 'RESTAURANTS',
  } };

  function complete(detail) {
    window.dispatchEvent(new CustomEvent('ocr-completed', { detail }));
  }

  it('keeps a fast OCR result that arrives before the upload response', async () => {
    const { container, resolveUpload } = await pendingScan();
    complete(completed);
    resolveUpload({ receiptId: 'current-receipt', url: '/api/storage/receipts/u1/receipt.jpg' });
    await vi.waitFor(() => expect(container.querySelector('#bs-name')?.value).toBe('Chilis Hamburgers'));
    expect(container.querySelector('#bs-amount').value).toBe('28.31');
    expect(container.querySelector('.advisor-skeleton')).toBeNull();
    expect(window._bsReceiptUrl).toBe('/api/storage/receipts/u1/receipt.jpg');
  });

  it('keeps an early OCR error instead of restoring the loading skeleton', async () => {
    const { container, resolveUpload } = await pendingScan();
    complete({ receiptId: 'current-receipt', status: 'ERROR', message: 'No readable text' });
    resolveUpload({ receiptId: 'current-receipt', url: '/api/storage/receipts/u1/receipt.jpg' });
    await vi.waitFor(() => expect(container.querySelector('#bs-result').textContent).toContain('No readable text'));
    expect(container.querySelector('.advisor-skeleton')).toBeNull();
  });

  it('matches buffered completions to this receipt and ignores later stale events', async () => {
    const { container, resolveUpload } = await pendingScan();
    complete({ ...completed, receiptId: 'old-receipt', data: { ...completed.data, name: 'Stale receipt' } });
    complete(completed);
    resolveUpload({ receiptId: 'current-receipt' });
    await vi.waitFor(() => expect(container.querySelector('#bs-name')?.value).toBe('Chilis Hamburgers'));
    container.querySelector('#bs-name').value = 'Reviewed merchant';
    complete({ ...completed, receiptId: 'old-receipt' });
    complete(completed);
    expect(container.querySelector('#bs-name').value).toBe('Reviewed merchant');
  });

  it('still fills the form when OCR finishes after upload acknowledgement', async () => {
    const { container, resolveUpload } = await pendingScan();
    resolveUpload({ receiptId: 'current-receipt' });
    await vi.waitFor(() => expect(container.querySelector('.advisor-skeleton')).not.toBeNull());
    complete(completed);
    expect(container.querySelector('#bs-name').value).toBe('Chilis Hamburgers');
  });

  it('does not let an older upload response replace a newer scan', async () => {
    const { container, resolveUpload } = await pendingScan();
    let resolveNewUpload;
    api.upload.mockImplementationOnce(() => new Promise(resolve => { resolveNewUpload = resolve; }));
    const input = container.querySelector('#bs-file');
    Object.defineProperty(input, 'files', { configurable: true, value: [new File(['new receipt'], 'new.jpg', { type: 'image/jpeg' })] });
    input.dispatchEvent(new Event('change'));
    resolveNewUpload({ receiptId: 'current-receipt', url: '/api/storage/receipts/u1/new.jpg' });
    await vi.waitFor(() => expect(window._bsReceiptId).toBe('current-receipt'));
    complete(completed);
    resolveUpload({ receiptId: 'old-receipt', url: '/api/storage/receipts/u1/old.jpg' });
    await Promise.resolve();
    expect(container.querySelector('#bs-name').value).toBe('Chilis Hamburgers');
    expect(window._bsReceiptUrl).toBe('/api/storage/receipts/u1/new.jpg');
  });
});
