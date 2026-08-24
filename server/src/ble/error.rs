use axum::{
    Json,
    response::{IntoResponse, Response},
};
use bluer::Error as BluerError;
use reqwest::StatusCode;
use snafu::Snafu;

#[derive(Debug, Snafu)]
pub enum AppBleError {
    #[snafu(display("Bluer error: {source}"))]
    BluerError { source: BluerError },

    #[snafu(display("Bad request: {message}"))]
    BadRequest { message: String },

    #[snafu(display("Bad gateway: {message}"))]
    BadGateway { message: String },

    #[snafu(display("Internal server error: {message}"))]
    Internal { message: String },
}

impl IntoResponse for AppBleError {
    fn into_response(self) -> Response {
        let (status, message) = match &self {
            AppBleError::BluerError { source } => {
                tracing::error!("Bluer error: {:?}", source);
                (StatusCode::INTERNAL_SERVER_ERROR, "Bluer error".to_string())
            }
            AppBleError::BadRequest { message } => {
                tracing::warn!("Bad request: {}", message);
                (StatusCode::BAD_REQUEST, message.clone())
            }
            AppBleError::BadGateway { message } => {
                tracing::warn!("Bad gateway: {}", message);
                (StatusCode::BAD_GATEWAY, message.clone())
            }
            AppBleError::Internal { message } => {
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

impl From<BluerError> for AppBleError {
    fn from(source: BluerError) -> Self {
        AppBleError::BluerError { source }
    }
}
