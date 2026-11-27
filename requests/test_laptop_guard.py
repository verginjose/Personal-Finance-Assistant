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
    return dict(timestamp=time.time(), valid=True, cpu_c=50, gpu_c=40, ssd_c=40,
                available_gib=12, cpu_percent=5, memory_pressure_full=0, swapout_total=100, **changes)


class GuardTests(unittest.TestCase):
    def test_immediate_cutoffs_and_missing_sensor_values(self):
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
        sample=healthy();sample['cpu_c']=70
        self.assertIsNone(guard.violation(sample))
        self.assertIsNotNone(guard.violation(sample,starting=True))

    def test_real_child_is_terminated_on_first_hot_sample(self):
        class Reply:
            def __init__(self,data):self.data=data
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def read(self):return json.dumps(self.data).encode()
        with tempfile.TemporaryDirectory() as folder:
            args=argparse.Namespace(local_sensors=False,health_url='http://fixture', report=folder+'/report.json',
                                    command=[sys.executable,'-c','import time; time.sleep(60)'])
            hot=healthy();hot['cpu_c']=78
            replies=[Reply(healthy()),Reply(healthy()),Reply(hot)]
            real_popen=subprocess.Popen
            processes=[]
            def launch(*a,**kw):
                child=real_popen(*a,**kw);processes.append(child);return child
            with patch.object(guard,'COOL_SECONDS',0), patch.object(guard.urllib.request,'urlopen',side_effect=replies), patch.object(guard.subprocess,'Popen',side_effect=launch):
                self.assertEqual(guard.supervise(args),3)
            self.assertIsNotNone(processes[0].poll())
            self.assertEqual(json.loads(Path(args.report).read_text())['verdict'],'ABORTED')

    def test_absent_telemetry_never_launches_traffic(self):
        with tempfile.TemporaryDirectory() as folder:
            args=argparse.Namespace(local_sensors=False,health_url='http://fixture',report=folder+'/report.json',command=['unsafe'])
            with patch.object(guard.urllib.request,'urlopen',side_effect=OSError),patch.object(guard.subprocess,'Popen') as start:
                self.assertEqual(guard.supervise(args),3)
                start.assert_not_called()


if __name__=='__main__':unittest.main()
