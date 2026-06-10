#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"

DUMP_PATH="${DUMP_PATH:-data/demo-20250901-3m.sql.gz}"
DB_USER="${DB_USER:-postgres}"
DB_NAME="${DB_NAME:-demo}"
FORCE=false

usage() {
  cat <<USAGE
Usage: ./scripts/restore_demo_docker.sh [--force] [dump-path]

Restore the PostgresPro demo dump into the Docker Compose PostgreSQL container.
By default, the script refuses to restore if database "$DB_NAME" already exists,
so the dump is not accidentally replayed into an existing database.

Options:
  --force   Drop/recreate "$DB_NAME" from the dump. This terminates active
            connections to "$DB_NAME", for example DBeaver sessions.
  -h, --help
            Show this help.
USAGE
}

while (($#)); do
  case "$1" in
    --force)
      FORCE=true
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      DUMP_PATH="$1"
      ;;
  esac
  shift
done

cd "$PROJECT_DIR"

if [[ ! -s "$DUMP_PATH" ]]; then
  echo "Demo dump is missing or empty: $DUMP_PATH" >&2
  echo "Put demo-20250901-3m.sql.gz into d4-d6/data, then rerun this script." >&2
  exit 1
fi

database_exists() {
  docker compose exec -T db psql -U "$DB_USER" -d postgres -Atqc \
    "select 1 from pg_database where datname = '$DB_NAME'" | grep -qx '1'
}

terminate_database_connections() {
  docker compose exec -T db psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d postgres \
    -v db_name="$DB_NAME" <<'SQL'
select pg_terminate_backend(pid)
from pg_stat_activity
where datname = :'db_name'
  and pid <> pg_backend_pid();
SQL
}

if database_exists; then
  if [[ "$FORCE" != true ]]; then
    echo "Database $DB_NAME already exists; refusing to replay the dump." >&2
    echo "Use ./scripts/setup_d4_docker.sh to reuse it, or run this script with --force for a clean restore." >&2
    exit 1
  fi

  terminate_database_connections
fi

gunzip -c "$DUMP_PATH" \
  | sed "s/^DROP DATABASE ${DB_NAME};$/DROP DATABASE IF EXISTS ${DB_NAME};/" \
  | docker compose exec -T db psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d postgres
