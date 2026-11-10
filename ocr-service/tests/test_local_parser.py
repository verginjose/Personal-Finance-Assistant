import ast
import asyncio
from pathlib import Path
import sys
import unittest

APP = Path(__file__).resolve().parents[1] / 'app'
sys.path.insert(0, str(APP))
from document_parser import parse_file_text, analyze_history
from category_search import CategorySearch
from receipt_parser import CATEGORY_RULES


class LocalParserTests(unittest.TestCase):
    def test_receipt_preserves_line_layout(self):
        doc = parse_file_text('Acme Cafe\n04/25/2019\nCoffee\n3.00\nTOTAL\n3.25')
        self.assertEqual(doc['amount'], 3.25)
        self.assertEqual(doc['date'], '2019-04-25')
        self.assertEqual(doc['expenseCategory'], 'RESTAURANTS')

    def test_unreadable_amount_fails_instead_of_zero_transaction(self):
        with self.assertRaises(ValueError):
            parse_file_text('Acme Cafe\nNo readable amounts here')

    def test_bank_csv_debit_credit(self):
        docs = parse_file_text('date,description,debit,credit,currency\n2019-01-01,Cafe,12.50,,USD\n2019-01-02,Salary,,500,USD\n', is_csv=True, is_batch=True)
        self.assertEqual([d['transactionType'] for d in docs], ['Expense', 'Income'])
        self.assertEqual(docs[1]['amount'], 500)
        self.assertEqual(docs[1]['incomeCategory'], 'SALARY')

    def test_export_csv_preserves_enum_and_recurring(self):
        docs = parse_file_text('name,amount,type,expenseCategory,currency,recurring,createdAt\nRepairs,50,EXPENSE,HOME_MAINTENANCE,INR,true,2026-06-10T12:00\n', is_csv=True, is_batch=True)
        self.assertEqual(docs[0]['expenseCategory'], 'HOME_MAINTENANCE')
        self.assertTrue(docs[0]['recurring'])

    def test_invalid_csv_stops_import(self):
        for row in ['date,name,debit,credit\n2019-01-01,Cafe,10,20\n',
                    'name,amount,type\nCafe,NaN,Expense\n',
                    'name,amount,type,date\nCafe,10,Expense,not-a-date\n']:
            with self.subTest(row=row), self.assertRaises(ValueError):
                parse_file_text(row, is_csv=True, is_batch=True)

    def test_multiple_rows_require_batch(self):
        with self.assertRaisesRegex(ValueError, 'batch import'):
            parse_file_text('name,amount\nCafe,10\nCafe,20\n', is_csv=True)

    def test_unstructured_statements_fail_explicitly(self):
        with self.assertRaisesRegex(ValueError, 'CSV'):
            parse_file_text('A bank statement with arbitrary columns', is_batch=True)

    def test_advisor_is_local_and_keeps_currencies_separate(self):
        summary = analyze_history([{'a': 50, 't': 'EXPENSE', 'currency': 'USD'},
                                   {'a': 500, 't': 'INCOME', 'currency': 'INR'}])
        self.assertEqual(len(summary['insights']), 2)
        self.assertEqual(summary['warnings'], [])
        self.assertIn('insights', analyze_history([]))

    def test_production_adapter_calls_local_parser_and_preserves_callback(self):
        module = ast.parse((APP / 'main.py').read_text())
        functions = [n for n in module.body if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))
                     and n.name in ('_parse_bill', '_convert_currency')]
        async def inline_thread(function, *args, **kwargs):
            return function(*args, **kwargs)
        from types import SimpleNamespace
        import os
        scope = dict(asyncio=SimpleNamespace(to_thread=inline_thread), os=os, parse_file_text=parse_file_text,
                     category_search=CategorySearch(APP / 'category_examples.json', {n for n, _ in CATEGORY_RULES}),
                     rate_cache={'rates': {'USD': 1, 'INR': 80}})
        exec(compile(ast.Module(body=functions, type_ignores=[]), 'main.py', 'exec'), scope)
        result = asyncio.run(scope['_parse_bill']('user', b'name,amount,type,currency\nCafe,10,Expense,USD\n', 'test.csv', 'text/csv'))
        self.assertEqual(result['type'], 'EXPENSE')
        self.assertEqual(result['amount'], '10.0')
        self.assertEqual(result['amountInr'], 800)
        batch = asyncio.run(scope['_parse_bill']('user', b'name,amount,type,currency\nCafe,10,Expense,USD\n', 'test.csv', 'text/csv', True))
        self.assertEqual(batch[0], result)
        scope['_extract_text_from_image_bytes'] = lambda data, filename: 'Acme\n07/03/2024\nTOTAL 10.00'
        receipt = asyncio.run(scope['_parse_bill']('user', b'image', 'test.jpg', 'image/jpeg'))
        self.assertTrue(receipt['needsReview'])
        self.assertIn('currency', receipt['reviewFields'])
        self.assertIn('date_ambiguous', receipt['warnings'])
        self.assertEqual(receipt['evidence']['currency_source'], 'default')
        self.assertIsNone(receipt['date'])


if __name__ == '__main__':
    unittest.main()
