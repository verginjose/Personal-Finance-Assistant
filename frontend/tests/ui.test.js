import { describe, it, expect, beforeEach } from 'vitest';
import {
  esc,
  formatCurrency,
  formatMoney,
  formatCompact,
  formatCategory,
  formatDate,
  budgetStatusColor,
  pageHeader,
  emptyState,
  progressBar,
  typeBadge,
  categoryOptions,
  renderAiData,
  displayName,
  humanizeHandle,
  toSentenceCase,
  humanizeSystemText,
  displayAmountFor,
  formatDue,
  asServerInstant,
} from '../js/utils/ui.js';

describe('ui utilities', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('escapes HTML in user strings', () => {
    expect(esc('<script>alert(1)</script>')).toBe('&lt;script&gt;alert(1)&lt;/script&gt;');
    expect(esc(null)).toBe('');
  });

  it('formats currency with INR symbol', () => {
    expect(formatCurrency(1500)).toContain('1,500');
    expect(formatCurrency(1500)).toMatch(/^₹/);
  });

  it('formats USD currency', () => {
    expect(formatCurrency(99.5, 'USD')).toBe('$99.50');
  });

  it('formats money with Indian grouping and a true minus sign', () => {
    expect(formatMoney(-6327.59)).toBe('−₹6,327.59');
    expect(formatMoney(190830)).toBe('₹1,90,830.00');
  });

  it('formats compact axis values', () => {
    expect(formatCompact(0)).toBe('₹0');
    expect(formatCompact(50000)).toBe('₹50K');
    expect(formatCompact(120000)).toBe('₹1.2L');
  });

  it('formats category labels in sentence case', () => {
    expect(formatCategory('RESTAURANTS')).toBe('Restaurants');
    expect(formatCategory('OTT_SUBSCRIPTIONS')).toBe('OTT subscriptions');
    expect(formatCategory('CAB_AND_AUTO')).toBe('Cab and auto');
  });

  it('formats dates from ISO strings', () => {
    const result = formatDate('2025-06-09T10:00:00Z');
    expect(result).toMatch(/2025/);
  });

  it('returns budget status colors', () => {
    expect(budgetStatusColor('EXCEEDED')).toBe('var(--neg)');
    expect(budgetStatusColor('WARNING')).toBe('var(--warn)');
    expect(budgetStatusColor('SAFE')).toBe('var(--pos)');
  });

  it('builds page header HTML', () => {
    const html = pageHeader('Dashboard', 'Overview');
    expect(html).toContain('<h1>Dashboard</h1>');
    expect(html).toContain('Overview');
  });

  it('builds empty state HTML', () => {
    const html = emptyState('receipt', 'No data', 'Try again');
    expect(html).toContain('No data');
    expect(html).toContain('icon-svg');
    expect(html).toContain('Try again');
  });

  it('builds progress bar with clamped width', () => {
    expect(progressBar(150)).toContain('width:100%');
    expect(progressBar(-5)).toContain('width:0%');
  });

  it('builds type badges', () => {
    expect(typeBadge('INCOME')).toContain('badge-income');
    expect(typeBadge('EXPENSE')).toContain('badge-expense');
  });

  it('generates category option elements', () => {
    const html = categoryOptions(['RENT', 'RESTAURANTS'], 'RESTAURANTS');
    expect(html).toContain('value="RESTAURANTS"');
    expect(html).toContain('selected');
  });

  it('renders AI insights into DOM', () => {
    document.body.innerHTML = `
      <p id="i-ai-summary"></p>
      <div id="i-ai-insights-list"></div>`;

    renderAiData('i', {
      summary: '3 insights found',
      insights: [{ title: 'High spending', message: 'Cut dining', type: 'WARNING' }],
    }, null, { scopeLabel: 'November 2026' });

    // The panel states the scope; it never repeats a generated paragraph that
    // could disagree with the totals shown above it.
    expect(document.getElementById('i-ai-summary').textContent).toBe('Based on November 2026');
    expect(document.getElementById('i-ai-insights-list').children.length).toBe(1);
    expect(document.getElementById('i-ai-insights-list').textContent).toContain('High spending');
  });

  it('treats a server timestamp without an offset as UTC', () => {
    // Cached responses from before the backend emitted an offset.
    expect(asServerInstant('2026-11-05T10:02:00')).toBe('2026-11-05T10:02:00Z');
    expect(asServerInstant('2026-11-05T10:02:00.123')).toBe('2026-11-05T10:02:00.123Z');
    expect(asServerInstant('2026-11-05T10:02:00Z')).toBe('2026-11-05T10:02:00Z');
    expect(asServerInstant('2026-11-05T10:02:00+05:30')).toBe('2026-11-05T10:02:00+05:30');
    expect(asServerInstant('')).toBeNull();
    expect(asServerInstant(null)).toBeNull();
  });

  it('humanises handles into display names', () => {
    expect(humanizeHandle('aarav.menon.db241')).toBe('Aarav Menon');
    expect(displayName('aarav.menon.db241')).toBe('Aarav Menon');
    expect(displayName({ username: 'deepika.shah' })).toBe('Deepika Shah');
    // A real display name is trusted verbatim.
    expect(displayName({ name: 'Deepika Shah' })).toBe('Deepika Shah');
    expect(displayName('')).toBe('Member');
  });

  it('converts generated Title Case and shouting to sentence case', () => {
    expect(toSentenceCase('Emergency Fund Is Behind')).toBe('Emergency fund is behind');
    expect(toSentenceCase('Cut Electronics Spending')).toBe('Cut electronics spending');
    expect(humanizeSystemText('Auto-generated: monthly subscription')).toBe('Monthly subscription');
    expect(humanizeSystemText('Auto-generated (Aggregated 2 missed periods): monthly subscription'))
      .toBe('Monthly subscription · 2 charges caught up');
    expect(humanizeSystemText('RESTAURANTS changed by -77.7%')).toBe('Restaurants changed by -77.7%');
  });

  it('keeps amounts in the default currency when no conversion exists', () => {
    expect(displayAmountFor({ amount: 100, currency: 'INR' }, 'INR'))
      .toEqual({ amount: 100, currency: 'INR', secondary: '' });
    const foreign = displayAmountFor({ amount: 2.5, currency: 'USD' }, 'INR');
    expect(foreign.currency).toBe('USD');
    expect(foreign.secondary).toBe('not converted');
    const converted = displayAmountFor({ amount: 2.5, currency: 'USD', amountDefault: 210 }, 'INR');
    expect(converted.amount).toBe(210);
    expect(converted.currency).toBe('INR');
    expect(converted.secondary).toContain('originally');
  });

  it('puts the relative date first for upcoming charges', () => {
    const iso = new Date();
    iso.setDate(iso.getDate() + 1);
    expect(formatDue(iso.toISOString(), 1)).toMatch(/^Tomorrow · /);
  });
});
