use std::collections::{HashMap, HashSet};

use axum::{
    Json,
    body::Bytes,
    extract::{Path, State},
    http::{HeaderMap, HeaderValue, StatusCode, header},
    response::{IntoResponse, Response},
};
use chrono::{DateTime, Duration, Utc};
use sqlx::Row;

use crate::{
    app::AppState,
    error::AppError,
    models::{
        BoardingPass, BoardingPassListResponse, BookingClass, CheckInRequest, CheckInResponse,
        SeatMapResponse, SeatMapSeat,
    },
    validation::{
        invalid_seat, invalid_seats, parse_flight_id, validate_check_in_request, validate_ticket_no,
    },
};

use super::{format_rfc3339_utc, trim_code};

#[derive(Clone)]
struct TicketFlight {
    flight_id: i32,
    status: String,
    airplane_code: String,
    fare_conditions: BookingClass,
    scheduled_departure: DateTime<Utc>,
}

pub async fn list_ticket_flight_seats(
    State(state): State<AppState>,
    Path((ticket_no, flight_id)): Path<(String, String)>,
) -> Result<Json<SeatMapResponse>, AppError> {
    let ticket_no = validate_ticket_no(&ticket_no)?;
    let flight_id = parse_flight_id(&flight_id)?;

    ensure_ticket_exists(&state.pool, &ticket_no).await?;
    ensure_flight_exists(&state.pool, flight_id).await?;
    let ticket_flight = load_ticket_flight(&state.pool, &ticket_no, flight_id)
        .await?
        .ok_or_else(|| {
            AppError::not_found(
                "ticket_segment_not_found",
                "Flight is not part of this ticket",
            )
        })?;

    let rows = sqlx::query(
        r#"
        select
            s.seat_no,
            s.fare_conditions,
            bp.seat_no is not null as occupied
        from bookings.seats s
        left join bookings.boarding_passes bp
          on bp.flight_id = $2
         and bp.seat_no = s.seat_no
        where trim(s.airplane_code)::text = $1
        order by
            regexp_replace(s.seat_no, '[^0-9]', '', 'g')::int,
            regexp_replace(s.seat_no, '[0-9]', '', 'g')
        "#,
    )
    .bind(&ticket_flight.airplane_code)
    .bind(flight_id)
    .fetch_all(&state.pool)
    .await?;

    let items = rows
        .into_iter()
        .map(|row| {
            let fare_conditions = parse_booking_class_from_db(row.get("fare_conditions"))?;
            let occupied: bool = row.get("occupied");
            let status = if occupied {
                "occupied"
            } else if fare_conditions != ticket_flight.fare_conditions {
                "blocked"
            } else {
                "available"
            };
            Ok(SeatMapSeat {
                seat_no: row.get("seat_no"),
                fare_conditions,
                status: status.to_owned(),
            })
        })
        .collect::<Result<Vec<_>, AppError>>()?;

    Ok(Json(SeatMapResponse {
        ticket_no,
        flight_id,
        items,
    }))
}

pub async fn create_check_in(
    State(state): State<AppState>,
    body: Bytes,
) -> Result<Response, AppError> {
    let request: CheckInRequest = serde_json::from_slice(&body).map_err(|_| {
        AppError::bad(
            "invalid_request",
            "request body is invalid JSON or contains unknown fields",
        )
    })?;
    validate_check_in_request(&request)?;

    let mut tx = state.pool.begin().await?;

    ensure_ticket_exists(&state.pool, &request.ticket_no).await?;
    ensure_flight_exists(&state.pool, request.flight_id).await?;

    let flights = load_ticket_flights(&state.pool, &request.ticket_no).await?;
    let first = flights
        .first()
        .ok_or_else(|| AppError::not_found("ticket_not_found", "Ticket was not found"))?;
    if !flights
        .iter()
        .any(|flight| flight.flight_id == request.flight_id)
    {
        return Err(AppError::not_found(
            "ticket_segment_not_found",
            "Flight is not part of this ticket",
        ));
    }
    if first.flight_id != request.flight_id {
        return Err(AppError::conflict(
            "not_first_flight",
            "Check-in must be started from the first flight in the ticket",
        ));
    }

    let already_checked_in: bool = sqlx::query_scalar(
        "select exists (select 1 from bookings.boarding_passes where ticket_no = $1)",
    )
    .bind(&request.ticket_no)
    .fetch_one(&state.pool)
    .await?;
    if already_checked_in {
        return Err(AppError::conflict(
            "already_checked_in",
            "This ticket is already checked in",
        ));
    }

    validate_checkin_window(&state.pool, first).await?;
    validate_requested_seats(&state.pool, &flights, &request).await?;

    let checked_in_at: DateTime<Utc> = sqlx::query_scalar("select bookings.now()")
        .fetch_one(&state.pool)
        .await?;

    let requested_by_flight = request
        .seats
        .iter()
        .map(|seat| (seat.flight_id, seat.seat_no.clone()))
        .collect::<HashMap<_, _>>();
    for flight in &flights {
        let seat_no = requested_by_flight
            .get(&flight.flight_id)
            .expect("validated seat exists");
        let result = sqlx::query(
            r#"
            insert into bookings.boarding_passes
                (ticket_no, flight_id, seat_no, boarding_no, boarding_time)
            values ($1, $2, $3, null, $4)
            "#,
        )
        .bind(&request.ticket_no)
        .bind(flight.flight_id)
        .bind(seat_no)
        .bind(checked_in_at)
        .execute(&mut *tx)
        .await;

        if let Err(error) = result {
            if is_unique_violation(&error) {
                return Err(AppError::conflict(
                    "seat_unavailable",
                    "One or more selected seats are not available",
                ));
            }
            return Err(error.into());
        }
    }

    tx.commit().await?;

    let passes = list_boarding_passes(&state.pool, &request.ticket_no).await?;
    let location = format!("/api/v1/tickets/{}/boarding-passes", request.ticket_no);
    let mut headers = HeaderMap::new();
    headers.insert(header::LOCATION, HeaderValue::from_str(&location).unwrap());
    Ok((
        StatusCode::CREATED,
        headers,
        Json(CheckInResponse {
            ticket_no: request.ticket_no,
            checked_in_at: format_rfc3339_utc(checked_in_at),
            boarding_passes: passes,
        }),
    )
        .into_response())
}

