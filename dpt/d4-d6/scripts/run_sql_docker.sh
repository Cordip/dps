#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
DB_NAME="${DB_NAME:-demo}"
DB_USER="${DB_USER:-postgres}"

SQL_FILE="${1:-}"
if [[ -z "$SQL_FILE" ]]; then
  echo "Usage: ./scripts/run_sql_docker.sh path/to/file.sql [psql args...]" >&2
  exit 2
fi
shift

if [[ ! -f "$SQL_FILE" && -f "$PROJECT_DIR/$SQL_FILE" ]]; then
  SQL_FILE="$PROJECT_DIR/$SQL_FILE"
fi

if [[ ! -f "$SQL_FILE" ]]; then
  echo "SQL file not found: $SQL_FILE" >&2
  exit 1
fi

SQL_ABS="$(realpath "$SQL_FILE")"
PROJECT_ABS="$(realpath "$PROJECT_DIR")"

cd "$PROJECT_DIR"

if [[ "$SQL_ABS" == "$PROJECT_ABS"/* ]]; then
  CONTAINER_SQL_FILE="/workspace/${SQL_ABS#"$PROJECT_ABS"/}"
  docker compose exec -T db psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" "$@" -f "$CONTAINER_SQL_FILE"
else
  docker compose exec -T db psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" "$@" < "$SQL_FILE"
fi
