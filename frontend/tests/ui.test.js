import { describe, it, expect, beforeEach } from 'vitest';
import {
  esc,
  formatCurrency,
  formatMoney,
  formatCompact,
  formatCategory,
  formatDate,
  healthScoreColor,
  budgetStatusColor,
  pageHeader,
  emptyState,
  progressBar,
  typeBadge,
  categoryOptions,
  renderHealthData,
  renderAiData,
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

  it('returns health score colors by threshold', () => {
    expect(healthScoreColor(800)).toBe('var(--pos)');
    expect(healthScoreColor(600)).toBe('var(--warn)');
    expect(healthScoreColor(300)).toBe('var(--neg)');
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

  it('renders health data into DOM', () => {
    document.body.innerHTML = `
      <span id="h-health-score"></span>
      <span id="h-health-grade"></span>
      <p id="h-health-summary"></p>
      <div id="h-health-breakdown"></div>
      <div id="h-health-ring"></div>`;

    renderHealthData('h', {
      totalScore: 750,
      grade: 'A',
      summary: 'Great job',
      breakdown: { savings: 80, spending: 70 },
    });

    expect(document.getElementById('h-health-score').textContent).toBe('750');
    expect(document.getElementById('h-health-grade').textContent).toBe('Grade A');
    expect(document.getElementById('h-health-summary').textContent).toBe('Great job');
    expect(document.getElementById('h-health-breakdown').children.length).toBe(2);
  });

  it('renders AI insights into DOM', () => {
    document.body.innerHTML = `
      <p id="i-ai-summary"></p>
      <div id="i-ai-insights-list"></div>`;

    renderAiData('i', {
      summary: '3 insights found',
      insights: [{ title: 'High spending', message: 'Cut dining', type: 'WARNING' }],
    });

    expect(document.getElementById('i-ai-summary').textContent).toBe('3 insights found');
    expect(document.getElementById('i-ai-insights-list').children.length).toBe(1);
    expect(document.getElementById('i-ai-insights-list').textContent).toContain('High spending');
  });
});
