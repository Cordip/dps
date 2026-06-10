use axum::{
    Json, Router,
    extract::DefaultBodyLimit,
    routing::{get, post},
};
use serde_json::json;
use sqlx::PgPool;
use tower_http::{cors::CorsLayer, trace::TraceLayer};

use crate::routes;

#[derive(Clone)]
pub struct AppState {
    pub pool: PgPool,
}

pub fn build(pool: PgPool) -> Router {
    let state = AppState { pool };

    Router::new()
        .route("/health", get(health))
        .nest(
            "/api/v1",
            Router::new()
                .route("/cities", get(routes::cities::list_cities))
                .route("/airports", get(routes::airports::list_airports))
                .route(
                    "/airports/{airport_code}",
                    get(routes::airports::get_airport_by_code),
                )
                .route(
                    "/airports/{airport_code}/schedule/arrivals",
                    get(routes::schedules::list_arrivals),
                )
                .route(
                    "/airports/{airport_code}/schedule/departures",
                    get(routes::schedules::list_departures),
                )
                .route("/routes", get(routes::route_search::list_routes))
                .route("/bookings", post(routes::bookings::create_booking))
                .route(
                    "/bookings/{book_ref}",
                    get(routes::bookings::get_booking_by_ref),
                )
                .route(
                    "/tickets/{ticket_no}/flights/{flight_id}/seats",
                    get(routes::checkins::list_ticket_flight_seats),
                )
                .route("/check-ins", post(routes::checkins::create_check_in))
                .route(
                    "/tickets/{ticket_no}/boarding-passes",
                    get(routes::checkins::list_ticket_boarding_passes),
                ),
        )
        .layer(DefaultBodyLimit::max(16 * 1024))
        .layer(TraceLayer::new_for_http())
        .layer(CorsLayer::permissive())
        .with_state(state)
}

async fn health() -> Json<serde_json::Value> {
    Json(json!({ "status": "ok" }))
}
