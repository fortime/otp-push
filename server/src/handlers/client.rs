pub mod fcitx5_osk;

use axum::{
    Json,
    extract::{Path, State},
    http::StatusCode,
    response::{IntoResponse, Response},
};
use common::{OtpRequestResponse, OtpRequestStatus, OtpResponse};
use uuid::Uuid;

use crate::{
    auth::ApiTokenAuth, error::AppError, services::otp as otp_service, state::SharedState,
};

pub async fn request(
    State(state): State<SharedState>,
    auth: ApiTokenAuth,
) -> Result<Json<OtpRequestResponse>, AppError> {
    let result = otp_service::create_otp_request(&state, &auth.token).await?;

    Ok(Json(OtpRequestResponse {
        request_id: result.id,
        status: OtpRequestStatus::Pending,
    }))
}

pub async fn poll(
    State(state): State<SharedState>,
    auth: ApiTokenAuth,
    Path(request_id): Path<Uuid>,
) -> Result<Response, AppError> {
    let code = otp_service::wait_for_otp(&state, &auth.token, request_id).await?;

    if let Some(code) = code {
        return Ok(Json(OtpResponse { otp_code: code }).into_response());
    }

    Ok(StatusCode::NO_CONTENT.into_response())
}
