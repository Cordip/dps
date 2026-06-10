use axum::{
    Json,
    extract::{Path, Query, State},
};
use sqlx::Row;

use crate::{
    app::AppState,
    error::AppError,
    models::{AirportRef, Page, ScheduledArrival, ScheduledDeparture},
    routes::weekday_token,
    validation::{PaginationQuery, parse_pagination, validate_airport_code},
};

use super::{airports::airport_exists, trim_code};

pub async fn list_arrivals(
    State(state): State<AppState>,
    Path(airport_code): Path<String>,
    Query(query): Query<PaginationQuery>,
) -> Result<Json<Page<ScheduledArrival>>, AppError> {
    let airport_code = validate_airport_code(&airport_code)?;
    ensure_airport(&state.pool, &airport_code).await?;
    let pagination = parse_pagination(query.limit.as_deref(), query.offset.as_deref())?;

    let rows = sqlx::query(
        r#"
        select
            array_agg(
                distinct extract(isodow from t.scheduled_arrival_local)::integer
                order by extract(isodow from t.scheduled_arrival_local)::integer
            ) as days_of_week,
            to_char(t.scheduled_arrival_local::time, 'HH24:MI') as scheduled_time,
            t.route_no,
            trim(t.departure_airport)::text as origin_airport_code,
            ad.timezone
        from bookings.timetable t
        join bookings.airports_data ad
          on trim(ad.airport_code)::text = trim(t.arrival_airport)::text
        where trim(t.arrival_airport)::text = $1
        group by t.scheduled_arrival_local::time, t.route_no, t.departure_airport, ad.timezone
        order by scheduled_time, t.route_no, origin_airport_code
        limit $2 offset $3
        "#,
    )
    .bind(&airport_code)
    .bind(pagination.limit + 1)
    .bind(pagination.offset)
    .fetch_all(&state.pool)
    .await?;

    let has_more = rows.len() as i64 > pagination.limit;
    let items = rows
        .into_iter()
        .take(pagination.limit as usize)
        .map(|row| {
            let days: Vec<i32> = row.get("days_of_week");
            ScheduledArrival {
                route_no: row.get("route_no"),
                days_of_week: days.into_iter().map(weekday_token).collect(),
                scheduled_time: row.get("scheduled_time"),
                timezone: row.get("timezone"),
                origin: AirportRef {
                    airport_code: trim_code(row.get("origin_airport_code")),
                },
            }
        })
        .collect();

    Ok(Json(Page {
        items,
        limit: pagination.limit,
        offset: pagination.offset,
        has_more,
    }))
}

pub async fn list_departures(
    State(state): State<AppState>,
    Path(airport_code): Path<String>,
    Query(query): Query<PaginationQuery>,
) -> Result<Json<Page<ScheduledDeparture>>, AppError> {
    let airport_code = validate_airport_code(&airport_code)?;
    ensure_airport(&state.pool, &airport_code).await?;
    let pagination = parse_pagination(query.limit.as_deref(), query.offset.as_deref())?;

    let rows = sqlx::query(
        r#"
        select
            array_agg(
                distinct extract(isodow from t.scheduled_departure_local)::integer
                order by extract(isodow from t.scheduled_departure_local)::integer
            ) as days_of_week,
            to_char(t.scheduled_departure_local::time, 'HH24:MI') as scheduled_time,
            t.route_no,
            trim(t.arrival_airport)::text as destination_airport_code,
            ad.timezone
        from bookings.timetable t
        join bookings.airports_data ad
          on trim(ad.airport_code)::text = trim(t.departure_airport)::text
        where trim(t.departure_airport)::text = $1
        group by t.scheduled_departure_local::time, t.route_no, t.arrival_airport, ad.timezone
        order by scheduled_time, t.route_no, destination_airport_code
        limit $2 offset $3
        "#,
    )
    .bind(&airport_code)
    .bind(pagination.limit + 1)
    .bind(pagination.offset)
    .fetch_all(&state.pool)
    .await?;

    let has_more = rows.len() as i64 > pagination.limit;
    let items = rows
        .into_iter()
        .take(pagination.limit as usize)
        .map(|row| {
            let days: Vec<i32> = row.get("days_of_week");
            ScheduledDeparture {
                route_no: row.get("route_no"),
                days_of_week: days.into_iter().map(weekday_token).collect(),
                scheduled_time: row.get("scheduled_time"),
                timezone: row.get("timezone"),
                destination: AirportRef {
                    airport_code: trim_code(row.get("destination_airport_code")),
                },
            }
        })
        .collect();

    Ok(Json(Page {
        items,
        limit: pagination.limit,
        offset: pagination.offset,
        has_more,
    }))
}

async fn ensure_airport(pool: &sqlx::PgPool, airport_code: &str) -> Result<(), AppError> {
    if airport_exists(pool, airport_code).await? {
        Ok(())
    } else {
        Err(AppError::not_found(
            "airport_not_found",
            "Airport was not found",
        ))
    }
}
