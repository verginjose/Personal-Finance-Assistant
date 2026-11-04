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

function nearestRank(sorted, p) {
  if (!sorted.length) return null;
  const idx = Math.min(sorted.length - 1, Math.ceil(p * (sorted.length - 1)));
  return sorted[idx];
}

/**
 * On a phone the axis is capped near the 90th percentile when one value towers
 * over the rest, so a single outlier month does not flatten the chart.
 */
function clipLimits({ income, expense, net, showIncome, showExpense, showNet }) {
  const values = [];
  if (showIncome) values.push(...income);
  if (showExpense) values.push(...expense);
  const nonzero = values.map(Number).filter(v => Number.isFinite(v) && v !== 0).map(Math.abs).sort((a, b) => a - b);

  let max;
  if (nonzero.length > 4) {
    const p90 = nearestRank(nonzero, 0.9);
    const top = nonzero[nonzero.length - 1];
    if (p90 > 0 && top > p90 * 1.8) max = p90 * 1.15;
  }

  let min;
  if (showNet && net) {
    const sorted = net.map(Number).filter(v => Number.isFinite(v) && v !== 0).sort((a, b) => a - b);
    if (sorted.length > 4) {
      const p10 = nearestRank(sorted, 0.1);
      const lowest = sorted[0];
      if (p10 < 0 && lowest < p10 * 1.8) min = p10 * 1.15;
    }
  }

  return { max, min, clipped: max != null || min != null };
}

/** A tap elsewhere should not leave a crosshair pinned to the last column. */
function clearActive(chart) {
  if (!chart) return;
  try {
    chart.setActiveElements([]);
    if (chart.tooltip) chart.tooltip.setActiveElements([], { x: 0, y: 0 });
    chart.update('none');
  } catch { /* chart already destroyed */ }
}

/**
 * Grouped income/expense bars with an optional net line.
 * External legend buttons live in the view.
 */
export function createBarChart(ctx, { labels, income, expense, net, currency = 'INR', mobile = false, showIncome = true, showExpense = true, showNet = false }) {
  const p = applyDefaults();
  if (typeof Chart === 'undefined' || !ctx) return null;
  const clip = mobile ? clipLimits({ income, expense, net, showIncome, showExpense, showNet }) : null;

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

  const chart = new Chart(ctx, {
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
                // The Net line is its own dataset, so it already has a row.
                // Only add one when that dataset is hidden.
                if (items.some(i => String(i.dataset?.label || '') === 'Net')) return '';
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
          ticks: {
            color: p.text2,
            maxRotation: 0,
            // Phone labels are chosen below so the newest bucket is always
            // named; auto-skip alone drops the last one.
            autoSkip: !mobile,
            maxTicksLimit: mobile ? undefined : 11,
          },
          afterBuildTicks: mobile ? (scale) => {
            const total = scale.ticks.length;
            const max = 4;
            if (total <= max) return;
            const keep = new Set([0, total - 1]);
            for (let i = 1; i < max - 1; i++) {
              keep.add(Math.round((i * (total - 1)) / (max - 1)));
            }
            scale.ticks = scale.ticks.filter((_, i) => keep.has(i));
          } : undefined,
        },
        y: {
          beginAtZero: true,
          ...(clip?.max != null ? { max: clip.max } : {}),
          ...(clip?.min != null ? { min: clip.min } : {}),
          grid: { color: p.line, drawTicks: false },
          border: { display: true, color: p.line },
          ticks: {
            color: p.text2,
            padding: mobile ? 4 : 8,
            maxTicksLimit: mobile ? 5 : 11,
            callback: (value) => axisMoney(value, currency),
          },
          // Give the plot area more of a narrow phone screen.
          afterFit: mobile ? (scale) => { scale.width = Math.min(scale.width, 56); } : undefined,
        },
      },
    },
  });

  if (chart) {
    chart.$pfaClipped = !!clip?.clipped;
    const canvas = chart.canvas;
    if (canvas && typeof canvas.addEventListener === 'function') {
      const onPointerLeave = (e) => { if (e.pointerType !== 'touch') clearActive(chart); };
      const onDocumentDown = (e) => { if (!canvas.contains(e.target)) clearActive(chart); };
      canvas.addEventListener('pointerleave', onPointerLeave);
      canvas.addEventListener('mouseleave', onPointerLeave);
      document.addEventListener('pointerdown', onDocumentDown);
      chart.$pfaCleanup = () => {
        canvas.removeEventListener('pointerleave', onPointerLeave);
        canvas.removeEventListener('mouseleave', onPointerLeave);
        document.removeEventListener('pointerdown', onDocumentDown);
      };
    }
  }
  return chart;
}

export function destroyChart(chart) {
  if (!chart) return null;
  try { chart.$pfaCleanup?.(); } catch { /* already detached */ }
  chart.destroy();
  return null;
}
