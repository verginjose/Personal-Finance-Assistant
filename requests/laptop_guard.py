#!/usr/bin/env python3
"""Fail-closed hardware guard for this laptop. Limits cannot be relaxed by CLI."""
import argparse
from collections import deque
from datetime import datetime, timezone
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
import urllib.error
import urllib.request

LIMITS = dict(cpu_c=90, gpu_c=65, ssd_c=90, available_gib=6, cpu_percent=80)
CRITICAL = dict(cpu_c=90, gpu_c=75, ssd_c=90)
THERMAL_CONFIRM_SECONDS = dict(cpu_c=2, gpu_c=2, ssd_c=5)
# User-requested CPU/SSD ceiling: 90C, with 5C of start headroom.
# The display keeps this laptop's GPU around 50–52C at idle. Keep ten
# degrees of headroom while allowing a genuinely idle laptop to start.
START = dict(cpu_c=85, gpu_c=55, ssd_c=85, available_gib=8, cpu_percent=50)
FRESH_SECONDS = 3
COOL_SECONDS = 60


def invalid_sample(sample, now=None):
    now = time.time() if now is None else now
    if not isinstance(sample, dict):
        return 'malformed hardware telemetry'
    stamp = sample.get('timestamp')
    if (sample.get('valid') is not True or not isinstance(stamp, (int, float))
            or not math.isfinite(stamp) or not 0 <= now - stamp <= FRESH_SECONDS):
        return 'hardware telemetry missing, invalid or stale'
    for key in LIMITS:
        value = sample.get(key)
        if not isinstance(value, (int, float)) or not math.isfinite(value):
            return f'{key} unavailable'
    if not isinstance(sample.get('swapout_total'), int) or sample['swapout_total'] < 0:
        return 'swap-out telemetry unavailable'
    pressure = sample.get('memory_pressure_full', float('nan'))
    if not isinstance(pressure, (int, float)) or not math.isfinite(pressure) or pressure > 1:
        return 'memory pressure exceeds 1%'
    return None


def violation(sample, starting=False, now=None):
    """Raw limits, also used to avoid arming during a pending hot reading."""
    error = invalid_sample(sample, now)
    if error:
        return error
    limits = START if starting else LIMITS
    for key, limit in limits.items():
        value = sample[key]
        high = value > limit if starting else value >= limit
        if (key == 'available_gib' and value < limit) or (key != 'available_gib' and high):
            return f'{key}={value:.2f} reached safety limit {limit}'
    return None


def utc(stamp):
    try:
        return datetime.fromtimestamp(stamp, timezone.utc).isoformat().replace('+00:00', 'Z')
    except (ValueError, OverflowError, OSError, TypeError):
        return None


