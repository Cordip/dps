#!/usr/bin/env bash
set -euo pipefail

API_BASE="${API_BASE:-http://localhost:3000/api/v1}"
DB_CONTAINER="${DB_CONTAINER:-dpt-demo-postgres}"

TMP_DIR="$(mktemp -d)"
BOOK_REF=""
TICKET_NO=""
IDEMPOTENCY_KEY="d6-smoke-$(date +%s)-$$"

cleanup() {
    if [[ -n "${TICKET_NO}" || -n "${BOOK_REF}" ]]; then
        docker exec "${DB_CONTAINER}" psql -U postgres -d demo -v ON_ERROR_STOP=1 -c "
            set search_path to bookings, public;
            delete from boarding_passes where ticket_no = '${TICKET_NO}';
            delete from segments where ticket_no = '${TICKET_NO}';
            delete from tickets where ticket_no = '${TICKET_NO}';
            delete from bookings where book_ref = '${BOOK_REF}';
            delete from d6_idempotency_keys where key = '${IDEMPOTENCY_KEY}';
        " >/dev/null
    fi
    rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

psql_one() {
    docker exec "${DB_CONTAINER}" psql -U postgres -d demo -At -v ON_ERROR_STOP=1 -c "$1" \
        | sed '/^SET$/d' \
        | head -n 1
}

check_json() {
    python3 -m json.tool "$1" >/dev/null
}

require_error() {
    local file="$1"
    local expected="$2"
    python3 - "$file" "$expected" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as fh:
    body = json.load(fh)

if body.get("error") != sys.argv[2]:
    raise SystemExit(f"expected error {sys.argv[2]!r}, got {body!r}")
PY
}

require_any_item_field() {
    local file="$1"
    local field="$2"
    local expected="$3"
    python3 - "$file" "$field" "$expected" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as fh:
    body = json.load(fh)

field = sys.argv[2]
expected = sys.argv[3]
if not any(str(item.get(field)) == expected for item in body.get("items", [])):
    raise SystemExit(f"expected at least one item with {field}={expected!r}, got {body!r}")
PY
}

get_json() {
    local path="$1"
    local file="$2"
    curl -fsS "${API_BASE}${path}" >"${file}"
    check_json "${file}"
}

post_json() {
    local path="$1"
    local data="$2"
    local file="$3"
    shift 3
    curl -fsS -X POST "${API_BASE}${path}" \
        -H "Content-Type: application/json" \
        "$@" \
        --data "${data}" >"${file}"
    check_json "${file}"
}

echo "Using API ${API_BASE}"

FLIGHT_ROW="$(psql_one "
    set search_path to bookings, public;
    select concat_ws(
        '|',
        trim(r.departure_airport),
        trim(r.arrival_airport),
        (f.scheduled_departure at time zone dep.timezone)::date,
        f.flight_id,
        f.route_no
    )
    from flights f
    join routes r
      on r.route_no = f.route_no
     and r.validity @> f.scheduled_departure
    join airports_data dep
      on dep.airport_code = r.departure_airport
    join d4_pricing_rules pr
      on pr.route_no = f.route_no
     and pr.airplane_code = r.airplane_code
     and pr.fare_conditions = 'Economy'
    where f.status in ('On Time', 'Delayed')
      and f.scheduled_departure > bookings.now()
      and f.scheduled_departure <= bookings.now() + interval '24 hours'
    order by f.scheduled_departure, f.flight_id
    limit 1;
")"

if [[ -z "${FLIGHT_ROW}" ]]; then
    echo "No check-in-ready Economy flight found in demo DB" >&2
    exit 1
fi

IFS='|' read -r FROM_AIRPORT TO_AIRPORT DEPARTURE_DATE FLIGHT_ID ROUTE_NO <<<"${FLIGHT_ROW}"

SEAT_NO="$(psql_one "
    set search_path to bookings, public;
    select s.seat_no
    from seats s
    join routes r
      on r.airplane_code = s.airplane_code
    join flights f
      on f.route_no = r.route_no
     and r.validity @> f.scheduled_departure
    where f.flight_id = ${FLIGHT_ID}
      and s.fare_conditions = 'Economy'
      and not exists (
          select 1
          from boarding_passes bp
          where bp.flight_id = f.flight_id
            and bp.seat_no = s.seat_no
      )
    order by
        regexp_replace(s.seat_no, '[^0-9]', '', 'g')::int,
        regexp_replace(s.seat_no, '[0-9]', '', 'g')
    limit 1;
")"

if [[ -z "${SEAT_NO}" ]]; then
    echo "No available Economy seat found for flight ${FLIGHT_ID}" >&2
    exit 1
fi

get_json "/cities?limit=1" "${TMP_DIR}/cities.json"
get_json "/airports?limit=1" "${TMP_DIR}/airports.json"
get_json "/cities?scope=all&lang=en&search=moskva&limit=5" "${TMP_DIR}/cities-moskva.json"
require_any_item_field "${TMP_DIR}/cities-moskva.json" "city" "Moscow"
get_json "/cities?scope=all&lang=ru&search=Moscow&limit=5" "${TMP_DIR}/cities-moscow-ru.json"
require_any_item_field "${TMP_DIR}/cities-moscow-ru.json" "city" "Москва"
get_json "/airports?scope=all&lang=ru&search=moskva%20svo&limit=5" "${TMP_DIR}/airports-moskva-svo.json"
require_any_item_field "${TMP_DIR}/airports-moskva-svo.json" "airport_code" "SVO"
get_json "/airports?scope=all&lang=en&search=%D0%BE%D1%81%D0%BA%20russia&limit=5" "${TMP_DIR}/airports-cyrillic-trigram.json"
require_any_item_field "${TMP_DIR}/airports-cyrillic-trigram.json" "city" "Moscow"
get_json "/airports/${FROM_AIRPORT}" "${TMP_DIR}/airport.json"
get_json "/airports/${FROM_AIRPORT}/schedule/departures?limit=1" "${TMP_DIR}/departures.json"
get_json "/airports/${TO_AIRPORT}/schedule/arrivals?limit=1" "${TMP_DIR}/arrivals.json"
get_json "/routes?from_type=airport&from_airport=${FROM_AIRPORT}&to_type=airport&to_airport=${TO_AIRPORT}&departure_date=${DEPARTURE_DATE}&booking_class=Economy&max_connections=0&limit=1" "${TMP_DIR}/routes.json"

CONNECTED_ROW="$(psql_one "
    set search_path to bookings, public;
    with candidates as (
        select
            trim(r1.departure_airport) as source_airport,
            trim(r2.arrival_airport) as destination_airport,
            (f1.scheduled_departure at time zone dep.timezone)::date as departure_date,
            f1.flight_id as first_flight_id,
            f2.flight_id as second_flight_id,
            f1.scheduled_departure,
            f2.scheduled_departure
        from flights f1
        join routes r1
          on r1.route_no = f1.route_no
         and r1.validity @> f1.scheduled_departure
        join airports_data dep
          on dep.airport_code = r1.departure_airport
        join d4_pricing_rules pr1
          on pr1.route_no = f1.route_no
         and pr1.airplane_code = r1.airplane_code
         and pr1.fare_conditions = 'Economy'
        join routes r2
          on r2.departure_airport = r1.arrival_airport
        join flights f2
          on f2.route_no = r2.route_no
         and r2.validity @> f2.scheduled_departure
        join d4_pricing_rules pr2
          on pr2.route_no = f2.route_no
         and pr2.airplane_code = r2.airplane_code
         and pr2.fare_conditions = 'Economy'
        where f1.status in ('Scheduled', 'On Time', 'Delayed')
          and f2.status in ('Scheduled', 'On Time', 'Delayed')
          and f1.scheduled_departure >= bookings.now()
          and f2.scheduled_departure > f1.scheduled_arrival
          and f2.scheduled_departure < f1.scheduled_arrival + interval '24 hours'
          and r2.arrival_airport <> r1.departure_airport
        order by f1.scheduled_departure, f2.scheduled_departure
        limit 1
    )
    select concat_ws(
        '|',
        source_airport,
        destination_airport,
        departure_date,
        first_flight_id,
        second_flight_id
    )
    from candidates;
")"

if [[ -z "${CONNECTED_ROW}" ]]; then
    echo "No connected Economy route found in demo DB" >&2
    exit 1
fi

IFS='|' read -r CONNECTED_FROM CONNECTED_TO CONNECTED_DATE CONNECTED_FIRST_FLIGHT CONNECTED_SECOND_FLIGHT <<<"${CONNECTED_ROW}"
get_json "/routes?from_type=airport&from_airport=${CONNECTED_FROM}&to_type=airport&to_airport=${CONNECTED_TO}&departure_date=${CONNECTED_DATE}&booking_class=Economy&max_connections=1&limit=100" "${TMP_DIR}/connected-routes.json"
python3 - "${TMP_DIR}/connected-routes.json" "${CONNECTED_FIRST_FLIGHT}" "${CONNECTED_SECOND_FLIGHT}" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as fh:
    body = json.load(fh)

first = int(sys.argv[2])
second = int(sys.argv[3])
for item in body["items"]:
    segments = item["segments"]
    if len(segments) >= 2 and segments[0]["flight_id"] == first and segments[1]["flight_id"] == second:
        break
else:
    raise SystemExit(f"connected route with flights {first}, {second} was not returned")
PY

BOOKING_BODY="$(printf '{"booking_class":"Economy","passenger":{"passenger_id":"D6 SMOKE","passenger_name":"D6 Smoke Tester"},"trips":[{"direction":"outbound","segments":[{"flight_id":%s}]}]}' "${FLIGHT_ID}")"

post_json "/bookings" "${BOOKING_BODY}" "${TMP_DIR}/booking.json" -H "Idempotency-Key: ${IDEMPOTENCY_KEY}"
BOOK_REF="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["book_ref"])' "${TMP_DIR}/booking.json")"
TICKET_NO="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["trips"][0]["ticket_no"])' "${TMP_DIR}/booking.json")"

post_json "/bookings" "${BOOKING_BODY}" "${TMP_DIR}/booking-replay.json" -H "Idempotency-Key: ${IDEMPOTENCY_KEY}"
get_json "/bookings/${BOOK_REF}" "${TMP_DIR}/booking-read.json"
get_json "/tickets/${TICKET_NO}/flights/${FLIGHT_ID}/seats" "${TMP_DIR}/seats.json"

CHECKIN_BODY="$(printf '{"ticket_no":"%s","flight_id":%s,"seats":[{"flight_id":%s,"seat_no":"%s"}]}' "${TICKET_NO}" "${FLIGHT_ID}" "${FLIGHT_ID}" "${SEAT_NO}")"
post_json "/check-ins" "${CHECKIN_BODY}" "${TMP_DIR}/checkin.json"
get_json "/tickets/${TICKET_NO}/boarding-passes" "${TMP_DIR}/boarding-passes.json"

INVALID_FILE="${TMP_DIR}/invalid.json"
STATUS="$(curl -sS -o "${INVALID_FILE}" -w "%{http_code}" "${API_BASE}/airports/INVALID")"
[[ "${STATUS}" == "400" ]]
require_error "${INVALID_FILE}" "invalid_airport_code"

STATUS="$(curl -sS -o "${INVALID_FILE}" -w "%{http_code}" "${API_BASE}/tickets/0000000000000/flights/not-int/seats")"
[[ "${STATUS}" == "400" ]]
require_error "${INVALID_FILE}" "invalid_flight_id"

STATUS="$(curl -sS -X POST -o "${INVALID_FILE}" -w "%{http_code}" \
    "${API_BASE}/check-ins" \
    -H "Content-Type: application/json" \
    --data '{')"
[[ "${STATUS}" == "400" ]]
require_error "${INVALID_FILE}" "invalid_request"

echo "D6 Axum smoke passed: flight=${FLIGHT_ID} route=${ROUTE_NO} booking=${BOOK_REF} ticket=${TICKET_NO} seat=${SEAT_NO}"
