import json
from pathlib import Path
import re
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'ocr-service' / 'app'))
from accounting import INCOME_CATEGORIES, EXPENSE_CATEGORIES, MOVEMENT_CATEGORIES
from document_parser import parse_file_text, analyze_history
from receipt_parser import parse_document, CATEGORY_RULES


class AccountingTests(unittest.TestCase):
    def test_category_contract_matches_both_backends(self):
        for service, package in [('command-service', 'command'), ('query-service', 'query')]:
            source = (ROOT / service / f'src/main/java/com/finance/{package}/model/Category.java').read_text()
            categories = set(re.findall(r'^    ([A-Z][A-Z_]+)[,\n]', source, re.M))
            self.assertEqual(categories, INCOME_CATEGORIES | EXPENSE_CATEGORIES)
        examples = json.loads((ROOT / 'ocr-service/app/category_examples.json').read_text())
        self.assertTrue({e['category'] for e in examples} <= {n for n, _ in CATEGORY_RULES})

    def test_new_expense_rules(self):
        cases = {'Ikea furniture': 'FURNITURE', 'Cleaning supplies': 'HOUSEHOLD_SUPPLIES',
                 'Housekeeping service': 'DOMESTIC_HELP', 'Maid salary': 'DOMESTIC_HELP', 'Society maintenance': 'SOCIETY_MAINTENANCE',
                 'Bank charges': 'BANK_CHARGES', 'Card annual fee': 'CARD_FEES',
                 'Loan interest': 'LOAN_INTEREST', 'Adobe software subscription': 'SOFTWARE_SUBSCRIPTIONS',
                 'Google One cloud storage': 'CLOUD_STORAGE', 'Professional membership dues': 'PROFESSIONAL_MEMBERSHIPS'}
        for text, category in cases.items():
            with self.subTest(text=text):
                doc, _, _ = parse_document(text + '\nTotal INR 150.00')
                self.assertEqual(doc['category'], category)
                self.assertEqual(doc['transactionKind'], 'STANDARD')
                self.assertEqual(doc['transactionType'], 'Expense')

    def test_movement_refund_and_income_treatments(self):
        cases = [('Credit card bill payment', 'TRANSFER', 'CREDIT_CARD_PAYMENT'),
                 ('Own account transfer', 'TRANSFER', 'OWN_ACCOUNT_TRANSFER'),
                 ('Loan disbursement credited', 'TRANSFER', 'BORROWING'),
                 ('Mutual fund SIP', 'INVESTMENT', 'MUTUAL_FUNDS_SIP'),
                 ('Investment withdrawal', 'INVESTMENT', 'INVESTMENT_WITHDRAWAL'),
                 ('Acme Pharmacy\nRefund processed', 'REFUND', 'MEDICINES'),
                 ('Taxi expense reimbursement', 'REIMBURSEMENT', 'CAB_AND_AUTO'),
                 ('Income tax refund', 'STANDARD', 'TAX_REFUND'),
                 ('Commission received', 'STANDARD', 'COMMISSION'),
                 ('Capital gains credited', 'STANDARD', 'CAPITAL_GAINS')]
        for text, treatment, category in cases:
            with self.subTest(text=text):
                doc, _, _ = parse_document(text + '\nTotal INR 150.00')
                self.assertEqual(doc['transactionKind'], treatment)
                self.assertEqual(doc['category'], category)
                self.assertIsNone(doc['originalTransactionId'])

    def test_csv_preserves_accounting_fields_and_original_currency(self):
        csv = ('name,amount,type,category,currency,transactionKind,originalTransactionId,subcategory\n'
               'Refund,25,INCOME,GROCERIES,USD,REFUND,42,Weekly shop\n'
               'Interest,10,EXPENSE,LOAN_INTEREST,INR,STANDARD,,Car loan\n'
               'Savings,100,EXPENSE,FIXED_DEPOSIT,INR,INVESTMENT,,\n')
        rows = parse_file_text(csv, is_csv=True, is_batch=True)
        self.assertEqual(rows[0]['transactionKind'], 'REFUND')
        self.assertEqual(rows[0]['originalTransactionId'], 42)
        self.assertEqual(rows[0]['category'], 'GROCERIES')
        self.assertEqual(rows[0]['currency'], 'USD')
        self.assertEqual(rows[0]['subcategory'], 'Weekly shop')
        self.assertEqual(rows[2]['transactionKind'], 'INVESTMENT')

    def test_csv_incoming_movements_and_refunds_keep_detected_categories(self):
        rows = parse_file_text('name,amount,type,currency\nInvestment withdrawal,100,INCOME,INR\nPharmacy refund,25,INCOME,INR\n', is_csv=True, is_batch=True)
        self.assertEqual(rows[0]['category'], 'INVESTMENT_WITHDRAWAL')
        self.assertEqual(rows[0]['transactionKind'], 'INVESTMENT')
        self.assertEqual(rows[1]['category'], 'MEDICINES')
        self.assertEqual(rows[1]['transactionKind'], 'REFUND')
        rows = parse_file_text('name,amount,type,category,currency,transactionKind\nSettled,25,INCOME,SETTLEMENT,INR,STANDARD\n', is_csv=True, is_batch=True)
        self.assertEqual(rows[0]['category'], 'SETTLEMENT')

    def test_refund_policy_footer_does_not_turn_a_purchase_into_income(self):
        for footer in ['Refund policy: 30 days', 'No refunds', 'Refunds within 30 days', 'Refund/exchange policy']:
            with self.subTest(footer=footer):
                doc, _, _ = parse_document('Acme Pharmacy\nTOTAL 20.00\n' + footer)
                self.assertEqual(doc['transactionType'], 'Expense')
                self.assertEqual(doc['transactionKind'], 'STANDARD')

    def test_invalid_accounting_csv_is_rejected(self):
        header = 'name,amount,type,category,currency,transactionKind,originalTransactionId\n'
        for row in ['Refund,10,EXPENSE,GROCERIES,INR,REFUND,42',
                    'Refund,10,INCOME,GROCERIES,INR,REFUND,-1',
                    'Salary,10,INCOME,SALARY,INR,STANDARD,42',
                    'Other,10,EXPENSE,OTHERS,INR,UNKNOWN,']:
            with self.subTest(row=row), self.assertRaises(ValueError):
                parse_file_text(header + row, is_csv=True, is_batch=True)

    def test_advisor_excludes_movements_and_subtracts_recoveries(self):
        rows = [{'a': 200, 't': 'EXPENSE', 'c': 'GROCERIES', 'currency': 'INR'},
                {'a': 50, 't': 'INCOME', 'c': 'GROCERIES', 'transactionKind': 'REFUND', 'currency': 'INR'},
                {'a': 900, 't': 'EXPENSE', 'c': 'CREDIT_CARD_PAYMENT', 'currency': 'INR'},
                {'a': 500, 't': 'INCOME', 'transactionKind': 'TRANSFER', 'currency': 'INR'}]
        summary = analyze_history(rows)
        self.assertIn('expenses: 150.00 INR; recorded income: 0.00 INR', summary['insights'][0])


if __name__ == '__main__':
    unittest.main()
