#!/usr/bin/env python3
"""Render the guard ConfigMap; keep the executable script as the source."""
from pathlib import Path
import yaml

root = Path(__file__).resolve().parents[1]
manifest = root / 'k8s/system/laptop-health.yaml'
documents = list(yaml.safe_load_all(manifest.read_text())) if manifest.exists() else []
config = dict(apiVersion='v1', kind='ConfigMap', metadata=dict(name='laptop-health-script', namespace='pfa-system'),
              data={'laptop_guard.py': (root / 'requests/laptop_guard.py').read_text()})
class LiteralDumper(yaml.SafeDumper):
    pass
def text(dumper, value):
    return dumper.represent_scalar('tag:yaml.org,2002:str', value, style='|' if '\n' in value else None)
LiteralDumper.add_representer(str, text)
manifest.write_text(yaml.dump_all([config] + [d for d in documents if d['kind'] != 'ConfigMap'],
                                 Dumper=LiteralDumper, sort_keys=False))
