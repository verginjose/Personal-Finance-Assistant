import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderTransactions } from '../../js/views/transactions.js';
import { Auth } from '../../js/utils/api.js';

function mockFetchJson(data) {
  global.fetch.mockResolvedValueOnce({
    status: 200,
    ok: true,
    text: async () => JSON.stringify(data),
  });
}

describe('renderTransactions', () => {
  let container;

  beforeEach(() => {
    Auth.save({ token: 't', refreshToken: 'r', userId: 'u1', email: 'a@b.com' });
    container = document.createElement('div');
    document.body.appendChild(container);
    global.fetch.mockReset();
  });

  it('renders toolbar and table', async () => {
    mockFetchJson({ content: [], totalPages: 0 });
    await renderTransactions(container);

    expect(container.querySelector('#t-search')).toBeTruthy();
    expect(container.querySelector('#t-add')).toBeTruthy();
    expect(container.querySelector('#t-body')).toBeTruthy();
  });

  it('displays transaction rows from API', async () => {
    mockFetchJson({
      content: [{
        id: 1,
        name: 'Groceries',
        type: 'EXPENSE',
        expenseCategory: 'FOOD_AND_DINING',
        amount: 450,
        currency: 'INR',
        createdAt: '2025-06-01T10:00:00Z',
        recurring: true,
        recurringPeriod: 'MONTHLY',
      }],
      totalPages: 1,
    });
    await renderTransactions(container);

    await vi.waitFor(() => {
      expect(container.querySelector('#t-body')?.textContent).toContain('Groceries');
    });
    // Type is carried by the sign on the amount, not a badge.
    expect(container.querySelector('.amount-cell')?.textContent).toContain('−₹450.00');
    // Recurrence is a quiet tag on the row.
    expect(container.querySelector('.txn-tag')?.textContent).toContain('Monthly');
    expect(container.querySelector('.txn-tag .icon-svg')).toBeTruthy();
  });

  it('opens add transaction modal', async () => {
    mockFetchJson({ content: [], totalPages: 0 });
    await renderTransactions(container);
    container.querySelector('#t-add').click();

    await vi.waitFor(() => {
      expect(document.querySelector('.modal-overlay')).toBeTruthy();
    });
    expect(document.querySelector('#txn-form')).toBeTruthy();
    expect(document.querySelector('#m-name')).toBeTruthy();
  });

  it('changes the category straight from the detail sheet', async () => {
    const txn = {
      id: 7,
      name: 'Groceries',
      type: 'EXPENSE',
      expenseCategory: 'FOOD_AND_DINING',
      amount: 450,
      currency: 'INR',
      createdAt: '2025-06-01T10:00:00Z',
    };
    mockFetchJson({ content: [txn], totalPages: 1 });
    await renderTransactions(container);
    await vi.waitFor(() => {
      expect(container.querySelector('tr[data-txn]')).toBeTruthy();
    });

    mockFetchJson(txn); // the detail sheet refetches the row
    container.querySelector('tr[data-txn] .txn-name').click();

    await vi.waitFor(() => {
      expect(document.querySelector('#m-detail-cat .cat-edit-btn')).toBeTruthy();
    });
    const btn = document.querySelector('#m-detail-cat .cat-edit-btn');
    btn.click();

    // The chip becomes a select in place; the edit form is never opened.
    expect(document.querySelector('#m-detail-cat select')).toBeTruthy();
    expect(document.querySelector('#txn-form')).toBeFalsy();
  });
  it('offers investment treatment and custom subcategories', async () => {
    mockFetchJson({ content: [], totalPages: 0 });
    await renderTransactions(container);
    container.querySelector('#t-add').click();
    const kind = document.querySelector('#m-kind');
    kind.value = 'INVESTMENT';
    kind.dispatchEvent(new Event('change'));
    expect([...document.querySelector('#m-cat').options].map(o => o.value)).toContain('MUTUAL_FUNDS_SIP');
    expect(document.querySelector('#m-cat').textContent).not.toContain('Groceries');
    expect(document.querySelector('#m-kind-help').textContent).toContain('Excluded');
    expect(document.querySelector('#m-subcategory').maxLength).toBe(80);
  });

  it('links refunds to expenses and saves inherited category and original currency', async () => {
    mockFetchJson({ content: [], totalPages: 0 });
    await renderTransactions(container);
    await vi.waitFor(() => expect(container.querySelector('#t-body').textContent).toContain('No transactions'));
    container.querySelector('#t-add').click();
    mockFetchJson({ content: [{ id: 42, name: 'Weekly groceries', type: 'EXPENSE', category: 'GROCERIES', subcategory: 'Weekly shop', amount: 200, currency: 'USD', createdAt: '2026-11-01T09:00:00' }], totalPages: 1 });
    const kind = document.querySelector('#m-kind');
    kind.value = 'REFUND';
    kind.dispatchEvent(new Event('change'));
    await vi.waitFor(() => expect(document.querySelector('#m-original').options.length).toBe(2));
    const original = document.querySelector('#m-original');
    original.value = '42';
    original.dispatchEvent(new Event('change'));
    expect(document.querySelector('#m-type').value).toBe('INCOME');
    expect(document.querySelector('#m-cat').value).toBe('GROCERIES');
    expect(document.querySelector('#m-cat').disabled).toBe(true);
    expect(document.querySelector('#m-subcategory').value).toBe('Weekly shop');
    expect(document.querySelector('#m-currency').value).toBe('USD');
    document.querySelector('#m-name').value = 'Partial refund';
    document.querySelector('#m-amount').value = '50';
    mockFetchJson({ id: 43 });
    document.querySelector('#txn-form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await vi.waitFor(() => {
      const call = global.fetch.mock.calls.find(([url, options]) => String(url).includes('/upsert/create') && options?.method === 'POST');
      expect(call).toBeTruthy();
      expect(JSON.parse(call[1].body)).toMatchObject({ transactionKind: 'REFUND', originalTransactionId: 42, category: 'GROCERIES', currency: 'USD', amount: 50, subcategory: 'Weekly shop', recurring: false });
    });
    mockFetchJson({ content: [], totalPages: 0 });
    await vi.waitFor(() => expect(document.querySelector('#txn-form')).toBeFalsy(), { timeout: 2000 });
  });
});
