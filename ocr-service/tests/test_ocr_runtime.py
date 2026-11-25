"""Exercise real process lifecycle without downloading ML runtimes/models."""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
import os
from pathlib import Path
import sys
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'app'))
from ocr_runtime import OCRConfig, OCRRuntime, normalize_v2, normalize_v3


def fake_worker(connection, config):
    connection.send(('ready', None))
    while True:
        try:
            value = connection.recv()
        except EOFError:
            break
        if value is None:
            break
        if value == 'error':
            connection.send(('error', 'Inference failed'))
            break
        if value == 'crash':
            os._exit(1)
        if value == 'slow':
            time.sleep(3)
        connection.send(('result', {'full_text': str(value), 'pid': os.getpid()}))


class OCRRuntimeTests(unittest.TestCase):
    def runtime(self, **kwargs):
        runtime = OCRRuntime(replace(OCRConfig(idle_seconds=0), **kwargs), worker_target=fake_worker)
        self.addCleanup(runtime.close)
        return runtime

    def test_lazy_reuse_and_shutdown(self):
        runtime = self.runtime()
        self.assertFalse(runtime.stats()['loaded'])
        first = runtime.predict('one')
        self.assertEqual(runtime.predict('two')['pid'], first['pid'])
        self.assertTrue(runtime.stats()['loaded'])
        runtime.close()
        self.assertFalse(Path(f"/proc/{first['pid']}").exists())
        with self.assertRaisesRegex(RuntimeError, 'closed'):
            runtime.predict('three')

    def test_idle_releases_process_then_next_request_reloads(self):
        runtime = self.runtime(idle_seconds=0.2)
        first = runtime.predict('one')
        deadline = time.monotonic() + 3
        while runtime.stats()['loaded'] and time.monotonic() < deadline:
            time.sleep(0.05)
        self.assertFalse(runtime.stats()['loaded'])
        self.assertFalse(Path(f"/proc/{first['pid']}").exists())
        self.assertNotEqual(runtime.predict('two')['pid'], first['pid'])

    def test_parallel_requests_keep_their_own_result(self):
        runtime = self.runtime()
        with ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(runtime.predict, range(8)))
        self.assertEqual([r['full_text'] for r in results], list(map(str, range(8))))
        self.assertEqual(len({r['pid'] for r in results}), 1)

    def test_error_and_crash_clean_up_and_allow_retry(self):
        runtime = self.runtime()
        for value in ['error', 'crash']:
            with self.assertRaises((RuntimeError, EOFError, ConnectionError)):
                runtime.predict(value)
            self.assertFalse(runtime.stats()['loaded'])
            self.assertEqual(runtime.predict('retry')['full_text'], 'retry')

    def test_timeout_terminates_worker_and_recovers(self):
        runtime = self.runtime(timeout_seconds=1)
        with self.assertRaises(TimeoutError):
            runtime.predict('slow')
        self.assertFalse(runtime.stats()['loaded'])
        self.assertEqual(runtime.predict('retry')['full_text'], 'retry')

    def test_idle_unload_cannot_interrupt_inference(self):
        runtime = self.runtime(idle_seconds=0.1)
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(runtime.predict, 'slow')
            time.sleep(0.3)
            self.assertFalse(runtime.unload_if_idle())
            self.assertEqual(future.result(timeout=5)['full_text'], 'slow')

    def test_normalizers_keep_text_confidence_and_layout(self):
        box = [[1, 2], [3, 2], [3, 4], [1, 4]]
        expected = [{'text': 'TOTAL 80.00', 'confidence': 0.9123, 'bbox': box}]
        self.assertEqual(normalize_v2([[[box, ('TOTAL 80.00', 0.912345)]]]), expected)
        self.assertEqual(normalize_v3([{'rec_texts': ['TOTAL 80.00'],
                                      'rec_scores': [0.912345], 'rec_polys': [box]}]), expected)
        self.assertEqual(normalize_v2([None]), [])

    def test_bad_configuration_fails_before_consuming_jobs(self):
        for values in [dict(device='auto'), dict(profile='server'), dict(threads=0),
                       dict(idle_seconds=-1), dict(timeout_seconds=0)]:
            with self.assertRaises(ValueError):
                OCRConfig(**values)


if __name__ == '__main__':
    unittest.main()
