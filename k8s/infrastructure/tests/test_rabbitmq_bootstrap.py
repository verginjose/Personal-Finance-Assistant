"""Validate the actual ConfigMap startup hook with a fake RabbitMQ CLI."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import yaml

MANIFEST = Path(__file__).resolve().parents[1] / 'rabbitmq.yaml'
FAKE_CTL = '''#!/usr/bin/env python3
import os, pathlib, sys
args = sys.argv[1:]
ops = ['await_startup', 'authenticate_user', 'list_users', 'change_password', 'add_user', 'set_permissions']
op = next(a for a in args if a in ops)
root = pathlib.Path(os.environ['TEST_ROOT'])
with (root / 'calls').open('a') as f: f.write(op + '\\n')
case = os.environ['CASE']
if op == 'authenticate_user':
    sys.exit(0 if case == 'correct' or (root / 'updated').exists() else 1)
if op == 'list_users': print('guest []' if case == 'missing' else 'service []')
if op in ('add_user', 'change_password'): (root / 'updated').touch()
if op == 'set_permissions' and case == 'permission-denied': sys.exit(1)
'''


class BootstrapTests(unittest.TestCase):
    def run_hook(self, case):
        docs = list(yaml.safe_load_all(MANIFEST.read_text()))
        config = next(d for d in docs if d['kind'] == 'ConfigMap')
        deployment = next(d for d in docs if d['kind'] == 'StatefulSet')
        container = deployment['spec']['template']['spec']['containers'][0]
        self.assertEqual(container['lifecycle']['postStart']['exec']['command'],
                         ['/bin/sh', '/etc/rabbitmq/ensure-service-user.sh'])
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'hook.sh').write_text(config['data']['ensure-service-user.sh'])
            cli = root / 'rabbitmqctl'
            cli.write_text(FAKE_CTL)
            cli.chmod(0o755)
            env = dict(os.environ, PATH=folder + os.pathsep + os.environ['PATH'],
                       CASE=case, TEST_ROOT=folder, RABBITMQ_DEFAULT_USER='service',
                       RABBITMQ_DEFAULT_PASS='test-password-not-for-output')
            result = subprocess.run(['sh', str(root / 'hook.sh')], env=env,
                                    capture_output=True, text=True)
            calls = (root / 'calls').read_text().splitlines()
            self.assertNotIn(env['RABBITMQ_DEFAULT_PASS'], result.stdout + result.stderr)
            return result, calls

    def test_missing_account_is_created(self):
        result, calls = self.run_hook('missing')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('add_user', calls)
        self.assertIn('set_permissions', calls)

    def test_stale_password_is_reconciled(self):
        result, calls = self.run_hook('wrong')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('change_password', calls)
        self.assertNotIn('add_user', calls)

    def test_correct_password_is_not_changed(self):
        result, calls = self.run_hook('correct')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn('change_password', calls)
        self.assertNotIn('add_user', calls)

    def test_permission_failure_stops_hook(self):
        result, _ = self.run_hook('permission-denied')
        self.assertNotEqual(result.returncode, 0)

    def test_ocr_probe_is_promoted_with_the_new_image(self):
        root = MANIFEST.parents[2]
        workflow = yaml.safe_load((root / '.github/workflows/build.yml').read_text())
        step = next(s for s in workflow['jobs']['promote']['steps'] if 'run' in s)
        command = next(line.strip() for line in step['run'].splitlines()
                       if "sed -i '/readinessProbe:" in line)
        original = (root / 'k8s/apps/ocr-service.yaml').read_text()
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'ocr.yaml'
            path.write_text(original)
            subprocess.run(['sh', '-c', command], env=dict(os.environ, file=str(path)), check=True)
            docs = list(yaml.safe_load_all(path.read_text()))
        container = next(d for d in docs if d['kind'] == 'Deployment')['spec']['template']['spec']['containers'][0]
        self.assertEqual(container['readinessProbe']['httpGet']['path'], '/ready')
        self.assertEqual(container['livenessProbe']['httpGet']['path'], '/health')
        self.assertEqual(container['startupProbe']['httpGet']['path'], '/health')


if __name__ == '__main__':
    unittest.main()
