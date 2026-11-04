import { describe, it, expect, beforeEach } from 'vitest';
import { Auth } from '../js/utils/api.js';
import {
  normalizeTotals, emptyTotals, periodRange, previousRange, getTotals, clearTotalsCache,
} from '../js/utils/totals.js';

function jsonResponse(data) {
  return { status: 200, ok: true, text: async () => JSON.stringify(data) };
}

describe('totals selector', () => {
  beforeEach(() => {
    clearTotalsCache();
    global.fetch.mockReset();
    Auth.save({ token: 't', refreshToken: 'r', userId: 'u1', email: 'a@b.com' });
  });

  it('normalises a summary into one shape', () => {
    expect(normalizeTotals({ totalIncome: 50000, totalExpense: 30000, netBalance: 20000, totalCount: 7 }, 'INR'))
      .toEqual({ income: 50000, expense: 30000, net: 20000, count: 7, currency: 'INR', available: true });
    // An unexpected payload is flagged unavailable so callers can fall back.
    expect(normalizeTotals([], 'INR').available).toBe(false);
    expect(emptyTotals('INR').available).toBe(false);
  });

  it('compares the current month like-for-like, not against a whole month', () => {
    const now = new Date();
    const prev = previousRange('month');
    // The comparison range ends on the same day-of-month this month is at.
    const prevEnd = new Date(`${prev.endDate}T00:00:00`);
    const prevStart = new Date(`${prev.startDate}T00:00:00`);
    expect(prevEnd.getDate()).toBe(Math.min(now.getDate(), new Date(prevEnd.getFullYear(), prevEnd.getMonth() + 1, 0).getDate()));
    expect(prevStart.getDate()).toBe(1);
    expect(periodRange('all')).toEqual({});
  });

  it('shares one request per range', async () => {
    global.fetch.mockResolvedValue(jsonResponse({ totalIncome: 10, totalExpense: 4, netBalance: 6, totalCount: 2 }));
    const [a, b] = await Promise.all([getTotals({ period: 'month' }), getTotals({ period: 'month' })]);
    expect(a).toEqual(b);
    expect(a.net).toBe(6);
    expect(global.fetch).toHaveBeenCalledTimes(1);
  });
});
