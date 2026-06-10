use std::collections::{HashMap, HashSet};

use axum::{
    Json,
    body::Bytes,
    extract::{Path, State},
    http::{HeaderMap, HeaderValue, StatusCode, header},
    response::{IntoResponse, Response},
};
use chrono::{DateTime, Utc};
use rand::RngExt;
use serde_json::Value;
use sha2::{Digest, Sha256};
use sqlx::Row;

use crate::{
    app::AppState,
    error::AppError,
    models::{
        AirportRef, Booking, BookingClass, BookingCreateRequest, BookingPassenger, BookingSegment,
        BookingTrip, Price, TripDirection,
    },
    validation::{validate_book_ref, validate_booking_request, validate_idempotency_key},
};

use super::{cents_to_money, format_rfc3339_at, format_rfc3339_utc, money_to_cents, trim_code};

#[derive(Clone)]
struct BookableFlight {
    flight_id: i32,
    route_no: String,
    departure_airport: String,
    arrival_airport: String,
    scheduled_departure: DateTime<Utc>,
    scheduled_arrival: DateTime<Utc>,
    departure_timezone: String,
    arrival_timezone: String,
    price_amount: String,
    price_cents: i64,
}

struct PreparedTrip {
    direction: TripDirection,
    ticket_no: String,
    flights: Vec<BookableFlight>,
    total_price_cents: i64,
}

