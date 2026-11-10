"""Reviewed account regressions and uncertainty contract (no ledger writes)."""
import json
import re
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'ocr-service/app'))
from receipt_parser import parse_document, detect_currency
from document_parser import parse_file_text
from category_search import CategorySearch
from receipt_parser import CATEGORY_RULES
sys.path.insert(0, str(ROOT / 'ocr-service/bench'))
from accuracy import compare_fields


class ReceiptAccuracyTests(unittest.TestCase):
    def test_reviewed_flagged_images_have_correct_values_or_explicit_abstentions(self):
        rows = json.loads((ROOT / 'ocr-service/bench/tests/fixtures/reviewed_flags.json').read_text())
        self.assertEqual(len(rows), 91)
        for row in rows:
            if 'text' not in row:
                continue
            with self.subTest(image=row['image']):
                doc = parse_file_text(row['text'])
                matches, abstentions, errors = compare_fields(doc, row['expected'])
                self.assertFalse(errors, errors)
                self.assertEqual(len(matches) + len(abstentions), len(row['expected']))

    def test_fixes_use_totals_and_arithmetic_for_unfamiliar_merchants(self):
        cases = [
            ('Total D1-4\n38.23', 38.23),
            ('Amount 25.34\nTaxRate9.00%$2.28\nSub-Total:$27.62', 27.62),
            ('Subtotal\n50.50\nTax\n5.45\n55.95\n03/12 12:31pTOTAL', 55.95),
            ('Total\n20.00\n09:38PMTOTAL\n23.45', 23.45),
            ('Subtotal\n6.00\nTax\n0.40\nGRAT18\n06/10139pTOTAL\n1.15\n7.55', 7.55),
        ]
        for merchant in ('Zyphora', 'Quenval', 'Lumeris'):
            for text, amount in cases:
                with self.subTest(merchant=merchant, text=text):
                    self.assertEqual(parse_file_text(merchant + '\n' + text)['amount'], amount)

    def test_names_and_addresses_do_not_override_explicit_currency_or_category(self):
        for merchant in ('Zyphora', 'Quenval', 'Lumeris'):
            doc = parse_file_text(merchant + '\nImaginary City ZZ 12345\nLaptop computer 16GB\nTOTAL EUR 120.00')
            self.assertEqual(doc['vendor'], merchant)
            self.assertEqual(doc['currency'], 'EUR')
            self.assertEqual(doc['category'], 'ELECTRONICS')

    def test_cropped_dine_in_receipt_roundoff_does_not_select_tax(self):
        fixture = json.loads((ROOT / 'ocr-service/bench/tests/fixtures/cropped_dine_in_receipt.json').read_text())
        doc = parse_file_text(fixture['text'])
        for field, expected in fixture['expected'].items():
            self.assertEqual(doc[field], expected, field)
        self.assertEqual(doc['evidence']['amount_source'], 'total_after_roundoff')
        self.assertEqual(doc['evidence']['items'], ['IdlyPcs'])
        self.assertIn('name', doc['reviewFields'])
        self.assertIn('date', doc['reviewFields'])

    def test_roundoff_total_requires_adjacent_small_adjustment(self):
        for label in ('Round off', 'Round.off', 'Rounc.off'):
            doc = parse_file_text('Cafe\nGrand Total\n' + label + '\n-0.02\n80.00')
            self.assertEqual(doc['amount'], 80.0)
        for fragment in ('Cash\n0.02\n80.00', 'Round off\n20.00\n80.00'):
            doc = parse_file_text('Cafe\nGrand Total\n' + fragment)
            self.assertNotEqual(doc['evidence']['amount_source'], 'total_after_roundoff')

    def test_public_malaysian_receipt_currency(self):
        rows = json.loads((ROOT / 'ocr-service/bench/tests/fixtures/public_receipts.json').read_text())
        doc = parse_file_text(rows[1]['ocr']['text'])
        self.assertEqual(doc['currency'], 'MYR')
        self.assertNotIn('currency', doc['reviewFields'])

    def test_ringgit_markers_and_explicit_currency_precedence(self):
        for text in ('TOTAL RM12.50', 'Amount(RM)\n12.50', 'CHANGE RM\n0.00'):
            self.assertEqual(detect_currency(text), ('MYR', 'symbol'))
        for text in ('FARM SHOP', 'RMART', 'WARM'):
            self.assertEqual(detect_currency(text)[1], 'default')
        self.assertEqual(detect_currency('RM STORE\nTOTAL USD 12.50'), ('USD', 'explicit_code'))

    def test_blank_final_total_keeps_amount_flagged(self):
        doc = parse_file_text('Zyphora\nAmount\n158.07\nTip\n500\nTotal')
        self.assertIn('amount', doc['reviewFields'])

    def test_account_receipts_against_reviewed_fields(self):
        rows = json.loads((ROOT / 'ocr-service/bench/tests/fixtures/account_receipts.json').read_text())
        search = CategorySearch(ROOT / 'ocr-service/app/category_examples.json', {n for n, _ in CATEGORY_RULES})
        for row in rows:
            with self.subTest(image=row['image']):
                doc = parse_file_text(row['text'], category_search=search)
                for field, expected in row['expected'].items():
                    actual = doc[field]
                    review_field = 'name' if field == 'vendor' else field
                    if review_field in doc['reviewFields']:
                        # A safe abstention is a review contract, not an accuracy match.
                        continue
                    if field == 'vendor':
                        actual, expected = [re.sub(r'[^a-z0-9]', '', value.lower()) for value in (actual, expected)]
                    self.assertEqual(actual, expected, field)
                if row['image'].endswith('.pdf'):
                    self.assertFalse({'pcs', 'INR', 'Midnight', 'Grey'} & set(doc['evidence']['items']))

    def test_addresses_do_not_determine_currency_without_printed_evidence(self):
        for address in ('StantonCA 90680', 'Huntsvi11eAL35806', 'CA.90280', 'New York NY 11040'):
            with self.subTest(address=address):
                self.assertEqual(detect_currency(address)[1], 'default')
                self.assertEqual(detect_currency(address + '\nEUR 10.00'), ('EUR', 'explicit_code'))
        self.assertEqual(detect_currency('TOTAL12345')[1], 'default')

    def test_ambiguous_date_and_unknown_currency_need_review(self):
        doc = parse_file_text('Acme\n07/03/2024\nTotal 10.00')
        self.assertIsNone(doc['date'])
        self.assertTrue(doc['needsReview'])
        self.assertTrue({'currency', 'date', 'category'} <= set(doc['reviewFields']))
        self.assertIn('date_ambiguous', doc['warnings'])
        self.assertEqual(doc['evidence']['currency_source'], 'default')

    def test_explicit_order_overrides_country(self):
        text = 'Acme\nGSTIN: 27AABCT1234F1Z5\n07/03/2024\nTotal INR 10.00'
        self.assertEqual(parse_file_text(text)['date'], '2024-03-07')
        self.assertEqual(parse_file_text(text, date_order='mdy')['date'], '2024-07-03')

    def test_customer_does_not_control_category_or_income(self):
        text = ('Seller:\nAcme\nClient:\nSalary Mobile World\nITEMS\n'
                'Description\nQty\n1.\nTablet computer 64GB\n6.00\npcs\n'
                '100.00\nSUMMARY\nTotal INR 600.00')
        doc = parse_file_text(text)
        self.assertEqual(doc['category'], 'ELECTRONICS')
        self.assertEqual(doc['transactionType'], 'Expense')
        self.assertEqual(doc['evidence']['items'], ['Tablet computer 64GB'])

    def test_unlabelled_amount_fallback_is_flagged(self):
        doc = parse_file_text('Acme Cafe\nCoffee 10.00\n2024-03-07')
        self.assertIn('amount', doc['reviewFields'])
        self.assertIn('amount_uncertain', doc['warnings'])


if __name__ == '__main__':
    unittest.main()
