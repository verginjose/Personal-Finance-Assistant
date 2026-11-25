"""Lazy, serialized OCR in a disposable process; no Paddle import in the API.

Use spawn rather than fork: CUDA and native inference threads cannot safely be
inherited from the API process. Stopping the process releases native allocators
as well as Python objects. Health/readiness never starts a model download.
"""
from dataclasses import dataclass
import multiprocessing
import os
import signal
from pathlib import Path
import threading
import time


@dataclass(frozen=True)
class OCRConfig:
    backend: str = 'paddle'
    device: str = 'cpu'
    profile: str = 'legacy'
    threads: int = 2
    batch: int = 6
    side_len: int = 736
    angle: bool = False
    mkldnn: bool = False
    idle_seconds: float = 300
    timeout_seconds: float = 180
    det_model_dir: str = ''
    rec_model_dir: str = ''

    def __post_init__(self):
        if self.backend not in ('paddle', 'tesseract'):
            raise ValueError('OCR_BACKEND must be paddle or tesseract')
        if self.device not in ('cpu', 'gpu') or self.profile not in ('legacy', 'mobile-v5'):
            raise ValueError('Unsupported OCR device or model profile')
        if min(self.threads, self.batch, self.side_len, self.timeout_seconds) <= 0 or self.idle_seconds < 0:
            raise ValueError('OCR sizes/threads/timeouts must be positive; idle timeout may be zero')

    @classmethod
    def from_env(cls):
        return cls(
            backend=os.getenv('OCR_BACKEND', 'paddle'),
            device=os.getenv('OCR_DEVICE', 'gpu'),
            profile=os.getenv('OCR_MODEL_PROFILE', 'legacy'),
            threads=int(os.getenv('OCR_CPU_THREADS', '2')),
            batch=int(os.getenv('OCR_REC_BATCH_NUM', '6')),
            side_len=int(os.getenv('OCR_DET_LIMIT_SIDE_LEN', '736')),
            angle=os.getenv('OCR_USE_ANGLE_CLS', 'false').lower() == 'true',
            mkldnn=os.getenv('OCR_ENABLE_MKLDNN', 'false').lower() == 'true',
            idle_seconds=float(os.getenv('OCR_IDLE_SECONDS', '300')),
            timeout_seconds=float(os.getenv('OCR_INFERENCE_TIMEOUT_SECONDS', '180')),
            det_model_dir=os.getenv('OCR_DET_MODEL_DIR', ''),
            rec_model_dir=os.getenv('OCR_REC_MODEL_DIR', ''),
        )


def normalize_v2(result):
    return [{'text': text, 'confidence': round(float(score), 4), 'bbox': box}
            for box, (text, score) in (result[0] if result and result[0] else [])]


def normalize_v3(results):
    lines = []
    for result in results:
        for text, score, box in zip(result['rec_texts'], result['rec_scores'], result['rec_polys']):
            lines.append({'text': text, 'confidence': round(float(score), 4),
                          'bbox': box.tolist() if hasattr(box, 'tolist') else box})
    return lines


def _paddle_predictor(config):
    from importlib.metadata import version
    from paddleocr import PaddleOCR
    major = int(version('paddleocr').split('.')[0])
    if config.profile == 'mobile-v5':
        if major < 3:
            raise ValueError('mobile-v5 requires the PaddleOCR 3 CPU image')
        options = dict(device='cpu' if config.device == 'cpu' else 'gpu:0',
                       text_detection_model_name='PP-OCRv5_mobile_det',
                       text_recognition_model_name='en_PP-OCRv5_mobile_rec',
                       use_doc_orientation_classify=False, use_doc_unwarping=False,
                       use_textline_orientation=config.angle, cpu_threads=config.threads,
                       enable_mkldnn=config.mkldnn,
                       text_recognition_batch_size=config.batch,
                       text_det_limit_side_len=config.side_len, text_det_limit_type='max',
                       text_det_thresh=0.3, text_det_box_thresh=0.5)
        if config.det_model_dir:
            options['text_detection_model_dir'] = config.det_model_dir
        if config.rec_model_dir:
            options['text_recognition_model_dir'] = config.rec_model_dir
        engine = PaddleOCR(**options)
        return lambda image: normalize_v3(engine.predict(image))
    if major >= 3:
        raise ValueError('legacy profile requires the PaddleOCR 2 image')
    options = dict(use_angle_cls=config.angle, lang='en', use_gpu=config.device == 'gpu',
                   cpu_threads=config.threads, rec_batch_num=config.batch, enable_mkldnn=config.mkldnn,
                   det_limit_side_len=config.side_len, det_db_thresh=0.3,
                   det_db_box_thresh=0.5, show_log=False)
    if config.det_model_dir:
        options['det_model_dir'] = config.det_model_dir
    if config.rec_model_dir:
        options['rec_model_dir'] = config.rec_model_dir
    engine = PaddleOCR(**options)
    return lambda image: normalize_v2(engine.ocr(image, cls=config.angle))


