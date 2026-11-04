/* ═══════════════════════════════════════════════════════════════════════════
   charts.js — Chart.js helpers with PFA colour palette
   ═══════════════════════════════════════════════════════════════════════════ */

const COLORS = [
  '#8FA8FF','#4CC38A','#E0A23A','#F2766B','#A9BCFF',
  '#6F7787','#7FD8AE','#F0BE6A','#F59B92','#C9D4FF',
];

export function getColors(n) {
  const out = [];
  for (let i = 0; i < n; i++) out.push(COLORS[i % COLORS.length]);
  return out;
}

/* ── Global defaults ───────────────────────────────────────────────────── */
Chart.defaults.color = '#8B93A3';
Chart.defaults.borderColor = 'rgba(255,255,255,.06)';
Chart.defaults.font.family = "'Inter', system-ui, sans-serif";
Chart.defaults.font.size = 12;

/* ── Shared dark-glass tooltip plugin ──────────────────────────────────── */
const tooltipPlugin = {
  backgroundColor: 'rgba(10, 20, 25, 0.92)',
  borderColor: 'rgba(20, 184, 166, 0.35)',
  borderWidth: 1,
  titleColor: '#e8f4f2',
  bodyColor: '#8fa8a3',
  padding: { top: 10, bottom: 10, left: 14, right: 14 },
  cornerRadius: 10,
  titleFont: { family: "'Plus Jakarta Sans', 'Inter', system-ui", weight: '700', size: 13 },
  bodyFont: { family: "'Inter', system-ui", size: 12 },
  caretSize: 6,
  displayColors: true,
  boxWidth: 10,
  boxHeight: 10,
  boxPadding: 4,
  callbacks: {
    label(ctx) {
      let val = ctx.raw;
      if (val === undefined || typeof val === 'object') {
        const isHorizontal = ctx.chart?.options?.indexAxis === 'y';
        val = isHorizontal ? ctx.parsed.x : ctx.parsed.y;
      }
      val = val ?? ctx.parsed ?? 0;
      const num = Number(val);
      if (isNaN(num)) return ` ${val}`;
      return ` ₹${num.toLocaleString('en-IN', { minimumFractionDigits: 0, maximumFractionDigits: 2 })}`;
    }
  }
};

/* ── Doughnut ───────────────────────────────────────────────────────────── */
export function createDoughnut(ctx, labels, data, title = '', onClickHandler = null) {
  return new Chart(ctx, {
    type: 'doughnut',
    data: {
      labels,
      datasets: [{
        data,
        backgroundColor: getColors(data.length),
        borderWidth: 2,
        borderColor: 'rgba(5, 13, 18, 0.8)',
        hoverOffset: 10,
        hoverBorderWidth: 0,
      }]
    },
    options: {
      responsive: true, maintainAspectRatio: false,
      cutout: '70%',
      animation: { animateRotate: true, animateScale: true, duration: 700, easing: 'easeInOutQuart' },
      onClick: (e, elements) => {
        if (elements.length > 0 && onClickHandler) {
          const index = elements[0].index;
          onClickHandler(labels[index], data[index]);
        }
      },
      onHover: (e, elements) => {
        e.native.target.style.cursor = (elements.length > 0 && onClickHandler) ? 'pointer' : 'default';
      },
      plugins: {
        legend: {
          position: 'bottom',
          labels: { padding: 18, usePointStyle: true, pointStyleWidth: 10, color: '#8fa8a3' }
        },
        title: { display: !!title, text: title, font: { size: 14, weight: '700' }, color: '#e8f4f2' },
        tooltip: tooltipPlugin,
      }
    }
  });
}

/* ── Line ───────────────────────────────────────────────────────────────── */
export function createLine(ctx, labels, datasets, title = '') {
  return new Chart(ctx, {
    type: 'line',
    data: {
      labels,
      datasets: datasets.map((ds, i) => {
        const isIncome = ds.label && ds.label.toLowerCase() === 'income';
        const isExpense = ds.label && ds.label.toLowerCase() === 'expense';
        // Money direction keeps one meaning everywhere: green in, red out.
        const color = isIncome ? '#4CC38A' : isExpense ? '#F2766B' : COLORS[i % COLORS.length];
        return {
          ...ds,
          borderColor: color,
          backgroundColor: 'transparent',
          fill: false,               // no invented area between sparse points
          tension: 0,                // straight segments: never overshoot the data
          pointRadius: 3,
          pointHoverRadius: 6,
          pointBackgroundColor: color,
          pointBorderColor: '#151922',
          pointBorderWidth: 2,
          borderWidth: 2,
          // A second cue besides hue, so the series survive colour-blindness.
          borderDash: isExpense ? [5, 4] : [],
        };
      })
    },
    options: {
      responsive: true, maintainAspectRatio: false,
      animation: { duration: 300 },
      interaction: { mode: 'index', intersect: false },
      plugins: {
        legend: {
          position: 'bottom',
          labels: { padding: 16, usePointStyle: true, color: '#8B93A3', boxWidth: 8 }
        },
        title: { display: !!title, text: title, font: { size: 13, weight: '600' }, color: '#E7E9EE' },
        tooltip: { ...tooltipPlugin },
      },
      scales: {
        x: {
          grid: { display: false },
          ticks: { color: '#6F7787', maxRotation: 0, autoSkip: true },
        },
        y: {
          beginAtZero: true,
          grid: { color: 'rgba(255,255,255,.05)' },
          border: { display: false },
          ticks: {
            color: '#6F7787',
            callback: (v) => '₹' + Number(v).toLocaleString('en-IN'),
          },
        }
      }
    }
  });
}

/* ── Bar (horizontal, income by category) ───────────────────────────────── */
export function createBar(ctx, labels, data, title = '') {
  return new Chart(ctx, {
    type: 'bar',
    data: {
      labels,
      datasets: [{
        data,
        backgroundColor: getColors(data.length).map(c => c + 'bb'),
        hoverBackgroundColor: getColors(data.length),
        borderRadius: 8,
        borderWidth: 0,
        maxBarThickness: 36,
      }]
    },
    options: {
      indexAxis: 'y',
      responsive: true, maintainAspectRatio: false,
      animation: { duration: 600, easing: 'easeInOutCubic' },
      plugins: {
        legend: { display: false },
        title: { display: !!title, text: title, font: { size: 14, weight: '700' }, color: '#e8f4f2' },
        tooltip: { ...tooltipPlugin },
      },
      scales: {
        x: {
          beginAtZero: true,
          grid: { color: 'rgba(255,255,255,.04)' },
          ticks: {
            color: '#5a726d',
            callback: (v) => '₹' + Number(v).toLocaleString('en-IN'),
          },
        },
        y: {
          grid: { display: false },
          ticks: { color: '#8fa8a3' },
        }
      }
    }
  });
}

export function destroyChart(chart) {
  if (chart) chart.destroy();
  return null;
}
