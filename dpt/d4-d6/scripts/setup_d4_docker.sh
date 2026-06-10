#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"

DUMP_URL="${DUMP_URL:-https://edu.postgrespro.ru/demo-20250901-3m.sql.gz}"
DUMP_PATH="${DUMP_PATH:-data/demo-20250901-3m.sql.gz}"
DB_USER="${DB_USER:-postgres}"
DB_NAME="${DB_NAME:-demo}"

WITH_PROOF=false
FORCE_RESTORE=false

usage() {
  cat <<USAGE
Usage: ./scripts/setup_d4_docker.sh [options]

Download the PostgresPro demo dump if needed, start PostgreSQL in Docker,
restore the demo database if needed, create D4 pricing rules, and validate
that upcoming flight fare options are priced.

Options:
  --with-proof     Also run sql/d4_pricing/01_explore_existing_prices.sql.
  --force-restore  Restore demo from the dump even if the demo database exists.
  -h, --help       Show this help.

Environment:
  DUMP_URL   Override dump URL. Default: $DUMP_URL
  DUMP_PATH  Override local dump path. Default: $DUMP_PATH
  DB_USER    Override PostgreSQL user. Default: $DB_USER
  DB_NAME    Override demo database name. Default: $DB_NAME
USAGE
}

log() {
  printf '\n==> %s\n' "$*"
}

run_with_wait_messages() {
  local label="$1"
  shift

  printf '   [%s] starting...\n' "$label"
  "$@" &
  local pid=$!
  local elapsed=0

  while kill -0 "$pid" 2>/dev/null; do
    sleep 10
    elapsed=$((elapsed + 10))
    if kill -0 "$pid" 2>/dev/null; then
      printf '   [%s] still running (%ss elapsed)\n' "$label" "$elapsed"
    fi
  done

  if wait "$pid"; then
    printf '   [%s] done (%ss elapsed)\n' "$label" "$elapsed"
    return 0
  fi

  local status=$?
  printf '   [%s] failed after %ss\n' "$label" "$elapsed" >&2
  return "$status"
}

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

while (($#)); do
  case "$1" in
    --with-proof)
      WITH_PROOF=true
      ;;
    --force-restore)
      FORCE_RESTORE=true
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

cd "$PROJECT_DIR"

need_cmd docker
need_cmd curl
need_cmd gunzip

if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose is not available through 'docker compose'." >&2
  exit 1
fi

mkdir -p "$(dirname "$DUMP_PATH")"

if [[ ! -s "$DUMP_PATH" ]]; then
  log "Downloading demo dump"
  DUMP_PART="${DUMP_PATH}.part"
  curl -L --fail --progress-bar --output "$DUMP_PART" "$DUMP_URL"
  mv "$DUMP_PART" "$DUMP_PATH"
else
  log "Demo dump already exists: $DUMP_PATH"
fi

log "Starting PostgreSQL container"
docker compose up -d db

log "Waiting for PostgreSQL readiness"
for _ in {1..60}; do
  if docker compose exec -T db pg_isready -U "$DB_USER" -d postgres >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

if ! docker compose exec -T db pg_isready -U "$DB_USER" -d postgres >/dev/null 2>&1; then
  echo "PostgreSQL did not become ready in time." >&2
  exit 1
fi

database_exists() {
  docker compose exec -T db psql -U "$DB_USER" -d postgres -Atqc \
    "select 1 from pg_database where datname = '$DB_NAME'" | grep -qx '1'
}

if [[ "$FORCE_RESTORE" == true ]]; then
  log "Force-restoring $DB_NAME from $DUMP_PATH"
  ./scripts/restore_demo_docker.sh --force "$DUMP_PATH"
elif ! database_exists; then
  log "Restoring $DB_NAME from $DUMP_PATH"
  ./scripts/restore_demo_docker.sh "$DUMP_PATH"
else
  log "Database $DB_NAME already exists; skipping restore"
fi

log "Running smoke check"
./scripts/run_sql_docker.sh sql/00_smoke.sql

if [[ "$WITH_PROOF" == true ]]; then
  log "Running D4 formula proof"
  ./scripts/run_sql_docker.sh sql/d4_pricing/01_explore_existing_prices.sql
fi

log "Creating D4 pricing rules"
run_with_wait_messages "D4 pricing rules" \
  ./scripts/run_sql_docker.sh sql/d4_pricing/02_create_pricing_rules.sql

log "Validating D4 pricing coverage"
./scripts/run_sql_docker.sh sql/d4_pricing/03_validate_future_prices.sql

log "D4 summary"
docker compose exec -T db psql -U "$DB_USER" -d "$DB_NAME" -Atqc "
set search_path to bookings, public;
select 'pricing_rules=' || count(*) ||
       ', exact_history=' || count(*) filter (where source_rule = 'exact_history') ||
       ', inferred=' || count(*) filter (where source_rule <> 'exact_history')
from d4_pricing_rules;
with upcoming_fare_options as (
    select distinct
        f.flight_id,
        f.route_no,
        r.airplane_code,
        s.fare_conditions
    from flights f
    join routes r
        on r.route_no = f.route_no
       and r.validity @> f.scheduled_departure
    join seats s on s.airplane_code = r.airplane_code
    where f.scheduled_departure >= bookings.now()
)
select 'upcoming_fare_options=' || count(*) ||
       ', priced=' || count(pr.price) ||
       ', missing=' || (count(*) - count(pr.price))
from upcoming_fare_options u
left join d4_pricing_rules pr
    on pr.route_no = u.route_no
   and pr.airplane_code = u.airplane_code
   and pr.fare_conditions = u.fare_conditions;
"
