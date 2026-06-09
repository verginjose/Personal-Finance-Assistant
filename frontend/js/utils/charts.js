/* ═══════════════════════════════════════════════════════════════════════════
   charts.js — Chart.js helpers with PFA colour palette
   ═══════════════════════════════════════════════════════════════════════════ */

const COLORS = [
  '#7c5cfc','#c084fc','#f97068','#22c993','#f5b731',
  '#38bdf8','#e879f9','#fb923c','#34d399','#a78bfa',
  '#f472b6','#67e8f9'
];

export function getColors(n) {
  const out = [];
  for (let i = 0; i < n; i++) out.push(COLORS[i % COLORS.length]);
  return out;
}

Chart.defaults.color = '#8888a4';
Chart.defaults.borderColor = 'rgba(255,255,255,.06)';
Chart.defaults.font.family = "'Inter', system-ui, sans-serif";

export function createDoughnut(ctx, labels, data, title = '') {
  return new Chart(ctx, {
    type: 'doughnut',
    data: {
      labels,
      datasets: [{
        data,
        backgroundColor: getColors(data.length),
        borderWidth: 0,
        hoverOffset: 8
      }]
    },
    options: {
      responsive: true, maintainAspectRatio: false,
      cutout: '68%',
      plugins: {
        legend: { position: 'bottom', labels: { padding: 16, usePointStyle: true, pointStyleWidth: 10 } },
        title: { display: !!title, text: title, font: { size: 14, weight: 600 } }
      }
    }
  });
}

export function createLine(ctx, labels, datasets, title = '') {
  return new Chart(ctx, {
    type: 'line',
    data: { labels, datasets: datasets.map((ds, i) => {
      const color = COLORS[i % COLORS.length];
      return {
        ...ds,
        borderColor: color,
        backgroundColor: color + '18',
        fill: true,
        tension: .4,
        pointRadius: 3,
        pointHoverRadius: 6,
        borderWidth: 2
      };
    })},
    options: {
      responsive: true, maintainAspectRatio: false,
      interaction: { mode: 'index', intersect: false },
      plugins: {
        legend: { position: 'bottom', labels: { padding: 16, usePointStyle: true } },
        title: { display: !!title, text: title, font: { size: 14, weight: 600 } }
      },
      scales: {
        x: { grid: { display: false } },
        y: { beginAtZero: true }
      }
    }
  });
}

export function createBar(ctx, labels, data, title = '') {
  return new Chart(ctx, {
    type: 'bar',
    data: {
      labels,
      datasets: [{
        data,
        backgroundColor: getColors(data.length),
        borderRadius: 6,
        borderWidth: 0,
        maxBarThickness: 40
      }]
    },
    options: {
      indexAxis: 'y',
      responsive: true, maintainAspectRatio: false,
      plugins: {
        legend: { display: false },
        title: { display: !!title, text: title, font: { size: 14, weight: 600 } }
      },
      scales: {
        x: { beginAtZero: true, grid: { color: 'rgba(255,255,255,.04)' } },
        y: { grid: { display: false } }
      }
    }
  });
}

export function destroyChart(chart) {
  if (chart) chart.destroy();
  return null;
}
