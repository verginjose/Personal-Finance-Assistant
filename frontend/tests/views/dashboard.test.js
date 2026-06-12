import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderDashboard } from '../../js/views/dashboard.js';
import { Auth } from '../../js/utils/api.js';

// Mock charts.js since it's dynamically imported in dashboard
vi.mock('../../js/utils/charts.js', () => ({
  createDoughnut: vi.fn(() => ({ destroy: vi.fn() })),
  createLine: vi.fn(() => ({ destroy: vi.fn() })),
  destroyChart: vi.fn(() => null),
}));

function mockDashboardApis() {
  global.fetch.mockImplementation(async (url) => {
    let data = {};
    if (url.includes('/summary')) data = { totalIncome: 50000, totalExpense: 30000, netBalance: 20000, totalCount: 12 };
    else if (url.includes('transactionFilter=EXPENSE')) data = { labels: ['Food'], datasets: [{ data: [100] }] };
    else if (url.includes('transactionFilter=INCOME')) data = { labels: ['Salary'], datasets: [{ data: [5000] }] };
    else if (url.includes('/timeline-chart')) data = { labels: ['Jan'], datasets: [{ label: 'Income', data: [1000] }] };
    else if (url.includes('/health-score')) data = { totalScore: 720, grade: 'A', summary: 'Solid', breakdown: { savings: 80 } };
    else if (url.includes('/ai-insights')) data = { summary: 'All good', insights: [{ title: 'Tip', message: 'Save more', type: 'INFO' }] };
    else if (url.includes('/budgets')) data = [];
    else if (url.includes('/goals')) data = [];

    return {
      status: 200, ok: true,
      text: async () => JSON.stringify(data)
    };
  });
}

describe('renderDashboard', () => {
  let container;

  beforeEach(() => {
    Auth.save({ token: 't', refreshToken: 'r', userId: 'u1', email: 'a@b.com' });
    container = document.createElement('div');
    document.body.appendChild(container);
    global.fetch.mockReset();
  });

  it('renders page structure with KPI placeholders', async () => {
    mockDashboardApis();
    await renderDashboard(container);

    expect(container.querySelector('h1')?.textContent).toBe('Dashboard');
    expect(container.querySelector('#d-health-score')).toBeTruthy();
    expect(container.querySelector('#d-ai-insights-list')).toBeTruthy();
  });

  it('populates summary KPIs after data loads', async () => {
    mockDashboardApis();
    await renderDashboard(container);

    await vi.waitFor(() => {
      const income = container.querySelector('.stat-card.income .stat-value');
      if (!income) console.log('CONTAINER HTML:', container.innerHTML);
      expect(income?.textContent || '').toContain('50,000');
    });
    expect(container.querySelector('#d-health-score')?.textContent).toBe('720');
    expect(container.querySelector('#d-health-grade')?.textContent).toBe('Grade A');
  });
});
