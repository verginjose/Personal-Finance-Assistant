-- Performance indexes for the read (query-service) path.
--
-- These were previously created ad-hoc on live databases and never captured in
-- db-init, so a rebuilt database silently lost them. Each is IF NOT EXISTS so
-- re-running is safe.
--
-- NOTE: db-init runs only against a freshly created database (empty), where a
-- plain CREATE INDEX is fine. To add any of these to a *live* database, use
-- CREATE INDEX CONCURRENTLY (outside a transaction) so writes are not blocked.

-- Ledger list/count JOINs groups.expense_transaction_links ON
-- l.transaction_entry_id = t.id. Without this index PostgreSQL seq-scans the
-- entire (large, ~350k row) table to answer every ledger request.
CREATE INDEX IF NOT EXISTS idx_expense_txn_links_entry
    ON groups.expense_transaction_links (transaction_entry_id);

-- Group balance LATERAL subquery joins on (shared_expense_id, user_id).
CREATE INDEX IF NOT EXISTS idx_expense_splits_expense_user
    ON groups.expense_splits (shared_expense_id, user_id);

-- Group balance "paid" LATERAL (group_id, paid_by) and the recent-expenses
-- list ordering (group_id, created_at DESC, id DESC).
CREATE INDEX IF NOT EXISTS idx_shared_expenses_group_payer
    ON groups.shared_expenses (group_id, paid_by);
CREATE INDEX IF NOT EXISTS idx_shared_expenses_group_recent
    ON groups.shared_expenses (group_id, created_at DESC, id DESC);

-- Ledger filter/sort paths (previously created by the old JPA layer, not by
-- db-init, so a fresh database was missing them).
CREATE INDEX IF NOT EXISTS idx_transaction_user_type_created
    ON finance.transaction_entries (user_id, type, created_at);
CREATE INDEX IF NOT EXISTS idx_transaction_user_deleted
    ON finance.transaction_entries (user_id, deleted_at);
