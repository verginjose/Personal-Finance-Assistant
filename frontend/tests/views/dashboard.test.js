import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderDashboard } from '../../js/views/dashboard.js';
import { Auth } from '../../js/utils/api.js';

function mockFetchJson(data) {
  global.fetch.mockResolvedValueOnce({
    status: 200,
    ok: true,
    text: async () => JSON.stringify(data),
  });
}

function mockDashboardApis() {
  mockFetchJson({ totalIncome: 50000, totalExpense: 30000, netBalance: 20000, totalCount: 12 });
  mockFetchJson({ labels: ['Food'], datasets: [{ data: [100] }] });
  mockFetchJson({ labels: ['Jan'], datasets: [{ label: 'Income', data: [1000] }] });
  mockFetchJson({ totalScore: 720, grade: 'A', summary: 'Solid', breakdown: { savings: 80 } });
  mockFetchJson({ summary: 'All good', insights: [{ title: 'Tip', message: 'Save more', type: 'INFO' }] });
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
      expect(income?.textContent).toContain('50,000');
    });
    expect(container.querySelector('#d-health-score')?.textContent).toBe('720');
    expect(container.querySelector('#d-health-grade')?.textContent).toBe('Grade A');
  });
});
