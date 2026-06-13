/* ═══════════════════════════════════════════════════════════════════════════
   charts.js — Chart.js helpers with PFA colour palette
   ═══════════════════════════════════════════════════════════════════════════ */

const COLORS = [
  '#14b8a6','#2dd4bf','#f97316','#22c993','#eab308',
  '#38bdf8','#06b6d4','#fb923c','#34d399','#0d9488',
  '#67e8f9','#5eead4'
];

export function getColors(n) {
  const out = [];
  for (let i = 0; i < n; i++) out.push(COLORS[i % COLORS.length]);
  return out;
}

/* ── Global defaults ───────────────────────────────────────────────────── */
Chart.defaults.color = '#8fa8a3';
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
        let color = COLORS[i % COLORS.length];
        if (ds.label && ds.label.toLowerCase() === 'income') color = '#10b981'; // Green
        if (ds.label && ds.label.toLowerCase() === 'expense') color = '#f43f5e'; // Red
        return {
          ...ds,
          borderColor: color,
          backgroundColor: color + '15',
          fill: true,
          tension: 0.45,
          pointRadius: 4,
          pointHoverRadius: 8,
          pointBackgroundColor: color,
          pointBorderColor: 'rgba(5,13,18,0.8)',
          pointBorderWidth: 2,
          borderWidth: 2.5,
        };
      })
    },
    options: {
      responsive: true, maintainAspectRatio: false,
      animation: { duration: 600, easing: 'easeInOutCubic' },
      interaction: { mode: 'index', intersect: false },
      plugins: {
        legend: {
          position: 'bottom',
          labels: { padding: 18, usePointStyle: true, color: '#8fa8a3' }
        },
        title: { display: !!title, text: title, font: { size: 14, weight: '700' }, color: '#e8f4f2' },
        tooltip: { ...tooltipPlugin },
      },
      scales: {
        x: {
          grid: { display: false },
          ticks: { color: '#5a726d', maxRotation: 0 },
        },
        y: {
          beginAtZero: true,
          grid: { color: 'rgba(255,255,255,.04)' },
          ticks: {
            color: '#5a726d',
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
