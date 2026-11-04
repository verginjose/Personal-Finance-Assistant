import { api, Auth, toast } from '../utils/api.js?v=2026110417';
import { esc, pageHeader, emptyState, formatMoney, formatDate, confirmModal, openModal, modalActions } from '../utils/ui.js?v=2026110417';

const PERIOD_MONTHS = { DAILY: 30, WEEKLY: 4.345, MONTHLY: 1, YEARLY: 1 / 12 };

export async function renderSubscriptions(container) {
  const userId = Auth.getUserId();
  container.innerHTML = `
    ${pageHeader('Subscriptions', '')}
    <div class="subs-summary" id="subs-summary"></div>
    <div id="subs-grid" class="fade-up"></div>`;

  await loadSubscriptions(userId);

  async function loadSubscriptions(uid) {
    const grid = document.getElementById('subs-grid');
    const summary = document.getElementById('subs-summary');
    grid.innerHTML = '<div class="spinner-center"><span class="spinner"></span></div>';
    try {
      const data = await api.get('/upsert/subscriptions', { userId: uid });
      if (!data?.length) {
        if (summary) summary.innerHTML = '';
        grid.innerHTML = `<div class="card">${emptyState('check-circle', 'No subscriptions detected', 'Mark transactions as recurring to track them here.')}</div>`;
        return;
      }

      const currency = Auth.getDefaultCurrency();
      const sorted = [...data].sort((a, b) => monthly(b) - monthly(a));
      const monthlyTotal = sorted.reduce((sum, s) => sum + monthly(s), 0);

      if (summary) {
        summary.innerHTML = `
          <strong>${formatMoney(monthlyTotal, currency)}</strong>
          <span>a month in recurring charges · ${sorted.length} charge${sorted.length === 1 ? '' : 's'}</span>`;
      }

      grid.innerHTML = `
        <div class="table-wrap">
          <table class="subs-table">
            <thead><tr>
              <th>Subscription</th><th>Frequency</th><th>Amount</th>
              <th style="text-align:right">Per month</th><th>Next charge</th><th></th>
            </tr></thead>
            <tbody>
              ${sorted.map(subRow).join('')}
            </tbody>
          </table>
        </div>`;

      sorted.forEach(sub => {
        document.getElementById(`sub-row-${sub.id}`)?.addEventListener('click', (e) => {
          if (e.target.closest('.subs-not-a-sub')) return;
          editSub(sub, uid);
        });
        document.getElementById(`dismiss-${sub.id}`)?.addEventListener('click', (e) => {
          e.stopPropagation();
          dismissSub(sub, uid);
        });
      });
    } catch (err) { toast(err.message, 'error'); }
  }

  async function dismissSub(sub, uid) {
    if (!(await confirmModal('Stop tracking', `Stop tracking ${sub.name}? It stays in your transactions.`, 'Stop tracking'))) return;
    try {
      await api.delete(`/upsert/subscriptions/${sub.id}/deactivate`, { userId: uid });
      toast('No longer tracked', 'success');
      await loadSubscriptions(uid);
    } catch (err) { toast(err.message, 'error'); }
  }

  function editSub(sub, uid) {
    const periods = ['DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY'];
    openModal('Edit subscription', `
      <form id="edit-sub-form">
        <div class="form-group">
          <label>Name</label>
          <input type="text" class="form-input" name="name" value="${esc(sub.name)}" required>
        </div>
        <div class="form-group">
          <label>Amount</label>
          <input type="number" step="0.01" class="form-input" name="amount" value="${sub.amount}" required>
        </div>
        <div class="form-group">
          <label>Frequency</label>
          <select class="form-select" name="period" required>
            ${periods.map(p => `<option value="${p}" ${p === sub.period ? 'selected' : ''}>${titleCase(p)}</option>`).join('')}
          </select>
        </div>
        ${modalActions('Cancel', 'Save changes')}
      </form>
    `, {
      onSubmit: async (form) => {
        const body = {
          name: form.name.value,
          amount: parseFloat(form.amount.value),
          period: form.period.value
        };
        await api.put(`/upsert/subscriptions/${sub.id}`, body, { params: { userId: uid } });
        toast('Changes saved', 'success');
        await loadSubscriptions(uid);
      }
    });
  }
}

function monthly(sub) {
  const factor = PERIOD_MONTHS[sub.period] ?? 1;
  return Number(sub.amount || 0) * factor;
}

function titleCase(value) {
  return String(value || '').toLowerCase().replace(/\b\w/g, c => c.toUpperCase());
}

function nextChargeLabel(sub) {
  if (sub.daysUntilCharge === 0) return 'Today';
  if (sub.daysUntilCharge === 1) return 'Tomorrow';
  if (sub.daysUntilCharge != null) return `In ${sub.daysUntilCharge} days`;
  return formatDate(sub.nextChargeDate);
}

function subRow(sub) {
  const currency = sub.currency || Auth.getDefaultCurrency();
  return `
    <tr id="sub-row-${sub.id}" style="cursor:pointer">
      <td class="subs-name">${esc(sub.name)}</td>
      <td>${esc(titleCase(sub.period))}</td>
      <td>${formatMoney(sub.amount, currency)}</td>
      <td class="subs-amount">${formatMoney(monthly(sub), currency)}</td>
      <td class="subs-when">${esc(nextChargeLabel(sub))}</td>
      <td style="text-align:right">
        <button type="button" class="subs-link subs-not-a-sub" id="dismiss-${sub.id}">Not a subscription</button>
      </td>
    </tr>`;
}