pub async fn create_booking(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> Result<Response, AppError> {
    let idempotency_key = validate_idempotency_key(
        headers
            .get("Idempotency-Key")
            .and_then(|value| value.to_str().ok()),
    )?;
    let request_hash = request_hash(&body);
    let request: BookingCreateRequest = serde_json::from_slice(&body).map_err(|_| {
        AppError::bad(
            "invalid_request",
            "request body is invalid JSON or contains unknown fields",
        )
    })?;
    validate_booking_request(&request)?;

    let mut tx = state.pool.begin().await?;

    if let Some(row) = sqlx::query(
        r#"
        select request_hash, status, response_body
        from bookings.d6_idempotency_keys
        where key = $1
        for update
        "#,
    )
    .bind(&idempotency_key)
    .fetch_optional(&mut *tx)
    .await?
    {
        let stored_hash: String = row.get("request_hash");
        if stored_hash != request_hash {
            return Err(AppError::conflict(
                "idempotency_key_conflict",
                "Idempotency-Key was already used with a different request body",
            ));
        }

        let status: String = row.get("status");
        if status == "in_progress" {
            return Err(AppError::conflict(
                "idempotency_key_in_progress",
                "Request with this Idempotency-Key is still being processed",
            ));
        }

        let response_body: Value = row.get("response_body");
        let booking: Booking =
            serde_json::from_value(response_body).map_err(|_| AppError::internal())?;
        return Ok(booking_response(booking, true));
    }

    sqlx::query(
        r#"
        insert into bookings.d6_idempotency_keys (key, request_hash, status)
        values ($1, $2, 'in_progress')
        "#,
    )
    .bind(&idempotency_key)
    .bind(&request_hash)
    .execute(&mut *tx)
    .await?;

    let all_flight_ids = request
        .trips
        .iter()
        .flat_map(|trip| trip.segments.iter().map(|segment| segment.flight_id))
        .collect::<Vec<_>>();
    let flights =
        load_bookable_flights(&state.pool, &all_flight_ids, request.booking_class).await?;
    let mut prepared_trips = prepare_trips(&request, &flights)?;
    validate_return_trip(&prepared_trips)?;

    let total_price_cents = prepared_trips
        .iter()
        .map(|trip| trip.total_price_cents)
        .sum::<i64>();

    let total_amount = cents_to_money(total_price_cents);
    let (book_ref, book_date) = {
        let mut inserted = None;
        for _ in 0..20 {
            let candidate = generate_book_ref();
            let maybe_date: Option<DateTime<Utc>> = sqlx::query_scalar(
                r#"
                insert into bookings.bookings (book_ref, book_date, total_amount)
                values ($1, bookings.now(), $2::numeric)
                on conflict (book_ref) do nothing
                returning book_date
                "#,
            )
            .bind(&candidate)
            .bind(&total_amount)
            .fetch_optional(&mut *tx)
            .await?;
            if let Some(book_date) = maybe_date {
                inserted = Some((candidate, book_date));
                break;
            }
        }
        inserted.ok_or_else(AppError::internal)?
    };

    for trip in &mut prepared_trips {
        let ticket_no = {
            let mut inserted = None;
            for _ in 0..20 {
                let candidate = generate_ticket_no();
                let maybe_ticket_no: Option<String> = sqlx::query_scalar(
                    r#"
                    insert into bookings.tickets
                        (ticket_no, book_ref, passenger_id, passenger_name, outbound)
                    values ($1, $2, $3, $4, $5)
                    on conflict (ticket_no) do nothing
                    returning ticket_no
                    "#,
                )
                .bind(&candidate)
                .bind(&book_ref)
                .bind(&request.passenger.passenger_id)
                .bind(&request.passenger.passenger_name)
                .bind(trip.direction.outbound_bool())
                .fetch_optional(&mut *tx)
                .await?;
                if let Some(ticket_no) = maybe_ticket_no {
                    inserted = Some(ticket_no);
                    break;
                }
            }
            inserted.ok_or_else(AppError::internal)?
        };
        trip.ticket_no = ticket_no;

        for flight in &trip.flights {
            sqlx::query(
                r#"
                insert into bookings.segments
                    (ticket_no, flight_id, fare_conditions, price)
                values ($1, $2, $3, $4::numeric)
                "#,
            )
            .bind(&trip.ticket_no)
            .bind(flight.flight_id)
            .bind(request.booking_class.as_str())
            .bind(&flight.price_amount)
            .execute(&mut *tx)
            .await?;
        }
    }

    let booking = build_booking_response(
        book_ref,
        book_date,
        &request,
        prepared_trips,
        total_price_cents,
    );
    let response_body = serde_json::to_value(&booking).map_err(|_| AppError::internal())?;

    sqlx::query(
        r#"
        update bookings.d6_idempotency_keys
        set status = 'completed',
            response_status = 201,
            response_body = $2,
            updated_at = bookings.now()
        where key = $1
        "#,
    )
    .bind(&idempotency_key)
    .bind(response_body)
    .execute(&mut *tx)
    .await?;

    tx.commit().await?;
    Ok(booking_response(booking, false))
}

pub async fn get_booking_by_ref(
    State(state): State<AppState>,
    Path(book_ref): Path<String>,
) -> Result<Json<Booking>, AppError> {
    let book_ref = validate_book_ref(&book_ref)?;
    let booking = load_booking(&state.pool, &book_ref).await?;
    Ok(Json(booking))
}

pub async fn load_booking(pool: &sqlx::PgPool, book_ref: &str) -> Result<Booking, AppError> {
    let rows = sqlx::query(
        r#"
        with target_booking as materialized (
            select *
            from bookings.bookings
            where book_ref = $1::char(6)
        ),
        target_tickets as materialized (
            select *
            from bookings.tickets
            where book_ref = $1::char(6)
        )
        select
            trim(b.book_ref)::text as book_ref,
            b.book_date,
            b.total_amount::text as total_amount,
            t.ticket_no,
            t.passenger_id,
            t.passenger_name,
            t.outbound,
            s.flight_id,
            s.fare_conditions,
            s.price::text as price_amount,
            f.route_no,
            f.scheduled_departure,
            f.scheduled_arrival,
            trim(r.departure_airport)::text as departure_airport,
            trim(r.arrival_airport)::text as arrival_airport,
            dep.timezone as departure_timezone,
            arr.timezone as arrival_timezone
        from target_booking b
        join target_tickets t
          on t.book_ref = b.book_ref
        join bookings.segments s
          on s.ticket_no = t.ticket_no
        join bookings.flights f
          on f.flight_id = s.flight_id
        join bookings.routes r
          on r.route_no = f.route_no
         and r.validity @> f.scheduled_departure
        join bookings.airports_data dep
          on trim(dep.airport_code)::text = trim(r.departure_airport)::text
        join bookings.airports_data arr
          on trim(arr.airport_code)::text = trim(r.arrival_airport)::text
        order by t.outbound desc, t.ticket_no, f.scheduled_departure, f.flight_id
        "#,
    )
    .bind(book_ref)
    .fetch_all(pool)
    .await?;

    if rows.is_empty() {
        return Err(AppError::not_found(
            "booking_not_found",
            "Booking was not found",
        ));
    }

    let first = &rows[0];
    let first_book_ref: String = first.get("book_ref");
    let first_book_date: DateTime<Utc> = first.get("book_date");
    let first_total_amount: String = first.get("total_amount");
    let passenger = BookingPassenger {
        passenger_id: first.get("passenger_id"),
        passenger_name: first.get("passenger_name"),
    };
    let booking_class = parse_booking_class_from_db(first.get::<String, _>("fare_conditions"))?;

    let mut trips_by_ticket: Vec<BookingTrip> = Vec::new();
    let mut current_ticket = String::new();
    for row in rows {
        let ticket_no: String = row.get("ticket_no");
        if current_ticket != ticket_no {
            current_ticket = ticket_no.clone();
            trips_by_ticket.push(BookingTrip {
                direction: if row.get::<bool, _>("outbound") {
                    TripDirection::Outbound
                } else {
                    TripDirection::Return
                },
                ticket_no: ticket_no.clone(),
                total_price: Price::rub("0.00"),
                segments: Vec::new(),
            });
        }

        let trip = trips_by_ticket.last_mut().expect("trip exists");
        let sequence = trip.segments.len() as i32 + 1;
        let price_amount: String = row.get("price_amount");
        trip.segments.push(BookingSegment {
            sequence,
            flight_id: row.get("flight_id"),
            route_no: row.get("route_no"),
            departure_airport: AirportRef {
                airport_code: trim_code(row.get("departure_airport")),
            },
            arrival_airport: AirportRef {
                airport_code: trim_code(row.get("arrival_airport")),
            },
            scheduled_departure_at: format_rfc3339_at(
                row.get("scheduled_departure"),
                row.get::<String, _>("departure_timezone").as_str(),
            ),
            scheduled_arrival_at: format_rfc3339_at(
                row.get("scheduled_arrival"),
                row.get::<String, _>("arrival_timezone").as_str(),
            ),
            price: Price::rub(price_amount),
        });
    }

    for trip in &mut trips_by_ticket {
        let total = trip
            .segments
            .iter()
            .map(|segment| money_to_cents(&segment.price.amount))
            .sum::<i64>();
        trip.total_price = Price::rub(cents_to_money(total));
    }

    Ok(Booking {
        book_ref: first_book_ref,
        book_date: format_rfc3339_utc(first_book_date),
        passenger,
        booking_class,
        total_price: Price::rub(first_total_amount),
        trips: trips_by_ticket,
    })
}

async fn load_bookable_flights(
    pool: &sqlx::PgPool,
    flight_ids: &[i32],
    booking_class: BookingClass,
) -> Result<HashMap<i32, BookableFlight>, AppError> {
    let unique_ids = flight_ids.iter().copied().collect::<HashSet<_>>();
    let unique_ids_vec = unique_ids.iter().copied().collect::<Vec<_>>();

    let existing_count: i64 = sqlx::query_scalar(
        "select count(*) from bookings.flights where flight_id = any($1::int[])",
    )
    .bind(&unique_ids_vec)
    .fetch_one(pool)
    .await?;
    if existing_count != unique_ids.len() as i64 {
        return Err(AppError::not_found(
            "flight_not_found",
            "One or more flights were not found",
        ));
    }

    let rows = sqlx::query(
        r#"
        select
            f.flight_id,
            f.route_no,
            f.status,
            trim(r.departure_airport)::text as departure_airport,
            trim(r.arrival_airport)::text as arrival_airport,
            f.scheduled_departure,
            f.scheduled_arrival,
            dep.timezone as departure_timezone,
            arr.timezone as arrival_timezone,
            pr.price::text as price_amount
        from bookings.flights f
        join bookings.routes r
          on r.route_no = f.route_no
         and r.validity @> f.scheduled_departure
        join bookings.airports_data dep
          on trim(dep.airport_code)::text = trim(r.departure_airport)::text
        join bookings.airports_data arr
          on trim(arr.airport_code)::text = trim(r.arrival_airport)::text
        left join bookings.d4_pricing_rules pr
          on pr.route_no = f.route_no
         and trim(pr.airplane_code)::text = trim(r.airplane_code)::text
         and pr.fare_conditions = $2
        where f.flight_id = any($1::int[])
        "#,
    )
    .bind(&unique_ids_vec)
    .bind(booking_class.as_str())
    .fetch_all(pool)
    .await?;

    let mut flights = HashMap::new();
    for row in rows {
        let price_amount: Option<String> = row.get("price_amount");
        let price_amount = price_amount.ok_or_else(|| {
            AppError::conflict(
                "price_not_available",
                "Price is not available for one or more selected flights",
            )
        })?;
        let status: String = row.get("status");
        if !matches!(status.as_str(), "Scheduled" | "On Time" | "Delayed") {
            return Err(AppError::conflict(
                "flight_not_available",
                "One or more flights are not available for booking",
            ));
        }

        flights.insert(
            row.get("flight_id"),
            BookableFlight {
                flight_id: row.get("flight_id"),
                route_no: row.get("route_no"),
                departure_airport: trim_code(row.get("departure_airport")),
                arrival_airport: trim_code(row.get("arrival_airport")),
                scheduled_departure: row.get("scheduled_departure"),
                scheduled_arrival: row.get("scheduled_arrival"),
                departure_timezone: row.get("departure_timezone"),
                arrival_timezone: row.get("arrival_timezone"),
                price_cents: money_to_cents(&price_amount),
                price_amount,
            },
        );
    }

    Ok(flights)
}

fn prepare_trips(
    request: &BookingCreateRequest,
    flights: &HashMap<i32, BookableFlight>,
) -> Result<Vec<PreparedTrip>, AppError> {
    let mut prepared = Vec::new();
    for trip in &request.trips {
        let mut trip_flights = Vec::new();
        for segment in &trip.segments {
            let flight = flights
                .get(&segment.flight_id)
                .expect("all flight ids loaded")
                .clone();
            trip_flights.push(flight);
        }
        validate_trip_connection(&trip_flights)?;
        let total_price_cents = trip_flights.iter().map(|flight| flight.price_cents).sum();
        prepared.push(PreparedTrip {
            direction: trip.direction,
            ticket_no: String::new(),
            flights: trip_flights,
            total_price_cents,
        });
    }

    prepared.sort_by_key(|trip| match trip.direction {
        TripDirection::Outbound => 0,
        TripDirection::Return => 1,
    });
    Ok(prepared)
}

fn validate_trip_connection(flights: &[BookableFlight]) -> Result<(), AppError> {
    let mut visited = HashSet::new();
    if let Some(first) = flights.first() {
        visited.insert(first.departure_airport.clone());
    }
    for pair in flights.windows(2) {
        let prev = &pair[0];
        let next = &pair[1];
        if prev.arrival_airport != next.departure_airport
            || next.scheduled_departure <= prev.scheduled_arrival
        {
            return Err(AppError::conflict(
                "invalid_trip_connection",
                "Trip segments must form a connected path in the provided order",
            ));
        }
    }
    for flight in flights {
        if !visited.insert(flight.arrival_airport.clone()) {
            return Err(AppError::conflict(
                "invalid_trip_connection",
                "Trip segments must form a connected path in the provided order",
            ));
        }
    }
    Ok(())
}

fn validate_return_trip(trips: &[PreparedTrip]) -> Result<(), AppError> {
    let outbound = trips
        .iter()
        .find(|trip| trip.direction == TripDirection::Outbound)
        .expect("validated request has outbound");
    let Some(return_trip) = trips
        .iter()
        .find(|trip| trip.direction == TripDirection::Return)
    else {
        return Ok(());
    };

    let outbound_first = outbound.flights.first().expect("trip has flights");
    let outbound_last = outbound.flights.last().expect("trip has flights");
    let return_first = return_trip.flights.first().expect("trip has flights");
    let return_last = return_trip.flights.last().expect("trip has flights");

    if return_first.departure_airport != outbound_last.arrival_airport
        || return_last.arrival_airport != outbound_first.departure_airport
        || return_first.scheduled_departure <= outbound_last.scheduled_arrival
    {
        return Err(AppError::conflict(
            "invalid_return_trip",
            "Return trip must start after outbound and reverse outbound endpoints",
        ));
    }
    Ok(())
}

fn build_booking_response(
    book_ref: String,
    book_date: DateTime<Utc>,
    request: &BookingCreateRequest,
    trips: Vec<PreparedTrip>,
    total_price_cents: i64,
) -> Booking {
    Booking {
        book_ref,
        book_date: format_rfc3339_utc(book_date),
        passenger: BookingPassenger {
            passenger_id: request.passenger.passenger_id.clone(),
            passenger_name: request.passenger.passenger_name.clone(),
        },
        booking_class: request.booking_class,
        total_price: Price::rub(cents_to_money(total_price_cents)),
        trips: trips
            .into_iter()
            .map(|trip| BookingTrip {
                direction: trip.direction,
                ticket_no: trip.ticket_no,
                total_price: Price::rub(cents_to_money(trip.total_price_cents)),
                segments: trip
                    .flights
                    .into_iter()
                    .enumerate()
                    .map(|(index, flight)| BookingSegment {
                        sequence: index as i32 + 1,
                        flight_id: flight.flight_id,
                        route_no: flight.route_no,
                        departure_airport: AirportRef {
                            airport_code: flight.departure_airport,
                        },
                        arrival_airport: AirportRef {
                            airport_code: flight.arrival_airport,
                        },
                        scheduled_departure_at: format_rfc3339_at(
                            flight.scheduled_departure,
                            &flight.departure_timezone,
                        ),
                        scheduled_arrival_at: format_rfc3339_at(
                            flight.scheduled_arrival,
                            &flight.arrival_timezone,
                        ),
                        price: Price::rub(flight.price_amount),
                    })
                    .collect(),
            })
            .collect(),
    }
}

fn booking_response(booking: Booking, replayed: bool) -> Response {
    let location = format!("/api/v1/bookings/{}", booking.book_ref);
    let mut headers = HeaderMap::new();
    headers.insert(header::LOCATION, HeaderValue::from_str(&location).unwrap());
    headers.insert(
        "Idempotency-Replayed",
        HeaderValue::from_static(if replayed { "true" } else { "false" }),
    );
    (StatusCode::CREATED, headers, Json(booking)).into_response()
}

fn request_hash(body: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(body);
    hex::encode(hasher.finalize())
}

fn generate_book_ref() -> String {
    const ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    let mut rng = rand::rng();
    (0..6)
        .map(|_| {
            let idx = rng.random_range(0..ALPHABET.len());
            ALPHABET[idx] as char
        })
        .collect()
}

fn generate_ticket_no() -> String {
    let mut rng = rand::rng();
    let value: u64 = rng.random_range(0..10_000_000_000_000);
    format!("{value:013}")
}

fn parse_booking_class_from_db(value: String) -> Result<BookingClass, AppError> {
    match value.as_str() {
        "Economy" => Ok(BookingClass::Economy),
        "Comfort" => Ok(BookingClass::Comfort),
        "Business" => Ok(BookingClass::Business),
        _ => Err(AppError::internal()),
    }
}
