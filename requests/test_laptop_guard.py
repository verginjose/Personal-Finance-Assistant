import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import laptop_guard as guard


def healthy(**changes):
    result = dict(timestamp=time.time(), valid=True, cpu_c=50, gpu_c=40, ssd_c=40,
                  available_gib=12, cpu_percent=5, memory_pressure_full=0, swapout_total=100)
    result.update(changes)
    return result


class GuardTests(unittest.TestCase):
    def test_raw_limits_and_missing_sensor_values(self):
        for key, value in guard.LIMITS.items():
            sample=healthy();sample[key]=value if key!='available_gib' else value-.01
            self.assertIsNotNone(guard.violation(sample), key)
            del sample[key]
            self.assertIsNotNone(guard.violation(sample), key)

    def test_stale_future_and_nan_telemetry_fail_closed(self):
        for delta in (-4, 1):
            sample=healthy();sample['timestamp']+=delta
            self.assertIsNotNone(guard.violation(sample))
        sample=healthy();sample['cpu_c']=float('nan')
        self.assertIsNotNone(guard.violation(sample))
        sample=healthy();sample['memory_pressure_full']=float('nan')
        self.assertIsNotNone(guard.violation(sample))

    def test_cool_start_is_stricter_than_running_limits(self):
        sample=healthy();sample['cpu_c']=87
        self.assertIsNone(guard.violation(sample))
        self.assertIsNotNone(guard.violation(sample,starting=True))

    def test_real_child_is_terminated_on_first_critical_sample(self):
        class Reply:
            def __init__(self,data):self.data=data
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def read(self):return json.dumps(self.data).encode()
        with tempfile.TemporaryDirectory() as folder:
            args=argparse.Namespace(local_sensors=False,health_url='http://fixture', report=folder+'/report.json',
                                    command=[sys.executable,'-c','import time; time.sleep(60)'])
            hot=healthy();hot['cpu_c']=guard.CRITICAL['cpu_c']
            replies=[Reply(healthy()),Reply(healthy()),Reply(hot)]
            real_popen=subprocess.Popen
            processes=[]
            def launch(*a,**kw):
                child=real_popen(*a,**kw);processes.append(child);return child
            with patch.object(guard,'COOL_SECONDS',0), patch.object(guard.urllib.request,'urlopen',side_effect=replies), patch.object(guard.subprocess,'Popen',side_effect=launch):
                self.assertEqual(guard.supervise(args),3)
            self.assertIsNotNone(processes[0].poll())
            report=json.loads(Path(args.report).read_text())
            self.assertEqual(report['verdict'],'ABORTED')
            self.assertIn('critical',report['reason'])
            self.assertEqual(report['runtime_events'][0]['kind'],'critical')
            self.assertTrue(report['recent_samples'][-1]['sample_utc'])

    def test_absent_telemetry_never_launches_traffic(self):
        with tempfile.TemporaryDirectory() as folder:
            args=argparse.Namespace(local_sensors=False,health_url='http://fixture',report=folder+'/report.json',command=['unsafe'])
            with patch.object(guard.urllib.request,'urlopen',side_effect=OSError),patch.object(guard.subprocess,'Popen') as start:
                self.assertEqual(guard.supervise(args),3)
                start.assert_not_called()

    def test_one_ssd_spike_clears_without_stopping(self):
        window=guard.SafetyWindow(starting=True)
        self.assertIsNone(window.check(healthy(timestamp=100,ssd_c=86),now=100))
        self.assertIsNone(window.check(healthy(timestamp=100.5,ssd_c=42),now=100.5))
        self.assertFalse(window.hot)
        self.assertEqual([e['kind'] for e in window.events],['warning','cleared'])
        self.assertEqual(window.events[0]['value'],86)

    def test_persistent_heat_requires_two_seconds_of_new_samples(self):
        window=guard.SafetyWindow()
        for stamp in [100,100.5,101,101.5]:
            self.assertIsNone(window.check(healthy(timestamp=stamp,gpu_c=65),now=stamp))
        reason=window.check(healthy(timestamp=102,gpu_c=65),now=102)
        self.assertIn('2s',reason)
        self.assertEqual(window.events[-1]['kind'],'confirmed')
        self.assertEqual(window.events[-1]['first_sample_utc'],guard.utc(100))

    def test_observed_ssd_pulse_clears_but_five_seconds_of_heat_stops(self):
        window=guard.SafetyWindow(starting=True)
        for stamp in [100,100.7,101.4,102.1]:
            self.assertIsNone(window.check(healthy(timestamp=stamp,ssd_c=86),now=stamp))
        self.assertIsNone(window.check(healthy(timestamp=102.8,ssd_c=46),now=102.8))
        self.assertEqual(window.events[-1]['kind'],'cleared')
        for stamp in [110,111,112,113,114]:
            self.assertIsNone(window.check(healthy(timestamp=stamp,ssd_c=86),now=stamp))
        self.assertIn('5s',window.check(healthy(timestamp=115,ssd_c=86),now=115))

    def test_server_policy_also_stops_on_swap_out(self):
        window=guard.SafetyWindow()
        self.assertIsNone(window.check(healthy(timestamp=100),now=100))
        self.assertEqual(window.check(healthy(timestamp=100.5,swapout_total=101),now=100.5),'active swap-out')
        self.assertTrue(window.immediate)

    def test_duplicate_samples_do_not_confirm_heat(self):
        window=guard.SafetyWindow(starting=True);sample=healthy(timestamp=100,ssd_c=86)
        self.assertIsNone(window.check(sample,now=100))
        self.assertIsNone(window.check(sample,now=102))
        self.assertIn('stale',window.check(sample,now=103.1))

    def test_resource_and_sensor_failures_do_not_wait_for_heat_confirmation(self):
        for change in [dict(available_gib=5.9),dict(cpu_percent=80),dict(gpu_c=None),dict(swapout_total=None)]:
            window=guard.SafetyWindow()
            self.assertIsNotNone(window.check(healthy(ssd_c=86,**change)))
            self.assertTrue(window.immediate)

    def test_cpu_and_ssd_use_the_requested_90_degree_ceiling(self):
        for key in ['cpu_c', 'ssd_c']:
            self.assertEqual(guard.LIMITS[key], 90)
            self.assertEqual(guard.START[key], 85)
            self.assertIsNone(guard.SafetyWindow().check(healthy(**{key: 89.9})))
            self.assertIn('critical', guard.SafetyWindow().check(healthy(**{key: 90})))
        self.assertEqual(guard.LIMITS['gpu_c'], 65)
        self.assertEqual(guard.START['gpu_c'], 55)

    def test_every_critical_limit_trips_immediately(self):
        for key,value in guard.CRITICAL.items():
            window=guard.SafetyWindow()
            self.assertIn('critical',window.check(healthy(**{key:value})))
            self.assertTrue(window.immediate)

    def test_server_trip_is_sticky_even_when_current_temperature_is_cool(self):
        window=guard.SafetyWindow()
        self.assertIn('server watchdog',window.check(healthy(safety_trip='ssd_c confirmed hot')))

    def test_preflight_short_spike_does_not_erase_accumulated_cool_time(self):
        class Reply:
            def __init__(self,data):self.data=data
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def read(self):return json.dumps(self.data).encode()
        calls=0
        def read(*args,**kwargs):
            nonlocal calls
            calls+=1
            return Reply(healthy(ssd_c=86 if calls==3 else 40))
        with tempfile.TemporaryDirectory() as folder:
            args=argparse.Namespace(local_sensors=False,health_url='http://fixture',report=folder+'/report.json',
                                    command=[sys.executable,'-c','pass'])
            with patch.object(guard,'COOL_SECONDS',1),patch.object(guard.urllib.request,'urlopen',side_effect=read):
                self.assertEqual(guard.supervise(args),0)
            report=json.loads(Path(args.report).read_text())
            self.assertEqual(report['verdict'],'PASS')
            self.assertGreaterEqual(report['preflight_cool_seconds'],1)
            self.assertEqual([e['kind'] for e in report['preflight_events']],['warning','cleared'])

    def test_real_child_stops_after_confirmed_noncritical_heat(self):
        class Reply:
            def __init__(self,data):self.data=data
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def read(self):return json.dumps(self.data).encode()
        calls=0
        def read(*args,**kwargs):
            nonlocal calls
            calls+=1
            return Reply(healthy(gpu_c=65 if calls>2 else 50))
        with tempfile.TemporaryDirectory() as folder:
            args=argparse.Namespace(local_sensors=False,health_url='http://fixture',report=folder+'/report.json',
                                    command=[sys.executable,'-c','import time;time.sleep(60)'])
            with patch.object(guard,'COOL_SECONDS',0),patch.object(guard.urllib.request,'urlopen',side_effect=read):
                self.assertEqual(guard.supervise(args),3)
            report=json.loads(Path(args.report).read_text())
            self.assertEqual(report['runtime_events'][-1]['kind'],'confirmed')
            self.assertGreaterEqual(report['runtime_events'][-1]['duration_seconds'],2)


if __name__=='__main__':unittest.main()
