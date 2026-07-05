-- This file is automatically executed by Hibernate after schema creation.

-- Create partial index for fast access to active transactions
CREATE INDEX IF NOT EXISTS idx_active_txns 
ON finance.transaction_entries (user_id) 
WHERE deleted_at IS NULL;

-- Create partial index for active recurring transactions
CREATE INDEX IF NOT EXISTS idx_active_recurring_txns 
ON finance.transaction_entries (user_id, next_run_date) 
WHERE deleted_at IS NULL AND is_recurring = true;
