#!/bin/bash
set -e

echo "Replica initialisation check..."

# standby.signal is written by pg_basebackup -R. It is NOT written by initdb.
# Checking for it (not PG_VERSION) correctly distinguishes:
#   - first run after Docker initdb  → no standby.signal → run pg_basebackup
#   - container restart after backup → standby.signal exists → skip
if [ -f "$PGDATA/standby.signal" ]; then
  echo "Replica already initialised, skipping pg_basebackup."
  exit 0
fi

echo "Running pg_basebackup from postgres-master..."

# Clear any files that Docker's initdb wrote before this script ran
rm -rf "$PGDATA"/*

PGPASSWORD=replicator_secret pg_basebackup \
  -h postgres-master \
  -D "$PGDATA" \
  -U replicator \
  -P \
  -Xs \
  -R

echo "Replica base backup complete."
