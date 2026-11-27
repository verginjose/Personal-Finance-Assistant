#!/usr/bin/env python3
"""Configure service database privileges without exposing their passwords."""
import argparse
import base64
import json
import os
from pathlib import Path
import subprocess
import time

ROLES = {
    'auth-service-secrets': 'pfa_auth',
    'command-service-secrets': 'pfa_command',
    'query-service-secrets': 'pfa_query',
    'postgres-grafana-secrets': 'pfa_grafana',
    'postgres-replica-secrets': 'pfa_replicator',
}


def literal(value):
    return "'" + value.replace("'", "''") + "'"


def role_sql(credentials, database='pfa'):
    if not database.replace('_', '').isalnum():
        raise ValueError('Unexpected database name')
    statements = ['BEGIN;']
    for name, expected in ROLES.items():
        entry = credentials[name]
        if entry['DB_USER'] != expected or not entry.get('DB_PASSWORD'):
            raise ValueError('Missing or unexpected scoped database credential: ' + name)
        statements += [
            f"DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname={literal(expected)}) THEN CREATE ROLE {expected}; END IF; END $$;",
            f"ALTER ROLE {expected} WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS "
            + ('REPLICATION ' if expected == 'pfa_replicator' else 'NOREPLICATION ')
            + f"PASSWORD {literal(entry['DB_PASSWORD'])};",
        ]
    statements += [
        f'REVOKE ALL ON DATABASE "{database}" FROM PUBLIC;',
        f'GRANT CONNECT ON DATABASE "{database}" TO pfa_auth,pfa_command,pfa_query,pfa_grafana;',
        'REVOKE ALL ON SCHEMA public FROM PUBLIC;',
        'GRANT USAGE ON SCHEMA public TO pfa_auth,pfa_command,pfa_query,pfa_grafana;',
        'REVOKE ALL ON SCHEMA auth,finance,groups,activity FROM PUBLIC;',
        'GRANT USAGE ON SCHEMA auth TO pfa_auth,pfa_command,pfa_query,pfa_grafana;',
        'GRANT SELECT,INSERT,UPDATE ON auth.users TO pfa_auth;',
        'GRANT SELECT(id,email,username,role,enabled,account_non_locked,profile_picture,default_currency) ON auth.users TO pfa_command,pfa_query,pfa_grafana;',
        'GRANT USAGE ON SCHEMA finance,groups,activity TO pfa_command,pfa_query,pfa_grafana;',
        'GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA finance,groups,activity TO pfa_command;',
        'GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA finance,groups,activity TO pfa_command;',
        'GRANT SELECT ON ALL TABLES IN SCHEMA finance,groups,activity TO pfa_query,pfa_grafana;',
        'ALTER DEFAULT PRIVILEGES IN SCHEMA finance,groups,activity GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO pfa_command;',
        'ALTER DEFAULT PRIVILEGES IN SCHEMA finance,groups,activity GRANT USAGE,SELECT ON SEQUENCES TO pfa_command;',
        'ALTER DEFAULT PRIVILEGES IN SCHEMA finance,groups,activity GRANT SELECT ON TABLES TO pfa_query,pfa_grafana;',
        'GRANT pg_monitor TO pfa_grafana;',
        'ALTER ROLE pfa_query SET default_transaction_read_only=on;',
        'ALTER ROLE pfa_grafana SET default_transaction_read_only=on;',
        'COMMIT;',
    ]
    return '\n'.join(statements) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--namespace', default='pfa')
    parser.add_argument('--kubeconfig', default=os.environ.get('KUBECONFIG'))
    args = parser.parse_args()
    base = ['kubectl'] + (['--kubeconfig', args.kubeconfig] if args.kubeconfig else [])
    base += ['-n', args.namespace]
    credentials = {}
    for name in ROLES:
        response = subprocess.run(base + ['get', 'secret', name, '-o', 'json'], capture_output=True, text=True, check=True)
        secret = json.loads(response.stdout)
        credentials[name] = {k: base64.b64decode(v).decode() for k, v in secret['data'].items()}
    database = credentials['auth-service-secrets']['DB_NAME']
    result = subprocess.run(base + ['exec', '-i', 'postgres-primary-0', '--', 'sh', '-c',
        'PGPASSWORD="$POSTGRES_PASSWORD" exec psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1'],
        input=role_sql(credentials, database), capture_output=True, text=True)
    if result.returncode:
        raise SystemExit('Database role configuration failed; no credential values were printed.')
    print('Configured five scoped database roles; application accounts have no administrative privileges.')
    replication = credentials['postgres-replica-secrets']
    connection = ('host=postgres-primary port=5432 user=pfa_replicator password='
                  + replication['DB_PASSWORD']
                  + ' sslmode=verify-full sslrootcert=/etc/postgres-tls/ca.crt application_name=pfa-replica')
    sql = 'ALTER SYSTEM SET primary_conninfo=' + literal(connection) + '; SELECT pg_reload_conf();'
    # A new replica first needs the role above before its base backup can run.
    for _ in range(45):
        result = subprocess.run(base + ['exec', '-i', 'postgres-replica-0', '--',
            'psql', '-X', '-U', 'pfa_admin', '-d', database, '-v', 'ON_ERROR_STOP=1'],
            input=sql, capture_output=True, text=True)
        if result.returncode == 0:
            print('Configured certificate-verified replication with its dedicated replication role.')
            break
        time.sleep(2)
    else:
        raise SystemExit('Replica connection configuration failed; no credential values were printed.')


if __name__ == '__main__':
    main()
