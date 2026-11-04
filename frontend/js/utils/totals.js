/* ═══════════════════════════════════════════════════════════════════════════
   totals.js — One source of truth for Earned / Spent / Net.
   Every screen that shows a total asks this module, so the Dashboard,
   Analytics, Insights and health copy cannot drift apart. The backend summary
   already excludes settlements (payments between group members are not income
   or spending), so the rule lives in one place.
   ═══════════════════════════════════════════════════════════════════════════ */
import { api, Auth } from './api.js';

const CACHE_TTL_MS = 15_000;
const cache = new Map();

export const PERIODS = [
  { id: 'month', label: 'This month' },
  { id: 'last', label: 'Last month' },
  { id: 'quarter', label: 'Last 3 months' },
  { id: 'all', label: 'All time' },
];

function isoDate(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function startOfMonth(now, offset = 0) {
  return new Date(now.getFullYear(), now.getMonth() + offset, 1);
}

function endOfMonth(now, offset = 0) {
  return new Date(now.getFullYear(), now.getMonth() + offset + 1, 0);
}

/** Resolved date range for a dashboard/analytics period. */
export function periodRange(period) {
  const now = new Date();
  if (period === 'all' || !period) return {};
  if (period === 'month') {
    return { startDate: isoDate(startOfMonth(now)), endDate: isoDate(endOfMonth(now)) };
  }
  if (period === 'last') {
    return { startDate: isoDate(startOfMonth(now, -1)), endDate: isoDate(endOfMonth(now, -1)) };
  }
  return { startDate: isoDate(startOfMonth(now, -2)), endDate: isoDate(endOfMonth(now, 0)) };
}

/**
 * The period a comparison is measured against.
 * The current month is compared like-for-like: day 1 to today against day 1
 * to the same day of the previous month, never a full month.
 */
export function previousRange(period) {
  const now = new Date();
  if (period === 'all' || !period) return null;
  if (period === 'month') {
    const prevEnd = endOfMonth(now, -1);
    const sameDay = Math.min(now.getDate(), prevEnd.getDate());
    return {
      startDate: isoDate(startOfMonth(now, -1)),
      endDate: isoDate(new Date(prevEnd.getFullYear(), prevEnd.getMonth(), sameDay)),
    };
  }
  if (period === 'last') {
    return { startDate: isoDate(startOfMonth(now, -2)), endDate: isoDate(endOfMonth(now, -2)) };
  }
  return { startDate: isoDate(startOfMonth(now, -5)), endDate: isoDate(endOfMonth(now, -2)) };
}

export function periodHeading(period) {
  const now = new Date();
  if (period === 'month') return now.toLocaleDateString('en-IN', { month: 'long', year: 'numeric' });
  if (period === 'last') {
    return new Date(now.getFullYear(), now.getMonth() - 1, 1)
      .toLocaleDateString('en-IN', { month: 'long', year: 'numeric' });
  }
  if (period === 'quarter') return 'Last 3 months';
  return 'All time';
}

/** Scope label used under generated panels. */
export function periodScopeLabel(period) {
  if (period === 'month') {
    return new Date().toLocaleDateString('en-IN', { month: 'long', year: 'numeric' });
  }
  if (period === 'last') {
    return new Date(new Date().getFullYear(), new Date().getMonth() - 1, 1)
      .toLocaleDateString('en-IN', { month: 'long', year: 'numeric' });
  }
  if (period === 'quarter') return 'the last 3 months';
  return 'all time';
}

/** The period the comparison names, e.g. "the same days in October". */
export function comparisonLabel(period) {
  const now = new Date();
  if (period === 'month') {
    return `the same days in ${new Date(now.getFullYear(), now.getMonth() - 1, 1)
      .toLocaleDateString('en-IN', { month: 'long' })}`;
  }
  if (period === 'last') {
    return new Date(now.getFullYear(), now.getMonth() - 2, 1)
      .toLocaleDateString('en-IN', { month: 'long' });
  }
  if (period === 'quarter') return 'the previous 3 months';
  return 'the previous period';
}

export function normalizeTotals(summary, currency = Auth.getDefaultCurrency()) {
  const available = summary
    && !Array.isArray(summary)
    && (summary.totalIncome != null || summary.totalExpense != null || summary.netBalance != null);
  const income = Number(summary?.totalIncome) || 0;
  const expense = Number(summary?.totalExpense) || 0;
  const net = summary?.netBalance != null ? Number(summary.netBalance) : income - expense;
  return {
    income,
    expense,
    net,
    count: Number(summary?.totalCount) || 0,
    currency: String(currency || 'INR').toUpperCase(),
    available,
  };
}

export function emptyTotals(currency = Auth.getDefaultCurrency()) {
  return {
    income: 0, expense: 0, net: 0, count: 0,
    currency: String(currency || 'INR').toUpperCase(), available: false,
  };
}

function rangeKey({ startDate, endDate }) {
  return `${startDate || 'MIN'}:${endDate || 'MAX'}`;
}

/**
 * Fetch the shared totals for a period id or an explicit date range.
 * Identical ranges share a short-lived cache and in-flight request.
 */
export async function getTotals({ period = null, startDate = null, endDate = null, force = false } = {}) {
  const userId = Auth.getUserId();
  if (!userId) return emptyTotals();
  let start = startDate;
  let end = endDate;
  if (period) ({ startDate: start, endDate: end } = periodRange(period));
  const key = `${userId}:${rangeKey({ startDate: start, endDate: end })}`;
  const now = Date.now();
  const hit = cache.get(key);
  if (!force && hit && now - hit.at < CACHE_TTL_MS) return hit.promise;

  const params = { userId };
  if (start) params.startDate = start;
  if (end) params.endDate = end;
  const promise = api.get('/upsert/summary', params)
    .then(summary => normalizeTotals(summary))
    .catch(err => {
      cache.delete(key);
      throw err;
    });
  cache.set(key, { at: now, promise });
  return promise;
}

export function clearTotalsCache() {
  cache.clear();
}
