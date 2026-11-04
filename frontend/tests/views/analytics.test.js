import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderAnalytics } from '../../js/views/analytics.js';
import { Auth } from '../../js/utils/api.js';

function jsonResponse(data) {
  return { status: 200, ok: true, text: async () => JSON.stringify(data) };
}

function mockAnalyticsApis() {
  global.fetch.mockImplementation((url) => {
    const u = new URL(String(url));
    const path = u.pathname;
    if (path.endsWith('/analytics/category-pie-chart')) {
      const income = u.searchParams.get('transactionFilter') === 'INCOME';
      return Promise.resolve(jsonResponse({
        labels: income ? ['SALARY'] : ['FOOD_DELIVERY'],
        datasets: [{ data: income ? [50000] : [12000] }],
      }));
    }
    if (path.endsWith('/analytics/timeline-chart')) {
      return Promise.resolve(jsonResponse({
        labels: ['2026-10'],
        datasets: [{ label: 'Income', data: [50000] }, { label: 'Expense', data: [12000] }],
      }));
    }
    if (path.endsWith('/analytics/health-score')) {
      return Promise.resolve(jsonResponse({ totalScore: 720, grade: 'A', summary: 'Solid', breakdown: {} }));
    }
    if (path.endsWith('/analytics/ai-insights')) {
      return Promise.resolve(jsonResponse({ summary: 'All good', insights: [] }));
    }
    return Promise.resolve(jsonResponse([]));
  });
}

describe('renderAnalytics', () => {
  let container;

  beforeEach(() => {
    Auth.save({ token: 't', refreshToken: 'r', userId: 'u1', email: 'a@b.com' });
    container = document.createElement('div');
    document.body.appendChild(container);
    // A regular function is constructible; the shared setup's arrow mock is not.
    global.Chart = vi.fn(function ChartMock() {
      return { destroy: vi.fn(), update: vi.fn() };
    });
    global.Chart.defaults = { color: '', borderColor: '', font: { family: '' } };
  });

  it('sends the browser timezone and the selected granularity with filters', async () => {
    mockAnalyticsApis();
    await renderAnalytics(container);
    // Let the initial (unfiltered) load finish before asserting on the next one.
    await vi.waitFor(() => {
      expect(container.querySelector('#a-kpis')?.textContent).toContain('All time');
    });

    const timeline = container.querySelector('#a-timeline');
    timeline.value = 'WEEKLY';
    timeline.dispatchEvent(new Event('change'));

    // One range control replaces the two date inputs; changes apply themselves.
    const range = container.querySelector('#a-range');
    range.value = '2026-11-01 to 2026-11-30';
    range.dispatchEvent(new Event('change'));

    global.fetch.mockClear();
    mockAnalyticsApis();

    await vi.waitFor(() => {
      const timelineCall = global.fetch.mock.calls
        .map(c => new URL(String(c[0])))
        .find(u => u.pathname.endsWith('/analytics/timeline-chart'));
      expect(timelineCall).toBeTruthy();
      expect(timelineCall.searchParams.get('timelineType')).toBe('WEEKLY');
      expect(timelineCall.searchParams.get('startDate')).toBe('2026-11-01T00:00:00');
      expect(timelineCall.searchParams.get('endDate')).toBe('2026-11-30T23:59:59.999');
      expect(timelineCall.searchParams.get('tzOffsetMinutes'))
        .toBe(String(-new Date().getTimezoneOffset()));
    });
  });

  it('renders readable category labels and range KPIs', async () => {
    mockAnalyticsApis();
    await renderAnalytics(container);

    await vi.waitFor(() => {
      // Categories are rendered as a readable bar list, not a doughnut.
      const labels = [...container.querySelectorAll('.barlist-label')].map(el => el.textContent);
      expect(labels).toContain('Food delivery');
      expect(labels).not.toContain('FOOD_DELIVERY');

      const kpis = container.querySelector('#a-kpis')?.textContent || '';
      expect(kpis).toContain('50,000');
      expect(kpis).toContain('12,000');
      expect(kpis).toContain('All time');
    });
  });
});
