#!/bin/bash
# Custom entrypoint for the postgres-slave container.
# Bypasses docker-entrypoint.sh entirely to avoid the pg_ctl stop failure
# that occurs when pg_basebackup replaces PGDATA mid-init.

set -e

PGDATA="${PGDATA:-/var/lib/postgresql/data}"

# ── Step 1: fix ownership (Docker volume starts as root-owned) then drop to postgres ──
if [ "$(id -u)" = '0' ]; then
    mkdir -p "$PGDATA"
    chown -R postgres:postgres "$PGDATA"
    chmod 700 "$PGDATA"
    exec gosu postgres "$0" "$@"
fi

# ── Running as postgres user from here ──────────────────────────────────────

echo "Replica: checking initialisation state..."

if [ -f "$PGDATA/standby.signal" ]; then
    echo "Replica: standby.signal found — already initialised, skipping pg_basebackup."
else
    echo "Replica: first start — running pg_basebackup from postgres-master..."

    # pg_basebackup requires an empty target directory
    rm -rf "$PGDATA"/*

    PGPASSWORD=replicator_secret pg_basebackup \
        -h postgres-master \
        -D "$PGDATA" \
        -U replicator \
        -P \
        -Xs \
        -R

    echo "Replica: base backup complete."
fi

echo "Replica: starting PostgreSQL..."
# Append to postgresql.auto.conf (highest-priority, overrides postgresql.conf):
#   - listen_addresses: initdb default is 'localhost'; app services reach slave over Docker network
#   - max_connections: must be >= master's value (200) or hot-standby recovery aborts
#   - wal_level/hot_standby: ensure replica accepts read connections
cat >> "$PGDATA/postgresql.auto.conf" << 'EOF'
listen_addresses = '*'
max_connections = 200
hot_standby = on
EOF

exec postgres -D "$PGDATA"