def _tesseract_predict(image):
    """Optional comparison baseline, grouped into lines like Paddle's output."""
    import csv
    import io
    import subprocess
    import cv2
    ok, encoded = cv2.imencode('.png', image)
    if not ok:
        raise ValueError('Cannot encode receipt')
    completed = subprocess.run(['tesseract', 'stdin', 'stdout', '-l', 'eng', '--psm', '6', 'tsv'],
                               input=encoded.tobytes(), capture_output=True, check=True)
    groups = {}
    for row in csv.DictReader(io.StringIO(completed.stdout.decode()), delimiter='\t'):
        if not row['text'].strip() or float(row['conf']) < 0:
            continue
        key = tuple(row[k] for k in ('page_num', 'block_num', 'par_num', 'line_num'))
        groups.setdefault(key, []).append(row)
    lines = []
    for rows in groups.values():
        x = min(int(r['left']) for r in rows)
        y = min(int(r['top']) for r in rows)
        right = max(int(r['left']) + int(r['width']) for r in rows)
        bottom = max(int(r['top']) + int(r['height']) for r in rows)
        lines.append(dict(text=' '.join(r['text'] for r in rows),
                          confidence=round(sum(float(r['conf']) for r in rows) / len(rows) / 100, 4),
                          bbox=[[x, y], [right, y], [right, bottom], [x, bottom]]))
    return lines


def _worker(connection, config):
    # Bound native libraries before importing them; one worker serves all jobs.
    os.environ['OMP_NUM_THREADS'] = str(config.threads)
    os.environ['MKL_NUM_THREADS'] = str(config.threads)
    try:
        predict = _paddle_predictor(config) if config.backend == 'paddle' else _tesseract_predict
        connection.send(('ready', None))
        while True:
            image = connection.recv()
            if image is None:
                break
            started = time.perf_counter()
            lines = predict(image)
            connection.send(('result', dict(full_text='\n'.join(r['text'] for r in lines),
                                            lines=lines, line_count=len(lines),
                                            inference_ms=round((time.perf_counter()-started)*1000, 2))))
    except EOFError:
        pass
    except Exception as error:
        try:
            connection.send(('error', f'{type(error).__name__}: {error}'))
        except (BrokenPipeError, EOFError, OSError):
            pass
    finally:
        connection.close()


def _worker_entry(target, connection, config):
    # Include any native helper/Tesseract subprocesses in timeout cleanup.
    os.setsid()
    target(connection, config)


class OCRRuntime:
    def __init__(self, config=None, *, worker_target=_worker):
        self.config = config or OCRConfig.from_env()
        self._target = worker_target
        self._context = multiprocessing.get_context('spawn')
        self._lock = threading.Lock()
        self._process = self._connection = None
        self._last_used = 0
        self._closed = False
        self._stop = threading.Event()
        self._reaper = threading.Thread(target=self._reap, name='ocr-idle', daemon=True)
        self._reaper.start()

    def _receive(self):
        if not self._connection.poll(self.config.timeout_seconds):
            raise TimeoutError('OCR worker timed out')
        kind, payload = self._connection.recv()
        if kind == 'error':
            raise RuntimeError(payload)
        return kind, payload

    def _start(self):
        if self._process is not None and self._process.is_alive():
            return
        self._dispose()
        parent, child = self._context.Pipe()
        self._connection = parent
        self._process = self._context.Process(target=_worker_entry, args=(self._target, child, self.config), daemon=True)
        try:
            self._process.start()
        finally:
            child.close()
        if self._receive()[0] != 'ready':
            raise RuntimeError('Invalid OCR startup response')

    def _dispose(self):
        process = self._process
        if self._connection is not None:
            try:
                self._connection.send(None)
            except (BrokenPipeError, EOFError, OSError):
                pass
            self._connection.close()
            self._connection = None
        if process is not None:
            if process.pid is not None:
                # Normal idle exits are graceful; a hung predictor is bounded.
                process.join(timeout=1)
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    if process.is_alive():
                        process.terminate()
                process.join(timeout=2)
                # Also reap descendants if a native helper ignored SIGTERM.
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    if process.is_alive():
                        process.kill()
                process.join()
            process.close()
            self._process = None

    def warmup(self):
        with self._lock:
            if self._closed:
                raise RuntimeError('OCR runtime is closed')
            try:
                self._start()
                self._last_used = time.monotonic()
            except Exception:
                self._dispose()
                raise

    def predict(self, image):
        with self._lock:
            if self._closed:
                raise RuntimeError('OCR runtime is closed')
            started = time.perf_counter()
            try:
                self._start()
                self._connection.send(image)
                kind, result = self._receive()
                if kind != 'result':
                    raise RuntimeError('Invalid OCR result response')
                result['runtime_ms'] = round((time.perf_counter()-started)*1000, 2)
                self._last_used = time.monotonic()
                return result
            except Exception:
                self._dispose()
                raise

    def unload_if_idle(self):
        # Never interrupt an active receipt or make another request wait for it.
        if not self._lock.acquire(blocking=False):
            return False
        try:
            if (self._process is not None and self.config.idle_seconds > 0
                    and time.monotonic() - self._last_used >= self.config.idle_seconds):
                self._dispose()
                return True
            return False
        finally:
            self._lock.release()

    def _reap(self):
        interval = min(5, max(0.05, self.config.idle_seconds / 2))
        while not self._stop.wait(interval):
            self.unload_if_idle()

    def stats(self):
        # Linux RSS includes this worker only, not API/cache or GPU allocations.
        process = self._process
        result = dict(backend=self.config.backend, device=self.config.device,
                      profile=self.config.profile, loaded=False, worker_rss_mib=0, worker_peak_mib=0)
        if process is not None:
            try:
                status = Path(f'/proc/{process.pid}/status').read_text()
                result['loaded'] = process.is_alive()
                for name, key in [('VmRSS:', 'worker_rss_mib'), ('VmHWM:', 'worker_peak_mib')]:
                    value = next((line.split()[1] for line in status.splitlines() if line.startswith(name)), '0')
                    result[key] = round(int(value) / 1024, 2)
            except (OSError, ValueError):
                pass
        return result

    def close(self):
        self._stop.set()
        self._reaper.join(timeout=6)
        with self._lock:
            self._closed = True
            self._dispose()
