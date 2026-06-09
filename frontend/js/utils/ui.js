/* ═══════════════════════════════════════════════════════════════════════════
   ui.js — Shared UI helpers, formatters, and component builders
   ═══════════════════════════════════════════════════════════════════════════ */

export function esc(s) {
  const d = document.createElement('div');
  d.textContent = s ?? '';
  return d.innerHTML;
}

export function formatCurrency(n, currency = 'INR') {
  const sym = currency === 'INR' ? '₹' : currency === 'USD' ? '$' : currency === 'EUR' ? '€' : currency === 'GBP' ? '£' : '';
  const num = Number(n || 0);
  if (sym) {
    return sym + num.toLocaleString('en-IN', { minimumFractionDigits: 0, maximumFractionDigits: 2 });
  }
  return `${currency} ${num.toLocaleString('en-IN', { minimumFractionDigits: 2 })}`;
}

export function formatCategory(cat) {
  return (cat || '—').replace(/_/g, ' ');
}

export function formatDate(iso) {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

export function healthScoreColor(score) {
  if (score >= 700) return 'var(--accent-g)';
  if (score >= 500) return 'var(--accent-y)';
  return 'var(--accent)';
}

export function budgetStatusColor(status) {
  if (status === 'EXCEEDED') return 'var(--accent)';
  if (status === 'WARNING') return 'var(--accent-y)';
  return 'var(--accent-g)';
}

export function pageHeader(title, subtitle, actionsHtml = '') {
  return `
    <div class="page-header fade-up">
      <div class="page-header-text">
        <h1>${esc(title)}</h1>
        ${subtitle ? `<p>${esc(subtitle)}</p>` : ''}
      </div>
      ${actionsHtml ? `<div class="page-header-actions">${actionsHtml}</div>` : ''}
    </div>`;
}

export function emptyState(icon, title, subtitle = '') {
  return `
    <div class="empty-state">
      <div class="empty-icon">${icon}</div>
      <h3>${esc(title)}</h3>
      ${subtitle ? `<p>${esc(subtitle)}</p>` : ''}
    </div>`;
}

export function skeletonKpiRow(count = 4) {
  return `<div class="card-grid card-grid-4">${Array.from({ length: count }, () =>
    `<div class="card stat-card skeleton-card"><div class="skeleton skeleton-icon"></div><div class="skeleton skeleton-value"></div><div class="skeleton skeleton-label"></div></div>`
  ).join('')}</div>`;
}

export function skeletonChart(height = 280) {
  return `<div class="skeleton skeleton-chart" style="height:${height}px"></div>`;
}

export function progressBar(pct, color = 'var(--primary)') {
  const width = Math.min(Math.max(pct, 0), 100);
  return `
    <div class="progress-track" role="progressbar" aria-valuenow="${width}" aria-valuemin="0" aria-valuemax="100">
      <div class="progress-fill" style="width:${width}%;background:${color}"></div>
    </div>`;
}

export function badge(text, variant = 'info') {
  return `<span class="badge badge-${variant}">${esc(text)}</span>`;
}

export function typeBadge(type) {
  return badge(type, type === 'INCOME' ? 'income' : 'expense');
}

export function insightIcon(type) {
  if (type === 'WARNING') return '⚠️';
  if (type === 'ACHIEVEMENT') return '🎉';
  return '💡';
}

export function insightColor(type) {
  if (type === 'WARNING') return 'var(--accent)';
  if (type === 'ACHIEVEMENT') return 'var(--accent-g)';
  return 'var(--accent-b)';
}

export function healthPanelHtml(prefix) {
  return `
    <div class="card insight-card insight-card--health fade-up">
      <div class="insight-card-header">
        <span class="insight-card-icon">🛡️</span>
        <h3>Financial Health Score</h3>
      </div>
      <div class="health-panel">
        <div class="health-ring" id="${prefix}-health-ring">
          <div class="health-ring-inner">
            <span class="health-score" id="${prefix}-health-score">—</span>
            <span class="health-grade" id="${prefix}-health-grade">Grade —</span>
          </div>
        </div>
        <div class="health-details">
          <p id="${prefix}-health-summary" class="health-summary">Calculating your financial health…</p>
          <div id="${prefix}-health-breakdown" class="health-breakdown"></div>
        </div>
      </div>
    </div>`;
}

export function aiPanelHtml(prefix) {
  return `
    <div class="card insight-card insight-card--ai fade-up">
      <div class="insight-card-header">
        <span class="insight-card-icon">💡</span>
        <h3>AI Financial Advisor</h3>
      </div>
      <p id="${prefix}-ai-summary" class="ai-summary">Analyzing your transactions…</p>
      <div id="${prefix}-ai-insights-list" class="ai-insights-list"></div>
    </div>`;
}

export function renderHealthData(prefix, health) {
  if (!health) return;
  const elScore = document.getElementById(`${prefix}-health-score`);
  const elRing = document.getElementById(`${prefix}-health-ring`);
  if (elScore) {
    elScore.textContent = health.totalScore;
    elScore.style.color = healthScoreColor(health.totalScore);
  }
  if (elRing) {
    const pct = Math.min(health.totalScore / 10, 100);
    elRing.style.setProperty('--ring-pct', pct);
    elRing.style.setProperty('--ring-color', healthScoreColor(health.totalScore));
  }
  const elGrade = document.getElementById(`${prefix}-health-grade`);
  if (elGrade) elGrade.textContent = `Grade ${health.grade}`;
  const elSummary = document.getElementById(`${prefix}-health-summary`);
  if (elSummary) elSummary.textContent = health.summary;
  const elBreakdown = document.getElementById(`${prefix}-health-breakdown`);
  if (elBreakdown && health.breakdown) {
    elBreakdown.innerHTML = Object.entries(health.breakdown).map(([k, v]) =>
      `<span class="chip">${esc(k)}: <strong>${esc(String(v))}</strong></span>`
    ).join('');
  }
}

export function renderAiData(prefix, ai) {
  if (!ai) return;
  const elSummary = document.getElementById(`${prefix}-ai-summary`);
  if (elSummary) elSummary.textContent = ai.summary;
  const elList = document.getElementById(`${prefix}-ai-insights-list`);
  if (!elList || !ai.insights) return;
  elList.innerHTML = ai.insights.map(ins => `
    <div class="insight-item" style="--insight-color:${insightColor(ins.type)}">
      <span class="insight-item-icon">${insightIcon(ins.type)}</span>
      <div>
        <strong>${esc(ins.title)}</strong>
        <p>${esc(ins.message)}</p>
      </div>
    </div>`).join('');
}

export function openModal(title, bodyHtml, { onSubmit, submitLabel = 'Save', size = '' } = {}) {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.innerHTML = `
    <div class="modal ${size}" role="dialog" aria-modal="true" aria-labelledby="modal-title">
      <div class="modal-header">
        <h2 id="modal-title">${esc(title)}</h2>
        <button type="button" class="btn btn-icon btn-ghost modal-close" aria-label="Close">✕</button>
      </div>
      <div class="modal-body">${bodyHtml}</div>
    </div>`;
  document.body.appendChild(overlay);

  const close = () => overlay.remove();
  overlay.querySelector('.modal-close').onclick = close;
  overlay.onclick = (e) => { if (e.target === overlay) close(); };

  const cancelBtn = overlay.querySelector('[data-modal-cancel]');
  if (cancelBtn) cancelBtn.onclick = close;

  const form = overlay.querySelector('form');
  if (form && onSubmit) {
    form.onsubmit = async (e) => {
      e.preventDefault();
      const submitBtn = form.querySelector('[type="submit"]');
      if (submitBtn) {
        submitBtn.disabled = true;
        submitBtn.dataset.originalText = submitBtn.textContent;
        submitBtn.innerHTML = '<span class="spinner"></span>';
      }
      try {
        await onSubmit(form, overlay);
        close();
      } catch (err) {
        if (submitBtn) {
          submitBtn.disabled = false;
          submitBtn.textContent = submitBtn.dataset.originalText || submitLabel;
        }
        const { toast } = await import('./api.js');
        toast(err.message || 'Something went wrong', 'error');
      }
    };
  }

  return { overlay, close };
}

export function modalActions(cancelLabel = 'Cancel', submitLabel = 'Save') {
  return `
    <div class="modal-actions">
      <button type="button" class="btn btn-secondary" data-modal-cancel>${esc(cancelLabel)}</button>
      <button type="submit" class="btn btn-primary">${esc(submitLabel)}</button>
    </div>`;
}

export function dataField(label, value) {
  return `
    <div class="data-field">
      <span class="data-field-label">${esc(label)}</span>
      <span class="data-field-value">${esc(value ?? '—')}</span>
    </div>`;
}

export const EXPENSE_CATS = [
  'FOOD_AND_DINING', 'TRANSPORTATION', 'SHOPPING', 'ENTERTAINMENT',
  'BILLS_AND_UTILITIES', 'HEALTHCARE', 'TRAVEL', 'EDUCATION', 'OTHERS'
];

export const INCOME_CATS = [
  'SALARY', 'BUSINESS', 'INVESTMENTS', 'GIFTS', 'FREELANCE',
  'RENTAL_INCOME', 'INTEREST', 'OTHERS'
];

export function categoryOptions(cats, selected) {
  return cats.map(c =>
    `<option value="${c}" ${selected === c ? 'selected' : ''}>${formatCategory(c)}</option>`
  ).join('');
}
