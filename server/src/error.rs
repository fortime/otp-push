use axum::{
    Json,
    http::StatusCode,
    response::{IntoResponse, Response},
};
use sea_orm::{DbErr, TransactionError};
use serde_json::json;
use snafu::prelude::*;

#[derive(Debug, Snafu)]
#[snafu(visibility(pub))]
pub enum AppError {
    #[snafu(display("Database error: {source}"))]
    DatabaseError { source: DbErr },

    #[snafu(display("Auth error: {message}"))]
    AuthError { message: String },

    #[snafu(display("Google OAuth error: {message}"))]
    GoogleAuthError { message: String },

    #[snafu(display("User is disabled"))]
    UserDisabled,

    #[snafu(display("Not found: {message}"))]
    NotFound { message: String },

    #[snafu(display("Device conflict: {message}"))]
    DeviceConflict { message: String },

    #[snafu(display("Forbidden to modify yourself"))]
    SelfModificationForbidden,

    #[snafu(display("Limit exceeded: {message}"))]
    LimitExceeded { message: String },

    #[snafu(display("Bad request: {message}"))]
    BadRequest { message: String },

    #[snafu(display("Internal server error: {message}"))]
    Internal { message: String },

    #[snafu(display("Startup error: {message}"))]
    StartupError { message: String },
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let (status, message) = match &self {
            AppError::DatabaseError { source } => {
                tracing::error!(error = %source, "Database error");
                (StatusCode::INTERNAL_SERVER_ERROR, source.to_string())
            }
            AppError::AuthError { message } => {
                tracing::warn!("Auth error: {}", message);
                (StatusCode::UNAUTHORIZED, message.clone())
            }
            AppError::GoogleAuthError { message } => {
                tracing::warn!("Google OAuth error: {}", message);
                (StatusCode::UNAUTHORIZED, message.clone())
            }
            AppError::UserDisabled => {
                tracing::warn!("User disabled");
                (StatusCode::FORBIDDEN, "User is disabled".to_string())
            }
            AppError::NotFound { message } => {
                tracing::warn!("Not found: {}", message);
                (StatusCode::NOT_FOUND, message.clone())
            }
            AppError::DeviceConflict { message } => {
                tracing::warn!("Device conflict: {}", message);
                (StatusCode::CONFLICT, message.clone())
            }
            AppError::LimitExceeded { message } => {
                tracing::warn!("Limit exceeded: {}", message);
                (StatusCode::CONFLICT, message.clone())
            }
            AppError::BadRequest { message } => {
                tracing::warn!("Bad request: {}", message);
                (StatusCode::BAD_REQUEST, message.clone())
            }
            AppError::SelfModificationForbidden => {
                tracing::warn!("Forbidden to modify yourself");
                (
                    StatusCode::FORBIDDEN,
                    "Forbidden to modify yourself".to_string(),
                )
            }
            AppError::Internal { message } => {
                tracing::error!("Internal server error: {}", message);
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "Internal server error".to_string(),
                )
            }
            AppError::StartupError { message } => {
                tracing::error!("StartupError shouldn't be returned as Response: {message}");
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "Internal server error".to_string(),
                )
            }
        };

        let body = Json(json!({
            "error": message,
        }));

        (status, body).into_response()
    }
}

impl From<DbErr> for AppError {
    fn from(source: DbErr) -> Self {
        AppError::DatabaseError { source }
    }
}

impl From<TransactionError<AppError>> for AppError {
    fn from(e: TransactionError<AppError>) -> Self {
        match e {
            TransactionError::Connection(e) => e.into(),
            TransactionError::Transaction(e) => e,
        }
    }
}
