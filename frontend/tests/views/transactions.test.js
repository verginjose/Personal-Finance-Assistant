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
    expect(container.querySelector('.badge-expense')).toBeTruthy();
    expect(container.querySelector('.badge-recurring')).toBeTruthy();
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
});