pub async fn list_ticket_boarding_passes(
    State(state): State<AppState>,
    Path(ticket_no): Path<String>,
) -> Result<Json<BoardingPassListResponse>, AppError> {
    let ticket_no = validate_ticket_no(&ticket_no)?;
    ensure_ticket_exists(&state.pool, &ticket_no).await?;
    let items = list_boarding_passes(&state.pool, &ticket_no).await?;
    Ok(Json(BoardingPassListResponse { ticket_no, items }))
}

async fn ensure_ticket_exists(pool: &sqlx::PgPool, ticket_no: &str) -> Result<(), AppError> {
    let exists: bool =
        sqlx::query_scalar("select exists (select 1 from bookings.tickets where ticket_no = $1)")
            .bind(ticket_no)
            .fetch_one(pool)
            .await?;
    if exists {
        Ok(())
    } else {
        Err(AppError::not_found(
            "ticket_not_found",
            "Ticket was not found",
        ))
    }
}

async fn ensure_flight_exists(pool: &sqlx::PgPool, flight_id: i32) -> Result<(), AppError> {
    let exists: bool =
        sqlx::query_scalar("select exists (select 1 from bookings.flights where flight_id = $1)")
            .bind(flight_id)
            .fetch_one(pool)
            .await?;
    if exists {
        Ok(())
    } else {
        Err(AppError::not_found(
            "flight_not_found",
            "Flight was not found",
        ))
    }
}

async fn load_ticket_flight(
    pool: &sqlx::PgPool,
    ticket_no: &str,
    flight_id: i32,
) -> Result<Option<TicketFlight>, AppError> {
    let row = sqlx::query(
        r#"
        select
            s.ticket_no,
            s.flight_id,
            s.fare_conditions,
            f.route_no,
            f.status,
            f.scheduled_departure,
            trim(r.airplane_code)::text as airplane_code
        from bookings.segments s
        join bookings.flights f on f.flight_id = s.flight_id
        join bookings.routes r
          on r.route_no = f.route_no
         and r.validity @> f.scheduled_departure
        where s.ticket_no = $1
          and s.flight_id = $2
        "#,
    )
    .bind(ticket_no)
    .bind(flight_id)
    .fetch_optional(pool)
    .await?;

    row.map(row_to_ticket_flight).transpose()
}

async fn load_ticket_flights(
    pool: &sqlx::PgPool,
    ticket_no: &str,
) -> Result<Vec<TicketFlight>, AppError> {
    let rows = sqlx::query(
        r#"
        select
            s.ticket_no,
            s.flight_id,
            s.fare_conditions,
            f.route_no,
            f.status,
            f.scheduled_departure,
            trim(r.airplane_code)::text as airplane_code
        from bookings.segments s
        join bookings.flights f on f.flight_id = s.flight_id
        join bookings.routes r
          on r.route_no = f.route_no
         and r.validity @> f.scheduled_departure
        where s.ticket_no = $1
        order by f.scheduled_departure, f.flight_id
        "#,
    )
    .bind(ticket_no)
    .fetch_all(pool)
    .await?;

    rows.into_iter().map(row_to_ticket_flight).collect()
}

fn row_to_ticket_flight(row: sqlx::postgres::PgRow) -> Result<TicketFlight, AppError> {
    Ok(TicketFlight {
        flight_id: row.get("flight_id"),
        status: row.get("status"),
        airplane_code: trim_code(row.get("airplane_code")),
        fare_conditions: parse_booking_class_from_db(row.get("fare_conditions"))?,
        scheduled_departure: row.get("scheduled_departure"),
    })
}

