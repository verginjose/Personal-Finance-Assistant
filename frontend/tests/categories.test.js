import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { EXPENSE_CATS, INCOME_CATS, SPENDING_CATS, MOVEMENT_CATS, transactionCategories, transactionKindLabel } from '../js/utils/ui.js';

describe('accounting categories', () => {
  it('keeps both Java catalogs in sync with frontend options', () => {
    const expected = [...EXPENSE_CATS, ...INCOME_CATS].sort();
    for (const [service, pkg] of [['command-service', 'command'], ['query-service', 'query']]) {
      const source = readFileSync(resolve(process.cwd(), `../${service}/src/main/java/com/finance/${pkg}/model/Category.java`), 'utf8');
      const actual = [...source.matchAll(/^    ([A-Z][A-Z_]+)[,\n]/gm)].map(m => m[1]).sort();
      expect(actual).toEqual(expected);
    }
  });
  it('offers spending categories for budgets and movements for transfers', () => {
    expect(SPENDING_CATS).toContain('BANK_CHARGES');
    expect(SPENDING_CATS).not.toContain('CREDIT_CARD_PAYMENT');
    expect(SPENDING_CATS.some(c => MOVEMENT_CATS.includes(c))).toBe(false);
    expect(transactionCategories('EXPENSE', 'TRANSFER')).toContain('OWN_ACCOUNT_TRANSFER');
    expect(transactionCategories('INCOME', 'INVESTMENT')).toContain('INVESTMENT_WITHDRAWAL');
    expect(transactionCategories('INCOME', 'REFUND')).toContain('GROCERIES');
    expect(transactionKindLabel({ category: 'CREDIT_CARD_PAYMENT' })).toBe('Money movement');
  });
});
