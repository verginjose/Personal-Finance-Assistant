import { describe, it, expect, beforeEach, beforeAll, vi } from 'vitest';
import { Auth } from '../js/utils/api.js';
import '../js/app.js';

function jsonResponse(data) {
  return { status: 200, ok: true, text: async () => JSON.stringify(data) };
}

beforeAll(() => {
  // jsdom has no EventSource; the shell only needs the constructor to exist.
  global.EventSource = class {
    constructor() { this.addEventListener = vi.fn(); this.close = vi.fn(); }
  };
});

function mockApis() {
  global.fetch.mockImplementation((url) => {
    const u = String(url);
    if (u.includes('/analytics/ai-insights')) return Promise.resolve(jsonResponse({ insights: [] }));
    if (u.includes('/upsert/entries')) return Promise.resolve(jsonResponse({ content: [], totalPages: 1 }));
    return Promise.resolve(jsonResponse(u.includes('/auth/me') ? { defaultCurrency: 'INR' } : []));
  });
}

describe('app shell navigation', () => {
  beforeEach(() => {
    Auth.save({ token: 't', refreshToken: 'r', userId: 'u1', email: 'a@b.com' });
    document.body.innerHTML = '<div id="app"></div>';
    mockApis();
    window.dispatchEvent(new Event('auth-change')); // resets to dashboard + renders
  });

  it('exposes every destination on desktop and mobile', () => {
    expect(document.querySelectorAll('#sidebar-nav a')).toHaveLength(8);

    // Bottom bar: Dashboard, Transactions, (crucially, Add), Split, More.
    const barViews = [...document.querySelectorAll('#bottom-nav [data-view]')].map(a => a.dataset.view);
    expect(barViews).toEqual(['dashboard', 'transactions', 'split']);

    // Everything else is grouped under More — including Bill Scanner.
    const moreViews = [...document.querySelectorAll('#more-menu-sheet [data-view]')].map(a => a.dataset.view);
    expect(moreViews).toEqual(['bill-scanner', 'analytics', 'subscriptions', 'goals', 'profile']);
  });

  it('highlights More while a grouped view is open', () => {
    window.navigateTo('goals');
    expect(document.getElementById('mobile-more-btn').classList.contains('active')).toBe(true);
    expect(document.getElementById('mobile-more-btn').getAttribute('aria-current')).toBe('page');

    window.navigateTo('dashboard');
    expect(document.getElementById('mobile-more-btn').classList.contains('active')).toBe(false);
    expect(document.getElementById('mobile-more-btn').hasAttribute('aria-current')).toBe(false);
  });

  it('opens the transaction form from the mobile Add button', async () => {
    document.getElementById('mobile-fab').click();

    await vi.waitFor(() => {
      expect(document.querySelector('.modal-overlay')).toBeTruthy();
    });
  });

  it('opens the transaction form from the N shortcut', async () => {
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'n', bubbles: true }));

    await vi.waitFor(() => {
      expect(document.querySelector('.modal-overlay')).toBeTruthy();
    });
  });

  it('keeps one primary add action off the sidebar', () => {
    // The pinned sidebar button was removed; the action lives where the task
    // happens (the Transactions header) and as the mobile Add button.
    expect(document.getElementById('sidebar-add-txn')).toBeNull();
    expect(document.getElementById('mobile-fab')).toBeTruthy();
  });
});
