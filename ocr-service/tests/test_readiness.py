import ast
import asyncio
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest

APP = Path(__file__).resolve().parents[1] / 'app'
sys.path.insert(0, str(APP))
from readiness import consumer_ready


class ReadinessTests(unittest.TestCase):
    def setUp(self):
        self.connected = asyncio.Event()
        self.connected.set()
        self.connection = SimpleNamespace(is_closed=False, connected=self.connected)
        self.task = SimpleNamespace(done=lambda: False)

    def test_requires_live_connection_and_subscription(self):
        self.assertTrue(consumer_ready(self.connection, self.task, True))
        self.assertFalse(consumer_ready(self.connection, self.task, False))
        self.assertFalse(consumer_ready(None, self.task, True))
        self.assertFalse(consumer_ready(self.connection, None, True))

    def test_disconnect_and_closed_connection_are_not_ready(self):
        self.connected.clear()
        self.assertFalse(consumer_ready(self.connection, self.task, True))
        self.connected.set()
        self.connection.is_closed = True
        self.assertFalse(consumer_ready(self.connection, self.task, True))

    def test_stopped_task_is_not_ready(self):
        self.task.done = lambda: True
        self.assertFalse(consumer_ready(self.connection, self.task, True))

    def test_endpoint_rejects_disconnected_consumer(self):
        # Compile the real handler without importing the CUDA runtime.
        function = next(n for n in ast.parse((APP / 'main.py').read_text()).body
                        if isinstance(n, ast.FunctionDef) and n.name == 'ready')
        function.decorator_list = []
        class HTTPException(Exception):
            def __init__(self, status_code, detail):
                self.status_code = status_code
        scope = dict(consumer_ready=consumer_ready, HTTPException=HTTPException,
                     mq_connection=self.connection, mq_consumer_task=self.task,
                     mq_subscribed=True)
        exec(compile(ast.Module(body=[function], type_ignores=[]), 'main.py', 'exec'), scope)
        self.assertEqual(scope['ready']()['rabbitmq'], 'consuming')
        self.connected.clear()
        with self.assertRaises(HTTPException) as raised:
            scope['ready']()
        self.assertEqual(raised.exception.status_code, 503)


if __name__ == '__main__':
    unittest.main()
