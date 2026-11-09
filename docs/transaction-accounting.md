# Categories and transaction reporting

Transactions keep their original positive amount, currency, and direction (`INCOME` for money received; `EXPENSE` for money paid). `transactionKind` explains how that movement affects reports:

| Treatment | Reporting |
| --- | --- |
| `STANDARD` | Earned income or consumption spending |
| `TRANSFER` | Excluded from earned/spent totals |
| `INVESTMENT` | Savings/investment movement; excluded from earned/spent totals |
| `REFUND` | Incoming payment that reduces the linked expense category |
| `REIMBURSEMENT` | Incoming payment that reduces the linked expense category |

The transaction form exposes these treatments and a personal `subcategory` label (up to 80 characters) under the selected category. Category descriptions clarify freelance, side-hustle, business, investment earnings, and travel overlaps. New income categories include commission and capital gains. New expense categories cover furniture, household supplies, domestic help, society maintenance, bank/card fees, loan interest, software/cloud subscriptions, and professional memberships.

Credit card repayments, SIPs, stocks, crypto, deposits, emergency-fund and goal contributions, settlements, own-account transfers, borrowing, lending, loan principal, and investment withdrawals are excluded by category too. This handles existing records without rewriting them. Whole EMI/loan-repayment categories retain their existing expense behavior because their principal/interest breakdown is unknown. For precise reports, record principal as a transfer and interest as an expense. Record returned investment capital as an investment withdrawal and earnings separately as capital gains, interest, or dividends.

Refunds and reimbursements require `originalTransactionId`, reference the same user's active personal expense in the same currency, inherit its category/subcategory, and cannot recur. Their combined active amount cannot exceed the original amount. Validation locks the original row to protect against concurrent refunds. Remove linked recoveries before changing or deleting an original expense; shared expense corrections belong in the group. Recoveries affect the period when received, so a later period can have negative net spending in a category. Analytics shows signed category bars in that case.

Summary, category/timeline analytics, budgets, and advisor input apply the same rules. The ledger retains all movements and shows their direction and treatment. Its daily net is the cash movement of the displayed rows, which can differ from earned income minus consumption spending.

## OCR and imports

OCR recognizes the added expense categories and common movement/refund/reimbursement language. It never guesses an internal transaction link. Scanned recoveries pass into the transaction form for selection of the original expense. A tax refund remains income. Review the detected treatment before saving.

CSV imports accept the existing category columns and the unified `category` column, plus `transactionKind`, `originalTransactionId`, and `subcategory`. CSV exports include these fields. Batch review exposes treatment, category, subcategory, and the original expense ID. Links must reference existing expenses; exported IDs are not remapped to newly imported rows. A full-ledger export containing recoveries therefore needs its links reviewed before being imported into a different database. Amounts remain in their original currency throughout import.

## Deployment

Apply `db-init/010_transaction_accounting.sql` to the existing primary database **before** deploying both Java services. It adds the three metadata columns, a recovery lookup index, and widens category check constraints on transactions, budgets, and shared expenses. It is safe to rerun and skips absent Hibernate-managed tables on a fresh database. Hibernate creates the fields on fresh installations. Deploy command-service, query-service, OCR, and frontend together; report cache keys have been versioned to avoid old-policy totals.

No historical amounts or category IDs are rewritten. Historical records with excluded movement categories will produce lower earned/spent totals under the corrected policy. Legacy `INVESTMENTS` income remains supported because its capital/profit breakdown cannot be inferred.

## Verification

Coverage includes catalog parity across frontend/Java/OCR, OCR movement and recovery detection, CSV metadata, scanner handoff without currency conversion, signed category displays, ownership/currency/cap validation, PostgreSQL summary/budget/category/timeline totals, and two simultaneous partial refunds. The migration was tested on a legacy schema, a rerun, and a fresh schema.

The command-service suite has seven pre-existing failures in GoalBudgetControllerTest, SplitControllerTest, GoalBudgetServiceTest, and SplitServiceUpdateTest; all seven were reproduced against the unchanged HEAD in an isolated checkout. The focused accounting tests, PostgreSQL accounting tests, query-service unit suite, frontend suite, and OCR suites pass.
