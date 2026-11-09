-- Run before deploying command-service and query-service. Safe to rerun.
-- New databases without these Hibernate-managed tables are handled on startup.
BEGIN;
DO $$
BEGIN
    IF to_regclass('finance.transaction_entries') IS NOT NULL THEN
        ALTER TABLE finance.transaction_entries
            ADD COLUMN IF NOT EXISTS transaction_kind VARCHAR(20) DEFAULT 'STANDARD',
            ADD COLUMN IF NOT EXISTS original_transaction_id BIGINT,
            ADD COLUMN IF NOT EXISTS subcategory VARCHAR(80);
        CREATE INDEX IF NOT EXISTS idx_transaction_recovery_original
            ON finance.transaction_entries (original_transaction_id)
            WHERE deleted_at IS NULL AND original_transaction_id IS NOT NULL;
        ALTER TABLE finance.transaction_entries DROP CONSTRAINT IF EXISTS transaction_entries_transaction_kind_check;
        ALTER TABLE finance.transaction_entries ADD CONSTRAINT transaction_entries_transaction_kind_check
            CHECK (transaction_kind IN ('STANDARD', 'TRANSFER', 'INVESTMENT', 'REFUND', 'REIMBURSEMENT'));
    END IF;
END $$;

DO $$
DECLARE existing_check RECORD;
BEGIN
    IF to_regclass('finance.transaction_entries') IS NOT NULL THEN
        -- Only replace the enum check on this exact column.
        FOR existing_check IN
            SELECT c.conname FROM pg_constraint c
            JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
            WHERE c.conrelid = 'finance.transaction_entries'::regclass AND c.contype = 'c'
              AND a.attname = 'category' AND array_length(c.conkey, 1) = 1
              AND pg_get_constraintdef(c.oid) LIKE '%ANY%'
        LOOP
            EXECUTE format('ALTER TABLE finance.transaction_entries DROP CONSTRAINT %I', existing_check.conname);
        END LOOP;
        ALTER TABLE finance.transaction_entries DROP CONSTRAINT IF EXISTS transaction_entries_category_check;
        ALTER TABLE finance.transaction_entries ADD CONSTRAINT transaction_entries_category_check
            CHECK (category IN ('SALARY', 'COMMISSION', 'CAPITAL_GAINS', 'FREELANCE', 'BUSINESS', 'INVESTMENTS', 'RENTAL_INCOME', 'DIVIDENDS', 'INTEREST', 'BONUS', 'PENSION', 'GOVT_BENEFITS', 'CASHBACK_REWARDS', 'GIFTS_RECEIVED', 'TAX_REFUND', 'SIDE_HUSTLE', 'OTHER_INCOME', 'RENT', 'HOME_LOAN_EMI', 'FURNITURE', 'HOUSEHOLD_SUPPLIES', 'DOMESTIC_HELP', 'SOCIETY_MAINTENANCE', 'HOME_MAINTENANCE', 'PROPERTY_TAX', 'ELECTRICITY', 'WATER', 'GAS', 'INTERNET', 'MOBILE_PHONE', 'OTT_SUBSCRIPTIONS', 'GROCERIES', 'RESTAURANTS', 'FOOD_DELIVERY', 'COFFEE_AND_SNACKS', 'FUEL', 'PUBLIC_TRANSPORT', 'CAB_AND_AUTO', 'VEHICLE_EMI', 'VEHICLE_MAINTENANCE', 'PARKING_AND_TOLLS', 'FLIGHT_AND_TRAIN', 'DOCTOR_AND_CLINIC', 'MEDICINES', 'HEALTH_INSURANCE', 'GYM_AND_FITNESS', 'MENTAL_WELLNESS', 'TUITION_AND_FEES', 'BOOKS_AND_COURSES', 'COACHING', 'STUDENT_LOAN_EMI', 'CLOTHING', 'ELECTRONICS', 'HOME_APPLIANCES', 'PERSONAL_CARE', 'GIFTS_GIVEN', 'MOVIES_AND_EVENTS', 'GAMING', 'SPORTS_AND_HOBBIES', 'BOOKS_AND_MAGAZINES', 'TRAVEL_VACATION', 'HOTEL_AND_STAYS', 'BANK_CHARGES', 'CARD_FEES', 'LOAN_INTEREST', 'SOFTWARE_SUBSCRIPTIONS', 'CLOUD_STORAGE', 'PROFESSIONAL_MEMBERSHIPS', 'LIFE_INSURANCE', 'VEHICLE_INSURANCE', 'CREDIT_CARD_PAYMENT', 'LOAN_REPAYMENT', 'MUTUAL_FUNDS_SIP', 'STOCKS_AND_TRADING', 'CRYPTO', 'EMERGENCY_FUND', 'FIXED_DEPOSIT', 'CHARITY_AND_DONATIONS', 'TAXES', 'FINES_AND_PENALTIES', 'PETS', 'CHILDCARE', 'ELDER_CARE', 'GOAL', 'SETTLEMENT', 'OTHERS', 'OWN_ACCOUNT_TRANSFER', 'BORROWING', 'LENDING', 'LOAN_PRINCIPAL', 'INVESTMENT_WITHDRAWAL'));
    END IF;
