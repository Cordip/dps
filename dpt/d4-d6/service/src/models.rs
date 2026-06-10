use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize)]
pub struct Page<T> {
    pub items: Vec<T>,
    pub limit: i64,
    pub offset: i64,
    pub has_more: bool,
}

#[derive(Debug, Serialize)]
pub struct City {
    pub city: String,
    pub country: String,
}

#[derive(Debug, Serialize, Clone)]
pub struct Coordinates {
    pub longitude: f64,
    pub latitude: f64,
}

#[derive(Debug, Serialize, Clone)]
pub struct Airport {
    pub airport_code: String,
    pub airport_name: String,
    pub city: String,
    pub country: String,
    pub coordinates: Coordinates,
    pub timezone: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct AirportRef {
    pub airport_code: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct Price {
    pub amount: String,
    pub currency: String,
}

impl Price {
    pub fn rub(amount: impl Into<String>) -> Self {
        Self {
            amount: normalize_money(amount.into()),
            currency: "RUB".to_owned(),
        }
    }
}

#[derive(Debug, Serialize)]
pub struct ScheduledArrival {
    pub route_no: String,
    pub days_of_week: Vec<String>,
    pub scheduled_time: String,
    pub timezone: String,
    pub origin: AirportRef,
}

#[derive(Debug, Serialize)]
pub struct ScheduledDeparture {
    pub route_no: String,
    pub days_of_week: Vec<String>,
    pub scheduled_time: String,
    pub timezone: String,
    pub destination: AirportRef,
}

#[derive(Debug, Serialize, Deserialize, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "PascalCase")]
pub enum BookingClass {
    Economy,
    Comfort,
    Business,
}

impl BookingClass {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Economy => "Economy",
            Self::Comfort => "Comfort",
            Self::Business => "Business",
        }
    }
}

#[derive(Debug, Serialize, Deserialize, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum TripDirection {
    Outbound,
    Return,
}

impl TripDirection {
    pub fn outbound_bool(self) -> bool {
        matches!(self, Self::Outbound)
    }
}

#[derive(Debug, Serialize)]
pub struct RouteSegment {
    pub sequence: i32,
    pub flight_id: i32,
    pub route_no: String,
    pub departure_airport: AirportRef,
    pub arrival_airport: AirportRef,
    pub scheduled_departure_at: String,
    pub scheduled_arrival_at: String,
    pub duration: String,
    pub price: Price,
}

#[derive(Debug, Serialize)]
pub struct RouteOption {
    pub booking_class: BookingClass,
    pub total_duration: String,
    pub total_price: Price,
    pub segments: Vec<RouteSegment>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BookingCreateRequest {
    pub booking_class: BookingClass,
    pub passenger: BookingPassengerInput,
    pub trips: Vec<BookingCreateTrip>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BookingPassengerInput {
    pub passenger_id: String,
    pub passenger_name: String,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BookingCreateTrip {
    pub direction: TripDirection,
    pub segments: Vec<BookingCreateSegment>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BookingCreateSegment {
    pub flight_id: i32,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct BookingPassenger {
    pub passenger_id: String,
    pub passenger_name: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct Booking {
    pub book_ref: String,
    pub book_date: String,
    pub passenger: BookingPassenger,
    pub booking_class: BookingClass,
    pub total_price: Price,
    pub trips: Vec<BookingTrip>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct BookingTrip {
    pub direction: TripDirection,
    pub ticket_no: String,
    pub total_price: Price,
    pub segments: Vec<BookingSegment>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct BookingSegment {
    pub sequence: i32,
    pub flight_id: i32,
    pub route_no: String,
    pub departure_airport: AirportRef,
    pub arrival_airport: AirportRef,
    pub scheduled_departure_at: String,
    pub scheduled_arrival_at: String,
    pub price: Price,
}

#[derive(Debug, Serialize)]
pub struct SeatMapResponse {
    pub ticket_no: String,
    pub flight_id: i32,
    pub items: Vec<SeatMapSeat>,
}

#[derive(Debug, Serialize)]
pub struct SeatMapSeat {
    pub seat_no: String,
    pub fare_conditions: BookingClass,
    pub status: String,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CheckInRequest {
    pub ticket_no: String,
    pub flight_id: i32,
    pub seats: Vec<CheckInSeatRequest>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CheckInSeatRequest {
    pub flight_id: i32,
    pub seat_no: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct BoardingPass {
    pub ticket_no: String,
    pub flight_id: i32,
    pub route_no: String,
    pub seat_no: String,
}

#[derive(Debug, Serialize)]
pub struct CheckInResponse {
    pub ticket_no: String,
    pub checked_in_at: String,
    pub boarding_passes: Vec<BoardingPass>,
}

#[derive(Debug, Serialize)]
pub struct BoardingPassListResponse {
    pub ticket_no: String,
    pub items: Vec<BoardingPass>,
}

pub fn normalize_money(mut amount: String) -> String {
    if let Some(dot) = amount.find('.') {
        let decimals = amount.len() - dot - 1;
        if decimals == 0 {
            amount.push_str("00");
        } else if decimals == 1 {
            amount.push('0');
        } else if decimals > 2 {
            amount.truncate(dot + 3);
        }
        amount
    } else {
        format!("{amount}.00")
    }
}
