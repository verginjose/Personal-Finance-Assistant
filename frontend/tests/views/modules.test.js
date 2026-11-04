import { describe, it, expect } from 'vitest';
import { renderDashboard } from '../../js/views/dashboard.js';
import { renderTransactions } from '../../js/views/transactions.js';
import { renderBillScanner } from '../../js/views/bill-scanner.js';
import { renderSplit } from '../../js/views/split.js';
import { renderAnalytics } from '../../js/views/analytics.js';
import { renderSubscriptions } from '../../js/views/subscriptions.js';
import { renderGoals } from '../../js/views/goals.js';
import { renderProfile } from '../../js/views/profile.js';

describe('view modules', () => {
  it('load every view without missing exports', () => {
    for (const render of [renderDashboard, renderTransactions, renderBillScanner,
      renderSplit, renderAnalytics, renderSubscriptions, renderGoals, renderProfile]) {
      expect(typeof render).toBe('function');
    }
  });
});
