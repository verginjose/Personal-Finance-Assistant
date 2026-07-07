import { api, Auth, toast } from '../utils/api.js?v=2026070701';
import { esc, pageHeader, emptyState, formatCurrency, formatDate, confirmModal, openModal, modalActions } from '../utils/ui.js?v=2026070701';

export async function renderSubscriptions(container) {
  const userId = Auth.getUserId();
  container.innerHTML = `
    ${pageHeader('Subscription Tracker', 'Detect recurring charges draining your wallet')}
    <div id="subs-grid" class="fade-up"></div>`;

  await loadSubscriptions(userId);

  async function loadSubscriptions(uid) {
    const grid = document.getElementById('subs-grid');
    grid.innerHTML = '<div class="spinner-center"><span class="spinner"></span></div>';
    try {
      const data = await api.get('/upsert/subscriptions', { userId: uid });
      if (!data?.length) {
        grid.innerHTML = `<div class="card">${emptyState('check-circle', 'No subscriptions detected', 'Mark transactions as recurring to track them here.')}</div>`;
        return;
      }
      grid.innerHTML = `<div class="sub-grid">${data.map(subCard).join('')}</div>`;
      data.forEach(sub => {
        const cardEl = document.getElementById(`sub-card-${sub.id}`);
        const dismissBtn = document.getElementById(`dismiss-${sub.id}`);
        if (dismissBtn) {
          dismissBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            dismissSub(sub.id, uid);
          });
        }
        if (cardEl) {
          cardEl.addEventListener('click', () => editSub(sub, uid));
        }
      });
    } catch (err) { toast(err.message, 'error'); }
  }

  async function dismissSub(id, uid) {
    if (!(await confirmModal('Dismiss Subscription', 'Dismiss this subscription alert?', 'Dismiss'))) return;
    try {
      await api.delete(`/upsert/subscriptions/${id}/deactivate`, { userId: uid });
      toast('Subscription dismissed', 'success');
      await loadSubscriptions(uid);
    } catch (err) { toast(err.message, 'error'); }
  }

  function editSub(sub, uid) {
    const periods = ['DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY'];
    openModal('Edit Subscription', `
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
          <label>Period</label>
          <select class="form-select" name="period" required>
            ${periods.map(p => `<option value="${p}" ${p === sub.period ? 'selected' : ''}>${p}</option>`).join('')}
          </select>
        </div>
        ${modalActions('Save Changes')}
      </form>
    `, {
      onSubmit: async (form) => {
        const body = {
          name: form.name.value,
          amount: parseFloat(form.amount.value),
          period: form.period.value
        };
        await api.put(`/upsert/subscriptions/${sub.id}`, body, { params: { userId: uid } });
        toast('Subscription updated', 'success');
        await loadSubscriptions(uid);
      }
    });
  }
}

function subCard(sub) {
  const color = sub.daysUntilCharge <= 3 ? 'var(--accent)' :
                sub.daysUntilCharge <= 7 ? 'var(--accent-y)' : 'var(--accent-g)';
  const urgency = sub.daysUntilCharge === 0 ? 'Due today' :
                  sub.daysUntilCharge === 1 ? '1 day' :
                  `${sub.daysUntilCharge} days`;
  return `
    <div class="card sub-card cursor-pointer" id="sub-card-${sub.id}" style="--sub-color:${color}; cursor: pointer;">
      <div style="display:flex;justify-content:space-between;align-items:flex-start">
        <div>
          <h3 style="font-weight:700;font-size:1.05rem;margin-bottom:4px">${esc(sub.name)}</h3>
          <span class="badge badge-info">${esc(sub.period)}</span>
        </div>
        <div class="sub-urgency" style="color:${color}">${urgency}</div>
      </div>
      <div style="margin-top:16px;display:flex;justify-content:space-between;align-items:center">
        <div>
          <div style="font-size:1.4rem;font-weight:800">${formatCurrency(sub.amount, sub.currency)}</div>
          <div style="font-size:.8rem;color:var(--text-muted);margin-top:2px">Next: ${formatDate(sub.nextChargeDate)}</div>
        </div>
        <button id="dismiss-${sub.id}" class="btn btn-secondary btn-sm">Dismiss</button>
      </div>
    </div>`;
}
