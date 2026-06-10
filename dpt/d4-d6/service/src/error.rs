use axum::{Json, http::StatusCode, response::IntoResponse};
use serde::Serialize;
use std::borrow::Cow;
use tracing::error;

#[derive(Debug, Serialize)]
pub struct ErrorBody {
    pub error: String,
    pub message: String,
}

#[derive(Debug)]
pub struct AppError {
    pub status: StatusCode,
    pub code: Cow<'static, str>,
    pub message: Cow<'static, str>,
}

impl AppError {
    pub fn new(
        status: StatusCode,
        code: impl Into<Cow<'static, str>>,
        message: impl Into<Cow<'static, str>>,
    ) -> Self {
        Self {
            status,
            code: code.into(),
            message: message.into(),
        }
    }

    pub fn bad(code: &'static str, message: &'static str) -> Self {
        Self::new(StatusCode::BAD_REQUEST, code, message)
    }

    pub fn not_found(code: &'static str, message: &'static str) -> Self {
        Self::new(StatusCode::NOT_FOUND, code, message)
    }

    pub fn conflict(code: &'static str, message: &'static str) -> Self {
        Self::new(StatusCode::CONFLICT, code, message)
    }

    pub fn internal() -> Self {
        Self::new(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "Internal server error",
        )
    }
}

impl IntoResponse for AppError {
    fn into_response(self) -> axum::response::Response {
        let body = ErrorBody {
            error: self.code.into_owned(),
            message: self.message.into_owned(),
        };
        (self.status, Json(body)).into_response()
    }
}

impl From<sqlx::Error> for AppError {
    fn from(value: sqlx::Error) -> Self {
        error!(error = ?value, "database error");
        Self::internal()
    }
}
