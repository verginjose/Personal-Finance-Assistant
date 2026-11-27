#!/usr/bin/env python3
"""Fail-closed hardware guard for this laptop. Limits cannot be relaxed by CLI."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import os
from pathlib import Path
import signal
import ssl
import subprocess
import threading
import time
import urllib.request

LIMITS = dict(cpu_c=78, gpu_c=65, ssd_c=70, available_gib=6, cpu_percent=80)
# The display keeps this laptop's GPU around 50–52C at idle. Keep ten
# degrees of headroom while allowing a genuinely idle laptop to start.
START = dict(cpu_c=65, gpu_c=55, ssd_c=60, available_gib=8, cpu_percent=50)
FRESH_SECONDS = 3
COOL_SECONDS = 60


def violation(sample, starting=False, now=None):
    now = time.time() if now is None else now
    if not sample.get('valid') or not 0 <= now - sample.get('timestamp', 0) <= FRESH_SECONDS:
        return 'hardware telemetry missing, invalid or stale'
    limits = START if starting else LIMITS
    for key, limit in limits.items():
        value = sample.get(key)
        if not isinstance(value, (int, float)) or not math.isfinite(value):
            return f'{key} unavailable'
        high = value > limit if starting else value >= limit
        if (key == 'available_gib' and value < limit) or (key != 'available_gib' and high):
            return f'{key}={value:.2f} reached safety limit {limit}'
    pressure = sample.get('memory_pressure_full', float('nan'))
    if not isinstance(pressure, (int, float)) or not math.isfinite(pressure) or pressure > 1:
        return 'memory pressure exceeds 1%'
    return None


class Sensors:
    def __init__(self, sys_root='/sys', proc_root='/proc'):
        self.sys_root, self.proc_root = Path(sys_root), Path(proc_root)
        self.previous_cpu = None
        self.previous_swap = None

    def sample(self):
        result = {'timestamp': time.time(), 'valid': False}
        try:
            cpu, ssd = [], []
            for folder in (self.sys_root / 'class/hwmon').glob('hwmon*'):
                name = (folder / 'name').read_text().strip()
                if name in ('coretemp', 'k10temp'):
                    cpu += [float(p.read_text()) / 1000 for p in folder.glob('temp*_input')]
                elif name == 'nvme':
                    # Composite sensor, rather than the hotter controller sensor.
                    ssd.append(float((folder / 'temp1_input').read_text()) / 1000)
            result['cpu_c'], result['ssd_c'] = max(cpu), max(ssd)
            gpu = subprocess.check_output(['nvidia-smi', '--query-gpu=temperature.gpu',
                                           '--format=csv,noheader,nounits'], timeout=1.5, text=True)
            result['gpu_c'] = max(float(v) for v in gpu.splitlines())
            mem = dict((line.split(':', 1)[0], line.split(':', 1)[1].strip())
                       for line in (self.proc_root / 'meminfo').read_text().splitlines())
            result['available_gib'] = int(mem['MemAvailable'].split()[0]) / 1024**2
            ticks = list(map(int, (self.proc_root / 'stat').read_text().splitlines()[0].split()[1:9]))
            total, idle = sum(ticks), ticks[3] + ticks[4]
            if self.previous_cpu:
                dt, di = total - self.previous_cpu[0], idle - self.previous_cpu[1]
                result['cpu_percent'] = 100 * (1 - di / dt) if dt > 0 else 0
            self.previous_cpu = (total, idle)
            vm = dict(line.split() for line in (self.proc_root / 'vmstat').read_text().splitlines())
            swap = int(vm['pswpout'])
            result['swapout_total'] = swap
            result['swapout_pages'] = 0 if self.previous_swap is None else max(0, swap - self.previous_swap)
            self.previous_swap = swap
            pressure = (self.proc_root / 'pressure/memory').read_text().splitlines()
            full = next(line for line in pressure if line.startswith('full '))
            result['memory_pressure_full'] = float(dict(v.split('=') for v in full.split()[1:])['avg10'])
            result['valid'] = 'cpu_percent' in result
        except (OSError, ValueError, KeyError, StopIteration, subprocess.SubprocessError):
            # Never treat a sensor failure as a safe zero temperature.
            result['error'] = 'required hardware sensor unavailable'
        return result


def serve(args):
    sensors = Sensors(args.sys_root, args.proc_root)
    latest = {'valid': False}
    armed_until = 0
    tripped = False
    def pause_ocr():
        nonlocal tripped
        token = Path('/var/run/secrets/kubernetes.io/serviceaccount/token').read_text().strip()
        context = ssl.create_default_context(cafile='/var/run/secrets/kubernetes.io/serviceaccount/ca.crt')
        request = urllib.request.Request('https://kubernetes.default.svc/apis/apps/v1/namespaces/pfa/deployments/ocr-service/scale',
            data=b'{"spec":{"replicas":0}}', method='PATCH', headers={
                'Authorization':'Bearer '+token,'Content-Type':'application/merge-patch+json'})
        with urllib.request.urlopen(request,context=context,timeout=2) as response:
            response.read()
        tripped = True
    def update():
        nonlocal latest
        while True:
            latest = sensors.sample()
            if time.monotonic() < armed_until and violation(latest):
                try:
                    pause_ocr()
                except Exception:
                    latest['ocr_pause_failed'] = True
            latest['test_armed'] = time.monotonic() < armed_until
            latest['ocr_paused'] = tripped
            time.sleep(0.5)
    threading.Thread(target=update, daemon=True).start()
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != '/health':
                self.send_error(404)
                return
            body = json.dumps(latest).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        def do_POST(self):
            nonlocal armed_until
            if self.path == '/arm' and not violation(latest, starting=True):
                armed_until = time.monotonic() + 600
            elif self.path == '/disarm':
                armed_until = 0
            elif self.path == '/trip':
                # This private endpoint is reachable only from the CI runner.
                # A missing sensor is also a valid reason to pause GPU work.
                try:
                    pause_ocr()
                except Exception:
                    self.send_error(503)
                    return
                armed_until = 0
            else:
                self.send_error(409)
                return
            self.send_response(204)
            self.end_headers()
        def log_message(self, *unused):
            pass
    ThreadingHTTPServer(('0.0.0.0', args.port), Handler).serve_forever()


def terminate(process):
    if process.poll() is not None:
        return
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=1)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=2)


def supervise(args):
    sensors = Sensors() if args.local_sensors else None
    def sample():
        if sensors:
            return sensors.sample()
        try:
            with urllib.request.urlopen(args.health_url, timeout=1.5) as response:
                return json.load(response)
        except Exception:
            return {'valid': False}
    report = dict(verdict='REFUSED', limits=LIMITS, start_limits=START,
                  cool_seconds=COOL_SECONDS, samples=0, peaks={}, reason='preflight incomplete')
    process = None
    previous_swap = None
    def swap_active(reading):
        nonlocal previous_swap
        value = reading.get('swapout_total')
        if not isinstance(value, int):
            return True
        active = previous_swap is not None and value > previous_swap
        previous_swap = value
        return active
    def control(action):
        if not getattr(args, 'pause_ocr_on_abort', False):
            return
        url = args.health_url.rsplit('/health',1)[0] + '/' + action
        request = urllib.request.Request(url, data=b'', method='POST')
        with urllib.request.urlopen(request,timeout=2) as response:
            response.read()
    try:
        # Wait at most two minutes for 60 continuous seconds below start limits.
        cool_since = None
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            reading = sample()
            reason = violation(reading, starting=True)
            if not reason and swap_active(reading):
                reason = 'active swap-out'
            if reason:
                cool_since = None
                report['reason'] = reason
                if not reading.get('valid'):
                    # First local sample establishes a CPU delta; allow one tick.
                    if sensors and sensors.previous_cpu and report['samples'] == 0:
                        report['samples'] += 1
                        time.sleep(0.5)
                        continue
                    return 3
            elif cool_since is None:
                cool_since = time.monotonic()
            elif time.monotonic() - cool_since >= COOL_SECONDS:
                break
            report['samples'] += 1
            time.sleep(0.5)
        else:
            return 3
        if not args.command:
            raise ValueError('No guarded command supplied')
        control('arm')
        process = subprocess.Popen(args.command, start_new_session=True,
                                   env={**os.environ, 'PFA_STRICT_HEALTH_GUARD':'1'})
        started = time.monotonic()
        def interrupted(signum, frame):
            raise KeyboardInterrupt
        for sig in (signal.SIGTERM, signal.SIGINT):
            signal.signal(sig, interrupted)
        while process.poll() is None:
            reading = sample()
            report['samples'] += 1
            for key in LIMITS:
                if isinstance(reading.get(key), (int, float)):
                    previous = report['peaks'].get(key, reading[key])
                    report['peaks'][key] = min(previous, reading[key]) if key == 'available_gib' else max(previous, reading[key])
            reason = violation(reading)
            if swap_active(reading):
                reason = 'active swap-out'
            if time.monotonic() - started >= 600:
                reason = 'ten-minute hard runtime limit'
            if reason:
                report.update(verdict='ABORTED', reason=reason)
                terminate(process)
                try:
                    control('trip')
                    report['ocr_paused'] = getattr(args, 'pause_ocr_on_abort', False)
                except Exception:
                    report['ocr_pause_failed'] = True
                return 3
            time.sleep(0.5)
        report.update(verdict='PASS' if process.returncode == 0 else 'FAILED', reason='guarded process finished')
        if process.returncode == 0:
            control('disarm')
        else:
            control('trip')
            report['ocr_paused'] = getattr(args, 'pause_ocr_on_abort', False)
        return process.returncode
    except KeyboardInterrupt:
        report.update(verdict='ABORTED', reason='cancelled')
        try:
            control('trip')
        except Exception:
            report['ocr_pause_failed'] = True
        return 3
    finally:
        if process:
            terminate(process)
        Path(args.report).write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report), flush=True)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    commands = p.add_subparsers(dest='mode', required=True)
    server = commands.add_parser('serve')
    server.add_argument('--sys-root', default='/sys')
    server.add_argument('--proc-root', default='/proc')
    server.add_argument('--port', type=int, default=9187)
    run = commands.add_parser('run')
    run.add_argument('--health-url', default='http://laptop-health.pfa-system.svc.cluster.local:9187/health')
    run.add_argument('--local-sensors', action='store_true')
    run.add_argument('--pause-ocr-on-abort', action='store_true')
    run.add_argument('--report', required=True)
    run.add_argument('command', nargs=argparse.REMAINDER)
    args = p.parse_args()
    if args.mode == 'serve':
        serve(args)
        return 0
    if args.command[:1] == ['--']:
        args.command = args.command[1:]
    return supervise(args)


if __name__ == '__main__':
    raise SystemExit(main())