END $$;

DO $$
DECLARE existing_check RECORD;
BEGIN
    IF to_regclass('finance.category_budgets') IS NOT NULL THEN
        -- Only replace the enum check on this exact column.
        FOR existing_check IN
            SELECT c.conname FROM pg_constraint c
            JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
            WHERE c.conrelid = 'finance.category_budgets'::regclass AND c.contype = 'c'
              AND a.attname = 'expense_category' AND array_length(c.conkey, 1) = 1
              AND pg_get_constraintdef(c.oid) LIKE '%ANY%'
        LOOP
            EXECUTE format('ALTER TABLE finance.category_budgets DROP CONSTRAINT %I', existing_check.conname);
        END LOOP;
        ALTER TABLE finance.category_budgets DROP CONSTRAINT IF EXISTS category_budgets_expense_category_check;
        ALTER TABLE finance.category_budgets ADD CONSTRAINT category_budgets_expense_category_check
            CHECK (expense_category IN ('SALARY', 'COMMISSION', 'CAPITAL_GAINS', 'FREELANCE', 'BUSINESS', 'INVESTMENTS', 'RENTAL_INCOME', 'DIVIDENDS', 'INTEREST', 'BONUS', 'PENSION', 'GOVT_BENEFITS', 'CASHBACK_REWARDS', 'GIFTS_RECEIVED', 'TAX_REFUND', 'SIDE_HUSTLE', 'OTHER_INCOME', 'RENT', 'HOME_LOAN_EMI', 'FURNITURE', 'HOUSEHOLD_SUPPLIES', 'DOMESTIC_HELP', 'SOCIETY_MAINTENANCE', 'HOME_MAINTENANCE', 'PROPERTY_TAX', 'ELECTRICITY', 'WATER', 'GAS', 'INTERNET', 'MOBILE_PHONE', 'OTT_SUBSCRIPTIONS', 'GROCERIES', 'RESTAURANTS', 'FOOD_DELIVERY', 'COFFEE_AND_SNACKS', 'FUEL', 'PUBLIC_TRANSPORT', 'CAB_AND_AUTO', 'VEHICLE_EMI', 'VEHICLE_MAINTENANCE', 'PARKING_AND_TOLLS', 'FLIGHT_AND_TRAIN', 'DOCTOR_AND_CLINIC', 'MEDICINES', 'HEALTH_INSURANCE', 'GYM_AND_FITNESS', 'MENTAL_WELLNESS', 'TUITION_AND_FEES', 'BOOKS_AND_COURSES', 'COACHING', 'STUDENT_LOAN_EMI', 'CLOTHING', 'ELECTRONICS', 'HOME_APPLIANCES', 'PERSONAL_CARE', 'GIFTS_GIVEN', 'MOVIES_AND_EVENTS', 'GAMING', 'SPORTS_AND_HOBBIES', 'BOOKS_AND_MAGAZINES', 'TRAVEL_VACATION', 'HOTEL_AND_STAYS', 'BANK_CHARGES', 'CARD_FEES', 'LOAN_INTEREST', 'SOFTWARE_SUBSCRIPTIONS', 'CLOUD_STORAGE', 'PROFESSIONAL_MEMBERSHIPS', 'LIFE_INSURANCE', 'VEHICLE_INSURANCE', 'CREDIT_CARD_PAYMENT', 'LOAN_REPAYMENT', 'MUTUAL_FUNDS_SIP', 'STOCKS_AND_TRADING', 'CRYPTO', 'EMERGENCY_FUND', 'FIXED_DEPOSIT', 'CHARITY_AND_DONATIONS', 'TAXES', 'FINES_AND_PENALTIES', 'PETS', 'CHILDCARE', 'ELDER_CARE', 'GOAL', 'SETTLEMENT', 'OTHERS', 'OWN_ACCOUNT_TRANSFER', 'BORROWING', 'LENDING', 'LOAN_PRINCIPAL', 'INVESTMENT_WITHDRAWAL'));
    END IF;
