import { describe, it, expect, beforeEach } from 'vitest';
import { renderSubscriptions } from '../../js/views/subscriptions.js';
import { Auth } from '../../js/utils/api.js';

function jsonResponse(data) {
  return { status: 200, ok: true, text: async () => JSON.stringify(data) };
}

function isoDay(offsetDays) {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function sub(overrides) {
  return {
    id: 1, name: 'Amul milk delivery', amount: 55.96, currency: 'INR',
    period: 'DAILY', nextChargeDate: isoDay(1), daysUntilCharge: 1, active: true,
    ...overrides,
  };
}

async function render(subs) {
  global.fetch.mockImplementation(() => Promise.resolve(jsonResponse(subs)));
  const container = document.createElement('div');
  document.body.appendChild(container);
  await renderSubscriptions(container);
  return container;
}

describe('renderSubscriptions — countdowns', () => {
  beforeEach(() => {
    Auth.save({ token: 't', refreshToken: 'r', userId: 'u1', email: 'a@b.com' });
    document.body.innerHTML = '';
    global.fetch.mockReset();
  });

  it('shows the actual charge, not the monthly projection', async () => {
    const container = await render([sub({ amount: 64, nextChargeDate: isoDay(1) })]);

    const amount = container.querySelector('.subs-card-amount')?.textContent || '';
    expect(amount).toContain('64.00 per charge');
    expect(amount).not.toContain('1,920.00'); // the daily × 30 projection stays in the headline
  });

  it('labels a future charge from its date', async () => {
    const container = await render([sub({ nextChargeDate: isoDay(5), daysUntilCharge: 5 })]);

    expect(container.querySelector('.subs-card-meta')?.textContent).toContain('in 5 days');
  });

  it('never calls a past date "tomorrow" because the stored countdown says 1', async () => {
    // A row frozen by the nightly job: the date moved on, the snapshot did not.
    const container = await render([sub({ nextChargeDate: isoDay(-1), daysUntilCharge: 1 })]);

    const meta = container.querySelector('.subs-card-meta')?.textContent || '';
    expect(meta).toContain('overdue');
    expect(meta).not.toContain('tomorrow');
  });

  it('excludes past charges from the "Due this week" strip', async () => {
    const container = await render([sub({ nextChargeDate: isoDay(-2), daysUntilCharge: 1 })]);

    expect(container.querySelector('.upcoming-strip')).toBeNull();
  });

  it('asks for the countdown in the browser timezone', async () => {
    await render([sub()]);

    const url = String(global.fetch.mock.calls[0][0]);
    expect(url).toContain(`tzOffsetMinutes=${-new Date().getTimezoneOffset()}`);
  });
});
