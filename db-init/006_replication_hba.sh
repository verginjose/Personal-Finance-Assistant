#!/bin/bash
set -e

# Add replication rule to pg_hba.conf to allow the replica to stream WAL
echo "host replication all all md5" >> "$PGDATA/pg_hba.conf"
echo "host replication all all scram-sha-256" >> "$PGDATA/pg_hba.conf"

echo "Replication rules added to pg_hba.conf"
