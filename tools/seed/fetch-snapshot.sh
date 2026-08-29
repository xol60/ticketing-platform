#!/usr/bin/env bash
# Thin wrapper around fetch-snapshot.mjs. See that file / README for options.
#   TM_API_KEY=xxx ./fetch-snapshot.sh --count=200
#   ./fetch-snapshot.sh --synthetic
set -euo pipefail
exec node "$(dirname "$0")/fetch-snapshot.mjs" "$@"
