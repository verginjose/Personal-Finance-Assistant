#!/usr/bin/env python3
"""Build independent service images concurrently on one CI runner."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import threading

SERVICES = {'auth-service', 'edge-router', 'command-service', 'query-service', 'frontend', 'ocr-service'}


def build_images(services, env, root, output, jobs=2, executable='buildctl'):
    if not services or any(service not in SERVICES for service in services):
        raise ValueError('Unknown or empty service selection')
    if len(set(services)) != len(services):
        raise ValueError('Duplicate service selection')
    if jobs not in (1, 2):
        raise ValueError('Build concurrency must be one or two')
    source = env['GITHUB_SHA']
    if not re.fullmatch(r'[a-f0-9]{40}', source):
        raise ValueError('Build source must be a full commit SHA')
    registry, tls = env['PUSH_REGISTRY'], env['BUILDKIT_TLS_DIR']
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    print(f'Build plan: {len(services)} image(s), concurrency={jobs}', flush=True)
    for service in services:
        print(f'Queued image: {registry}/finance/{service}:{source[:12]}', flush=True)
    processes = set()
    process_lock, output_lock = threading.Lock(), threading.Lock()
    stop = threading.Event()

    def cancel(signum, frame):
        stop.set()
        with process_lock:
            active = list(processes)
        for process in active:
            if process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
        raise SystemExit(128 + signum)

    previous = {}
    if threading.current_thread() is threading.main_thread():
        for sig in (signal.SIGTERM, signal.SIGINT):
            previous[sig] = signal.signal(sig, cancel)

    def build(service):
        if stop.is_set():
            return False
        image = f'{registry}/finance/{service}:{source[:12]}'
        print(f'[{service}] Starting image build: {image}', flush=True)
        metadata = output / f'{service}.json'
        metadata.unlink(missing_ok=True)
        command = [executable, '--tlscacert', f'{tls}/ca.crt',
                   '--tlscert', f'{tls}/client.cert', '--tlskey', f'{tls}/client.key',
                   'build', '--progress', 'plain', '--metadata-file', str(metadata),
                   '--frontend', 'dockerfile.v0', '--local', f'context=./{service}',
                   '--local', f'dockerfile=./{service}', '--opt', 'filename=Dockerfile',
                   '--opt', f'build-arg:BASE_IMAGE_REGISTRY={registry}/base',
                   '--opt', f'build-arg:OCR_BASE={registry}/finance/ocr-base:cu126-py311-v1',
                   '--opt', f'build-arg:CARGO_MIRROR_URL={env.get("CARGO_MIRROR_URL", "")}',
                   '--secret', 'id=ci_ca,src=/certs/ca.crt', '--output',
                   f'type=image,name={registry}/finance/{service}:{source[:12]},push=true']
        process = subprocess.Popen(command, cwd=root, env=env, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, bufsize=1,
                                   start_new_session=True)
        with process_lock:
            processes.add(process)
            if stop.is_set() and process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
        try:
            for line in process.stdout:
                with output_lock:
                    print(f'[{service}] {line.rstrip()}', flush=True)
            exit_code = process.wait()
            if exit_code != 0:
                metadata.unlink(missing_ok=True)
                print(f'[{service}] Image build failed (exit {exit_code})', flush=True)
                return False
            digest = json.loads(metadata.read_text()).get('containerimage.digest', '')
            if not re.fullmatch(r'sha256:[a-f0-9]{64}', digest):
                metadata.unlink(missing_ok=True)
                print(f'[{service}] Build completed without a valid image digest', flush=True)
                return False
            print(f'[{service}] Pushed image: {image}@{digest}', flush=True)
            return True
        finally:
            process.stdout.close()
            with process_lock:
                processes.discard(process)

    try:
        # OCR's large CUDA/Python image builds alone; the runtime OCR pod is
        # unrelated and remains running. Other images share two worker slots.
        ordinary = [service for service in services if service != 'ocr-service']
        with ThreadPoolExecutor(max_workers=jobs) as pool:
            results = list(pool.map(build, ordinary))
        if not all(results):
            return 1
        if 'ocr-service' in services and not build('ocr-service'):
            return 1
        summary = env.get('GITHUB_STEP_SUMMARY')
        if summary:
            lines = ['## Images built', '', '| Service | Image |', '| --- | --- |']
            for service in services:
                digest = json.loads((output / f'{service}.json').read_text())['containerimage.digest']
                lines.append(f'| `{service}` | `{registry}/finance/{service}:{source[:12]}@{digest}` |')
            with open(summary, 'a', encoding='utf-8') as stream:
                stream.write('\n'.join(lines) + '\n')
        return 0
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--services', required=True, help='JSON array from service detection')
    parser.add_argument('--jobs', type=int, choices=(1, 2), default=2)
    parser.add_argument('--output', default='image-digests')
    args = parser.parse_args()
    services = json.loads(args.services)
    if not isinstance(services, list):
        raise ValueError('Service selection must be a JSON array')
    return build_images(services, dict(os.environ), Path.cwd(), args.output, args.jobs)


if __name__ == '__main__':
    raise SystemExit(main())
