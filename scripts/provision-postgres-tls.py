#!/usr/bin/env python3
"""Create or renew PostgreSQL TLS files and seal the server private key for GitOps."""
import argparse
import base64
import json
import os
from pathlib import Path
import subprocess
import tempfile


def run(args, **kwargs):
    result = subprocess.run(args, capture_output=True, **kwargs)
    if result.returncode:
        raise SystemExit('TLS provisioning failed; private command output was suppressed.')
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path)
    parser.add_argument('--renew', action='store_true', help='Issue a new one-year server certificate using the existing CA')
    parser.add_argument('--kubeconfig', default=os.environ.get('KUBECONFIG'))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    directory = args.directory or root / '.certs/postgres'
    os.umask(0o077)
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    ca_key, ca_cert = directory / 'postgres-ca.key', directory / 'postgres-ca.crt'
    key, cert = directory / 'postgres.key', directory / 'postgres.crt'
    if args.renew and not all(path.exists() for path in (ca_key, ca_cert, key, cert)):
        raise SystemExit('Renewal requires the existing CA and server material; restore the private backup first.')
    if ca_key.exists() != ca_cert.exists():
        raise SystemExit('Incomplete CA material; restore the existing CA before proceeding.')
    if not ca_key.exists():
        if key.exists() or cert.exists():
            raise SystemExit('Refusing to replace a missing CA while server material exists.')
        run(['openssl', 'ecparam', '-name', 'prime256v1', '-genkey', '-noout', '-out', str(ca_key)])
        run(['openssl', 'req', '-new', '-x509', '-sha256', '-days', '1825', '-key', str(ca_key),
             '-out', str(ca_cert), '-subj', '/CN=PFA PostgreSQL CA'])
    if run(['openssl', 'pkey', '-in', str(ca_key), '-pubout']) != run(['openssl', 'x509', '-in', str(ca_cert), '-pubkey', '-noout']):
        raise SystemExit('CA certificate/key mismatch; restore the matching material.')
    if key.exists() != cert.exists():
        raise SystemExit('Incomplete server material; restore it or move both files aside before reissuing.')
    if args.renew or not cert.exists():
        with tempfile.TemporaryDirectory(dir=directory) as temporary:
            temporary = Path(temporary)
            server_key, csr, server_cert = [temporary / name for name in ('server.key', 'server.csr', 'server.crt')]
            extensions = temporary / 'extensions'
            names = [name + suffix for name in ('postgres-primary', 'postgres-replica')
                     for suffix in ('', '.pfa', '.pfa.svc', '.pfa.svc.cluster.local')]
            extensions.write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyAgreement\n'
                                  'extendedKeyUsage=serverAuth\nsubjectAltName=' + ','.join('DNS:' + name for name in names) + '\n')
            run(['openssl', 'ecparam', '-name', 'prime256v1', '-genkey', '-noout', '-out', str(server_key)])
            run(['openssl', 'req', '-new', '-sha256', '-key', str(server_key), '-out', str(csr), '-subj', '/CN=postgres-primary'])
            run(['openssl', 'x509', '-req', '-sha256', '-days', '365', '-in', str(csr), '-CA', str(ca_cert),
                 '-CAkey', str(ca_key), '-CAcreateserial', '-out', str(server_cert), '-extfile', str(extensions)])
            server_key.replace(key)
            server_cert.replace(cert)
    if run(['openssl', 'pkey', '-in', str(key), '-pubout']) != run(['openssl', 'x509', '-in', str(cert), '-pubkey', '-noout']):
        raise SystemExit('Server certificate/key mismatch; refusing to seal unusable TLS material.')
    run(['openssl', 'verify', '-CAfile', str(ca_cert), str(cert)])
    run(['openssl', 'x509', '-checkend', '2592000', '-noout', '-in', str(cert)])
    for private_key in (ca_key, key):
        private_key.chmod(0o600)
    secret = {'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': 'postgres-tls', 'namespace': 'pfa'},
              'type': 'kubernetes.io/tls', 'data': {name: base64.b64encode(path.read_bytes()).decode()
              for name, path in {'tls.crt': cert, 'tls.key': key, 'ca.crt': ca_cert}.items()}}
    command = ['kubeseal'] + (['--kubeconfig', args.kubeconfig] if args.kubeconfig else [])
    command += ['--controller-namespace', 'kube-system', '--controller-name', 'sealed-secrets-controller', '--format', 'yaml']
    sealed = run(command, input=json.dumps(secret).encode())
    (root / 'k8s/sealed/postgres-tls.yaml').write_bytes(sealed)
    config = {'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': {'name': 'postgres-ca', 'namespace': 'pfa'},
              'data': {'ca.crt': ca_cert.read_text()}}
    # JSON is valid YAML; only the public CA is written here.
    (root / 'k8s/config/infra/postgres-ca.yaml').write_text(json.dumps(config, indent=2) + '\n')
    print('Wrote the SealedSecret and public CA ConfigMap. Commit both and verify primary/replica TLS after deployment.')


if __name__ == '__main__':
    main()
