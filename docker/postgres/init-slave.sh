#!/bin/bash
set -e

echo "Starting slave initialization..."

# If data directory already has data, skip
if [ -f "$PGDATA/PG_VERSION" ]; then
  echo "Slave data directory already initialized, skipping."
  exit 0
fi

echo "Running pg_basebackup from master..."
PGPASSWORD=replicator_secret pg_basebackup \
  -h postgres-master \
  -D "$PGDATA" \
  -U replicator \
  -P \
  -Xs \
  -R

echo "Slave base backup complete."
