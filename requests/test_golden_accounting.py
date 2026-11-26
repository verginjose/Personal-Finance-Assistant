"""The golden check must compare accounting totals, including recoveries."""
import unittest
from types import SimpleNamespace
from run_golden_test import _read_listing, _refresh_cookie

class GoldenAccountingTests(unittest.TestCase):
    def test_listing_uses_reporting_projection(self):
        class Client:
            def request(self, *args, **kwargs):
                return 200, {'totalElements': 4, 'content': [
                    {'type':'INCOME','amount':100,'transactionKind':'STANDARD'},
                    {'type':'EXPENSE','amount':40,'transactionKind':'STANDARD'},
                    {'type':'EXPENSE','category':'GOAL','amount':1000,'transactionKind':'STANDARD'},
                    {'type':'INCOME','amount':10,'transactionKind':'REFUND'},
                ]}, {}
        member=SimpleNamespace(user_id='id',headers=lambda:{})
        self.assertEqual(_read_listing(Client(),member),(4,100,30))

    def test_http_only_refresh_cookie_is_captured(self):
        self.assertEqual(_refresh_cookie({'Set-Cookie':'pfa_refresh=credential; HttpOnly; Path=/api/auth; Secure'}),'credential')
        self.assertIsNone(_refresh_cookie({}))

if __name__=='__main__':
    unittest.main()