async fn validate_checkin_window(
    pool: &sqlx::PgPool,
    first: &TicketFlight,
) -> Result<(), AppError> {
    match first.status.as_str() {
        "Cancelled" => {
            return Err(AppError::conflict(
                "flight_cancelled",
                "Cannot check in for a cancelled flight",
            ));
        }
        "Boarding" | "Departed" | "Arrived" => {
            return Err(AppError::conflict(
                "checkin_closed",
                "Check-in is closed for this flight",
            ));
        }
        "Scheduled" => {
            return Err(AppError::conflict(
                "checkin_not_open",
                "Check-in opens 24 hours before scheduled departure",
            ));
        }
        "On Time" | "Delayed" => {}
        _ => {
            return Err(AppError::conflict(
                "flight_not_available",
                "One or more flights are not available for booking",
            ));
        }
    }

    let now = demo_now(pool).await?;
    if now < first.scheduled_departure - Duration::hours(24) {
        return Err(AppError::conflict(
            "checkin_not_open",
            "Check-in opens 24 hours before scheduled departure",
        ));
    }
    if now >= first.scheduled_departure {
        return Err(AppError::conflict(
            "checkin_closed",
            "Check-in is closed for this flight",
        ));
    }
    Ok(())
}

async fn demo_now(pool: &sqlx::PgPool) -> Result<DateTime<Utc>, AppError> {
    let now = sqlx::query_scalar("select bookings.now()")
        .fetch_one(pool)
        .await?;
    Ok(now)
}

async fn validate_requested_seats(
    pool: &sqlx::PgPool,
    flights: &[TicketFlight],
    request: &CheckInRequest,
) -> Result<(), AppError> {
    let expected_flights = flights
        .iter()
        .map(|flight| flight.flight_id)
        .collect::<HashSet<_>>();
    let requested_flights = request
        .seats
        .iter()
        .map(|seat| seat.flight_id)
        .collect::<HashSet<_>>();
    if expected_flights != requested_flights {
        return Err(invalid_seats());
    }

    let requested_by_flight = request
        .seats
        .iter()
        .map(|seat| (seat.flight_id, seat.seat_no.clone()))
        .collect::<HashMap<_, _>>();

    for flight in flights {
        let seat_no = requested_by_flight
            .get(&flight.flight_id)
            .expect("request contains every flight");
        let row = sqlx::query(
            r#"
            select fare_conditions
            from bookings.seats
            where trim(airplane_code)::text = $1
              and seat_no = $2
            "#,
        )
        .bind(&flight.airplane_code)
        .bind(seat_no)
        .fetch_optional(pool)
        .await?;

        let Some(row) = row else {
            return Err(invalid_seat());
        };
        let seat_fare = parse_booking_class_from_db(row.get("fare_conditions"))?;
        if seat_fare != flight.fare_conditions {
            return Err(invalid_seat());
        }

        let occupied: bool = sqlx::query_scalar(
            "select exists (select 1 from bookings.boarding_passes where flight_id = $1 and seat_no = $2)",
        )
        .bind(flight.flight_id)
        .bind(seat_no)
        .fetch_one(pool)
        .await?;
        if occupied {
            return Err(AppError::conflict(
                "seat_unavailable",
                "One or more selected seats are not available",
            ));
        }
    }

    Ok(())
}

async fn list_boarding_passes(
    pool: &sqlx::PgPool,
    ticket_no: &str,
) -> Result<Vec<BoardingPass>, AppError> {
    let rows = sqlx::query(
        r#"
        select
            bp.ticket_no,
            bp.flight_id,
            f.route_no,
            bp.seat_no
        from bookings.boarding_passes bp
        join bookings.flights f on f.flight_id = bp.flight_id
        where bp.ticket_no = $1
        order by f.scheduled_departure, f.flight_id
        "#,
    )
    .bind(ticket_no)
    .fetch_all(pool)
    .await?;

    Ok(rows
        .into_iter()
        .map(|row| BoardingPass {
            ticket_no: row.get("ticket_no"),
            flight_id: row.get("flight_id"),
            route_no: row.get("route_no"),
            seat_no: row.get("seat_no"),
        })
        .collect())
}

fn parse_booking_class_from_db(value: String) -> Result<BookingClass, AppError> {
    match value.as_str() {
        "Economy" => Ok(BookingClass::Economy),
        "Comfort" => Ok(BookingClass::Comfort),
        "Business" => Ok(BookingClass::Business),
        _ => Err(AppError::internal()),
    }
}

fn is_unique_violation(error: &sqlx::Error) -> bool {
    error
        .as_database_error()
        .and_then(|db_error| db_error.code())
        .is_some_and(|code| code == "23505")
}
