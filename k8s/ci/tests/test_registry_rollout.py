"""The GC must not collect while the old writable registry pod is still ready."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

SOURCE = Path(__file__).resolve().parents[1] / 'registry-gc-cron.yaml'


class RegistryRolloutTests(unittest.TestCase):
    def wait(self, initial):
        source = SOURCE.read_text()
        body = source.split('                  wait_rollout() {', 1)[1].split('\n                  }', 1)[0]
        function = 'wait_rollout() {\n' + textwrap.dedent(body) + '\n}\n'
        ready = {'metadata': {'generation': 7}, 'spec': {'replicas': 1},
                 'status': {'observedGeneration': 7, 'updatedReplicas': 1,
                            'readyReplicas': 1, 'availableReplicas': 1, 'replicas': 1}}
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'states.json').write_text(json.dumps([initial, ready]))
            (root / 'calls').write_text('0')
            kubectl = root / 'kubectl'
            kubectl.write_text('''#!/usr/bin/env python3
import json, os, pathlib
root = pathlib.Path(os.environ['FIXTURES'])
count = int((root / 'calls').read_text())
states = json.loads((root / 'states.json').read_text())
print(json.dumps(states[min(count, len(states)-1)]))
(root / 'calls').write_text(str(count+1))
''')
            kubectl.chmod(0o755)
            sleep = root / 'sleep'
            sleep.write_text('#!/bin/sh\nexit 0\n')
            sleep.chmod(0o755)
            env = dict(os.environ, PATH=folder + os.pathsep + os.environ['PATH'], FIXTURES=folder)
            result = subprocess.run(['sh', '-c', function + 'NS=pfa; DEPLOY=registry; wait_rollout 10'],
                                    env=env, capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            return int((root / 'calls').read_text())

    def test_rejects_ready_status_from_previous_generation(self):
        old = {'metadata': {'generation': 7}, 'spec': {'replicas': 1},
               'status': {'observedGeneration': 6, 'updatedReplicas': 1,
                          'readyReplicas': 1, 'availableReplicas': 1, 'replicas': 1}}
        self.assertEqual(self.wait(old), 2)

    def test_waits_until_old_pod_is_replaced(self):
        old = {'metadata': {'generation': 7}, 'spec': {'replicas': 1},
               'status': {'observedGeneration': 7, 'updatedReplicas': 0,
                          'readyReplicas': 1, 'availableReplicas': 1, 'replicas': 1}}
        self.assertEqual(self.wait(old), 2)


if __name__ == '__main__':
    unittest.main()