END $$;

DO $$
DECLARE existing_check RECORD;
BEGIN
    IF to_regclass('groups.shared_expenses') IS NOT NULL THEN
        -- Only replace the enum check on this exact column.
        FOR existing_check IN
            SELECT c.conname FROM pg_constraint c
            JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
            WHERE c.conrelid = 'groups.shared_expenses'::regclass AND c.contype = 'c'
              AND a.attname = 'expense_category' AND array_length(c.conkey, 1) = 1
              AND pg_get_constraintdef(c.oid) LIKE '%ANY%'
        LOOP
            EXECUTE format('ALTER TABLE groups.shared_expenses DROP CONSTRAINT %I', existing_check.conname);
        END LOOP;
        ALTER TABLE groups.shared_expenses DROP CONSTRAINT IF EXISTS shared_expenses_expense_category_check;
        ALTER TABLE groups.shared_expenses ADD CONSTRAINT shared_expenses_expense_category_check
            CHECK (expense_category IN ('RENT', 'HOME_LOAN_EMI', 'FURNITURE', 'HOUSEHOLD_SUPPLIES', 'DOMESTIC_HELP', 'SOCIETY_MAINTENANCE', 'HOME_MAINTENANCE', 'PROPERTY_TAX', 'ELECTRICITY', 'WATER', 'GAS', 'INTERNET', 'MOBILE_PHONE', 'OTT_SUBSCRIPTIONS', 'GROCERIES', 'RESTAURANTS', 'FOOD_DELIVERY', 'COFFEE_AND_SNACKS', 'FUEL', 'PUBLIC_TRANSPORT', 'CAB_AND_AUTO', 'VEHICLE_EMI', 'VEHICLE_MAINTENANCE', 'PARKING_AND_TOLLS', 'FLIGHT_AND_TRAIN', 'DOCTOR_AND_CLINIC', 'MEDICINES', 'HEALTH_INSURANCE', 'GYM_AND_FITNESS', 'MENTAL_WELLNESS', 'TUITION_AND_FEES', 'BOOKS_AND_COURSES', 'COACHING', 'STUDENT_LOAN_EMI', 'CLOTHING', 'ELECTRONICS', 'HOME_APPLIANCES', 'PERSONAL_CARE', 'GIFTS_GIVEN', 'MOVIES_AND_EVENTS', 'GAMING', 'SPORTS_AND_HOBBIES', 'BOOKS_AND_MAGAZINES', 'TRAVEL_VACATION', 'HOTEL_AND_STAYS', 'BANK_CHARGES', 'CARD_FEES', 'LOAN_INTEREST', 'SOFTWARE_SUBSCRIPTIONS', 'CLOUD_STORAGE', 'PROFESSIONAL_MEMBERSHIPS', 'LIFE_INSURANCE', 'VEHICLE_INSURANCE', 'CREDIT_CARD_PAYMENT', 'LOAN_REPAYMENT', 'MUTUAL_FUNDS_SIP', 'STOCKS_AND_TRADING', 'CRYPTO', 'EMERGENCY_FUND', 'FIXED_DEPOSIT', 'CHARITY_AND_DONATIONS', 'TAXES', 'FINES_AND_PENALTIES', 'PETS', 'CHILDCARE', 'ELDER_CARE', 'SETTLEMENT', 'OTHERS'));
    END IF;
END $$;

COMMIT;
