use std::{
    collections::{HashMap, HashSet, VecDeque},
    sync::Arc,
};

use axum::{Json, extract::Query, extract::State, http::StatusCode};
use chrono::{DateTime, NaiveDate, Utc};
use chrono_tz::Tz;
use sqlx::Row;

use crate::{
    app::AppState,
    error::AppError,
    models::{AirportRef, BookingClass, Page, Price, RouteOption, RouteSegment},
    validation::{
        Lang, RouteQuery, Sort, parse_booking_class, parse_date, parse_max_connections,
        parse_pagination, validate_airport_code,
    },
};

use super::{cents_to_money, format_rfc3339_at, iso_duration, money_to_cents, trim_code};

const ROUTE_SEARCH_PARTIAL_PATH_CAP: usize = 20_000;

#[derive(Clone)]
struct CandidateSegment {
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

struct RouteWork {
    segments: Vec<Arc<CandidateSegment>>,
    total_price_cents: i64,
}

pub async fn list_routes(
    State(state): State<AppState>,
    Query(query): Query<RouteQuery>,
) -> Result<Json<Page<RouteOption>>, AppError> {
    let lang = Lang::parse(query.lang.as_deref())?;
    let departure_date = parse_date(query.departure_date.as_deref())?;
    let booking_class = parse_booking_class(query.booking_class.as_deref())?;
    let max_connections = parse_max_connections(query.max_connections.as_deref())?;
    let max_segments = max_connections + 1;
    let sort = Sort::parse(query.sort.as_deref())?;
    let pagination = parse_pagination(query.limit.as_deref(), query.offset.as_deref())?;

    let source_airports = resolve_point(
        &state.pool,
        query.from_type.as_deref(),
        query.from_airport.as_deref(),
        query.from_country.as_deref(),
        query.from_city.as_deref(),
        lang,
    )
    .await?;
    let destination_airports = resolve_point(
        &state.pool,
        query.to_type.as_deref(),
        query.to_airport.as_deref(),
        query.to_country.as_deref(),
        query.to_city.as_deref(),
        lang,
    )
    .await?;

    let candidates = load_candidate_segments(&state.pool, departure_date, booking_class).await?;
    let by_departure = group_by_departure(candidates);
    let destination_set: HashSet<String> = destination_airports.into_iter().collect();

    let route_options = build_route_options(
        &source_airports,
        &destination_set,
        &by_departure,
        departure_date,
        max_segments,
        booking_class,
        sort,
    )?;

    let has_more = route_options.len() as i64 > pagination.offset + pagination.limit;
    let items = route_options
        .into_iter()
        .skip(pagination.offset as usize)
        .take(pagination.limit as usize)
        .map(|route| route_to_response(route, booking_class))
        .collect();

    Ok(Json(Page {
        items,
        limit: pagination.limit,
        offset: pagination.offset,
        has_more,
    }))
}

async fn resolve_point(
    pool: &sqlx::PgPool,
    point_type: Option<&str>,
    airport: Option<&str>,
    country: Option<&str>,
    city: Option<&str>,
    lang: Lang,
) -> Result<Vec<String>, AppError> {
    match point_type {
        Some("airport") => {
            let airport = airport.ok_or_else(missing_point_field)?;
            let airport = validate_airport_code(airport)?;
            let exists: bool = sqlx::query_scalar(
                "select exists (select 1 from bookings.airports_data where trim(airport_code)::text = $1)",
            )
            .bind(&airport)
            .fetch_one(pool)
            .await?;
            if exists {
                Ok(vec![airport])
            } else {
                Err(AppError::not_found(
                    "airport_not_found",
                    "Airport was not found",
                ))
            }
        }
        Some("city") => {
            let country = country.ok_or_else(missing_point_field)?;
            let city = city.ok_or_else(missing_point_field)?;
            let rows = sqlx::query(
                r#"
                select trim(airport_code)::text as airport_code
                from bookings.airports_data
                where country ->> $1 = $2
                  and city ->> $1 = $3
                order by airport_code
                "#,
            )
            .bind(lang.key())
            .bind(country)
            .bind(city)
            .fetch_all(pool)
            .await?;

            if rows.is_empty() {
                return Err(AppError::not_found("city_not_found", "City was not found"));
            }
            Ok(rows
                .into_iter()
                .map(|row| trim_code(row.get("airport_code")))
                .collect())
        }
        _ => Err(AppError::bad(
            "invalid_point_type",
            "from_type and to_type must be one of: airport, city",
        )),
    }
}

async fn load_candidate_segments(
    pool: &sqlx::PgPool,
    departure_date: NaiveDate,
    booking_class: BookingClass,
) -> Result<Vec<Arc<CandidateSegment>>, AppError> {
    let rows = sqlx::query(
        r#"
        select
            f.flight_id,
            f.route_no,
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
        join bookings.d4_pricing_rules pr
          on pr.route_no = f.route_no
         and trim(pr.airplane_code)::text = trim(r.airplane_code)::text
         and pr.fare_conditions = $2
        where f.status in ('Scheduled', 'On Time', 'Delayed')
          and f.scheduled_departure >= ($1::date::timestamptz - interval '1 day')
          and f.scheduled_departure < ($1::date::timestamptz + interval '9 days')
        order by f.scheduled_departure, f.flight_id
        "#,
    )
    .bind(departure_date)
    .bind(booking_class.as_str())
    .fetch_all(pool)
    .await?;

    Ok(rows
        .into_iter()
        .map(|row| {
            let price_amount: String = row.get("price_amount");
            Arc::new(CandidateSegment {
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
            })
        })
        .collect())
}

fn group_by_departure(
    candidates: Vec<Arc<CandidateSegment>>,
) -> HashMap<String, Vec<Arc<CandidateSegment>>> {
    let mut by_departure: HashMap<String, Vec<Arc<CandidateSegment>>> = HashMap::new();
    for segment in candidates {
        by_departure
            .entry(segment.departure_airport.clone())
            .or_default()
            .push(segment);
    }
    by_departure
}

fn build_route_options(
    source_airports: &[String],
    destination_airports: &HashSet<String>,
    by_departure: &HashMap<String, Vec<Arc<CandidateSegment>>>,
    departure_date: NaiveDate,
    max_segments: usize,
    booking_class: BookingClass,
    sort: Sort,
) -> Result<Vec<RouteWork>, AppError> {
    let mut queue: VecDeque<Vec<Arc<CandidateSegment>>> = VecDeque::new();

    for source in source_airports {
        if let Some(first_segments) = by_departure.get(source) {
            for segment in first_segments {
                if departs_on_local_date(segment, departure_date) {
                    queue.push_back(vec![Arc::clone(segment)]);
                }
            }
        }
    }

    let mut processed = 0usize;
    let mut routes = Vec::new();
    while let Some(path) = queue.pop_front() {
        processed += 1;
        if processed > ROUTE_SEARCH_PARTIAL_PATH_CAP {
            return Err(AppError::new(
                StatusCode::SERVICE_UNAVAILABLE,
                "route_search_too_complex",
                "Route search exceeded the internal safety cap; narrow the request",
            ));
        }

        let last = path.last().expect("path is never empty");
        if destination_airports.contains(&last.arrival_airport) {
            routes.push(RouteWork {
                total_price_cents: path.iter().map(|segment| segment.price_cents).sum(),
                segments: path,
            });
            continue;
        }

        if path.len() >= max_segments {
            continue;
        }

        let visited = visited_airports(&path);
        if let Some(next_segments) = by_departure.get(&last.arrival_airport) {
            for next in next_segments {
                if next.scheduled_departure <= last.scheduled_arrival {
                    continue;
                }
                if visited.contains(&next.arrival_airport) {
                    continue;
                }
                let mut next_path = path.clone();
                next_path.push(Arc::clone(next));
                queue.push_back(next_path);
            }
        }
    }

    sort_routes(&mut routes, sort, booking_class);
    Ok(routes)
}

fn departs_on_local_date(segment: &CandidateSegment, date: NaiveDate) -> bool {
    let tz = segment
        .departure_timezone
        .parse::<Tz>()
        .unwrap_or(chrono_tz::UTC);
    segment.scheduled_departure.with_timezone(&tz).date_naive() == date
}

fn visited_airports(path: &[Arc<CandidateSegment>]) -> HashSet<String> {
    let mut visited = HashSet::new();
    if let Some(first) = path.first() {
        visited.insert(first.departure_airport.clone());
    }
    for segment in path {
        visited.insert(segment.arrival_airport.clone());
    }
    visited
}

fn sort_routes(routes: &mut [RouteWork], sort: Sort, _booking_class: BookingClass) {
    routes.sort_by(|left, right| {
        let left_first = left.segments.first().expect("route has segments");
        let right_first = right.segments.first().expect("route has segments");
        let left_last = left.segments.last().expect("route has segments");
        let right_last = right.segments.last().expect("route has segments");
        let left_duration = left_last.scheduled_arrival - left_first.scheduled_departure;
        let right_duration = right_last.scheduled_arrival - right_first.scheduled_departure;

        match sort {
            Sort::Duration => (
                left_duration.num_seconds(),
                left.total_price_cents,
                left_first.scheduled_departure,
                left_first.flight_id,
            )
                .cmp(&(
                    right_duration.num_seconds(),
                    right.total_price_cents,
                    right_first.scheduled_departure,
                    right_first.flight_id,
                )),
            Sort::Price => (
                left.total_price_cents,
                left_duration.num_seconds(),
                left_first.scheduled_departure,
                left_first.flight_id,
            )
                .cmp(&(
                    right.total_price_cents,
                    right_duration.num_seconds(),
                    right_first.scheduled_departure,
                    right_first.flight_id,
                )),
            Sort::DepartureTime => (
                left_first.scheduled_departure,
                left_duration.num_seconds(),
                left.total_price_cents,
                left_first.flight_id,
            )
                .cmp(&(
                    right_first.scheduled_departure,
                    right_duration.num_seconds(),
                    right.total_price_cents,
                    right_first.flight_id,
                )),
        }
    });
}

fn route_to_response(route: RouteWork, booking_class: BookingClass) -> RouteOption {
    let first = route.segments.first().expect("route has segments");
    let last = route.segments.last().expect("route has segments");
    let total_duration = last.scheduled_arrival - first.scheduled_departure;

    RouteOption {
        booking_class,
        total_duration: iso_duration(total_duration),
        total_price: Price::rub(cents_to_money(route.total_price_cents)),
        segments: route
            .segments
            .into_iter()
            .enumerate()
            .map(|(index, segment)| RouteSegment {
                sequence: index as i32 + 1,
                flight_id: segment.flight_id,
                route_no: segment.route_no.clone(),
                departure_airport: AirportRef {
                    airport_code: segment.departure_airport.clone(),
                },
                arrival_airport: AirportRef {
                    airport_code: segment.arrival_airport.clone(),
                },
                scheduled_departure_at: format_rfc3339_at(
                    segment.scheduled_departure,
                    &segment.departure_timezone,
                ),
                scheduled_arrival_at: format_rfc3339_at(
                    segment.scheduled_arrival,
                    &segment.arrival_timezone,
                ),
                duration: iso_duration(segment.scheduled_arrival - segment.scheduled_departure),
                price: Price::rub(segment.price_amount.clone()),
            })
            .collect(),
    }
}

fn missing_point_field() -> AppError {
    AppError::bad(
        "missing_point_field",
        "airport point requires airport code; city point requires country and city",
    )
}
