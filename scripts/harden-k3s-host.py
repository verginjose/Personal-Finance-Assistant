#!/usr/bin/env python3
"""Enable single-server k3s Secret encryption, metadata auditing and default seccomp."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import time
from datetime import datetime, timezone

CONFIG = Path('/etc/rancher/k3s/config.yaml.d/90-pfa-security.yaml')
POLICY = Path('/var/lib/rancher/k3s/server/audit-policy.yaml')
SERVER = Path('/var/lib/rancher/k3s/server')
DROPIN = '''# Managed by scripts/harden-k3s-host.py. Preserve earlier config with append keys.
secrets-encryption: true
secrets-encryption-provider: secretbox
kube-apiserver-arg+:
  - audit-policy-file=/var/lib/rancher/k3s/server/audit-policy.yaml
  - audit-log-path=/var/lib/rancher/k3s/server/logs/audit.log
  - audit-log-maxage=7
  - audit-log-maxbackup=3
  - audit-log-maxsize=50
kubelet-arg+:
  - seccomp-default=true
'''
AUDIT = '''apiVersion: audit.k8s.io/v1
kind: Policy
omitStages:
  - RequestReceived
rules:
  - level: Metadata
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise SystemExit('Run directly on the k3s host as root.')
    if not (SERVER / 'db/state.db').exists() or (SERVER / 'db/etcd').exists():
        raise SystemExit('This helper only supports the verified single-server SQLite deployment.')
    nodes = subprocess.check_output(['k3s', 'kubectl', 'get', 'nodes', '-o', 'json'])
    if len(json.loads(nodes)['items']) != 1:
        raise SystemExit('Refusing to change a multi-node cluster with the single-server procedure.')
    status = subprocess.check_output(['k3s', 'secrets-encrypt', 'status'], text=True)
    print(status.strip(), flush=True)
    if not args.apply:
        print('Check only; no host settings changed.', flush=True)
        return
    os.umask(0o077)
    backup = Path('/var/backups/pfa-k3s-security-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
    backup.mkdir(mode=0o700)
    log = open(backup / 'operations.log', 'wb')

    def run(command):
        result = subprocess.run(command, stdout=log, stderr=log)
        log.flush()
        if result.returncode:
            raise RuntimeError('Host operation failed; private details: ' + str(backup / 'operations.log'))

    def ready():
        for _ in range(120):
            result = subprocess.run(['k3s', 'kubectl', 'get', '--raw=/readyz'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if result.returncode == 0:
                return
            time.sleep(2)
        raise RuntimeError('Control plane readiness timed out; inspect the private operation log and systemd journal.')

    def secret_fingerprint():
        data = json.loads(subprocess.check_output(['k3s', 'kubectl', 'get', 'secrets', '-A', '-o', 'json']))
        values = sorted((s['metadata']['namespace'], s['metadata']['name'], s.get('type'), s.get('data')) for s in data['items'])
        return len(values), hashlib.sha256(json.dumps(values, sort_keys=True).encode()).hexdigest()

    before = secret_fingerprint()
    (backup / 'preflight.json').write_text(json.dumps({'secretCount': before[0], 'secretFingerprint': before[1], 'status': status}))
    if CONFIG.exists() and CONFIG.read_text() != DROPIN:
        raise SystemExit('Existing managed drop-in differs; review it before changing host settings.')
    # Current k3s procedure: enable while running, restart with the flag, rotate
    # to encrypt existing Secrets, and restart again. Do not use legacy --force.
    disabled = 'Disabled' in status
    if disabled:
        run(['k3s', 'secrets-encrypt', 'enable'])
    run(['systemctl', 'stop', 'k3s'])
    try:
        with tarfile.open(backup / 'control-plane.tar.gz', 'w:gz') as archive:
            for source in [SERVER / 'db', SERVER / 'tls', SERVER / 'cred', SERVER / 'token',
                           SERVER / 'agent-token', Path('/etc/rancher/k3s'), Path('/etc/systemd/system/k3s.service'),
                           Path('/etc/systemd/system/k3s.service.env')]:
                if source.exists():
                    archive.add(source, arcname=str(source).lstrip('/'))
        CONFIG.parent.mkdir(parents=True, exist_ok=True)
        (SERVER / 'logs').mkdir(exist_ok=True, mode=0o700)
        POLICY.write_text(AUDIT)
        POLICY.chmod(0o600)
        CONFIG.write_text(DROPIN)
        CONFIG.chmod(0o600)
    finally:
        run(['systemctl', 'start', 'k3s'])
    ready()
    if disabled:
        run(['k3s', 'secrets-encrypt', 'rotate-keys'])
        for _ in range(180):
            result = subprocess.run(['k3s', 'secrets-encrypt', 'status'], capture_output=True, text=True, check=True)
            if 'reencrypt_finished' in result.stdout and 'Enabled' in result.stdout:
                break
            time.sleep(2)
        else:
            raise RuntimeError('Reencryption not complete; do not restore the old database over the running server.')
        run(['systemctl', 'restart', 'k3s'])
        ready()
    final = subprocess.check_output(['k3s', 'secrets-encrypt', 'status'], text=True)
    if 'Enabled' not in final or 'All hashes match' not in final:
        raise RuntimeError('Encryption status needs operator review; private backup: ' + str(backup))
    after = secret_fingerprint()
    if before != after:
        raise RuntimeError('Secret inventory changed during maintenance; compare privately before claiming preservation.')
    # Keep the final key set with the pre-change snapshot for disaster recovery.
    shutil.copytree(SERVER / 'cred', backup / 'enabled-credentials')
    audit = SERVER / 'logs/audit.log'
    if not audit.exists() or audit.stat().st_size == 0:
        raise RuntimeError('Audit log was not populated.')
    result = {'encryption': final.strip(), 'secretCount': after[0], 'secretValuesUnchanged': True,
              'auditLogPopulated': True, 'backupDirectory': str(backup)}
    (backup / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2), flush=True)


if __name__ == '__main__':
    main()