class SafetyWindow:
    """Confirm ordinary heat using sensor time, never repeated HTTP samples."""
    def __init__(self, starting=False):
        self.starting = starting
        self.hot = {}
        self.last_stamp = None
        self.events = deque(maxlen=64)
        self.immediate = False
        self.previous_swap = None

    def check(self, sample, now=None):
        self.immediate = True
        error = invalid_sample(sample, now)
        if error:
            return error
        if not self.starting and sample.get('safety_trip'):
            return 'server watchdog tripped: ' + str(sample['safety_trip'])
        swap = sample['swapout_total']
        previous_swap, self.previous_swap = self.previous_swap, swap
        if previous_swap is not None and swap != previous_swap:
            return 'active swap-out' if swap > previous_swap else 'swap-out counter moved backwards'
        limits = START if self.starting else LIMITS
        if sample['available_gib'] < limits['available_gib']:
            return f"available_gib={sample['available_gib']:.2f} below safety limit {limits['available_gib']}"
        busy = sample['cpu_percent'] > limits['cpu_percent'] if self.starting else sample['cpu_percent'] >= limits['cpu_percent']
        if busy:
            return 'host CPU utilization reached safety limit'
        for key, limit in CRITICAL.items():
            if sample[key] >= limit:
                event = dict(kind='critical', sensor=key, value=sample[key], limit=limit,
                             sample_utc=utc(sample['timestamp']))
                if not self.events or self.events[-1] != event:
                    self.events.append(event)
                return f'{key}={sample[key]:.2f} reached immediate critical limit {limit}'
        self.immediate = False
        stamp = sample['timestamp']
        if self.last_stamp is not None and stamp < self.last_stamp:
            self.immediate = True
            return 'hardware telemetry timestamp moved backwards'
        fresh = stamp != self.last_stamp
        self.last_stamp = stamp
        for key in CRITICAL:
            high = sample[key] > limits[key] if self.starting else sample[key] >= limits[key]
            if not high:
                previous = self.hot.pop(key, None)
                if previous:
                    self.events.append(dict(kind='cleared', sensor=key, first_sample_utc=utc(previous['first']),
                                            sample_utc=utc(stamp), peak=previous['peak']))
                continue
            if key not in self.hot:
                self.hot[key] = dict(first=stamp, peak=sample[key])
                self.events.append(dict(kind='warning', sensor=key, value=sample[key], limit=limits[key],
                                        sample_utc=utc(stamp)))
            self.hot[key]['peak'] = max(self.hot[key]['peak'], sample[key])
            if fresh and stamp - self.hot[key]['first'] >= THERMAL_CONFIRM_SECONDS[key]:
                event = dict(kind='confirmed', sensor=key, value=sample[key], limit=limits[key],
                             first_sample_utc=utc(self.hot[key]['first']), sample_utc=utc(stamp),
                             duration_seconds=round(stamp-self.hot[key]['first'], 3))
                self.events.append(event)
                return f'{key}={sample[key]:.2f} exceeded {limits[key]} for {event["duration_seconds"]}s'
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
    trip_reason = None
    runtime_window = SafetyWindow()
    def pause_ocr(reason):
        nonlocal tripped, trip_reason
        token = Path('/var/run/secrets/kubernetes.io/serviceaccount/token').read_text().strip()
        context = ssl.create_default_context(cafile='/var/run/secrets/kubernetes.io/serviceaccount/ca.crt')
        request = urllib.request.Request('https://kubernetes.default.svc/apis/apps/v1/namespaces/pfa/deployments/ocr-service/scale',
            data=b'{"spec":{"replicas":0}}', method='PATCH', headers={
                'Authorization':'Bearer '+token,'Content-Type':'application/merge-patch+json'})
        with urllib.request.urlopen(request,context=context,timeout=2) as response:
            response.read()
        tripped = True
        trip_reason = reason
    def update():
        nonlocal latest
        while True:
            reading = sensors.sample()
            reason = runtime_window.check(reading)
            reading['thermal_warning'] = dict(runtime_window.hot)
            reading['safety_events'] = list(runtime_window.events)
            if time.monotonic() < armed_until and reason and not tripped:
                try:
                    pause_ocr(reason)
                except Exception:
                    reading['ocr_pause_failed'] = True
            reading['test_armed'] = time.monotonic() < armed_until
            reading['ocr_paused'] = tripped
            reading['safety_trip'] = trip_reason
            reading['guard_policy'] = dict(thermal_seconds=THERMAL_CONFIRM_SECONDS, critical=CRITICAL)
            latest = reading
            time.sleep(0.5)
    threading.Thread(target=update, daemon=True).start()
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != '/health':
                self.send_error(404)
                return
            body = json.dumps(latest).encode()
            self.send_response(200 if latest.get('valid') else 503)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        def do_POST(self):
            nonlocal armed_until, runtime_window, trip_reason, tripped
            if self.path == '/arm' and not violation(latest, starting=True):
                runtime_window = SafetyWindow()
                trip_reason = None
                tripped = False
                latest['safety_trip'] = None
                latest['ocr_paused'] = False
                armed_until = time.monotonic() + 600
            elif self.path == '/disarm':
                armed_until = 0
            elif self.path == '/trip':
                # This private endpoint is reachable only from the CI runner.
                # A missing sensor is also a valid reason to pause GPU work.
                try:
                    pause_ocr('load supervisor requested stop')
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
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        process.wait(timeout=2)
        return
    try:
        process.wait(timeout=1)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait(timeout=2)


