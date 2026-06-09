import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderSplit } from '../../js/views/split.js';
import { Auth } from '../../js/utils/api.js';

function mockFetchJson(data, status = 200) {
  global.fetch.mockResolvedValueOnce({
    status,
    ok: status >= 200 && status < 300,
    text: async () => JSON.stringify(data),
  });
}

describe('renderSplit', () => {
  const userId = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
  let container;

  beforeEach(() => {
    Auth.save({ token: 't', refreshToken: 'r', userId, email: 'a@b.com' });
    container = document.createElement('div');
    document.body.appendChild(container);
    global.fetch.mockReset();
    vi.stubGlobal('crypto', { randomUUID: () => '11111111-2222-3333-4444-555555555555' });
  });

  it('sends createdBy when creating a group', async () => {
    mockFetchJson([]);
    await renderSplit(container);
    container.querySelector('#sp-create').click();

    await vi.waitFor(() => expect(document.querySelector('#cg-name')).toBeTruthy());

    document.querySelector('#cg-name').value = 'Weekend Trip';
    document.querySelector('#cg-desc').value = 'Cab share';
    mockFetchJson({ id: 1, name: 'Weekend Trip' }, 201);

    document.querySelector('#cg-form').requestSubmit();

    await vi.waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(2));

    const [, opts] = global.fetch.mock.calls[1];
    const body = JSON.parse(opts.body);
    expect(body.createdBy).toBe(userId);
    expect(body.name).toBe('Weekend Trip');
    expect(body).not.toHaveProperty('createdByUserId');
  });

  it('sends paidBy UUID when adding a shared expense', async () => {
    const memberUserId = 'bbbbbbbb-cccc-dddd-eeee-ffffffffffff';
    mockFetchJson([{ id: 5, name: 'Trip', description: '' }]);
    await renderSplit(container);
    await vi.waitFor(() => expect(container.querySelector('.group-card')).toBeTruthy());

    mockFetchJson({ id: 5, name: 'Trip' });
    mockFetchJson([{ id: 1, name: 'Me', userId: memberUserId }]);
    mockFetchJson([]);
    mockFetchJson({ memberBalances: [], simplifiedDebts: [] });
    container.querySelector('.group-card').click();

    await vi.waitFor(() => expect(document.getElementById('sp-add-expense')).toBeTruthy());
    document.getElementById('sp-add-expense').click();

    await vi.waitFor(() => expect(document.querySelector('#ae-desc')).toBeTruthy());

    document.querySelector('#ae-desc').value = 'Lunch';
    document.querySelector('#ae-amt').value = '200';
    document.querySelector('#ae-paid').value = memberUserId;
    mockFetchJson({ id: 99 }, 201);

    document.querySelector('#ae-form').requestSubmit();

    await vi.waitFor(() => {
      const expenseCall = global.fetch.mock.calls.find(([, o]) => {
        try {
          const b = JSON.parse(o.body);
          return b.description === 'Lunch';
        } catch { return false; }
      });
      expect(expenseCall).toBeTruthy();
      const body = JSON.parse(expenseCall[1].body);
      expect(body.paidBy).toBe(memberUserId);
      expect(body.splitType).toBe('EQUAL');
      expect(body).not.toHaveProperty('paidByMemberId');
    });
  });
});
