use axum::{
    Json,
    http::StatusCode,
    response::{IntoResponse, Response},
};
use sea_orm::{DbErr, TransactionError};
use snafu::prelude::*;

#[derive(Debug, Snafu)]
pub enum AppHttpError {
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
}

impl IntoResponse for AppHttpError {
    fn into_response(self) -> Response {
        let (status, message) = match &self {
            AppHttpError::DatabaseError { source } => {
                tracing::error!(error = %source, "Database error");
                (StatusCode::INTERNAL_SERVER_ERROR, source.to_string())
            }
            AppHttpError::AuthError { message } => {
                tracing::warn!("Auth error: {}", message);
                (StatusCode::UNAUTHORIZED, message.clone())
            }
            AppHttpError::GoogleAuthError { message } => {
                tracing::warn!("Google OAuth error: {}", message);
                (StatusCode::UNAUTHORIZED, message.clone())
            }
            AppHttpError::UserDisabled => {
                tracing::warn!("User disabled");
                (StatusCode::FORBIDDEN, "User is disabled".to_string())
            }
            AppHttpError::NotFound { message } => {
                tracing::warn!("Not found: {}", message);
                (StatusCode::NOT_FOUND, message.clone())
            }
            AppHttpError::DeviceConflict { message } => {
                tracing::warn!("Device conflict: {}", message);
                (StatusCode::CONFLICT, message.clone())
            }
            AppHttpError::LimitExceeded { message } => {
                tracing::warn!("Limit exceeded: {}", message);
                (StatusCode::CONFLICT, message.clone())
            }
            AppHttpError::BadRequest { message } => {
                tracing::warn!("Bad request: {}", message);
                (StatusCode::BAD_REQUEST, message.clone())
            }
            AppHttpError::SelfModificationForbidden => {
                tracing::warn!("Forbidden to modify yourself");
                (
                    StatusCode::FORBIDDEN,
                    "Forbidden to modify yourself".to_string(),
                )
            }
            AppHttpError::Internal { message } => {
                tracing::error!("Internal server error: {}", message);
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "Internal server error".to_string(),
                )
            }
        };

        let body = Json(serde_json::json!({
            "error": message,
        }));

        (status, body).into_response()
    }
}

impl From<DbErr> for AppHttpError {
    fn from(source: DbErr) -> Self {
        AppHttpError::DatabaseError { source }
    }
}

impl From<TransactionError<AppHttpError>> for AppHttpError {
    fn from(e: TransactionError<AppHttpError>) -> Self {
        match e {
            TransactionError::Connection(e) => e.into(),
            TransactionError::Transaction(e) => e,
        }
    }
}
