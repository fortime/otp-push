use snafu::Snafu;

use crate::{ble::error::AppBleError, http::error::AppHttpError};

#[derive(Debug, Snafu)]
#[snafu(visibility(pub))]
pub enum AppError {
    #[snafu(display("App ble module error: {source}"))]
    Ble { source: AppBleError },
    #[snafu(display("App http module error: {source}"))]
    Http { source: AppHttpError },
    #[snafu(display("Startup error: {message}"))]
    Startup { message: String },
}

impl From<AppBleError> for AppError {
    fn from(source: AppBleError) -> Self {
        AppError::Ble { source }
    }
}

impl From<AppHttpError> for AppError {
    fn from(source: AppHttpError) -> Self {
        AppError::Http { source }
    }
}