def supervise(args):
    sensors = Sensors() if args.local_sensors else None
    def sample():
        if sensors:
            return sensors.sample()
        try:
            with urllib.request.urlopen(args.health_url, timeout=1.5) as response:
                reading = json.load(response)
                return reading if isinstance(reading, dict) else {'valid':False}
        except Exception:
            return {'valid': False}
    report = dict(verdict='REFUSED', limits=LIMITS, start_limits=START, critical_limits=CRITICAL,
                  thermal_confirmation_seconds=THERMAL_CONFIRM_SECONDS,
                  cool_seconds=COOL_SECONDS, samples=0, peaks={}, reason='preflight incomplete')
    process = None
    armed = False
    preflight_window = SafetyWindow(starting=True)
    runtime_window = SafetyWindow()
    trace = deque(maxlen=120)
    def remember(reading, phase):
        row = dict(phase=phase, observed_utc=utc(time.time()), sample_utc=utc(reading.get('timestamp')),
                   valid=bool(reading.get('valid')))
        for key in ['timestamp', *LIMITS, 'memory_pressure_full', 'swapout_total']:
            value = reading.get(key)
            row[key] = value if isinstance(value, (int, float)) and math.isfinite(value) else None
        trace.append(row)
        if reading.get('safety_events'):
            report['server_events'] = reading['safety_events'][-8:]
        report['samples'] += 1
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
        def interrupted(signum, frame):
            raise KeyboardInterrupt
        for sig in (signal.SIGTERM, signal.SIGINT):
            signal.signal(sig, interrupted)
        # Only fresh, cool intervals count. A short spike pauses this clock;
        # confirmed heat resets it, rather than erasing it for one bad sample.
        cool_seconds = 0
        previous_cool_stamp = None
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            reading = sample()
            remember(reading, 'preflight')
            reason = preflight_window.check(reading)
            if invalid_sample(reading) is None and swap_active(reading):
                reason = 'active swap-out'
            if reason:
                cool_seconds = 0
                previous_cool_stamp = None
                report['reason'] = reason
                if preflight_window.immediate and ('critical' in reason or invalid_sample(reading)):
                    # First local sample establishes a CPU delta; allow one tick.
                    if sensors and sensors.previous_cpu and report['samples'] == 1:
                        time.sleep(0.5)
                        continue
                    return 3
            elif violation(reading, starting=True) is None:
                if previous_cool_stamp is not None:
                    cool_seconds += min(max(0, reading['timestamp'] - previous_cool_stamp), FRESH_SECONDS)
                    if cool_seconds >= COOL_SECONDS:
                        report['preflight_cool_seconds'] = round(cool_seconds, 3)
                        try:
                            control('arm')
                            armed = bool(getattr(args, 'pause_ocr_on_abort', False))
                            break
                        except urllib.error.HTTPError as error:
                            code = error.code
                            error.close()
                            if code != 409:
                                report['reason'] = 'hardware watchdog could not be armed'
                                return 3
                            # A new hot sample arrived between checking and arming.
                            previous_cool_stamp = None
                        except OSError:
                            report['reason'] = 'hardware watchdog could not be armed'
                            return 3
                previous_cool_stamp = reading['timestamp']
            else:
                previous_cool_stamp = None
            report['preflight_cool_seconds'] = round(cool_seconds, 3)
            time.sleep(0.5)
        else:
            report['reason'] = f'cooldown incomplete: {cool_seconds:.1f}/{COOL_SECONDS} cool seconds'
            return 3
        if not args.command:
            raise ValueError('No guarded command supplied')
        process = subprocess.Popen(args.command, start_new_session=True,
                                   env={**os.environ, 'PFA_STRICT_HEALTH_GUARD':'1'})
        started = time.monotonic()
        while process.poll() is None:
            reading = sample()
            remember(reading, 'runtime')
            for key in LIMITS:
                if isinstance(reading.get(key), (int, float)) and math.isfinite(reading[key]):
                    previous = report['peaks'].get(key, reading[key])
                    report['peaks'][key] = min(previous, reading[key]) if key == 'available_gib' else max(previous, reading[key])
            reason = runtime_window.check(reading)
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
        final_reading = sample()
        remember(final_reading, 'finished')
        final_reason = runtime_window.check(final_reading)
        if final_reason:
            report.update(verdict='ABORTED', reason=final_reason)
            control('trip')
            report['ocr_paused'] = getattr(args, 'pause_ocr_on_abort', False)
            return 3
        report.update(verdict='PASS' if process.returncode == 0 else 'FAILED', reason='guarded process finished')
        if process.returncode == 0:
            control('disarm')
            armed = False
        else:
            control('trip')
            report['ocr_paused'] = getattr(args, 'pause_ocr_on_abort', False)
        return process.returncode
    except KeyboardInterrupt:
        report.update(verdict='ABORTED', reason='cancelled')
        try:
            if armed or process:
                control('trip')
        except Exception:
            report['ocr_pause_failed'] = True
        return 3
    except Exception as error:
        report.update(verdict='ABORTED' if process else 'REFUSED',
                      reason=f'watchdog operation failed ({type(error).__name__})')
        try:
            if armed or process:
                control('trip')
        except Exception:
            report['ocr_pause_failed'] = True
        return 3
    finally:
        if process:
            terminate(process)
        elif armed:
            try:
                control('disarm')
            except Exception:
                report['watchdog_disarm_failed'] = True
        report['preflight_events'] = list(preflight_window.events)
        report['runtime_events'] = list(runtime_window.events)
        report['recent_samples'] = list(trace)
        Path(args.report).write_text(json.dumps(report, indent=2) + '\n')
        summary = {k:v for k,v in report.items() if k not in ('recent_samples', 'preflight_events', 'runtime_events')}
        summary['recorded_samples'] = len(trace)
        print(json.dumps(summary), flush=True)


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
