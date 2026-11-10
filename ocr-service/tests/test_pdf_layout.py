import importlib.util
import datetime
import json
import re
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'ocr-service/app'))
from pdf_layout import extract_pdf_text
from document_parser import parse_file_text


@unittest.skipUnless(importlib.util.find_spec('pymupdf'), 'Run in the OCR image with PyMuPDF installed')
class PdfLayoutTests(unittest.TestCase):
    def test_all_invoice_fields_and_items_against_source_metadata(self):
        rows = json.loads((ROOT / 'ocr-service/bench/tests/fixtures/invoice_labels.json').read_text())
        self.assertEqual(len(rows), 100)
        for filename, expected in rows.items():
            with self.subTest(invoice=filename):
                text = extract_pdf_text((ROOT / 'ocr-service/bench/tests/fixtures/invoices' / filename).read_bytes(),
                                        lambda *_: self.fail('Unexpected OCR for text invoice'))
                doc = parse_file_text(text)
                self.assertEqual(doc['vendor'], expected['seller']['name'])
                self.assertEqual(doc['date'], datetime.datetime.strptime(expected['date_of_issue'], '%d/%m/%Y').date().isoformat())
                self.assertEqual(doc['currency'], 'INR')
                self.assertEqual(doc['amount'], float(re.sub(r'[^\d.]', '', expected['summary']['total_gross_worth'])))
                self.assertEqual(doc['evidence']['items'], [item['description'] for item in expected['items']][:10])

    def test_real_invoice_preserves_wrapped_products(self):
        data = (ROOT / 'ocr-service/bench/tests/fixtures/invoices/invoice_51109382.pdf').read_bytes()
        text = extract_pdf_text(data, lambda *_: self.fail('Text PDF must not invoke OCR'))
        doc = parse_file_text(text)
        self.assertEqual(doc['amount'], 782634.6)
        self.assertEqual(doc['date'], '2023-05-11')
        self.assertEqual(doc['category'], 'ELECTRONICS')
        self.assertEqual(doc['evidence']['items'], [
            'Apple Watch Series 9 GPS 45mm Midnight', 'Apple iPad Air M1 64GB WiFi Space Grey'])

    def test_mixed_pdf_reads_every_scanned_page_and_keeps_boundaries(self):
        import pymupdf
        with pymupdf.open() as document:
            document.new_page().insert_text((40, 40), 'Readable first page')
            document.new_page()
            document.new_page()
            data = document.tobytes()
        calls = []
        def read_image(data, filename):
            calls.append(filename)
            return f'Scanned page {len(calls)}'
        text = extract_pdf_text(data, read_image)
        self.assertEqual(len(calls), 2)
        self.assertIn('Readable first page', text)
        self.assertIn('\nScanned page 1\nScanned page 2', text)

    def test_unknown_invoice_layout_keeps_original_text(self):
        import pymupdf
        with pymupdf.open() as document:
            document.new_page().insert_text((40, 40), 'Acme Cafe\nTotal USD 12.50')
            text = extract_pdf_text(document.tobytes(), lambda *_: self.fail('Unexpected OCR'))
        self.assertEqual(parse_file_text(text)['amount'], 12.5)


if __name__ == '__main__':
    unittest.main()
