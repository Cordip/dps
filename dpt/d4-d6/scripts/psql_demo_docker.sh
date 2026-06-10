#!/usr/bin/env bash
set -euo pipefail

docker compose exec db psql -U postgres -d demo "$@"
