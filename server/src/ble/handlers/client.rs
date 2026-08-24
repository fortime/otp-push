pub mod fcitx5_osk;

use std::time::Duration;

use axum::{
    Json,
    extract::{Path, State},
    http::StatusCode,
    response::{IntoResponse, Response},
};
use chrono::Utc;
use common::{BleOtpRequest, BleOtpResponse, OtpRequestResponse, OtpRequestStatus, OtpResponse};
use uuid::Uuid;

use crate::{
    ble::{config::BleMode, error::AppBleError, state::SharedAppBleState},
    crypto,
};

#[derive(Debug, serde::Deserialize)]
pub struct BleCreateOtpRequest {
    pub name: String,
    pub service_identifier: String,
    pub pub_key: Option<String>,
}

fn validate_x509_public_key_pem(pub_key: &str) -> Result<(), AppBleError> {
    crypto::validate_x509_public_key_pem(pub_key, |s| AppBleError::BadRequest {
        message: s.to_string(),
    })
}

async fn send_request(state: SharedAppBleState, request: BleOtpRequest) {
    let request_id = request.request_id;
    let mut lock = state.managers.lock().await;
    if let Some(info) = state.request_manager.request_info(request.request_id) {
        if info.created_at + Duration::from_secs(30) < Utc::now() {
            tracing::warn!(
                "Request[{}] is too old[{:?}], skipped",
                request.request_id,
                info.created_at
            );
            return;
        }
    } else {
        tracing::warn!("Request[{}] doesn't exist", request.request_id);
        return;
    }

    let response = match &state.config.ble_mode {
        BleMode::Server(_) => lock.1.send(request).await,
        BleMode::Client(_) => lock.0.send(request).await,
    };

    match response {
        Ok(response) => {
            state.request_manager.fulfill_request(request_id, response);
        }
        Err(e) => {
            tracing::error!("Failed to handle request[{request_id}]: {e:#?}");
            state.request_manager.fulfill_request(
                request_id,
                BleOtpResponse {
                    request_id,
                    body: common::BleOtpResponseBody::Err {
                        message: e.to_string(),
                    },
                },
            );
        }
    }
}

pub async fn request(
    State(state): State<SharedAppBleState>,
    Json(request): Json<BleCreateOtpRequest>,
) -> Result<Json<OtpRequestResponse>, AppBleError> {
    let pub_key = request
        .pub_key
        .map(|key| key.trim().to_string())
        .filter(|key| !key.is_empty());
    if let Some(pub_key) = &pub_key {
        validate_x509_public_key_pem(pub_key)?;
    }

    let request =
        state
            .request_manager
            .create_request(request.name, request.service_identifier, pub_key)?;

    let request_id = request.request_id;

    // request otp asynchronized
    tokio::spawn(send_request(state, request));

    Ok(Json(OtpRequestResponse {
        request_id,
        status: OtpRequestStatus::Pending,
    }))
}

pub async fn poll(
    State(state): State<SharedAppBleState>,
    Path(request_id): Path<Uuid>,
) -> Result<Response, AppBleError> {
    if let Some((_, otp_code)) = state.request_manager.wait_for_otp(request_id).await? {
        Ok(Json(OtpResponse { otp_code }).into_response())
    } else {
        Ok(StatusCode::NO_CONTENT.into_response())
    }
}
