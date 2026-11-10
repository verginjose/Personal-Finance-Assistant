import { describe, it, expect, vi } from 'vitest';
import { renderBillScanner } from '../../js/views/bill-scanner.js';
import { Auth } from '../../js/utils/api.js';

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
