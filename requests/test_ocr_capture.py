"""OCR upload regression; no server or test accounts required."""
import random
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from run_golden_test import stage_ocr


class OcrCaptureTests(unittest.TestCase):
    def test_upload_stage_does_not_fabricate_ledger_values(self):
        member = SimpleNamespace(user_id='user', token='token', uploads=0,
                                 ocr_ids=[], ocr_files={}, receipt_urls=[])
        calls = []
        class Client:
            metrics = SimpleNamespace(fixture=lambda *_: None)
            def request(self, phase, path, method, **kwargs):
                calls.append(path)
                return 202, {'receiptId': 'receipt', 'url': '/receipt/image'}, {}
        class Checks:
            def status(self, expected, actual, *args):
                if expected != actual:
                    raise AssertionError((expected, actual))
            def ok(self, condition, *args):
                if not condition:
                    raise AssertionError(args)
        with patch('run_golden_test._dataset_files', return_value=['image.jpg']):
            stage_ocr(Client(), member, Checks(), SimpleNamespace(bills=1, samples='unused'), random.Random(11))
        self.assertEqual(calls, ['/bill/process/user'])
        self.assertEqual(member.receipt_urls, ['/receipt/image'])


if __name__ == '__main__':
    unittest.main()
