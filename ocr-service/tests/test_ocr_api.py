"""API wiring checks; real model inference is covered by compare_runtime.py."""
import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'app'))
AVAILABLE = all(importlib.util.find_spec(name) for name in ('cv2', 'fastapi', 'httpx', 'aio_pika', 'minio'))


@unittest.skipUnless(AVAILABLE, 'Run inside the OCR image')
class OCRApiTests(unittest.TestCase):
    def setUp(self):
        import main
        from fastapi.testclient import TestClient
        self.service = main
        self.runtime = Mock()
        self.runtime.predict.return_value = dict(full_text='TOTAL 80.00', line_count=1,
                                                inference_ms=12, runtime_ms=15,
                                                lines=[{'text': 'TOTAL 80.00', 'confidence': .99,
                                                        'bbox': [[0, 0], [2, 0], [2, 2], [0, 2]]}])
        self.engine_patch = patch.object(main, 'ocr_engine', self.runtime)
        self.token_patch = patch.object(main, 'INTERNAL_API_TOKEN', 'test-only-token')
        self.engine_patch.start()
        self.token_patch.start()
        self.addCleanup(self.engine_patch.stop)
        self.addCleanup(self.token_patch.stop)
        # No context manager: these isolated HTTP checks do not start RabbitMQ.
        self.client = TestClient(main.app)
        self.addCleanup(self.client.close)

    def test_health_and_rejected_uploads_never_load_models(self):
        self.assertEqual(self.client.get('/health').status_code, 200)
        response = self.client.post('/ocr', files={'file': ('image.png', b'invalid', 'image/png')})
        self.assertEqual(response.status_code, 401)
        response = self.client.post('/ocr', headers={'X-Internal-Token': 'test-only-token'},
                                    files={'file': ('image.png', b'invalid', 'image/png')})
        self.assertEqual(response.status_code, 400)
        self.runtime.predict.assert_not_called()

    def test_image_request_delegates_to_lazy_runtime_and_preserves_contract(self):
        import cv2
        import numpy as np
        ok, image = cv2.imencode('.png', np.zeros((4, 5, 3), dtype=np.uint8))
        self.assertTrue(ok)
        response = self.client.post('/ocr?boxes=true', headers={'X-Internal-Token': 'test-only-token'},
                                    files={'file': ('receipt.png', image.tobytes(), 'image/png')})
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual((body['width'], body['height']), (5, 4))
        self.assertEqual(body['text'], 'TOTAL 80.00')
        self.assertEqual(body['lines'][0]['confidence'], .99)
        self.runtime.predict.assert_called_once()


if __name__ == '__main__':
    unittest.main()
