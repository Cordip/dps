use chrono::NaiveDate;
use serde::Deserialize;
use std::collections::HashSet;

use crate::{
    error::AppError,
    models::{BookingClass, BookingCreateRequest, CheckInRequest, TripDirection},
};

#[derive(Debug, Clone, Copy)]
pub enum Lang {
    En,
    Ru,
}

impl Lang {
    pub fn parse(value: Option<&str>) -> Result<Self, AppError> {
        match value.unwrap_or("en") {
            "en" => Ok(Self::En),
            "ru" => Ok(Self::Ru),
            _ => Err(AppError::bad("invalid_lang", "lang must be one of: en, ru")),
        }
    }

    pub fn key(self) -> &'static str {
        match self {
            Self::En => "en",
            Self::Ru => "ru",
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub enum Scope {
    All,
    RouteSource,
    RouteDestination,
    RouteAny,
}

impl Scope {
    pub fn parse(value: Option<&str>) -> Result<Self, AppError> {
        match value.unwrap_or("route_any") {
            "all" => Ok(Self::All),
            "route_source" => Ok(Self::RouteSource),
            "route_destination" => Ok(Self::RouteDestination),
            "route_any" => Ok(Self::RouteAny),
            _ => Err(AppError::bad(
                "invalid_scope",
                "scope must be one of: all, route_source, route_destination, route_any",
            )),
        }
    }

    pub fn airport_condition(self) -> &'static str {
        match self {
            Self::All => "true",
            Self::RouteSource => {
                "a.airport_code in (select r.departure_airport from bookings.routes r)"
            }
            Self::RouteDestination => {
                "a.airport_code in (select r.arrival_airport from bookings.routes r)"
            }
            Self::RouteAny => {
                "(a.airport_code in (select r.departure_airport from bookings.routes r) or a.airport_code in (select r.arrival_airport from bookings.routes r))"
            }
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub enum Sort {
    Duration,
    Price,
    DepartureTime,
}

impl Sort {
    pub fn parse(value: Option<&str>) -> Result<Self, AppError> {
        match value.unwrap_or("duration") {
            "duration" => Ok(Self::Duration),
            "price" => Ok(Self::Price),
            "departure_time" => Ok(Self::DepartureTime),
            _ => Err(AppError::bad(
                "invalid_sort",
                "sort must be one of: duration, price, departure_time",
            )),
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub struct Pagination {
    pub limit: i64,
    pub offset: i64,
}

pub fn parse_pagination(limit: Option<&str>, offset: Option<&str>) -> Result<Pagination, AppError> {
    let limit = match limit {
        Some(value) => value.parse::<i64>().map_err(|_| invalid_limit())?,
        None => 100,
    };
    if !(1..=500).contains(&limit) {
        return Err(invalid_limit());
    }

    let offset = match offset {
        Some(value) => value.parse::<i64>().map_err(|_| invalid_offset())?,
        None => 0,
    };
    if offset < 0 {
        return Err(invalid_offset());
    }

    Ok(Pagination { limit, offset })
}

pub fn validate_airport_code(value: &str) -> Result<String, AppError> {
    if value.len() == 3 && value.chars().all(|c| c.is_ascii_alphabetic()) {
        Ok(value.to_ascii_uppercase())
    } else {
        Err(AppError::bad(
            "invalid_airport_code",
            "airport_code must be a 3-letter IATA code",
        ))
    }
}

pub fn validate_book_ref(value: &str) -> Result<String, AppError> {
    if value.len() == 6 && value.chars().all(|c| c.is_ascii_alphanumeric()) {
        Ok(value.to_ascii_uppercase())
    } else {
        Err(AppError::bad(
            "invalid_book_ref",
            "book_ref must be a 6-character alphanumeric string",
        ))
    }
}

pub fn validate_ticket_no(value: &str) -> Result<String, AppError> {
    if value.len() == 13 && value.chars().all(|c| c.is_ascii_digit()) {
        Ok(value.to_owned())
    } else {
        Err(AppError::bad(
            "invalid_ticket_no",
            "ticket_no must be a 13-digit string",
        ))
    }
}

pub fn validate_flight_id(value: i32) -> Result<i32, AppError> {
    if value > 0 {
        Ok(value)
    } else {
        Err(AppError::bad(
            "invalid_flight_id",
            "flight_id must be an integer",
        ))
    }
}

pub fn parse_flight_id(value: &str) -> Result<i32, AppError> {
    let value = value
        .parse::<i32>()
        .map_err(|_| AppError::bad("invalid_flight_id", "flight_id must be an integer"))?;
    validate_flight_id(value)
}

pub fn validate_seat_no(value: &str) -> Result<(), AppError> {
    let mut digits = 0;
    let mut letters = 0;
    for c in value.chars() {
        if c.is_ascii_digit() && letters == 0 {
            digits += 1;
        } else if c.is_ascii_uppercase() {
            letters += 1;
        } else {
            return Err(invalid_seat());
        }
    }

    if (1..=3).contains(&digits) && letters == 1 && value.len() <= 8 {
        Ok(())
    } else {
        Err(invalid_seat())
    }
}

pub fn parse_date(value: Option<&str>) -> Result<NaiveDate, AppError> {
    let value = value.ok_or_else(|| {
        AppError::bad(
            "invalid_departure_date",
            "departure_date must be a valid date in YYYY-MM-DD format",
        )
    })?;
    NaiveDate::parse_from_str(value, "%Y-%m-%d").map_err(|_| {
        AppError::bad(
            "invalid_departure_date",
            "departure_date must be a valid date in YYYY-MM-DD format",
        )
    })
}

pub fn parse_booking_class(value: Option<&str>) -> Result<BookingClass, AppError> {
    match value {
        Some("Economy") => Ok(BookingClass::Economy),
        Some("Comfort") => Ok(BookingClass::Comfort),
        Some("Business") => Ok(BookingClass::Business),
        _ => Err(AppError::bad(
            "invalid_booking_class",
            "booking_class must be one of: Economy, Comfort, Business",
        )),
    }
}

pub fn parse_max_connections(value: Option<&str>) -> Result<usize, AppError> {
    match value {
        Some("0") => Ok(0),
        Some("1") => Ok(1),
        Some("2") => Ok(2),
        Some("3") => Ok(3),
        Some("unbound") => Ok(7),
        _ => Err(AppError::bad(
            "invalid_max_connections",
            "max_connections must be one of: 0, 1, 2, 3, unbound",
        )),
    }
}

pub fn search_tokens(search: Option<&str>) -> Vec<String> {
    let mut seen = HashSet::new();
    search
        .unwrap_or("")
        .split(|c: char| !c.is_alphanumeric())
        .filter(|token| !token.is_empty())
        .map(|token| token.to_lowercase())
        .filter(|token| seen.insert(token.clone()))
        .collect()
}

pub fn validate_idempotency_key(value: Option<&str>) -> Result<String, AppError> {
    let value = value.ok_or_else(|| {
        AppError::bad(
            "missing_idempotency_key",
            "Idempotency-Key header is required",
        )
    })?;
    if value.is_empty() || value.len() > 128 {
        return Err(AppError::bad(
            "invalid_idempotency_key",
            "Idempotency-Key must be a string between 1 and 128 characters",
        ));
    }
    Ok(value.to_owned())
}

pub fn validate_booking_request(request: &BookingCreateRequest) -> Result<(), AppError> {
    if request.passenger.passenger_id.trim().is_empty()
        || request.passenger.passenger_id.len() > 64
        || request.passenger.passenger_name.trim().is_empty()
        || request.passenger.passenger_name.len() > 200
    {
        return Err(AppError::bad(
            "invalid_passenger",
            "passenger_id and passenger_name are required and must fit length limits",
        ));
    }

    if request.trips.is_empty() || request.trips.len() > 2 {
        return Err(invalid_trips());
    }

    let outbound = request
        .trips
        .iter()
        .filter(|trip| trip.direction == TripDirection::Outbound)
        .count();
    let returns = request
        .trips
        .iter()
        .filter(|trip| trip.direction == TripDirection::Return)
        .count();
    if outbound != 1 || returns > 1 {
        return Err(invalid_trips());
    }

    for trip in &request.trips {
        if trip.segments.is_empty() || trip.segments.len() > 8 {
            return Err(invalid_segments());
        }
        for segment in &trip.segments {
            if segment.flight_id <= 0 {
                return Err(invalid_segments());
            }
        }
    }

    Ok(())
}

pub fn validate_check_in_request(request: &CheckInRequest) -> Result<(), AppError> {
    validate_ticket_no(&request.ticket_no)?;
    validate_flight_id(request.flight_id)?;
    if request.seats.is_empty() || request.seats.len() > 8 {
        return Err(invalid_seats());
    }

    let mut seen = HashSet::new();
    for seat in &request.seats {
        validate_flight_id(seat.flight_id)?;
        validate_seat_no(&seat.seat_no)?;
        if !seen.insert(seat.flight_id) {
            return Err(invalid_seats());
        }
    }

    Ok(())
}

#[derive(Debug, Deserialize)]
pub struct ListQuery {
    pub scope: Option<String>,
    pub lang: Option<String>,
    pub search: Option<String>,
    pub country: Option<String>,
    pub city: Option<String>,
    pub limit: Option<String>,
    pub offset: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct LangQuery {
    pub lang: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct PaginationQuery {
    pub limit: Option<String>,
    pub offset: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct RouteQuery {
    pub from_type: Option<String>,
    pub to_type: Option<String>,
    pub lang: Option<String>,
    pub from_airport: Option<String>,
    pub from_country: Option<String>,
    pub from_city: Option<String>,
    pub to_airport: Option<String>,
    pub to_country: Option<String>,
    pub to_city: Option<String>,
    pub departure_date: Option<String>,
    pub booking_class: Option<String>,
    pub max_connections: Option<String>,
    pub sort: Option<String>,
    pub limit: Option<String>,
    pub offset: Option<String>,
}

pub fn invalid_limit() -> AppError {
    AppError::bad(
        "invalid_limit",
        "limit must be an integer between 1 and 500",
    )
}

pub fn invalid_offset() -> AppError {
    AppError::bad(
        "invalid_offset",
        "offset must be an integer greater than or equal to 0",
    )
}

pub fn invalid_seat() -> AppError {
    AppError::bad(
        "invalid_seat",
        "One or more selected seats do not exist or do not match fare conditions",
    )
}

pub fn invalid_seats() -> AppError {
    AppError::bad(
        "invalid_seats",
        "seats must contain exactly one seat for each flight in the ticket",
    )
}

fn invalid_segments() -> AppError {
    AppError::bad(
        "invalid_segments",
        "Each trip must contain between 1 and 8 segments",
    )
}

fn invalid_trips() -> AppError {
    AppError::bad(
        "invalid_trips",
        "trips must contain one outbound trip and optionally one return trip",
    )
}
