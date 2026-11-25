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

/**
 * URL-routed mock for the group-detail screen. The view loads activity,
 * group, members, expenses and balances concurrently, so a queued
 * mockResolvedValueOnce sequence breaks whenever the fetch order changes.
 */
function mockGroupDetailApi(members) {
  global.fetch.mockImplementation(async (url, opts = {}) => {
    const p = new URL(url).pathname;
    const method = (opts.method || 'GET').toUpperCase();
    const json = (data, status = 200) => ({ status, ok: status < 400, text: async () => JSON.stringify(data) });

    if (method === 'POST' && p.endsWith('/expenses')) return json({ id: 99 }, 201);
    if (method === 'POST' && p.endsWith('/auth/users/bulk')) {
      return json(members.map(m => ({ userId: m.userId, name: m.name })));
    }
    if (p.endsWith('/activity')) return json({ content: [], totalPages: 1 });
    if (p.endsWith('/members')) return json(members);
    if (p.endsWith('/expenses')) return json({ content: [], totalPages: 1 });
    if (p.endsWith('/balances')) return json({ memberBalances: [], simplifiedDebts: [] });
    if (p.endsWith('/groups/5')) return json({ id: 5, name: 'Trip' });
    if (p.endsWith('/groups')) return json([{ id: 5, name: 'Trip', description: '' }]);
    return json({});
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

  it('keeps expense JSON intact when the description contains an apostrophe', async () => {
    const memberA = 'bbbbbbbb-cccc-dddd-eeee-ffffffffffff';
    const memberB = 'cccccccc-dddd-eeee-ffff-000000000000';
    const expense = {
      id: 42,
      description: "Dinner at Jo's",
      amount: 300,
      paidBy: memberA,
      splitType: 'EQUAL',
      expenseDate: '2026-11-01',
      myShare: 150,
      currency: 'INR',
      expenseCategory: 'FOOD',
    };

    global.fetch.mockImplementation(async (url, opts = {}) => {
      const p = new URL(url).pathname;
      const json = (data, status = 200) => ({ status, ok: status < 400, text: async () => JSON.stringify(data) });
      if (p.endsWith('/auth/users/bulk')) return json([
        { userId: memberA, name: 'alice' },
        { userId: memberB, name: 'bob' },
      ]);
      if (p.endsWith('/activity')) return json({ content: [], totalPages: 1 });
      if (p.endsWith('/members')) return json([
        { id: 1, name: 'alice', userId: memberA, status: 'ACCEPTED' },
        { id: 2, name: 'bob', userId: memberB, status: 'ACCEPTED' },
      ]);
      if (p.endsWith('/expenses')) return json({ content: [expense], totalPages: 1 });
      if (p.endsWith('/balances')) return json({ memberBalances: [], simplifiedDebts: [] });
      if (p.endsWith('/groups/5')) return json({ id: 5, name: 'Trip' });
      if (p.endsWith('/groups')) return json([{ id: 5, name: 'Trip', description: '' }]);
      return json({});
    });

    await renderSplit(container);
    await vi.waitFor(() => expect(container.querySelector('.group-card')).toBeTruthy());
    container.querySelector('.group-card').click();

    await vi.waitFor(() => expect(document.querySelector('.expense-row')).toBeTruthy());
    const row = document.querySelector('.expense-row');
    // An unescaped apostrophe used to truncate the single-quoted attribute,
    // making this throw "unterminated string".
    expect(JSON.parse(row.dataset.exp).description).toBe("Dinner at Jo's");
    expect(() => row.click()).not.toThrow();
  });

  it('sends paidBy UUID when adding a shared expense', async () => {
    const memberUserId = 'bbbbbbbb-cccc-dddd-eeee-ffffffffffff';
    mockGroupDetailApi([{ id: 1, name: 'Me', userId: memberUserId, status: 'ACCEPTED' }]);
    await renderSplit(container);
    await vi.waitFor(() => expect(container.querySelector('.group-card')).toBeTruthy());

    container.querySelector('.group-card').click();

    await vi.waitFor(() => expect(document.getElementById('sp-add-expense')).toBeTruthy());
    document.getElementById('sp-add-expense').click();

    await vi.waitFor(() => expect(document.querySelector('#ae-desc')).toBeTruthy());

    document.querySelector('#ae-desc').value = 'Lunch';
    document.querySelector('#ae-amt').value = '200';
    document.querySelector('#ae-paid').value = memberUserId;

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
    mockGroupDetailApi([
      { id: 1, name: 'alice', userId: memberA, status: 'ACCEPTED' },
      { id: 2, name: 'bob', userId: memberB, status: 'ACCEPTED' }
    ]);
    await renderSplit(container);
    await vi.waitFor(() => expect(container.querySelector('.group-card')).toBeTruthy());

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

describe('group list latency', () => {
  const userId = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
  const json = data => ({ status: 200, ok: true, text: async () => JSON.stringify(data) });
  let container;
  beforeEach(() => {
    Auth.save({ token: 't', userId, email: 'a@b.com' });
    container = document.createElement('div');
    document.body.appendChild(container);
  });

  it('renders usable cards before bulk members and enriches without replacing them', async () => {
    let finishMembers;
    global.fetch.mockImplementation(url => {
      const path = new URL(url).pathname;
      if (path.endsWith('/groups/members')) return new Promise(resolve => { finishMembers = resolve; });
      if (path.endsWith('/auth/users/bulk')) return Promise.resolve(json([
        { userId, profilePicture: '/api/storage/profiles/photo.png' },
      ]));
      return Promise.resolve(json([
        { id: 5, name: 'Trip', currentUserStatus: 'ACCEPTED', currentUserNetBalance: 50 },
        { id: 6, name: 'Home', currentUserStatus: 'ACCEPTED' },
      ]));
    });
    await renderSplit(container);
    await vi.waitFor(() => expect(container.querySelectorAll('.group-card')).toHaveLength(2));
    const card = container.querySelector('.group-card');
    expect(card.textContent).toContain('Trip');
    expect(card.querySelector('.group-member-avatar')).toBeNull();
    await vi.waitFor(() => expect(finishMembers).toBeTypeOf('function'));
    finishMembers(json({ 5: [{ userId, name: 'Alice', status: 'ACCEPTED' }], 6: [] }));
    await vi.waitFor(() => expect(card.querySelector('.group-member-avatar img')).toBeTruthy());
    expect(container.querySelector('.group-card')).toBe(card);
    expect(global.fetch.mock.calls.filter(([url]) => /\/groups\/\d+\/members/.test(url))).toHaveLength(0);
    expect(global.fetch.mock.calls.filter(([url]) => new URL(url).pathname.endsWith('/groups/members'))).toHaveLength(1);
  });

  it('keeps later navigation intact when enrichment completes', async () => {
    let finishMembers;
    global.fetch.mockImplementation(url => new URL(url).pathname.endsWith('/groups/members')
      ? new Promise(resolve => { finishMembers = resolve; })
      : Promise.resolve(json([{ id: 5, name: 'Trip', currentUserStatus: 'ACCEPTED' }])));
    await renderSplit(container);
    await vi.waitFor(() => expect(finishMembers).toBeTypeOf('function'));
    container.innerHTML = '<h1>Another view</h1>';
    finishMembers(json({ 5: [{ userId, name: 'Alice', status: 'ACCEPTED' }] }));
    await new Promise(resolve => setTimeout(resolve, 10));
    expect(container.innerHTML).toBe('<h1>Another view</h1>');
  });

  it('limits enrichment to two concurrent batches and twenty IDs per batch', async () => {
    const pending = [];
    global.fetch.mockImplementation(url => {
      const u = new URL(url);
      if (u.pathname.endsWith('/groups/members')) {
        expect(u.searchParams.get('groupIds').split(',').length).toBeLessThanOrEqual(20);
        return new Promise(resolve => pending.push(resolve));
      }
      return Promise.resolve(json(Array.from({ length: 65 }, (_, i) => ({
        id: i + 1, name: `Group ${i + 1}`, currentUserStatus: 'ACCEPTED',
      }))));
    });
    await renderSplit(container);
    await vi.waitFor(() => expect(pending).toHaveLength(2));
    expect(container.querySelectorAll('.group-card')).toHaveLength(65);
    pending[0](json({})); pending[1](json({}));
    await vi.waitFor(() => expect(pending).toHaveLength(4));
    pending[2](json({})); pending[3](json({}));
  });
});
