import json
from pathlib import Path
import sys
import tempfile
import unittest

BENCH = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BENCH))
from parse_receipts import parse_document, extract_date, CATEGORY_RULES
from receipt_parser import extract_amount
from category_search import CategorySearch


class ParserTests(unittest.TestCase):
    def test_reviewed_real_ocr(self):
        rows = json.loads((BENCH / "tests/fixtures/ocr_regressions.json").read_text())
        for row in rows:
            with self.subTest(image=row["image"]):
                doc, evidence, _ = parse_document(row["text"], convert=False, default_currency="USD")
                for field, value in row["expected"].items():
                    self.assertEqual(doc[field], value, field)
                for field, value in row.get("expected_evidence", {}).items():
                    self.assertEqual(evidence[field], value, field)

    def test_signed_refund(self):
        for amount in ("-$12.50", "$-12.50", "-12.50"):
            with self.subTest(amount=amount):
                doc, _, warnings = parse_document("Acme\nTotal " + amount)
                self.assertEqual(doc["transactionType"], "Income")
                self.assertEqual(doc["amount"], 12.5)
                self.assertNotIn("amount_out_of_range", warnings)

    def test_cash_is_not_total(self):
        doc, _, warnings = parse_document("Acme\nAmount Due\nCash\n100.00\nChange\n20.00")
        self.assertIn("no_amount", warnings)

    def test_invalid_dates_are_not_invented(self):
        for text in ("MART 11814", "02/30/2019", "Date:ov-15-06-N", "7/201303:08PM"):
            with self.subTest(text=text):
                self.assertEqual(extract_date(text), (None, None))

    def test_explicit_date_order(self):
        self.assertEqual(extract_date("04/05/2019", "dmy")[0], "2019-05-04")
        self.assertEqual(extract_date("04/05/2019", "mdy")[0], "2019-04-05")

    def test_notices_and_percentages_are_not_fallback_amounts(self):
        for notice in ("FOR PURCHASES UNDER $20.00", "OR PUFCHASES UNDER $20.00",
                       "Win $500.00", "Tax rate 99.00%"):
            with self.subTest(notice=notice):
                self.assertEqual(extract_amount("Acme\nCoffee 3.00\n" + notice)[0], 3.0)

    def test_final_subtotal_tax_block_can_recover_missing_total(self):
        amount, _, source = extract_amount(
            "Acme\nSub-total\n164.00\nDiscount\n-12.00\n"
            "Sub-total\n152.00\nSales Tax\n0.00\nTOTAL")
        self.assertEqual(amount, 152.0)
        self.assertEqual(source, "subtotal_plus_tax")

    def test_missing_total_does_not_reconstruct_across_unreadable_fees(self):
        _, _, source = extract_amount("Acme\nSubtotal\n100.00\nTax\n5.00\nService Charge\nTOTAL")
        self.assertNotEqual(source, "subtotal_plus_tax")

    def test_missing_decimal_requires_total_tip_agreement(self):
        for digits, expected in (("1250", 12.5), ("9999", 10.0)):
            with self.subTest(digits=digits):
                amount, _, _ = extract_amount("Acme\nTotal 10.00\nTip\n2.50\n" + digits + "\nTotal")
                self.assertEqual(amount, expected)

    def test_total_labels_and_notices_are_not_items(self):
        doc, evidence, _ = parse_document("Acme\nCoffee 3.00\nTOTALE\n3.00\nFOR PURCHASES UNDER $20.00")
        self.assertEqual(doc["amount"], 3.0)
        self.assertEqual(evidence["items"], ["Coffee"])

    def test_currency_lookahead_does_not_cross_tax_or_tip_labels(self):
        for label in ("tax", "tip"):
            with self.subTest(label=label):
                self.assertEqual(extract_amount("Acme\nTotal\n20.00\n" + label + "\n$2.00")[0], 20.0)

    def test_signature_menu_items_are_preserved(self):
        _, evidence, _ = parse_document("Acme\nSignature Burger 8.00\nTotal 8.00")
        self.assertEqual(evidence["items"], ["Signature Burger"])


class VectorTests(unittest.TestCase):
    def setUp(self):
        self.search = CategorySearch(BENCH / "category_examples.json",
                                     {name for name, _ in CATEGORY_RULES})

    def test_fallback_can_categorize_unmatched_menu(self):
        text = "Acme\nCrepe croissant nutella\n8.00\nTotal 8.00"
        plain, _, _ = parse_document(text)
        self.assertEqual(plain["expenseCategory"], "OTHERS")
        doc, evidence, _ = parse_document(text, category_search=self.search)
        self.assertEqual(doc["expenseCategory"], "RESTAURANTS")
        self.assertEqual(evidence["category_source"], "vector")
        self.assertTrue(evidence["category_search"]["accepted"])

    def test_rules_take_precedence(self):
        doc, evidence, _ = parse_document("Shell\nCrepe croissant nutella\nTotal 8.00",
                                          category_search=self.search)
        self.assertEqual(doc["expenseCategory"], "FUEL")
        self.assertEqual(evidence["category_source"], "rule")
        self.assertNotIn("category_search", evidence)

    def test_unknown_query_abstains(self):
        category, evidence = self.search.search("quux xyzzy")
        self.assertIsNone(category)
        self.assertFalse(evidence["accepted"])

    def test_self_match_is_excluded(self):
        row = self.search.examples[1]
        _, evidence = self.search.search(row["text"], exclude_id=row["id"])
        self.assertNotIn(row["id"], [n["id"] for n in evidence["neighbors"]])

    def test_reproducible_results(self):
        self.assertEqual(self.search.search("crepe nutella croissant"),
                         self.search.search("crepe nutella croissant"))

    def test_competing_categories_abstain(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "examples.json"
            path.write_text(json.dumps([
                {"id": "a", "category": "FUEL", "text": "shared ambiguous"},
                {"id": "b", "category": "GROCERIES", "text": "shared ambiguous"}]))
            search = CategorySearch(path, {"FUEL", "GROCERIES"})
            self.assertIsNone(search.search("shared ambiguous")[0])

    def test_invalid_category_is_rejected(self):
        with self.assertRaises(ValueError):
            CategorySearch(BENCH / "category_examples.json", {"INVALID"})


if __name__ == "__main__":
    unittest.main()
