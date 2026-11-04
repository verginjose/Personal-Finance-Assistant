/* ═══════════════════════════════════════════════════════════════════════════
   charts.js — Chart.js helpers that read the design tokens
   ═══════════════════════════════════════════════════════════════════════════ */
import { formatCompact, formatMoney } from './ui.js';

function token(name, fallback) {
  try {
    const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    return v || fallback;
  } catch { return fallback; }
}

function palette() {
  return {
    pos: token('--pos', '#5DBB93'),
    neg: token('--neg', '#D9806A'),
    text: token('--text', '#E7EEEA'),
    text2: token('--text-2', '#93A39D'),
    line: token('--line', '#243430'),
    raised: token('--raised', '#1A2623'),
    brand: token('--brand', '#C9A45C'),
    font: "'Hanken Grotesk', 'Public Sans', system-ui, sans-serif",
  };
}

function applyDefaults() {
  if (typeof Chart === 'undefined') return null;
  const p = palette();
  if (Chart.defaults) {
    Chart.defaults.color = p.text2;
    Chart.defaults.borderColor = p.line;
    Chart.defaults.font.family = p.font;
    Chart.defaults.font.size = 12;
  }
  return p;
}

function axisMoney(value, currency) {
  return formatCompact(value, currency);
}

/**
 * Grouped income/expense bars with an optional net line.
 * External legend buttons live in the view.
 */
export function createBarChart(ctx, { labels, income, expense, net, currency = 'INR', showIncome = true, showExpense = true, showNet = false }) {
  const p = applyDefaults();
  if (typeof Chart === 'undefined' || !ctx) return null;

  const datasets = [];
  if (showIncome) {
    datasets.push({
      label: 'Income',
      type: 'bar',
      data: income,
      backgroundColor: p.pos,
      borderRadius: 4,
      borderSkipped: false,
      maxBarThickness: 28,
      order: 2,
    });
  }
  if (showExpense) {
    datasets.push({
      label: 'Expenses',
      type: 'bar',
      data: expense,
      backgroundColor: p.text2,
      borderRadius: 4,
      borderSkipped: false,
      maxBarThickness: 28,
      order: 2,
    });
  }
  if (showNet && net) {
    datasets.push({
      label: 'Net',
      type: 'line',
      data: net,
      borderColor: p.brand,
      backgroundColor: p.brand,
      borderWidth: 2,
      pointRadius: 0,
      pointHoverRadius: 4,
      tension: 0.2,
      fill: false,
      order: 1,
    });
  }

  return new Chart(ctx, {
    type: 'bar',
    data: { labels, datasets },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      interaction: { mode: 'index', intersect: false },
      animation: { duration: 300 },
      plugins: {
        legend: { display: false },
        tooltip: {
          backgroundColor: p.raised,
          borderColor: p.line,
          borderWidth: 1,
          titleColor: p.text,
          bodyColor: p.text2,
          padding: 12,
          cornerRadius: 8,
          displayColors: true,
          boxWidth: 8,
          boxHeight: 8,
          boxPadding: 4,
          callbacks: {
            title: (items) => (items[0] ? String(items[0].label) : ''),
            label: (ctx2) => {
              const value = Number(ctx2.parsed.y) || 0;
              return ` ${ctx2.dataset.label}  ${formatMoney(value, currency)}`;
            },
            ...(net && showNet ? {
              afterBody: (items) => {
                const i = items[0]?.dataIndex;
                if (i == null) return '';
                return ` Net  ${formatMoney(Number(net[i]) || 0, currency)}`;
              },
            } : {}),
          },
        },
      },
      scales: {
        x: {
          grid: { display: false },
          border: { color: p.line },
          ticks: { color: p.text2, maxRotation: 0, autoSkip: true },
        },
        y: {
          beginAtZero: true,
          grid: { color: p.line, drawTicks: false },
          border: { display: true, color: p.line },
          ticks: {
            color: p.text2,
            padding: 8,
            callback: (value) => axisMoney(value, currency),
          },
        },
      },
    },
  });
}

export function destroyChart(chart) {
  if (chart) chart.destroy();
  return null;
}
