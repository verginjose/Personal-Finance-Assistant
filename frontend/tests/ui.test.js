import { describe, it, expect, beforeEach } from 'vitest';
import {
  esc,
  formatCurrency,
  formatCategory,
  formatDate,
  healthScoreColor,
  budgetStatusColor,
  pageHeader,
  emptyState,
  progressBar,
  typeBadge,
  categoryOptions,
  EXPENSE_CATS,
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
    expect(formatCurrency(99.5, 'USD')).toBe('$99.5');
  });

  it('formats category labels', () => {
    expect(formatCategory('FOOD_AND_DINING')).toBe('FOOD AND DINING');
  });

  it('formats dates from ISO strings', () => {
    const result = formatDate('2025-06-09T10:00:00Z');
    expect(result).toMatch(/2025/);
  });

  it('returns health score colors by threshold', () => {
    expect(healthScoreColor(800)).toBe('var(--accent-g)');
    expect(healthScoreColor(600)).toBe('var(--accent-y)');
    expect(healthScoreColor(300)).toBe('var(--accent)');
  });

  it('returns budget status colors', () => {
    expect(budgetStatusColor('EXCEEDED')).toBe('var(--accent)');
    expect(budgetStatusColor('WARNING')).toBe('var(--accent-y)');
    expect(budgetStatusColor('SAFE')).toBe('var(--accent-g)');
  });

  it('builds page header HTML', () => {
    const html = pageHeader('Dashboard', 'Overview');
    expect(html).toContain('<h1>Dashboard</h1>');
    expect(html).toContain('Overview');
  });

  it('builds empty state HTML', () => {
    const html = emptyState('🧾', 'No data', 'Try again');
    expect(html).toContain('No data');
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
    const html = categoryOptions(EXPENSE_CATS.slice(0, 2), 'FOOD_AND_DINING');
    expect(html).toContain('value="FOOD_AND_DINING"');
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
