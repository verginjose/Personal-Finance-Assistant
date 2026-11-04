import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderDashboard } from '../../js/views/dashboard.js';
import { Auth } from '../../js/utils/api.js';

function jsonResponse(data) {
  return {
    status: 200,
    ok: true,
    text: async () => JSON.stringify(data),
  };
}

function mockDashboardApis({ aiDelay = 0 } = {}) {
  global.fetch.mockImplementation((url) => {
    const u = String(url);
    if (u.includes('/analytics/ai-insights')) {
      const data = { summary: 'All good', insights: [{ title: 'Tip', message: 'Save more', type: 'INFO' }] };
      if (aiDelay) {
        return new Promise((resolve) => setTimeout(() => resolve(jsonResponse(data)), aiDelay));
      }
      return Promise.resolve(jsonResponse(data));
    }
    if (u.includes('/analytics/category-pie-chart')) {
      return Promise.resolve(jsonResponse({ labels: ['Food'], datasets: [{ data: [100] }] }));
    }
    if (u.includes('/analytics/timeline-chart')) {
      return Promise.resolve(jsonResponse({ labels: ['Jan'], datasets: [{ label: 'Income', data: [1000] }] }));
    }
    if (u.includes('/analytics/health-score')) {
      return Promise.resolve(jsonResponse({ totalScore: 720, grade: 'A', summary: 'Solid', breakdown: { savings: 80 } }));
    }
    if (u.includes('/upsert/summary')) {
      return Promise.resolve(jsonResponse({ totalIncome: 50000, totalExpense: 30000, netBalance: 20000, totalCount: 12 }));
    }
    // budgets, goals and anything else
    return Promise.resolve(jsonResponse([]));
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

  it('renders page structure with KPI and recent-activity containers', async () => {
    mockDashboardApis();
    await renderDashboard(container);

    expect(container.querySelector('h1')?.textContent).toBe('November 2026');
    expect(container.querySelector('#d-health-score')).toBeTruthy();
    expect(container.querySelector('#d-ai-insights-list')).toBeTruthy();
    expect(container.querySelector('#d-kpis')).toBeTruthy();
    expect(container.querySelector('#d-recent')).toBeTruthy();
  });

  it('renders the money overview alongside health data', async () => {
    mockDashboardApis();
    await renderDashboard(container);

    await vi.waitFor(() => {
      expect(container.querySelector('#d-health-score')?.textContent).toBe('720');
      expect(container.querySelector('#d-net')?.textContent).toBe('₹20,000.00');
    });
    expect(container.querySelector('#d-health-grade')?.textContent).toBe('Grade A');
    // The dashboard now surfaces totals; Analytics keeps the breakdowns.
    const kpis = container.querySelector('#d-kpis')?.textContent || '';
    expect(kpis).toContain('₹20,000'); // net balance
    expect(kpis).toContain('₹50,000'); // income
    expect(kpis).toContain('₹30,000'); // expenses
  });

  it('does not hold the page for slow AI insights', async () => {
    mockDashboardApis({ aiDelay: 250 });
    await renderDashboard(container);

    // The dashboard has already resolved while the AI request is still in
    // flight, so every other panel must be painted.
    expect(container.querySelector('#d-health-score')?.textContent).toBe('720');
    expect(container.querySelector('#d-ai-summary')?.textContent).toContain('Analyzing');
    expect(container.querySelectorAll('#d-ai-insights-list .advisor-skeleton').length).toBeGreaterThan(0);

    await vi.waitFor(() => {
      expect(container.querySelector('#d-ai-insights-list')?.textContent).toContain('Save more');
    });
  });
});
