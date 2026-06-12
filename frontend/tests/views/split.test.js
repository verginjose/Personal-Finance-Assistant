import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderSplit } from '../../js/views/split.js';
import { Auth } from '../../js/utils/api.js';

let currentMockResponses = {};

function mockFetchJson(urlMap) {
  global.fetch.mockImplementation(async (url, opts) => {
    let data = []; // default to array since most are lists
    for (const [key, value] of Object.entries(urlMap)) {
      if (url.includes(key)) {
        data = value;
        break;
      }
    }
    // Only return a 201 for POST requests
    const isPost = opts && opts.method === 'POST';
    return {
      status: isPost ? 201 : 200,
      ok: true,
      text: async () => JSON.stringify(data)
    };
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
    mockFetchJson({ '/groups': [] });
    await renderSplit(container);
    container.querySelector('#sp-create').click();

    await vi.waitFor(() => expect(document.querySelector('#cg-name')).toBeTruthy());

    document.querySelector('#cg-name').value = 'Weekend Trip';
    document.querySelector('#cg-desc').value = 'Cab share';
    mockFetchJson({ '/groups': { id: 1, name: 'Weekend Trip' } });

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
    mockFetchJson({ '/groups': [{ id: 5, name: 'Trip', description: '' }] });
    await renderSplit(container);
    await vi.waitFor(() => expect(container.querySelector('.group-card')).toBeTruthy());

    mockFetchJson({
      '/groups/5/members': [{ id: 1, name: 'Me', userId: memberUserId }],
      '/groups/5/activities': [],
      '/groups/5/balances': { memberBalances: [], simplifiedDebts: [] },
      '/groups/5/expenses': [],
      '/groups/5': { id: 5, name: 'Trip' },
    });
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
    mockFetchJson({ '/groups': [{ id: 5, name: 'Trip', description: '' }] });
    await renderSplit(container);
    await vi.waitFor(() => expect(container.querySelector('.group-card')).toBeTruthy());

    mockFetchJson({
      '/groups/5/members': [
        { id: 1, name: 'alice', userId: memberA },
        { id: 2, name: 'bob', userId: memberB }
      ],
      '/groups/5/activities': [],
      '/groups/5/balances': { memberBalances: [], simplifiedDebts: [] },
      '/groups/5/expenses': [],
      '/groups/5': { id: 5, name: 'Trip' }
    });
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
