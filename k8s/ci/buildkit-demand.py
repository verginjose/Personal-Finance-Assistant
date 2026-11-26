#!/usr/bin/env python3
"""Start/stop only pfa-system/buildkitd using a short-lived projected token."""
import argparse
import json
import os
from pathlib import Path
import ssl
import time
import urllib.request

ROOT = Path(os.environ.get('BUILDKIT_CONTROL_DIR', '/var/run/pfa-buildkit'))
API = 'https://kubernetes.default.svc'
DEPLOYMENT = '/apis/apps/v1/namespaces/pfa-system/deployments/buildkitd'


def request(path, payload=None):
    headers = {'Authorization': 'Bearer '+(ROOT/'token').read_text().strip()}
    data = None
    if payload is not None:
        headers['Content-Type'] = 'application/merge-patch+json'
        data = json.dumps(payload).encode()
    req = urllib.request.Request(API+path, data=data, headers=headers,
                                 method='PATCH' if data is not None else 'GET')
    context = ssl.create_default_context(cafile=str(ROOT/'ca.crt'))
    with urllib.request.urlopen(req, context=context, timeout=15) as response:
        return json.load(response)


def control(action):
    request(DEPLOYMENT+'/scale', {'spec': {'replicas': 1 if action == 'start' else 0}})
    if action == 'stop':
        print('BuildKit stopped; persistent build cache retained')
        return
    deadline = time.monotonic()+180
    while time.monotonic() < deadline:
        deployment = request(DEPLOYMENT)
        status = deployment.get('status', {})
        if status.get('observedGeneration', 0) >= deployment['metadata']['generation'] and status.get('readyReplicas', 0) >= 1:
            print('BuildKit ready')
            return
        time.sleep(2)
    raise TimeoutError('BuildKit did not become ready within 180 seconds')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['start', 'stop'])
    control(parser.parse_args().action)
