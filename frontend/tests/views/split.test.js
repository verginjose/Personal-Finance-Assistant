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

  it('records a settlement when clicking Record Payment', async () => {
    const memberA = 'bbbbbbbb-cccc-dddd-eeee-ffffffffffff';
    const memberB = 'cccccccc-dddd-eeee-ffff-000000000000';

    global.fetch.mockImplementation(async (url, opts = {}) => {
      const p = new URL(url).pathname;
      const method = (opts.method || 'GET').toUpperCase();
      const json = (data, status = 200) => ({ status, ok: status < 400, text: async () => JSON.stringify(data) });

      if (method === 'POST' && p.endsWith('/settle')) return json({ message: 'Settlement recorded successfully' });
      if (p.endsWith('/auth/users/bulk')) return json([
        { userId: memberA, name: 'alice' },
        { userId: memberB, name: 'bob' },
      ]);
      if (p.endsWith('/activity')) return json({ content: [], totalPages: 1 });
      if (p.endsWith('/members')) return json([
        { id: 1, name: 'alice', userId: memberA, status: 'ACCEPTED' },
        { id: 2, name: 'bob', userId: memberB, status: 'ACCEPTED' },
      ]);
      if (p.endsWith('/expenses')) return json({ content: [], totalPages: 1 });
      if (p.endsWith('/balances')) return json({
        memberBalances: [],
        simplifiedDebts: [{ fromUserId: memberB, toUserId: memberA, fromUserName: 'bob', toUserName: 'alice', amount: 50 }],
      });
      if (p.endsWith('/groups/5')) return json({ id: 5, name: 'Trip' });
      if (p.endsWith('/groups')) return json([{ id: 5, name: 'Trip', description: '' }]);
      return json({});
    });

    await renderSplit(container);
    await vi.waitFor(() => expect(container.querySelector('.group-card')).toBeTruthy());
    container.querySelector('.group-card').click();

    await vi.waitFor(() => expect(document.querySelector('.settle-btn')).toBeTruthy());
    document.querySelector('.settle-btn').click();

    await vi.waitFor(() => expect(document.getElementById('confirm-ok-btn')).toBeTruthy());
    document.getElementById('confirm-ok-btn').click();

    await vi.waitFor(() => {
      const call = global.fetch.mock.calls.find(([url, o]) => {
        const p = new URL(url).pathname;
        return p.endsWith('/settle') && (o.method || 'GET').toUpperCase() === 'POST';
      });
      expect(call).toBeTruthy();
      const url = new URL(call[0]);
      expect(url.searchParams.get('fromUserId')).toBe(memberB);
      expect(url.searchParams.get('toUserId')).toBe(memberA);
    });
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
    mockFetchJson([]);
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

  it('sends splitDetails when using percentage split', async () => {
    const memberA = 'bbbbbbbb-cccc-dddd-eeee-ffffffffffff';
    const memberB = 'cccccccc-dddd-eeee-ffff-000000000000';
    mockFetchJson([{ id: 5, name: 'Trip', description: '' }]);
    await renderSplit(container);
    await vi.waitFor(() => expect(container.querySelector('.group-card')).toBeTruthy());

    mockFetchJson({ id: 5, name: 'Trip' });
    mockFetchJson([
      { id: 1, name: 'alice', userId: memberA },
      { id: 2, name: 'bob', userId: memberB }
    ]);
    mockFetchJson([]);
    mockFetchJson({ memberBalances: [], simplifiedDebts: [] });
    mockFetchJson([]);
    container.querySelector('.group-card').click();

    await vi.waitFor(() => expect(document.getElementById('sp-add-expense')).toBeTruthy());
    document.getElementById('sp-add-expense').click();

    await vi.waitFor(() => expect(document.querySelector('#ae-split-type')).toBeTruthy());

    document.querySelector('#ae-desc').value = 'Hotel';
    document.querySelector('#ae-amt').value = '1000';
    document.querySelector('#ae-paid').value = memberA;
    document.querySelector('#ae-split-type').value = 'PERCENTAGE';
    document.querySelector('#ae-split-type').dispatchEvent(new Event('change'));

    await vi.waitFor(() => expect(document.getElementById(`ae-split-${memberA}`)).toBeTruthy());

    document.getElementById(`ae-split-${memberA}`).value = '60';
    document.getElementById(`ae-split-${memberB}`).value = '40';
    mockFetchJson({ id: 100 }, 201);

    document.querySelector('#ae-form').requestSubmit();

    await vi.waitFor(() => {
      const expenseCall = global.fetch.mock.calls.find(([, o]) => {
        try {
          const b = JSON.parse(o.body);
          return b.description === 'Hotel';
        } catch { return false; }
      });
      expect(expenseCall).toBeTruthy();
      const body = JSON.parse(expenseCall[1].body);
      expect(body.splitType).toBe('PERCENTAGE');
      expect(body.splitDetails).toEqual([
        { userId: memberA, userName: 'alice', value: 60 },
        { userId: memberB, userName: 'bob', value: 40 }
      ]);
    });
  });
});
