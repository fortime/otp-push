pub mod fcitx5_osk;

use axum::{
    Json,
    extract::{Path, State},
    http::StatusCode,
    response::{IntoResponse, Response},
};
use common::{CreateOtpRequest, OtpRequestResponse, OtpRequestStatus, OtpResponse};
use uuid::Uuid;

use crate::{
    crypto,
    http::{
        auth::ApiTokenAuth, error::AppHttpError, services::otp as otp_service,
        state::SharedAppHttpState,
    },
};

pub async fn request(
    State(state): State<SharedAppHttpState>,
    auth: ApiTokenAuth,
    Json(request): Json<CreateOtpRequest>,
) -> Result<Json<OtpRequestResponse>, AppHttpError> {
    let pub_key = request
        .pub_key
        .map(|key| key.trim().to_string())
        .filter(|key| !key.is_empty());
    if let Some(pub_key) = &pub_key {
        validate_x509_public_key_pem(pub_key)?;
    }

    let result = otp_service::create_otp_request(&state, &auth.token, pub_key).await?;

    Ok(Json(OtpRequestResponse {
        request_id: result.id,
        status: OtpRequestStatus::Pending,
    }))
}

fn validate_x509_public_key_pem(pub_key: &str) -> Result<(), AppHttpError> {
    crypto::validate_x509_public_key_pem(pub_key, |s| AppHttpError::BadRequest {
        message: s.to_string(),
    })
}

pub async fn poll(
    State(state): State<SharedAppHttpState>,
    auth: ApiTokenAuth,
    Path(request_id): Path<Uuid>,
) -> Result<Response, AppHttpError> {
    let code = otp_service::wait_for_otp(&state, &auth.token, request_id).await?;

    if let Some((_, code)) = code {
        return Ok(Json(OtpResponse { otp_code: code }).into_response());
    }

    Ok(StatusCode::NO_CONTENT.into_response())
}
